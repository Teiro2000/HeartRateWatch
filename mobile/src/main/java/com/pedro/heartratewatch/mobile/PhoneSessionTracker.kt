package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import kotlin.math.roundToInt

/**
 * The numbers for a session tracked by the phone's own GPS (no watch, so no heart rate): cumulative
 * distance in, rolling pace / best splits / time stopped / a [RunSummary] out. Pure Kotlin so it can be
 * unit tested; [PhoneTrackingService] feeds it GPS distance and plays the alerts. It follows the same
 * rules as the watch's ExerciseSessionService: pace comes from a 30 second rolling window (null until
 * there is enough distance and time, and ignored when physically implausible), and on an outdoor bike
 * the window runs on a clock that only counts moving time, so a red light cannot drag the speed down.
 */
class PhoneSessionTracker(val type: ActivityType, val startedAtMillis: Long) {

    var distanceMeters = 0f
        private set
    var currentPaceSecPerKm: Int? = null
        private set
    var movingMillis = 0L
        private set
    var stoppedMillis = 0L
        private set

    private val trackStops get() = type == ActivityType.BIKE
    private val minPlausiblePace get() = if (trackStops) MIN_PLAUSIBLE_BIKE_PACE else MIN_PLAUSIBLE_PACE

    private val recent = ArrayDeque<Pair<Long, Float>>()
    private val all = mutableListOf<Pair<Long, Float>>()
    private var lastMillis = 0L
    private var lastMeters = 0f

    /** Feeds one cumulative-distance reading taken at [nowMillis]; returns the new pace if one could be measured. */
    fun onDistance(nowMillis: Long, meters: Float): Int? {
        distanceMeters = meters

        // Skip a physically impossible jump for the splits, like the watch does (see ExerciseSessionService).
        val last = all.lastOrNull()
        val implied = last?.let { (prevMillis, prevMeters) ->
            val dMeters = meters - prevMeters
            val dMillis = nowMillis - prevMillis
            if (dMeters > 0f && dMillis > 0L) (dMillis / 1000.0) / (dMeters / 1000.0) else null
        }
        if (implied == null || implied >= minPlausiblePace) all += nowMillis to meters

        val pace: Int? = if (trackStops) {
            var moving = true
            if (lastMillis > 0L) {
                val dt = nowMillis - lastMillis
                val speed = if (dt > 0) (meters - lastMeters) / (dt / 1000f) else 0f
                moving = speed >= MIN_MOVING_METERS_PER_SECOND
                if (moving) movingMillis += dt else stoppedMillis += dt
            }
            lastMillis = nowMillis
            lastMeters = meters
            if (moving) rollingPace(meters, movingMillis) else null
        } else {
            rollingPace(meters, nowMillis)
        }
        if (pace != null) currentPaceSecPerKm = pace
        return pace
    }

    private fun rollingPace(meters: Float, clock: Long): Int? {
        recent.addLast(clock to meters)
        while (recent.isNotEmpty() && clock - recent.first().first > PACE_WINDOW_MILLIS) recent.removeFirst()
        val oldest = recent.firstOrNull() ?: return null
        val dMeters = meters - oldest.second
        val dMillis = clock - oldest.first
        if (dMeters < MIN_PACE_SAMPLE_METERS || dMillis < MIN_PACE_SAMPLE_MILLIS) return null
        val secPerKm = (dMillis / 1000.0) / (dMeters / 1000.0)
        if (secPerKm < minPlausiblePace) return null
        return secPerKm.toInt()
    }

    /** Fastest time (seconds) to cover [targetMeters] anywhere in the session, or null if it never got that far. */
    fun bestSplitSeconds(targetMeters: Float): Int? {
        if (all.size < 2) return null
        var start = 0
        var best = Long.MAX_VALUE
        for (end in all.indices) {
            while (all[end].second - all[start].second >= targetMeters) {
                val elapsed = all[end].first - all[start].first
                if (elapsed < best) best = elapsed
                start++
            }
        }
        return if (best == Long.MAX_VALUE) null else (best / 1000).toInt()
    }

    /** The finished session as a history entry. There is no heart rate from the phone, so those fields are 0. */
    fun summary(endMillis: Long): RunSummary {
        val durationSeconds = ((endMillis - startedAtMillis) / 1000).toInt()
        val stoppedSeconds = if (trackStops) (stoppedMillis / 1000).toInt() else 0
        return RunSummary(
            startedAtMillis = startedAtMillis,
            durationSeconds = durationSeconds,
            avgBpm = 0,
            maxBpm = 0,
            minBpm = 0,
            distanceMeters = distanceMeters,
            avgPaceSecPerKm = if (distanceMeters > 0f) {
                ((durationSeconds - stoppedSeconds) * 1000.0 / distanceMeters).roundToInt()
            } else null,
            best1kmSeconds = bestSplitSeconds(1_000f),
            best5kmSeconds = bestSplitSeconds(5_000f),
            best10kmSeconds = bestSplitSeconds(10_000f),
            activityType = type,
            stoppedSeconds = stoppedSeconds
        )
    }

    private companion object {
        const val PACE_WINDOW_MILLIS = 30_000L
        const val MIN_PACE_SAMPLE_METERS = 5f
        const val MIN_PACE_SAMPLE_MILLIS = 5_000L
        const val MIN_PLAUSIBLE_PACE = 100
        const val MIN_PLAUSIBLE_BIKE_PACE = 30
        const val MIN_MOVING_METERS_PER_SECOND = 0.7f
    }
}

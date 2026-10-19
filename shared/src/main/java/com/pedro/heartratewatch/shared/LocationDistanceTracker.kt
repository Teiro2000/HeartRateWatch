package com.pedro.heartratewatch.shared

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlin.math.max

/**
 * Cumulative GPS distance for a run, computed here rather than by Health Services (whose pipeline
 * stalls with the screen off on this watch -- see HeartRateSensorSource). A location-typed
 * foreground service (see the manifest) is what lets the fused provider keep delivering fixes with
 * the screen off.
 *
 * Raw GPS jitters even when standing still, so naively summing fix-to-fix distance would
 * accumulate fake distance. Instead each fix is measured against the last *counted* fix (the
 * anchor), and only counts once it's clearly farther than the noise: slow real movement still adds
 * up (the anchor stays put until it's passed), but jitter around one spot never does. [onDistance]
 * fires for every usable fix, even ones that don't add distance, so pace smoothing keeps getting
 * regular samples.
 */
class LocationDistanceTracker(
    context: Context,
    private val maxSpeedMetersPerSecond: Float = MAX_FOOT_SPEED_METERS_PER_SECOND,
    /** Called with each position that was accepted as real movement (and the very first fix): the points of the route, with the fix's GPS altitude (NaN if it has none). */
    private val onPoint: ((latitude: Double, longitude: Double, altitudeMeters: Double) -> Unit)? = null,
    private val onDistance: (Float) -> Unit
) {
    private val client = LocationServices.getFusedLocationProviderClient(context)

    private var anchor: Location? = null
    private var totalMeters = 0f

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::accept)
        }
    }

    /** Throws SecurityException if location permission is missing. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(UPDATE_INTERVAL_MS)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener { Log.w(TAG, "requestLocationUpdates failed", it) }
        Log.i(TAG, "started")
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    private fun gpsAltitude(fix: Location): Double = if (fix.hasAltitude()) fix.altitude else Double.NaN

    private fun accept(fix: Location) {
        if (fix.hasAccuracy() && fix.accuracy > MAX_ACCURACY_METERS) {
            Log.i(TAG, "dropped: accuracy ${fix.accuracy}m > $MAX_ACCURACY_METERS")
            return
        }

        val from = anchor
        if (from == null) {
            anchor = fix
            onPoint?.invoke(fix.latitude, fix.longitude, gpsAltitude(fix))
            onDistance(totalMeters)
            return
        }

        val seconds = (fix.elapsedRealtimeNanos - from.elapsedRealtimeNanos) / 1_000_000_000.0
        if (seconds <= 0.0) return
        val delta = from.distanceTo(fix)

        // Faster than any human can run (same ceiling as MIN_PLAUSIBLE_PACE_SEC_PER_KM): a bad fix,
        // not real movement. Dropped without moving the anchor, so the next good fix is measured
        // against the last trusted position over the real elapsed time.
        if (delta / seconds > maxSpeedMetersPerSecond) {
            Log.i(TAG, "dropped: implausible speed ${delta / seconds} m/s")
            return
        }

        val step = GpsStepFilter.countedMeters(
            deltaMeters = delta,
            seconds = seconds,
            speedMetersPerSecond = if (fix.hasSpeed()) fix.speed else null,
            accuracyMeters = if (fix.hasAccuracy()) fix.accuracy else null
        )
        Log.i(TAG, "fix acc=${fix.accuracy} speed=${if (fix.hasSpeed()) fix.speed else -1f} delta=$delta step=$step total=$totalMeters")
        if (step != null) {
            totalMeters += step
            anchor = fix
            onPoint?.invoke(fix.latitude, fix.longitude, gpsAltitude(fix))
        }
        onDistance(totalMeters)
    }

    companion object {
        /** The ceiling for a runner or walker: faster than this is a bad fix, not movement. */
        const val MAX_FOOT_SPEED_METERS_PER_SECOND = 10f
        /** A cyclist's ceiling (90 km/h) -- fast descents go well past a runner's. */
        const val MAX_BIKE_SPEED_METERS_PER_SECOND = 25f
        private const val TAG = "GpsDist"
        private const val UPDATE_INTERVAL_MS = 1_000L
        private const val MAX_ACCURACY_METERS = 15f
    }
}

/**
 * How much of the move from the last counted fix to a new one is real, kept separate from the Android location
 * classes so it can be tested. Raw GPS wanders by many meters between tall buildings, and adding up every
 * wander makes a run read far longer than it was (6.8 km for a 5 km run). The fix's own Doppler speed is steady
 * even when its position isn't, so it is used as the check on how far you could really have gone.
 */
object GpsStepFilter {
    private const val MIN_STEP_METERS = 3f
    private const val MIN_MOVING_SPEED_METERS_PER_SECOND = 0.8f
    private const val STANDING_SPEED_METERS_PER_SECOND = 0.5f
    private const val SPEED_MARGIN = 1.1f
    private const val SPEED_SLACK_METERS = 0.5f

    /** The meters to add for this fix, or null if it shouldn't count (the last counted position then stays the reference). */
    fun countedMeters(deltaMeters: Float, seconds: Double, speedMetersPerSecond: Float?, accuracyMeters: Float?): Float? {
        val accuracy = accuracyMeters ?: 0f
        // The fix's Doppler speed says we're clearly moving: count it right away so distance climbs every
        // second, but never more than that speed allows over the time since the last counted fix.
        if (speedMetersPerSecond != null && speedMetersPerSecond >= MIN_MOVING_SPEED_METERS_PER_SECOND) {
            val reachable = speedMetersPerSecond * seconds.toFloat() * SPEED_MARGIN + SPEED_SLACK_METERS
            return minOf(deltaMeters, reachable)
        }
        // Not clearly moving: only count a move bigger than the noise. When the speed says we're standing
        // still, the noise is the whole of the fix's accuracy, not half of it.
        val standing = speedMetersPerSecond != null && speedMetersPerSecond < STANDING_SPEED_METERS_PER_SECOND
        val noiseFloor = max(MIN_STEP_METERS, accuracy * if (standing) 1f else 0.5f)
        return if (deltaMeters >= noiseFloor) deltaMeters else null
    }
}

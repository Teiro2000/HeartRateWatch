package com.pedro.heartratewatch.mobile

import androidx.compose.ui.graphics.Color
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import java.time.Instant
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * XP, level and rank, shared by Cardio and Workout. Nothing is stored: everything is worked out from
 * the two histories whenever it's shown, so it always agrees with them (and your existing history
 * counts from day one; deleting a session takes its XP back).
 *
 * XP, roughly: a 5 km run is about 330, a 45 minute gym session about 300.
 *  - Cardio: distance x a rate for the activity (run 60 per km, walk 30, bike 20) plus 1 per minute
 *    moving. A ruck walk's rate grows with the pack (+1% per lb). A star for hitting the distance
 *    target adds 50.
 *  - Workout: 100 for the session, 5 per set logged with reps, and 1 per 200 lb lifted.
 *  - Streak: each active day adds 5 x its day in the streak (capped at 10), so keeping a streak going is worth a little extra.
 */
object Progression {

    data class Breakdown(
        val cardio: Int,
        val workouts: Int,
        val streak: Int,
        /** Full days without a counting session since penalties began (and no rest day to cover them), each costing a level. */
        val missedDays: Int = 0,
        /** The XP those missed days took away. */
        val lost: Int = 0,
        /** XP added by the Settings test button. */
        val bonus: Int = 0,
        /** The streak right now, in days that counted (or were covered by a rest day). */
        val streakDays: Int = 0,
        /** Rest days used so far, each of which spared a missed day. */
        val restDaysUsed: Int = 0,
        /** Rest days banked and ready (at most [MAX_REST_CREDITS]). */
        val restCredits: Int = 0,
        /** When the next rest day can be used, if one was used in the last week; null when one can be used now. */
        val nextRestOn: java.time.LocalDate? = null,
        /** Whether today already has a session that counts. */
        val todayCounts: Boolean = false,
        /** Counting days in a row towards the next rest day (0 to 6; the bar is this out of [REST_EARNED_EVERY]). */
        val restProgress: Int = 0
    ) {
        val total: Int get() = cardio + workouts + streak - lost + bonus
    }

    fun cardioXp(run: RunSummary): Int {
        val km = run.distanceMeters / 1000.0
        val movingMinutes = max(0, run.durationSeconds - run.stoppedSeconds) / 60.0
        val perKm = when (run.activityType) {
            ActivityType.RUN -> 60.0
            ActivityType.WALK -> 30.0 * (1.0 + run.packWeightLb / 100.0)
            ActivityType.BIKE, ActivityType.STATIONARY_BIKE -> 20.0
        }
        val star = if (run.reachedTarget) 50.0 else 0.0
        return (km * perKm + movingMinutes + star).roundToInt()
    }

    fun workoutXp(workout: WorkoutLog): Int {
        val sets = workout.exercises.flatMap { it.sets }.filter { (it.reps ?: 0) > 0 }
        if (sets.isEmpty()) return 0
        val volumeLb = sets.sumOf { set ->
            val weight = set.weight ?: 0.0
            if (weight > 0) weight * (set.reps ?: 0) else 0.0
        }
        return (100 + 5 * sets.size + volumeLb / 200.0).roundToInt()
    }

    /** 5 x the day's place in its streak (at most 10), for every day with a session. */
    fun streakXp(startedAtMillis: List<Long>, zone: ZoneId = ZoneId.systemDefault()): Int {
        val days = startedAtMillis.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().sorted()
        var run = 0
        var bonus = 0
        var previous: java.time.LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
            bonus += 5 * min(run, 10)
            previous = day
        }
        return bonus
    }

    private class Session(val startedAtMillis: Long, val xp: Int, val counts: Boolean, val kind: String) {
        var big = false
    }

    /**
     * Everything from [sinceMillis] on (a level reset moves this forward), replayed day by day.
     *
     * A session earns its XP always, but only one that meets the effort bar ([Effort], via [effort]) counts for
     * the day: it extends the streak and earns the streak bonus. On a day without a counting session (a full
     * day, from [penaltiesFrom] on) one of two things happens:
     *  - a banked rest day covers it, if there is one and none was used in the last 7 days: no level is lost
     *    and the streak carries on;
     *  - otherwise a level is lost (exactly one, never below level 1) and the streak resets.
     * Rest days are earned: one for every 7 counting days in a row, and one for a big session (counting,
     * and worth at least [BIG_SESSION_FACTOR] times the median of the last [BIG_SESSION_WINDOW] sessions of its
     * kind). At most [MAX_REST_CREDITS] are banked, and at most [MAX_EARNED_PER_WEEK] can be earned in any
     * 7 days. Today isn't judged until it is over. Nothing is
     * penalised before [penaltiesFrom], so a gap in old history costs nothing.
     */
    fun breakdown(
        runs: List<RunSummary>,
        workouts: List<WorkoutLog>,
        sinceMillis: Long = 0L,
        penaltiesFrom: java.time.LocalDate? = null,
        bonusXp: Int = 0,
        effort: EffortContext = EffortContext(),
        today: java.time.LocalDate = java.time.LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Breakdown {
        val counted = runs.filter { it.startedAtMillis >= sinceMillis }
        val countedWorkouts = workouts.filter { it.startedAtMillis >= sinceMillis }
        val cardio = counted.sumOf { cardioXp(it) }
        val workoutTotal = countedWorkouts.sumOf { workoutXp(it) }

        val sessions = (
            counted.map { Session(it.startedAtMillis, cardioXp(it), Effort.cardioQualifies(it, effort), it.activityType.name) } +
                countedWorkouts.map { Session(it.startedAtMillis, workoutXp(it), Effort.workoutQualifies(it, effort), "WORKOUT") }
            ).sortedBy { it.startedAtMillis }

        // A big session is one well above what is usual for that kind of session.
        val earlier = HashMap<String, MutableList<Int>>()
        for (session in sessions) {
            val before = earlier.getOrPut(session.kind) { mutableListOf() }
            if (session.counts && before.size >= BIG_SESSION_MIN_HISTORY) {
                val recent = before.takeLast(BIG_SESSION_WINDOW).sorted()
                val median = if (recent.size % 2 == 1) recent[recent.size / 2].toDouble()
                else (recent[recent.size / 2 - 1] + recent[recent.size / 2]) / 2.0
                session.big = session.xp >= BIG_SESSION_FACTOR * median
            }
            before += session.xp
        }
        if (sessions.isEmpty()) return Breakdown(cardio, workoutTotal, 0, bonus = bonusXp)

        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val byDay = sessions.groupBy { dayOf(it.startedAtMillis) }

        var xp = 0
        var streakBonusTotal = 0
        var missedDays = 0
        var restUsed = 0
        var credits = 0
        var countingRun = 0   // consecutive counting days, towards the next rest day
        var streakRun = 0     // the streak: counting days, with rest days carrying it over
        var lastRest: java.time.LocalDate? = null
        val earnedOn = mutableListOf<java.time.LocalDate>()
        var day = byDay.keys.min()
        fun earn(on: java.time.LocalDate) {
            if (credits >= MAX_REST_CREDITS) return
            if (earnedOn.count { it.isAfter(on.minusDays(7)) } >= MAX_EARNED_PER_WEEK) return
            credits++
            earnedOn += on
        }
        while (!day.isAfter(today)) {
            val list = byDay[day].orEmpty()
            xp += list.sumOf { it.xp }
            val counts = list.any { it.counts }
            if (counts) {
                streakRun++
                val bonus = 5 * min(streakRun, 10)
                xp += bonus
                streakBonusTotal += bonus
                countingRun++
                if (countingRun >= REST_EARNED_EVERY) {
                    earn(day)
                    countingRun = 0
                }
                if (list.any { it.counts && it.big }) earn(day)
            } else if (day.isBefore(today)) {
                val penalised = penaltiesFrom != null && !day.isBefore(penaltiesFrom)
                val restReady = credits > 0 && (lastRest == null || java.time.temporal.ChronoUnit.DAYS.between(lastRest, day) >= REST_MIN_GAP_DAYS)
                if (penalised && restReady) {
                    credits--
                    lastRest = day
                    restUsed++
                    countingRun = 0
                } else {
                    streakRun = 0
                    countingRun = 0
                    if (penalised) {
                        missedDays++
                        // Exactly one level down, keeping the progress made within the level (as far as the lower level has room for).
                        val level = levelForXp(xp)
                        if (level > 1) {
                            val into = xp - xpForLevel(level)
                            val room = xpForLevel(level) - xpForLevel(level - 1) - 1
                            xp = xpForLevel(level - 1) + min(into, room)
                        }
                    }
                }
            }
            day = day.plusDays(1)
        }
        val gained = cardio + workoutTotal + streakBonusTotal
        val nextRest = lastRest?.plusDays(REST_MIN_GAP_DAYS.toLong())?.takeIf { it.isAfter(today) }
        return Breakdown(
            cardio, workoutTotal, streakBonusTotal,
            missedDays = missedDays, lost = gained - xp, bonus = bonusXp,
            streakDays = streakRun, restDaysUsed = restUsed, restCredits = credits, nextRestOn = nextRest,
            todayCounts = byDay[today]?.any { it.counts } == true,
            restProgress = countingRun
        )
    }

    const val MAX_REST_CREDITS = 2
    const val MAX_EARNED_PER_WEEK = 2
    const val REST_EARNED_EVERY = 7
    private const val REST_MIN_GAP_DAYS = 7
    private const val BIG_SESSION_FACTOR = 2.0
    private const val BIG_SESSION_WINDOW = 10
    private const val BIG_SESSION_MIN_HISTORY = 3

    // ---------------------------------------------------------------- levels

    /** Total XP needed to reach [level] (level 1 is where everyone starts, at 0). */
    fun xpForLevel(level: Int): Int =
        if (level <= 1) 0 else (LEVEL_SCALE * (level - 1).toDouble().pow(LEVEL_POWER)).roundToInt()

    fun levelForXp(xp: Int): Int {
        var level = max(1, (xp / LEVEL_SCALE).pow(1.0 / LEVEL_POWER).toInt())
        while (xpForLevel(level + 1) <= xp) level++
        while (level > 1 && xpForLevel(level) > xp) level--
        return level
    }

    data class Standing(
        val xp: Int,
        val level: Int,
        val xpIntoLevel: Int,
        val xpForNext: Int,
        val rank: Rank,
        val nextRank: Rank?
    ) {
        val fraction: Float get() = if (xpForNext == 0) 0f else xpIntoLevel.toFloat() / xpForNext
    }

    fun standing(xp: Int): Standing {
        val level = levelForXp(xp)
        val start = xpForLevel(level)
        val rank = rankForLevel(level)
        return Standing(
            xp = xp,
            level = level,
            xpIntoLevel = xp - start,
            xpForNext = xpForLevel(level + 1) - start,
            rank = rank,
            nextRank = RANKS.firstOrNull { it.minLevel > level }
        )
    }

    // ---------------------------------------------------------------- ranks

    data class Rank(val name: String, val minLevel: Int, val color: Color)

    /** Lowest level first. Reaching [minLevel] earns the rank. */
    val RANKS = listOf(
        Rank("Rookie", 1, Color(0xFF9E9E9E)),
        Rank("Bronze", 5, Color(0xFFCD7F32)),
        Rank("Silver", 10, Color(0xFFB0BEC5)),
        Rank("Gold", 20, Color(0xFFFFC107)),
        Rank("Platinum", 30, Color(0xFF4DD0E1)),
        Rank("Diamond", 40, Color(0xFF64B5F6)),
        Rank("Master", 50, Color(0xFFBA68C8)),
        Rank("Grandmaster", 75, Color(0xFFEF5350)),
        Rank("Legend", 100, Color(0xFFFF7043))
    )

    fun rankForLevel(level: Int): Rank = RANKS.last { it.minLevel <= level }

    // Level n needs 123 x (n-1)^1.9 XP: level 10 is around 8,000 XP, level 25 around 50,000, level 50 around 200,000.
    private const val LEVEL_SCALE = 123.0
    private const val LEVEL_POWER = 1.9
}

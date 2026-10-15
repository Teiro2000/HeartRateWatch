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
 *  - Streak: a day (cardio and workouts together) with at least 300 XP counts for the streak and adds 5 x its day in
 *    the streak (capped at 10). A day with 600 XP also banks a rest day. See [breakdown].
 */
object Progression {

    data class Breakdown(
        val cardio: Int,
        val workouts: Int,
        val streak: Int,
        /** Full days that didn't reach [STREAK_XP] since penalties began (and no rest day to cover them), each costing a level. */
        val missedDays: Int = 0,
        /** The XP those missed days took away. */
        val lost: Int = 0,
        /** The streak right now, in days that reached [STREAK_XP] (rest days carry it over). */
        val streakDays: Int = 0,
        /** Rest days used so far, each of which spared a missed day. */
        val restDaysUsed: Int = 0,
        /** Rest days banked and ready (at most [MAX_REST_CREDITS]). */
        val restCredits: Int = 0,
        /** When the week's rest days are available again (next Monday), if both have been used; null otherwise. */
        val nextRestOn: java.time.LocalDate? = null,
        /** XP earned today, from cardio and workouts together: the bar towards [STREAK_XP] and [REST_XP]. */
        val todayXp: Int = 0,
        /** Whether today has reached [STREAK_XP], so the streak carries on. */
        val todayCounts: Boolean = false
    ) {
        val total: Int get() = cardio + workouts + streak - lost
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

    /** What one exercise in a workout is worth: 5 per set with reps and 1 per 200 lb lifted (the session's own 100 is on top, see [workoutXp]). */
    fun exerciseXp(log: ExerciseLog): Int {
        val sets = log.sets.filter { (it.reps ?: 0) > 0 }
        val volumeLb = sets.sumOf { set ->
            val weight = set.weight ?: 0.0
            if (weight > 0) weight * (set.reps ?: 0) else 0.0
        }
        return (5 * sets.size + volumeLb / 200.0).roundToInt()
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

    /** Monday of the week [date] is in: every week, for rest days, runs Monday to Sunday. */
    fun weekStart(date: java.time.LocalDate): java.time.LocalDate = date.with(java.time.DayOfWeek.MONDAY)

    /**
     * Everything from [sinceMillis] on (a level reset moves this forward), replayed day by day, with cardio and
     * workouts mixed freely: a day's XP is all its sessions together.
     *  - A day with at least [STREAK_XP] XP counts: it extends the streak and earns the streak bonus.
     *  - A day with at least [REST_XP] XP also banks a rest day. That is the only way to bank one. At most
     *    [MAX_REST_CREDITS] are held, and at most [MAX_EARNED_PER_WEEK] earned in a calendar week (on different days).
     *  - A full day that doesn't count (from [penaltiesFrom] on) is covered by a banked rest day if one is
     *    banked and fewer than [MAX_USED_PER_WEEK] were used that week; the streak carries on. Otherwise it costs exactly
     *    one level (never below level 1) and the streak resets. You pick your rest days simply by skipping them.
     * Weeks start on Monday. Today isn't judged until it is over. Nothing is penalised before [penaltiesFrom], so
     * a gap in old history costs nothing.
     */
    fun breakdown(
        runs: List<RunSummary>,
        workouts: List<WorkoutLog>,
        sinceMillis: Long = 0L,
        penaltiesFrom: java.time.LocalDate? = null,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Breakdown {
        val counted = runs.filter { it.startedAtMillis >= sinceMillis }
        val countedWorkouts = workouts.filter { it.startedAtMillis >= sinceMillis }
        val cardio = counted.sumOf { cardioXp(it) }
        val workoutTotal = countedWorkouts.sumOf { workoutXp(it) }

        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val dayXp = HashMap<java.time.LocalDate, Int>()
        counted.forEach { dayXp.merge(dayOf(it.startedAtMillis), cardioXp(it), Int::plus) }
        countedWorkouts.forEach { dayXp.merge(dayOf(it.startedAtMillis), workoutXp(it), Int::plus) }
        val todayXp = dayXp[today] ?: 0
        if (dayXp.isEmpty()) return Breakdown(cardio, workoutTotal, 0, todayXp = todayXp)

        var xp = 0
        var streakBonusTotal = 0
        var missedDays = 0
        var restUsed = 0
        var credits = 0
        var streakRun = 0
        val restOn = mutableListOf<java.time.LocalDate>()
        val earnedOn = mutableListOf<java.time.LocalDate>()
        var day = dayXp.keys.min()
        while (!day.isAfter(today)) {
            val earned = dayXp[day] ?: 0
            xp += earned
            if (earned >= STREAK_XP) {
                streakRun++
                val bonus = 5 * min(streakRun, 10)
                xp += bonus
                streakBonusTotal += bonus
                if (earned >= REST_XP && credits < MAX_REST_CREDITS &&
                    earnedOn.count { weekStart(it) == weekStart(day) } < MAX_EARNED_PER_WEEK
                ) {
                    credits++
                    earnedOn += day
                }
            } else if (day.isBefore(today)) {
                val penalised = penaltiesFrom != null && !day.isBefore(penaltiesFrom)
                val restReady = credits > 0 && restOn.count { weekStart(it) == weekStart(day) } < MAX_USED_PER_WEEK
                if (penalised && restReady) {
                    credits--
                    restOn += day
                    restUsed++
                } else {
                    streakRun = 0
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
        val thisWeek = weekStart(today)
        val nextRest = if (restOn.count { weekStart(it) == thisWeek } >= MAX_USED_PER_WEEK) thisWeek.plusWeeks(1) else null
        return Breakdown(
            cardio, workoutTotal, streakBonusTotal,
            missedDays = missedDays, lost = gained - xp,
            streakDays = streakRun, restDaysUsed = restUsed, restCredits = credits, nextRestOn = nextRest,
            todayXp = todayXp, todayCounts = todayXp >= STREAK_XP
        )
    }

    /** XP in a day (cardio and workouts together) that keeps the streak going. */
    const val STREAK_XP = 300

    /** XP in a day that banks a rest day (the only way to bank one). */
    const val REST_XP = 600

    const val MAX_REST_CREDITS = 2
    const val MAX_EARNED_PER_WEEK = 2
    const val MAX_USED_PER_WEEK = 2

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

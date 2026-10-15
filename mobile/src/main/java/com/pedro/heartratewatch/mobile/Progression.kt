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
        /** Full days without a session since penalties began, each of which cost a level. */
        val missedDays: Int = 0,
        /** The XP those missed days took away. */
        val lost: Int = 0,
        /** XP added by the Settings test button. */
        val bonus: Int = 0
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

    /**
     * Everything from [sinceMillis] on (a level reset moves this forward), replayed day by day: a day
     * with a session adds its XP and streak bonus; a full day without one, from [penaltiesFrom] on, takes
     * away one level (the XP that level's span is worth, never below 0). Today isn't penalised until it
     * is over, matching a streak that is still alive if you were active yesterday. Nothing is penalised
     * before [penaltiesFrom] or before the first session counted, so a break in old history costs nothing.
     */
    fun breakdown(
        runs: List<RunSummary>,
        workouts: List<WorkoutLog>,
        sinceMillis: Long = 0L,
        penaltiesFrom: java.time.LocalDate? = null,
        bonusXp: Int = 0,
        today: java.time.LocalDate = java.time.LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Breakdown {
        val counted = runs.filter { it.startedAtMillis >= sinceMillis }
        val countedWorkouts = workouts.filter { it.startedAtMillis >= sinceMillis }
        val cardio = counted.sumOf { cardioXp(it) }
        val workoutTotal = countedWorkouts.sumOf { workoutXp(it) }
        val starts = counted.map { it.startedAtMillis } + countedWorkouts.map { it.startedAtMillis }
        val streak = streakXp(starts, zone)
        if (penaltiesFrom == null || starts.isEmpty()) return Breakdown(cardio, workoutTotal, streak, bonus = bonusXp)

        fun dayOf(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        val sessionXp = HashMap<java.time.LocalDate, Int>()
        counted.forEach { sessionXp.merge(dayOf(it.startedAtMillis), cardioXp(it), Int::plus) }
        countedWorkouts.forEach { sessionXp.merge(dayOf(it.startedAtMillis), workoutXp(it), Int::plus) }
        val activeDays = sessionXp.keys.sorted()

        var xp = 0
        var missedDays = 0
        var run = 0
        var previousActive: java.time.LocalDate? = null
        var day = activeDays.first()
        while (!day.isAfter(today)) {
            val earned = sessionXp[day]
            if (earned != null) {
                run = if (previousActive != null && day == previousActive.plusDays(1)) run + 1 else 1
                xp += earned + 5 * min(run, 10)
                previousActive = day
            } else if (day.isBefore(today) && !day.isBefore(penaltiesFrom)) {
                val level = levelForXp(xp)
                if (level > 1) {
                    xp = max(0, xp - (xpForLevel(level) - xpForLevel(level - 1)))
                    missedDays++
                } else {
                    missedDays++
                }
            }
            day = day.plusDays(1)
        }
        val gained = cardio + workoutTotal + streak
        return Breakdown(cardio, workoutTotal, streak, missedDays = missedDays, lost = gained - xp, bonus = bonusXp)
    }

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

    // Level n needs 77 x (n-1)^1.9 XP: level 10 is around 5,000 XP, level 25 around 30,000, level 50 around 125,000.
    private const val LEVEL_SCALE = 77.0
    private const val LEVEL_POWER = 1.9
}

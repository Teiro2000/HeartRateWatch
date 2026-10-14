package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One calendar week (Monday to Sunday) of cardio and workouts, worked out from the saved histories. */
data class WeekSummary(
    val start: LocalDate,
    val activeDays: Int,
    val cardioSessions: Int,
    val cardioMeters: Double,
    val cardioMetersByActivity: Map<ActivityType, Double>,
    val cardioMinutes: Int,
    val workouts: Int,
    val sets: Int,
    val liftedLb: Double,
    val xp: Int,
    /** Average heart rate over the week's cardio sessions that had one, weighted by how long each lasted. */
    val avgBpm: Int?,
    val records: List<RecordEvent>
) {
    val end: LocalDate get() = start.plusDays(6)
}

object WeeklyRecap {

    fun mondayOf(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun summarize(
        runs: List<RunSummary>,
        workouts: List<WorkoutLog>,
        records: List<RecordEvent>,
        weekStart: LocalDate,
        zone: ZoneId = ZoneId.systemDefault()
    ): WeekSummary {
        fun inWeek(millis: Long): Boolean {
            val day = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
            return !day.isBefore(weekStart) && day.isBefore(weekStart.plusDays(7))
        }
        val weekRuns = runs.filter { inWeek(it.startedAtMillis) }
        val weekWorkouts = workouts.filter { inWeek(it.startedAtMillis) }

        val days = (weekRuns.map { it.startedAtMillis } + weekWorkouts.map { it.startedAtMillis })
            .map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.distinct().size

        val withHeartRate = weekRuns.filter { it.avgBpm > 0 && it.durationSeconds > 0 }
        val avgBpm = if (withHeartRate.isEmpty()) null else {
            (withHeartRate.sumOf { it.avgBpm.toLong() * it.durationSeconds } / withHeartRate.sumOf { it.durationSeconds.toLong() }).toInt()
        }

        val sets = weekWorkouts.flatMap { it.exercises }.flatMap { it.sets }.filter { (it.reps ?: 0) > 0 }
        return WeekSummary(
            start = weekStart,
            activeDays = days,
            cardioSessions = weekRuns.size,
            cardioMeters = weekRuns.sumOf { it.distanceMeters.toDouble() },
            cardioMetersByActivity = ActivityType.entries.associateWith { type ->
                weekRuns.filter { it.activityType == type }.sumOf { it.distanceMeters.toDouble() }
            },
            cardioMinutes = weekRuns.sumOf { (it.durationSeconds - it.stoppedSeconds).coerceAtLeast(0) } / 60,
            workouts = weekWorkouts.count { w -> w.exercises.any { e -> e.sets.any { (it.reps ?: 0) > 0 } } },
            sets = sets.size,
            liftedLb = sets.sumOf { s -> val w = s.weight ?: 0.0; if (w > 0) w * (s.reps ?: 0) else 0.0 },
            xp = weekRuns.sumOf { Progression.cardioXp(it) } + weekWorkouts.sumOf { Progression.workoutXp(it) },
            avgBpm = avgBpm,
            records = records.filter { inWeek(it.timeMillis) }
        )
    }

    /** The average heart rate of each of the [weeks] weeks ending with the one starting [lastWeekStart] (null where there were no readings), oldest first. */
    fun heartRateTrend(
        runs: List<RunSummary>,
        lastWeekStart: LocalDate,
        weeks: Int = 8,
        zone: ZoneId = ZoneId.systemDefault()
    ): List<Pair<LocalDate, Int?>> =
        (weeks - 1 downTo 0).map { back ->
            val start = lastWeekStart.minusWeeks(back.toLong())
            start to summarize(runs, emptyList(), emptyList(), start, zone).avgBpm
        }
}

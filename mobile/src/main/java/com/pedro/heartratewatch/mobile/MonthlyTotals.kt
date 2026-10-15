package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

data class DayTotal(val day: LocalDate, val meters: Map<ActivityType, Double>) {
    /** Distance covered on this day by one kind of activity. */
    fun of(type: ActivityType): Double = meters[type] ?: 0.0

    val runMeters: Double get() = of(ActivityType.RUN)
    val totalMeters: Double get() = meters.values.sum()
}

/**
 * Total distance per calendar day, split by activity. Derived straight from run history rather than
 * stored separately, so there's a single source of truth: the chart, its CSV export, and the run-history
 * CSV can never disagree, and discarding a workout removes it from its day too. Days with no workouts
 * between the first and last are included as zeros so the chart has no gaps. Oldest day first.
 */
fun dailyTotals(runs: List<RunSummary>, zone: ZoneId = ZoneId.systemDefault()): List<DayTotal> {
    if (runs.isEmpty()) return emptyList()
    val byDay = runs.groupBy { Instant.ofEpochMilli(it.startedAtMillis).atZone(zone).toLocalDate() }
    val days = byDay.keys.sorted()

    val totals = mutableListOf<DayTotal>()
    var day = days.first()
    while (!day.isAfter(days.last())) {
        val onDay = byDay[day].orEmpty()
        totals += DayTotal(
            day = day,
            meters = ActivityType.entries.associateWith { type ->
                onDay.filter { it.activityType == type }.sumOf { it.distanceMeters.toDouble() }
            }
        )
        day = day.plusDays(1)
    }
    return totals
}

/** Always kilometers, for spreadsheets/records regardless of the on-screen unit. */
fun dailyTotalsCsv(totals: List<DayTotal>): String =
    "day,run_km,walk_km,bike_km,stationary_bike_km,total_km\n" + totals.joinToString("\n") {
        String.format(
            Locale.US, "%s,%.2f,%.2f,%.2f,%.2f,%.2f",
            it.day, it.of(ActivityType.RUN) / 1000, it.of(ActivityType.WALK) / 1000,
            it.of(ActivityType.BIKE) / 1000, it.of(ActivityType.STATIONARY_BIKE) / 1000, it.totalMeters / 1000
        )
    }

/** Total run+bike distance so far in the current calendar month -- deliberately computed
 * directly against today's date rather than reusing monthlyTotals()'s last entry, since that
 * list only covers months that actually have a workout and would otherwise leave this month out
 * entirely until its first one. */
fun thisMonthDistanceMeters(runs: List<RunSummary>): Double {
    val thisMonth = YearMonth.now(ZoneId.systemDefault())
    return runs
        .filter { YearMonth.from(Instant.ofEpochMilli(it.startedAtMillis).atZone(ZoneId.systemDefault())) == thisMonth }
        .sumOf { it.distanceMeters.toDouble() }
}

/** Consecutive calendar days with at least one workout, counting backward from the most recent
 * workout day. A streak is still alive if that day is today or yesterday (today isn't over yet, so
 * no workout so far doesn't break it); once a whole day has passed without one it's back to 0. */
fun currentStreakDays(runs: List<RunSummary>): Int = streakDays(runs.map { it.startedAtMillis })

/** The same streak from bare start times, so cardio sessions and gym workouts can be counted together. */
fun streakDays(
    startedAtMillis: List<Long>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: java.time.LocalDate = java.time.LocalDate.now(zone)
): Int {
    if (startedAtMillis.isEmpty()) return 0
    val workoutDays = startedAtMillis.map { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }.toSortedSet()
    if (workoutDays.last() < today.minusDays(1)) return 0
    var streak = 1
    var day = workoutDays.last()
    while (workoutDays.contains(day.minusDays(1))) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}

/**
 * The streak a reminder should protect: the current streak length if it is alive but today has no
 * workout yet, otherwise null (already satisfied today, or nothing to protect).
 */
fun streakAtRisk(
    startedAtMillis: List<Long>,
    zone: ZoneId = ZoneId.systemDefault(),
    today: java.time.LocalDate = java.time.LocalDate.now(zone)
): Int? {
    val streak = streakDays(startedAtMillis, zone, today)
    if (streak == 0) return null
    val doneToday = startedAtMillis.any { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today }
    return if (doneToday) null else streak
}

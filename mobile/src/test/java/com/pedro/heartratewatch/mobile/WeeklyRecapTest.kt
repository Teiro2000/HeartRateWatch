package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class WeeklyRecapTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: String, hour: Int = 12) = LocalDate.parse(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun run(day: String, km: Float, minutes: Int, bpm: Int, type: ActivityType = ActivityType.RUN) = RunSummary(
        startedAtMillis = at(day), durationSeconds = minutes * 60, avgBpm = bpm, maxBpm = bpm + 20, minBpm = bpm - 20,
        distanceMeters = km * 1000f, activityType = type
    )

    private fun workout(day: String, weight: Double, reps: Int, sets: Int) = WorkoutLog(
        id = day, startedAtMillis = at(day), templateName = "x",
        exercises = listOf(ExerciseLog("e", "ex", "Ex", List(sets) { LoggedSet("$it", weight, reps) }))
    )

    @Test
    fun mondayStartsTheWeek() {
        assertEquals(LocalDate.parse("2026-10-12"), WeeklyRecap.mondayOf(LocalDate.parse("2026-10-18")))
        assertEquals(LocalDate.parse("2026-10-12"), WeeklyRecap.mondayOf(LocalDate.parse("2026-10-12")))
    }

    @Test
    fun summarizesOnlyThatWeek() {
        val runs = listOf(
            run("2026-10-12", 5f, 30, 150), run("2026-10-14", 10f, 60, 140), run("2026-10-19", 3f, 20, 160),
            run("2026-10-13", 20f, 60, 120, ActivityType.BIKE)
        )
        val workouts = listOf(workout("2026-10-14", 100.0, 5, 3), workout("2026-10-11", 100.0, 5, 3))
        val records = listOf(RecordEvent(at("2026-10-14"), "Fastest 5 km: 24:10"), RecordEvent(at("2026-10-20"), "later"))
        val week = WeeklyRecap.summarize(runs, workouts, records, LocalDate.parse("2026-10-12"), zone)

        assertEquals(3, week.cardioSessions)
        assertEquals(35_000.0, week.cardioMeters, 0.5)
        assertEquals(15_000.0, week.cardioMetersByActivity.getValue(ActivityType.RUN), 0.5)
        assertEquals(150, week.cardioMinutes)
        assertEquals(1, week.workouts)
        assertEquals(3, week.sets)
        assertEquals(1500.0, week.liftedLb, 0.1)
        assertEquals(3, week.activeDays)                      // 12th, 13th and 14th
        assertEquals(listOf("Fastest 5 km: 24:10"), week.records.map { it.text })
        // weighted by duration: (150*30 + 140*60 + 120*60) / 150
        assertEquals(134, week.avgBpm)
    }

    @Test
    fun heartRateTrendHasOneEntryPerWeekAndGapsWhereThereWereNoReadings() {
        val runs = listOf(run("2026-10-05", 5f, 30, 150), run("2026-10-19", 5f, 30, 140))
        val trend = WeeklyRecap.heartRateTrend(runs, LocalDate.parse("2026-10-19"), weeks = 3, zone = zone)
        assertEquals(listOf("2026-10-05", "2026-10-12", "2026-10-19"), trend.map { it.first.toString() })
        assertEquals(150, trend[0].second)
        assertNull(trend[1].second)
        assertEquals(140, trend[2].second)
    }
}

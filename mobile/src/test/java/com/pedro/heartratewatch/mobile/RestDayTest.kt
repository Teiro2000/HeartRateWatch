package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** The streak and rest day rules: a day is worth 300 XP for the streak and 600 XP for a banked rest day, cardio and workouts mixed. */
class RestDayTest {
    private val zone = ZoneId.of("UTC")
    private fun day(d: String) = LocalDate.parse(d)
    private fun at(d: String) = day(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A run worth exactly [xp] XP (1 minute plus 60 XP a km). */
    private fun runWorth(d: String, xp: Int) = RunSummary(
        startedAtMillis = at(d), durationSeconds = 60, avgBpm = 120, maxBpm = 140, minBpm = 100,
        distanceMeters = (xp - 1) * 1000f / 60f, activityType = ActivityType.RUN
    )

    /** A workout worth exactly [xp] XP (100 for the session plus 5 a set; [xp] must be 100 + a multiple of 5). */
    private fun workoutWorth(d: String, xp: Int) = WorkoutLog(
        id = "w$d$xp", startedAtMillis = at(d), templateName = "x",
        exercises = listOf(ExerciseLog("e$d$xp", "ex", "Ex", List((xp - 100) / 5) { LoggedSet("$it", null, 1) }))
    )

    private fun breakdown(runs: List<RunSummary> = emptyList(), workouts: List<WorkoutLog> = emptyList(), today: String, penaltiesFrom: String = "2026-01-01") =
        Progression.breakdown(runs, workouts, penaltiesFrom = day(penaltiesFrom), today = day(today), zone = zone)

    // 2026-10-05 is a Monday, so the 5th to the 11th is one week and the 12th starts the next.

    @Test
    fun aDayCountsForTheStreakAt300Xp() {
        assertEquals(1, breakdown(listOf(runWorth("2026-10-05", 300)), today = "2026-10-05").streakDays)
        assertEquals(0, breakdown(listOf(runWorth("2026-10-05", 299)), today = "2026-10-05").streakDays)
        val result = breakdown(listOf(runWorth("2026-10-05", 299)), today = "2026-10-05")
        assertFalse(result.todayCounts)
        assertEquals(299, result.todayXp)
        assertTrue(breakdown(listOf(runWorth("2026-10-05", 300)), today = "2026-10-05").todayCounts)
    }

    @Test
    fun cardioAndWorkoutsMixWithinADay() {
        // 200 from a run and 150 from a workout: 350 together keeps the streak
        val result = breakdown(listOf(runWorth("2026-10-05", 200)), listOf(workoutWorth("2026-10-05", 150)), today = "2026-10-05")
        assertEquals(350, result.todayXp)
        assertEquals(1, result.streakDays)
        // 350 + 250 reaches 600 and banks a rest day, in one day, from a workout and a run
        val banked = breakdown(listOf(runWorth("2026-10-05", 300)), listOf(workoutWorth("2026-10-05", 300)), today = "2026-10-05")
        assertEquals(1, banked.restCredits)
    }

    @Test
    fun aRestDayIsBankedAt600XpAndNoOtherWay() {
        assertEquals(1, breakdown(listOf(runWorth("2026-10-05", 600)), today = "2026-10-05").restCredits)
        assertEquals(0, breakdown(listOf(runWorth("2026-10-05", 599)), today = "2026-10-05").restCredits)
        // a long streak of 300 XP days banks nothing
        val steady = List(10) { runWorth(day("2026-10-01").plusDays(it.toLong()).toString(), 300) }
        val result = breakdown(steady, today = "2026-10-10")
        assertEquals(10, result.streakDays)
        assertEquals(0, result.restCredits)
    }

    @Test
    fun aBankedRestDayCoversAMissedDayAndKeepsTheStreak() {
        val runs = listOf(runWorth("2026-10-05", 600), runWorth("2026-10-06", 300), runWorth("2026-10-08", 300))   // the 7th is missed
        val result = breakdown(runs, today = "2026-10-08")
        assertEquals(0, result.missedDays)
        assertEquals(1, result.restDaysUsed)
        assertEquals(0, result.restCredits)
        assertEquals(3, result.streakDays)

        val uncovered = breakdown(listOf(runWorth("2026-10-05", 300), runWorth("2026-10-07", 300)), today = "2026-10-07")
        assertEquals(1, uncovered.missedDays)
        assertEquals(1, uncovered.streakDays)
    }

    @Test
    fun twoCanBeBankedAWeekOnDifferentDaysAndNoMore() {
        val runs = listOf(runWorth("2026-10-05", 600), runWorth("2026-10-06", 600), runWorth("2026-10-07", 600))
        assertEquals(2, breakdown(runs, today = "2026-10-07").restCredits)    // the bank holds two

        // bank two on Monday and Tuesday, use both on Wednesday and Thursday, and a third 600 XP day that week banks nothing
        val week = listOf(runWorth("2026-10-05", 600), runWorth("2026-10-06", 600), runWorth("2026-10-09", 600))
        val result = breakdown(week, today = "2026-10-10")
        assertEquals(2, result.restDaysUsed)
        assertEquals(0, result.restCredits)   // Friday's 600 XP was a third banked in the week
    }

    @Test
    fun onlyTwoCanBeUsedInACalendarWeek() {
        val banked = listOf(runWorth("2026-10-05", 600), runWorth("2026-10-06", 600)) +
            (7..11).map { runWorth("2026-10-%02d".format(it), 300) }     // the rest of that week is trained, so nothing is spent yet
        // next week: Monday to Wednesday missed; two covered, the third costs a level
        val result = breakdown(banked, today = "2026-10-15")
        assertEquals(2, result.restDaysUsed)
        assertEquals(1, result.missedDays)
        assertEquals(LocalDate.parse("2026-10-19"), result.nextRestOn)
    }

    @Test
    fun missedDaysBeforePenaltiesBeganAreNotPunished() {
        val runs = listOf(runWorth("2026-09-01", 300), runWorth("2026-09-15", 300))
        val result = breakdown(runs, today = "2026-09-15", penaltiesFrom = "2026-10-01")
        assertEquals(0, result.missedDays)
    }
}

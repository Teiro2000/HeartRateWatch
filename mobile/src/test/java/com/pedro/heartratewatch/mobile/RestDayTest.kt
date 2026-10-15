package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class RestDayTest {
    private val zone = ZoneId.of("UTC")
    private fun day(d: String) = LocalDate.parse(d)
    private fun at(d: String) = day(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A run that counts: 40 minutes at a heart rate above the default lower bound. */
    private fun run(d: String, km: Float = 8f, bpm: Int = 150, minutes: Int = 40, target: Boolean = false) = RunSummary(
        startedAtMillis = at(d), durationSeconds = minutes * 60, avgBpm = bpm, maxBpm = 175, minBpm = 120,
        distanceMeters = km * 1000f, activityType = ActivityType.RUN, reachedTarget = target
    )

    private fun week(from: String, days: Int) = List(days) { run(day(from).plusDays(it.toLong()).toString()) }

    private fun breakdown(runs: List<RunSummary>, today: String, penaltiesFrom: String = "2026-01-01") =
        Progression.breakdown(runs, emptyList(), penaltiesFrom = day(penaltiesFrom), today = day(today), zone = zone)

    @Test
    fun aSessionBelowTheEffortBarEarnsXpButDoesNotCount() {
        val lazy = run("2026-10-01", km = 1f, bpm = 80, minutes = 15)
        assertFalse(Effort.cardioQualifies(lazy, EffortContext()))
        assertTrue(Effort.cardioQualifies(run("2026-10-01"), EffortContext()))
        assertTrue(Effort.cardioQualifies(lazy.copy(reachedTarget = true), EffortContext()))
        assertTrue(Effort.cardioQualifies(lazy.copy(avgBpm = 0, durationSeconds = 31 * 60), EffortContext()))
        assertFalse(Effort.cardioQualifies(lazy.copy(avgBpm = 0, durationSeconds = 29 * 60), EffortContext()))

        val result = breakdown(listOf(lazy), today = "2026-10-01")
        assertEquals(0, result.streakDays)
        assertTrue(result.cardio > 0)
    }

    @Test
    fun sevenCountingDaysInARowEarnARestDayAndTheBarShowsTheProgress() {
        val partway = breakdown(week("2026-10-01", 4), today = "2026-10-04")
        assertEquals(4, partway.restProgress)
        assertEquals(0, partway.restCredits)

        val earned = breakdown(week("2026-10-01", 7), today = "2026-10-07")
        assertEquals(1, earned.restCredits)
        assertEquals(0, earned.restProgress)
        assertEquals(7, earned.streakDays)
    }

    @Test
    fun aBankedRestDayCoversAMissedDayAndKeepsTheStreak() {
        val runs = week("2026-10-01", 7) + run("2026-10-09")   // the 8th is missed
        val covered = breakdown(runs, today = "2026-10-09")
        assertEquals(0, covered.missedDays)
        assertEquals(1, covered.restDaysUsed)
        assertEquals(0, covered.restCredits)
        assertEquals(8, covered.streakDays)

        val uncovered = breakdown(week("2026-10-01", 3) + run("2026-10-05"), today = "2026-10-05")
        assertEquals(1, uncovered.missedDays)
        assertEquals(1, uncovered.streakDays)
    }

    @Test
    fun onlyOneRestDayCanBeUsedInAWeek() {
        // 7 days earns one rest day; a big session adds a second; two missed days in a row can use only one.
        val runs = week("2026-10-01", 7)
        val result = breakdown(runs, today = "2026-10-10")   // the 8th and 9th missed, today (10th) not over
        assertEquals(1, result.restDaysUsed)
        assertEquals(1, result.missedDays)
    }

    @Test
    fun aBigSessionEarnsARestDayButOnlyTwoCanBeBankedAndTwoEarnedAWeek() {
        // three ordinary runs, then one worth far more than double their usual XP
        val ordinary = listOf(run("2026-10-01"), run("2026-10-02"), run("2026-10-03"))
        val big = run("2026-10-04", km = 30f, minutes = 150)
        val result = breakdown(ordinary + big, today = "2026-10-04")
        assertEquals(1, result.restCredits)

        // a second big session the next day is the second earned this week, a third in the same week is not
        val more = listOf(run("2026-10-05", km = 40f, minutes = 200), run("2026-10-06", km = 60f, minutes = 300))
        val capped = breakdown(ordinary + big + more, today = "2026-10-06")
        assertEquals(2, capped.restCredits)
    }

    @Test
    fun missedDaysBeforePenaltiesBeganAreNotPunishedAndCostNoRestDays() {
        val runs = week("2026-09-01", 7) + run("2026-09-15")
        val result = breakdown(runs, today = "2026-09-15", penaltiesFrom = "2026-10-01")
        assertEquals(0, result.missedDays)
        assertEquals(1, result.restCredits)   // earned, but nothing to spend it on
    }
}

package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class LevelDecayTest {
    private val zone = ZoneId.of("UTC")
    private fun day(d: String) = LocalDate.parse(d)
    private fun at(d: String) = day(d).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    /** A 20 km run: 1200 + 60 = 1260 XP, plus the day's streak bonus. */
    private fun bigRun(d: String) = RunSummary(
        startedAtMillis = at(d), durationSeconds = 3600, avgBpm = 150, maxBpm = 170, minBpm = 120,
        distanceMeters = 20_000f, activityType = ActivityType.RUN
    )

    @Test
    fun withoutPenaltiesTheTotalIsJustWhatWasEarned() {
        val runs = listOf(bigRun("2026-10-01"), bigRun("2026-10-05"))
        val result = Progression.breakdown(runs, emptyList(), penaltiesFrom = null, today = day("2026-10-20"), zone = zone)
        assertEquals(0, result.lost)
        assertEquals(result.cardio + result.streak, result.total)
    }

    @Test
    fun eachFullDayWithoutASessionCostsALevel() {
        val runs = listOf(bigRun("2026-10-10"), bigRun("2026-10-11"))
        val kept = Progression.breakdown(runs, emptyList(), penaltiesFrom = day("2026-10-10"), today = day("2026-10-12"), zone = zone)
        // today (the 12th) isn't over, so nothing is lost yet
        assertEquals(0, kept.missedDays)

        val oneMissed = Progression.breakdown(runs, emptyList(), penaltiesFrom = day("2026-10-10"), today = day("2026-10-13"), zone = zone)
        val twoMissed = Progression.breakdown(runs, emptyList(), penaltiesFrom = day("2026-10-10"), today = day("2026-10-14"), zone = zone)
        assertEquals(1, oneMissed.missedDays)
        assertEquals(2, twoMissed.missedDays)
        val before = Progression.levelForXp(kept.total)
        assertEquals(before - 1, Progression.levelForXp(oneMissed.total))
        assertTrue(Progression.levelForXp(twoMissed.total) <= before - 2)
    }

    @Test
    fun missedDaysBeforePenaltiesBeganAreForgiven() {
        val runs = listOf(bigRun("2026-09-01"), bigRun("2026-10-10"))
        val result = Progression.breakdown(runs, emptyList(), penaltiesFrom = day("2026-10-10"), today = day("2026-10-11"), zone = zone)
        assertEquals(0, result.missedDays)
    }

    @Test
    fun theLevelNeverGoesBelowTheStart() {
        val runs = listOf(bigRun("2026-10-01"))
        val result = Progression.breakdown(runs, emptyList(), penaltiesFrom = day("2026-10-01"), today = day("2026-12-01"), zone = zone)
        assertEquals(1, Progression.levelForXp(result.total))
        assertTrue(result.total >= 0)
    }

    @Test
    fun aResetOnlyCountsSessionsFromThenOn() {
        val runs = listOf(bigRun("2026-10-01"), bigRun("2026-10-09"))
        val result = Progression.breakdown(runs, emptyList(), sinceMillis = at("2026-10-05"), penaltiesFrom = null, today = day("2026-10-10"), zone = zone)
        assertEquals(Progression.cardioXp(runs[1]), result.cardio)
        val fresh = Progression.breakdown(runs, emptyList(), sinceMillis = at("2026-10-20"), penaltiesFrom = day("2026-10-20"), today = day("2026-10-25"), zone = zone)
        assertEquals(0, fresh.total)
        assertEquals(1, Progression.levelForXp(fresh.total))
    }
}

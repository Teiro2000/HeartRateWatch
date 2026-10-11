package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class StreakTest {
    private val zone = ZoneId.of("UTC")
    private fun at(day: String) = LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun countsConsecutiveDaysFromTheLatestOne() {
        val starts = listOf(at("2026-10-08"), at("2026-10-09"), at("2026-10-09"), at("2026-10-11"), at("2026-10-10"))
        assertEquals(4, streakDays(starts, zone, LocalDate.parse("2026-10-11")))
    }

    @Test
    fun aGapEndsTheStreakAndNothingIsZero() {
        assertEquals(1, streakDays(listOf(at("2026-10-01"), at("2026-10-05")), zone, LocalDate.parse("2026-10-05")))
        assertEquals(0, streakDays(emptyList(), zone, LocalDate.parse("2026-10-05")))
    }

    @Test
    fun aStreakNotExtendedYesterdayOrTodayIsOver() {
        val starts = listOf(at("2026-10-08"), at("2026-10-09"))
        assertEquals(2, streakDays(starts, zone, LocalDate.parse("2026-10-10")))
        assertEquals(0, streakDays(starts, zone, LocalDate.parse("2026-10-11")))
    }

    @Test
    fun reminderOnlyWhenAStreakIsAliveAndTodayIsEmpty() {
        val starts = listOf(at("2026-10-09"), at("2026-10-10"))
        assertEquals(2, streakAtRisk(starts, zone, LocalDate.parse("2026-10-11")))
        assertEquals(null, streakAtRisk(starts + at("2026-10-11"), zone, LocalDate.parse("2026-10-11")))
        assertEquals(null, streakAtRisk(starts, zone, LocalDate.parse("2026-10-12")))
        assertEquals(null, streakAtRisk(emptyList(), zone, LocalDate.parse("2026-10-11")))
    }
}

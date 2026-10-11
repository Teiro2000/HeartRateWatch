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
        assertEquals(4, streakDays(starts, zone))
    }

    @Test
    fun aGapEndsTheStreakAndNothingIsZero() {
        assertEquals(1, streakDays(listOf(at("2026-10-01"), at("2026-10-05")), zone))
        assertEquals(0, streakDays(emptyList(), zone))
    }
}

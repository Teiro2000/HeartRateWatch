package com.pedro.heartratewatch.mobile

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class MonthlyVolumeTest {
    private val zone = ZoneId.systemDefault()
    private fun at(day: String) = LocalDate.parse(day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun workout(day: String, vararg sets: LoggedSet) = WorkoutLog(
        id = day, startedAtMillis = at(day), templateName = "x",
        exercises = listOf(ExerciseLog("e-$day", "ex", "Ex", sets.toList()))
    )

    @Test
    fun sumsWeightTimesRepsForThisMonthOnly() {
        val history = listOf(
            workout("2026-10-02", LoggedSet("1", 100.0, 5), LoggedSet("2", 100.0, 5)),
            workout("2026-10-09", LoggedSet("3", 50.0, 10), LoggedSet("4", null, 20), LoggedSet("5", -30.0, 8)),
            workout("2026-09-30", LoggedSet("6", 200.0, 5))
        )
        assertEquals(1500.0, monthlyVolumeLb(history, at("2026-10-11")), 0.001)
    }
}

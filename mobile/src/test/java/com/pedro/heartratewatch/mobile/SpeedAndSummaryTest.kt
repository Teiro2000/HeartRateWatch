package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.RunSummary
import com.pedro.heartratewatch.shared.formatSpeed
import com.pedro.heartratewatch.shared.paceFromSpeedKmh
import com.pedro.heartratewatch.shared.speedInUnit
import com.pedro.heartratewatch.shared.speedKmhFromUnit
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeedAndSummaryTest {

    @Test
    fun formatsBikeSpeedInKmhAndMph() {
        assertEquals("25.0 km/h", formatSpeed(144, DistanceUnit.KILOMETERS))
        assertEquals("15.5 mph", formatSpeed(144, DistanceUnit.MILES))
    }

    @Test
    fun speedConvertsToPaceAndBack() {
        assertEquals(144, paceFromSpeedKmh(25.0))
        val mph = speedInUnit(25.0, DistanceUnit.MILES)
        assertEquals(25.0, speedKmhFromUnit(mph, DistanceUnit.MILES), 0.0001)
    }

    @Test
    fun runSummaryKeepsPackWeightAndStoppedTimeOverTheWire() {
        val walk = RunSummary(
            startedAtMillis = 1_000L, durationSeconds = 600, avgBpm = 100, maxBpm = 120, minBpm = 90,
            distanceMeters = 1_500f, avgPaceSecPerKm = 400, activityType = ActivityType.WALK,
            packWeightLb = 35f, stoppedSeconds = 0
        )
        assertEquals(walk, RunSummary.fromBytes(walk.toBytes()))
        val ride = walk.copy(activityType = ActivityType.BIKE, packWeightLb = 0f, stoppedSeconds = 125)
        assertEquals(ride, RunSummary.fromBytes(ride.toBytes()))
    }
}

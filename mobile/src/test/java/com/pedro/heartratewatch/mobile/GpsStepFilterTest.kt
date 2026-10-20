package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.GpsStepFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpsStepFilterTest {

    @Test
    fun aNormalRunningStepCountsInFull() {
        // 3 m/s for one second, the position agrees
        assertEquals(3f, GpsStepFilter.countedMeters(3f, 1.0, 3f, 6f)!!, 0.001f)
    }

    @Test
    fun aWanderingPositionIsCappedByTheDopplerSpeed() {
        // between tall buildings the position jumps 14 m in a second while the speed still says 3 m/s
        val counted = GpsStepFilter.countedMeters(14f, 1.0, 3f, 12f)!!
        assertEquals(3.8f, counted, 0.001f)
    }

    @Test
    fun aGapOfSeveralSecondsAllowsTheDistanceCoveredInThatTime() {
        // fixes were dropped for 5 s; 15 m is just what 3 m/s covers
        assertEquals(15f, GpsStepFilter.countedMeters(15f, 5.0, 3f, 8f)!!, 0.001f)
    }

    @Test
    fun jitterWhileStandingStillDoesNotCount() {
        assertNull(GpsStepFilter.countedMeters(6f, 1.0, 0.1f, 10f))
        assertNull(GpsStepFilter.countedMeters(2f, 1.0, 0.1f, 4f))
    }

    @Test
    fun aRealMoveWithNoSpeedReadingStillCountsOnceItClearsTheNoise() {
        assertEquals(8f, GpsStepFilter.countedMeters(8f, 3.0, null, 10f)!!, 0.001f)
        assertNull(GpsStepFilter.countedMeters(2f, 3.0, null, 10f))
    }

    @Test
    fun aWholeNoisyRunStaysCloseToTheRealDistance() {
        // 1000 s at 3 m/s = 3000 m, but every fix's position wanders by several meters (tall buildings)
        val random = java.util.Random(7)
        var raw = 0.0
        var filtered = 0.0
        var previous = 0.0
        for (i in 1..1000) {
            val position = 3.0 * i + random.nextGaussian() * 5.0
            val delta = Math.abs(position - previous).toFloat()
            previous = position
            raw += delta
            filtered += GpsStepFilter.countedMeters(delta, 1.0, 3f, 10f) ?: 0f
        }
        // adding up every wander roughly doubles the distance; the filter keeps most of that out
        assertTrue(raw > 5500.0)
        assertEquals(3000.0, filtered, 450.0)
    }
}

package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneSessionTrackerTest {

    /** Feeds one reading a second at a steady speed for [seconds], starting where the tracker left off. */
    private fun PhoneSessionTracker.move(fromSecond: Int, seconds: Int, metersPerSecond: Float) {
        for (i in 1..seconds) {
            val second = fromSecond + i
            onDistance(START + second * 1000L, distanceMeters + metersPerSecond)
        }
    }

    @Test
    fun steadyRunGivesTheMatchingPaceAndSplit() {
        val tracker = PhoneSessionTracker(ActivityType.RUN, START)
        tracker.move(0, 400, 3f)   // 3 m/s is 333 s/km
        assertEquals(333.0, tracker.currentPaceSecPerKm!!.toDouble(), 2.0)
        assertEquals(333.0, tracker.bestSplitSeconds(1_000f)!!.toDouble(), 2.0)
        assertNull(tracker.bestSplitSeconds(5_000f))
        val summary = tracker.summary(START + 400_000L)
        assertEquals(1200f, summary.distanceMeters, 0.5f)
        assertEquals(0, summary.avgBpm)
        assertEquals(0, summary.stoppedSeconds)
    }

    @Test
    fun aRideStopAddsStoppedTimeButDoesNotChangeTheSpeed() {
        val tracker = PhoneSessionTracker(ActivityType.BIKE, START)
        tracker.move(0, 60, 6f)                 // 21.6 km/h, 167 s/km
        val before = tracker.currentPaceSecPerKm
        assertNotNull(before)
        tracker.move(60, 120, 0f)               // two minutes at a red light
        assertEquals(before, tracker.currentPaceSecPerKm)
        assertEquals(120.0, (tracker.stoppedMillis / 1000).toDouble(), 2.0)
        tracker.move(180, 60, 6f)
        assertEquals(167.0, tracker.currentPaceSecPerKm!!.toDouble(), 3.0)
        val summary = tracker.summary(START + 240_000L)
        assertEquals(120.0, summary.stoppedSeconds.toDouble(), 3.0)
        // the average leaves the stop out: 720 m in 120 s of moving time
        assertEquals(167.0, summary.avgPaceSecPerKm!!.toDouble(), 5.0)
    }

    private companion object {
        const val START = 1_000_000L
    }
}

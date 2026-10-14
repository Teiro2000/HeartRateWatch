package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.RouteCodec
import com.pedro.heartratewatch.shared.RoutePoint
import com.pedro.heartratewatch.shared.RunSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordsTest {

    private fun run(
        start: Long, km: Float, best1: Int? = null, best5: Int? = null, type: ActivityType = ActivityType.RUN
    ) = RunSummary(
        startedAtMillis = start, durationSeconds = 1800, avgBpm = 150, maxBpm = 170, minBpm = 120,
        distanceMeters = km * 1000f, best1kmSeconds = best1, best5kmSeconds = best5, activityType = type
    )

    @Test
    fun theFirstSessionOfAnActivityIsOnlyABaseline() {
        assertTrue(Records.cardio(emptyList(), run(2, 5f, 300, 1500), DistanceUnit.KILOMETERS).isEmpty())
        val earlierWalk = run(1, 3f, type = ActivityType.WALK)
        assertTrue(Records.cardio(listOf(earlierWalk), run(2, 5f, 300, 1500), DistanceUnit.KILOMETERS).isEmpty())
    }

    @Test
    fun fasterSplitsAndLongerDistancesAreRecords() {
        val history = listOf(run(1, 5f, 320, 1600), run(2, 4f, 310, null))
        val records = Records.cardio(history, run(3, 6f, 305, 1500), DistanceUnit.KILOMETERS)
        assertEquals(listOf("Fastest 1 km: 5:05", "Fastest 5 km: 25:00", "Longest run: 6.00 km"), records)
    }

    @Test
    fun aFirstTimeAtADistanceAndSlowerRunsAreHandledToo() {
        val history = listOf(run(1, 3f, 300, null))
        val first5k = Records.cardio(history, run(2, 5f, 310, 1700), DistanceUnit.KILOMETERS)
        assertEquals(listOf("First 5 km run", "Longest run: 5.00 km"), first5k)
        assertTrue(Records.cardio(history, run(3, 2f, 400, null), DistanceUnit.KILOMETERS).isEmpty())
    }

    @Test
    fun routesRoundTripAndShrink() {
        val points = List(1000) { RoutePoint(40.0 + it * 1e-4, -74.0 - it * 1e-4) }
        assertEquals(points.take(3), RouteCodec.decode(RouteCodec.encode(points.take(3))))
        val small = RouteCodec.downsample(points, 400)
        assertEquals(400, small.size)
        assertEquals(points.first(), small.first())
        assertEquals(points.last(), small.last())
    }
}

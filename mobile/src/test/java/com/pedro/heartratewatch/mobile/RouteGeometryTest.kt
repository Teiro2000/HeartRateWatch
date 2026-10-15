package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.RouteCodec
import com.pedro.heartratewatch.shared.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class RouteGeometryTest {

    /** A straight route heading north from the equator: [count] points, [stepMeters] apart, one every [secondsPerStep] seconds. */
    private fun straightRoute(count: Int, stepMeters: Double, secondsPerStep: (Int) -> Long = { 10L }): List<RoutePoint> {
        val degreesPerMeter = 1.0 / 111_195.0
        var time = 1_000_000L
        return List(count) { i ->
            if (i > 0) time += secondsPerStep(i) * 1000
            RoutePoint(i * stepMeters * degreesPerMeter, 0.0, time)
        }
    }

    @Test
    fun distanceAlongTheRouteAddsUp() {
        val route = straightRoute(11, 100.0)
        assertEquals(1000.0, RouteGeometry.cumulativeMeters(route).last(), 5.0)
    }

    @Test
    fun markersSitEveryUnitAlongTheRoute() {
        val route = straightRoute(32, 100.0)   // 3.1 km
        val markers = RouteGeometry.markers(route, spacingMeters = 1000.0)
        assertEquals(listOf(1000.0, 2000.0, 3000.0), markers.map { it.meters })
        // the first sits 1 km up the line
        assertEquals(1000.0 / 111_195.0, markers[0].latitude, 1e-5)
    }

    @Test
    fun aVeryShortSpacingIsWidenedSoTheRouteIsNotCoveredInMarkers() {
        val route = straightRoute(101, 100.0)   // 10 km
        val markers = RouteGeometry.markers(route, spacingMeters = 10.0, maxMarkers = 20)
        assertTrue(markers.size <= 20)
        assertTrue(markers.isNotEmpty())
    }

    @Test
    fun slowStretchesGetHigherSlownessThanFastOnes() {
        // fast first half (5 s per step), slow second half (20 s per step)
        val route = straightRoute(41, 20.0, secondsPerStep = { if (it <= 20) 5L else 20L })
        val slowness = RouteGeometry.slowness(route)!!
        assertEquals(40, slowness.size)
        assertTrue(slowness[2] < 0.2f)
        assertTrue(slowness[37] > 0.8f)
    }

    @Test
    fun aStandstillIsTheSlowestPart() {
        val moving = straightRoute(21, 20.0)
        val last = moving.last()
        val stopped = List(10) { RoutePoint(last.latitude, last.longitude, last.timeMillis + (it + 1) * 10_000L) }
        val slowness = RouteGeometry.slowness(moving + stopped)!!
        assertEquals(1f, slowness.last(), 0.001f)
    }

    @Test
    fun routesWithoutTimesHaveNoPaceColours() {
        assertNull(RouteGeometry.slowness(straightRoute(10, 20.0).map { it.copy(timeMillis = 0L) }))
        assertNotNull(RouteGeometry.slowness(straightRoute(10, 20.0)))
    }

    @Test
    fun routesSavedBeforeTimesWereKeptStillLoad() {
        val old = ByteBuffer.allocate(4 + 2 * 16).putInt(2).putDouble(40.0).putDouble(-74.0).putDouble(40.1).putDouble(-74.1).array()
        val decoded = RouteCodec.decode(old)
        assertEquals(listOf(RoutePoint(40.0, -74.0), RoutePoint(40.1, -74.1)), decoded)
        val current = listOf(RoutePoint(40.0, -74.0, 123L), RoutePoint(40.1, -74.1, 456L))
        assertEquals(current, RouteCodec.decode(RouteCodec.encode(current)))
    }
}

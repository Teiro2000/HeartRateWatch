package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.RouteCodec
import com.pedro.heartratewatch.shared.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteElevationTest {

    private val degreesPerMeter = 1.0 / 111_195.0

    private fun route(vararg altitudes: Double) = altitudes.mapIndexed { i, a ->
        RoutePoint(i * 10 * degreesPerMeter, 0.0, 1_000_000L + i * 5_000L, a)
    }

    @Test
    fun aRouteWithoutHeightsHasNoElevation() {
        assertNull(RouteGeometry.elevation(listOf(RoutePoint(0.0, 0.0), RoutePoint(0.001, 0.0))))
    }

    @Test
    fun aSteadyClimbIsCountedAsClimbOnly() {
        val e = RouteGeometry.elevation(route(*DoubleArray(21) { 100.0 + it * 2.0 }), window = 1)!!
        assertEquals(40.0, e.climb, 4.0)
        assertEquals(0.0, e.descent, 0.001)
        assertEquals(100.0, e.min, 0.001)
        assertEquals(140.0, e.max, 0.001)
    }

    @Test
    fun upThenDownCountsBoth() {
        val up = DoubleArray(11) { 100.0 + it * 3.0 }
        val down = DoubleArray(11) { 130.0 - it * 3.0 }
        val e = RouteGeometry.elevation(route(*(up + down)), window = 1)!!
        assertEquals(30.0, e.climb, 4.0)
        assertEquals(30.0, e.descent, 4.0)
    }

    @Test
    fun noiseSmallerThanTheThresholdIsNotAHill() {
        val e = RouteGeometry.elevation(route(*DoubleArray(40) { 100.0 + if (it % 2 == 0) 0.8 else -0.8 }), window = 1)!!
        assertEquals(0.0, e.climb, 0.001)
        assertEquals(0.0, e.descent, 0.001)
    }

    @Test
    fun pointsWithoutAHeightTakeTheNearestKnownOne() {
        val points = route(10.0, 20.0, 30.0).toMutableList()
        points[1] = points[1].copy(altitudeMeters = Double.NaN)
        val e = RouteGeometry.elevation(points, window = 1)!!
        assertEquals(10.0, e.meters[1], 0.001)   // equally near both, the earlier one wins
        assertEquals(3, e.meters.size)
    }

    @Test
    fun paceIsSecondsPerKilometerAroundAPoint() {
        // 10 m every 5 s = 0.5 s per meter = 500 s/km
        val pace = RouteGeometry.paceSecPerKm(route(*DoubleArray(10) { 0.0 }), index = 5)
        assertNotNull(pace)
        assertEquals(500.0, pace!!.toDouble(), 10.0)
    }

    @Test
    fun paceNeedsTimes() {
        val noTimes = listOf(RoutePoint(0.0, 0.0), RoutePoint(0.001, 0.0), RoutePoint(0.002, 0.0))
        assertNull(RouteGeometry.paceSecPerKm(noTimes, 1))
    }

    @Test
    fun heightsAndTheBarometerFlagSurviveSaving() {
        val points = route(100.0, 105.5, Double.NaN)
        val decoded = RouteCodec.decodeRoute(RouteCodec.encode(points, barometric = true))
        assertEquals(points, decoded.points)
        assertTrue(decoded.barometric)
        assertFalse(RouteCodec.decodeRoute(RouteCodec.encode(points)).barometric)
        assertTrue(decoded.points[0].hasAltitude)
        assertFalse(decoded.points[2].hasAltitude)
    }
}

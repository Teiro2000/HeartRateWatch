package com.pedro.heartratewatch.mobile

import com.pedro.heartratewatch.shared.RoutePoint
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The numbers behind the route drawing: distances along the route, where the distance markers go, and how fast each part was. Pure, so it can be tested. */
object RouteGeometry {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /** Great-circle distance between two points, in meters. */
    fun distanceMeters(a: RoutePoint, b: RoutePoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /** Meters travelled from the start at each point (so the first is 0). */
    fun cumulativeMeters(points: List<RoutePoint>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distanceMeters(points[i - 1], points[i])
        return out
    }

    /** A spot on the route [meters] from the start. */
    data class Marker(val latitude: Double, val longitude: Double, val meters: Double)

    /**
     * A marker every [spacingMeters] along the route (1 km or 1 mile, say). If that would be more than [maxMarkers],
     * the spacing is widened to a whole multiple of itself so the route isn't covered in them.
     */
    fun markers(points: List<RoutePoint>, spacingMeters: Double, maxMarkers: Int = 20): List<Marker> {
        if (points.size < 2 || spacingMeters <= 0.0) return emptyList()
        val cumulative = cumulativeMeters(points)
        val total = cumulative.last()
        val step = spacingMeters * max(1.0, ceil(total / spacingMeters / maxMarkers))
        val out = mutableListOf<Marker>()
        var target = step
        var i = 1
        while (target <= total && i < points.size) {
            while (i < points.size - 1 && cumulative[i] < target) i++
            val before = cumulative[i - 1]
            val span = cumulative[i] - before
            val t = if (span <= 0.0) 0.0 else (target - before) / span
            out += Marker(
                points[i - 1].latitude + (points[i].latitude - points[i - 1].latitude) * t,
                points[i - 1].longitude + (points[i].longitude - points[i - 1].longitude) * t,
                target
            )
            target += step
        }
        return out
    }

    /** Heights along a route, cleaned up for drawing and for climb totals. */
    class Elevation(val meters: DoubleArray, val climb: Double, val descent: Double, val min: Double, val max: Double)

    /**
     * The route's altitude at each point, smoothed over [window] points (GPS height is noisy), with points that have no
     * altitude taking the nearest one that does. Null when no point has an altitude (routes from before heights were kept).
     * The climb and descent only count rises and falls bigger than [threshold] meters, so noise doesn't add up to a hill.
     */
    fun elevation(points: List<RoutePoint>, window: Int = 5, threshold: Double = 3.0): Elevation? {
        val known = points.indices.filter { points[it].hasAltitude }
        if (known.isEmpty()) return null
        val filled = DoubleArray(points.size) { i ->
            if (points[i].hasAltitude) points[i].altitudeMeters
            else points[known.minBy { k -> kotlin.math.abs(k - i) }].altitudeMeters
        }
        val half = max(0, window / 2)
        val smooth = DoubleArray(points.size) { i ->
            val from = max(0, i - half)
            val to = min(points.size - 1, i + half)
            var sum = 0.0
            for (j in from..to) sum += filled[j]
            sum / (to - from + 1)
        }
        var climb = 0.0
        var descent = 0.0
        var reference = smooth[0]
        for (a in smooth) {
            if (a > reference + threshold) { climb += a - reference; reference = a }
            else if (a < reference - threshold) { descent += reference - a; reference = a }
        }
        return Elevation(smooth, climb, descent, smooth.min(), smooth.max())
    }

    /** Pace in seconds per kilometer around point [index] (a few points either side), or null without times or movement. */
    fun paceSecPerKm(points: List<RoutePoint>, index: Int, radius: Int = 2): Int? {
        if (points.size < 2) return null
        val from = max(0, index - radius)
        val to = min(points.size - 1, index + radius)
        if (from >= to || points[from].timeMillis <= 0L || points[to].timeMillis <= 0L) return null
        var meters = 0.0
        for (i in from until to) meters += distanceMeters(points[i], points[i + 1])
        val seconds = (points[to].timeMillis - points[from].timeMillis) / 1000.0
        if (meters < 5.0 || seconds <= 0.0) return null
        return (seconds / meters * 1000.0).toInt()
    }

    /**
     * How slow each segment (point to the next) was compared with the rest of the same route, from 0 (the fastest
     * stretches) to 1 (the slowest), or null when the route has no times. Each value averages a few segments around it, so
     * GPS noise doesn't make the colours flicker, and the scale runs from the 10th to the 90th percentile so one odd
     * stretch (a stop) doesn't wash out the rest.
     */
    fun slowness(points: List<RoutePoint>, window: Int = 4): List<Float>? {
        if (points.size < 2 || points.any { it.timeMillis <= 0L }) return null
        val n = points.size - 1
        val meters = DoubleArray(n) { distanceMeters(points[it], points[it + 1]) }
        val seconds = DoubleArray(n) { max(0.0, (points[it + 1].timeMillis - points[it].timeMillis) / 1000.0) }
        // Seconds per meter over a window around each segment; a stretch with no movement is the slowest of all.
        val pace = DoubleArray(n) { i ->
            val from = max(0, i - window / 2)
            val to = min(n - 1, i + window / 2)
            var m = 0.0
            var s = 0.0
            for (j in from..to) { m += meters[j]; s += seconds[j] }
            if (m < 1.0) Double.POSITIVE_INFINITY else s / m
        }
        val finite = pace.filter { it.isFinite() }.sorted()
        if (finite.isEmpty()) return List(n) { 0.5f }
        val low = finite[(finite.size * 0.10).toInt().coerceIn(0, finite.size - 1)]
        val high = finite[(finite.size * 0.90).toInt().coerceIn(0, finite.size - 1)]
        return pace.map { p ->
            when {
                !p.isFinite() -> 1f
                high <= low -> 0.5f
                else -> ((p - low) / (high - low)).toFloat().coerceIn(0f, 1f)
            }
        }
    }
}

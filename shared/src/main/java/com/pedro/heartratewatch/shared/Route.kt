package com.pedro.heartratewatch.shared

import java.nio.ByteBuffer

/**
 * One GPS position on a route, when it was recorded (0 for routes saved before times were kept), and its
 * altitude in meters ([Double.NaN] when unknown: routes saved before altitude was kept, or a device that couldn't tell).
 */
data class RoutePoint(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long = 0L,
    val altitudeMeters: Double = Double.NaN
) {
    val hasAltitude: Boolean get() = !altitudeMeters.isNaN()

    // NaN is never equal to itself, which would make two identical points unequal.
    override fun equals(other: Any?): Boolean =
        other is RoutePoint && latitude == other.latitude && longitude == other.longitude && timeMillis == other.timeMillis &&
            (altitudeMeters == other.altitudeMeters || (altitudeMeters.isNaN() && other.altitudeMeters.isNaN()))

    override fun hashCode(): Int = listOf(latitude, longitude, timeMillis, if (altitudeMeters.isNaN()) 0.0 else altitudeMeters).hashCode()
}

/** A decoded route, and whether its altitudes came from a barometer (accurate for climbs) rather than GPS. */
data class DecodedRoute(val points: List<RoutePoint>, val barometric: Boolean)

/**
 * A session's GPS route, as a compact byte array for sending from the watch to the phone and for saving on the
 * phone. Formats, newest first:
 *  - current: -3, the number of points, a flags number (1 = altitudes are from a barometer), then latitude, longitude,
 *    time and altitude of each point;
 *  - before altitude: -2, the number of points, then latitude, longitude and time of each;
 *  - before times: the number of points, then latitude and longitude of each.
 * All three are still read.
 */
object RouteCodec {

    private const val VERSION_WITH_ALTITUDE = -3
    private const val VERSION_WITH_TIME = -2
    private const val FLAG_BAROMETRIC = 1

    fun encode(points: List<RoutePoint>, barometric: Boolean = false): ByteArray {
        val buffer = ByteBuffer.allocate(12 + points.size * 32)
        buffer.putInt(VERSION_WITH_ALTITUDE)
        buffer.putInt(points.size)
        buffer.putInt(if (barometric) FLAG_BAROMETRIC else 0)
        points.forEach { buffer.putDouble(it.latitude).putDouble(it.longitude).putLong(it.timeMillis).putDouble(it.altitudeMeters) }
        return buffer.array()
    }

    fun decodeRoute(bytes: ByteArray): DecodedRoute {
        if (bytes.size < 4) return DecodedRoute(emptyList(), false)
        val buffer = ByteBuffer.wrap(bytes)
        val first = buffer.int
        return when (first) {
            VERSION_WITH_ALTITUDE -> {
                if (bytes.size < 12) return DecodedRoute(emptyList(), false)
                val count = buffer.int
                val flags = buffer.int
                if (count < 0 || bytes.size < 12 + count * 32) return DecodedRoute(emptyList(), false)
                DecodedRoute(List(count) { RoutePoint(buffer.double, buffer.double, buffer.long, buffer.double) }, flags and FLAG_BAROMETRIC != 0)
            }
            VERSION_WITH_TIME -> {
                if (bytes.size < 8) return DecodedRoute(emptyList(), false)
                val count = buffer.int
                if (count < 0 || bytes.size < 8 + count * 24) return DecodedRoute(emptyList(), false)
                DecodedRoute(List(count) { RoutePoint(buffer.double, buffer.double, buffer.long) }, false)
            }
            else -> {
                if (first < 0 || bytes.size < 4 + first * 16) return DecodedRoute(emptyList(), false)
                DecodedRoute(List(first) { RoutePoint(buffer.double, buffer.double) }, false)
            }
        }
    }

    fun decode(bytes: ByteArray): List<RoutePoint> = decodeRoute(bytes).points

    /**
     * At most [max] points, picked evenly along the route and always keeping the first and the last,
     * so a long session's route stays small to send and quick to draw without changing its shape much.
     */
    fun downsample(points: List<RoutePoint>, max: Int = 400): List<RoutePoint> {
        if (points.size <= max || max < 2) return points
        return List(max) { i -> points[(i.toLong() * (points.size - 1) / (max - 1)).toInt()] }
    }
}

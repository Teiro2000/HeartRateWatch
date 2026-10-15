package com.pedro.heartratewatch.shared

import java.nio.ByteBuffer

/** One GPS position on a route, and when it was recorded (0 for routes saved before times were kept). */
data class RoutePoint(val latitude: Double, val longitude: Double, val timeMillis: Long = 0L)

/**
 * A session's GPS route, as a compact byte array for sending from the watch to the phone and for
 * saving on the phone. The current format starts with a marker (-2), then the number of points, then
 * latitude, longitude and time of each. The older format (no times) started straight with the number
 * of points, then latitude and longitude of each; it is still read, with times of 0.
 */
object RouteCodec {

    private const val VERSION_WITH_TIME = -2

    fun encode(points: List<RoutePoint>): ByteArray {
        val buffer = ByteBuffer.allocate(8 + points.size * 24)
        buffer.putInt(VERSION_WITH_TIME)
        buffer.putInt(points.size)
        points.forEach { buffer.putDouble(it.latitude).putDouble(it.longitude).putLong(it.timeMillis) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<RoutePoint> {
        if (bytes.size < 4) return emptyList()
        val buffer = ByteBuffer.wrap(bytes)
        val first = buffer.int
        if (first == VERSION_WITH_TIME) {
            if (bytes.size < 8) return emptyList()
            val count = buffer.int
            if (count < 0 || bytes.size < 8 + count * 24) return emptyList()
            return List(count) { RoutePoint(buffer.double, buffer.double, buffer.long) }
        }
        // The older format.
        if (first < 0 || bytes.size < 4 + first * 16) return emptyList()
        return List(first) { RoutePoint(buffer.double, buffer.double) }
    }

    /**
     * At most [max] points, picked evenly along the route and always keeping the first and the last,
     * so a long session's route stays small to send and quick to draw without changing its shape much.
     */
    fun downsample(points: List<RoutePoint>, max: Int = 400): List<RoutePoint> {
        if (points.size <= max || max < 2) return points
        return List(max) { i -> points[(i.toLong() * (points.size - 1) / (max - 1)).toInt()] }
    }
}

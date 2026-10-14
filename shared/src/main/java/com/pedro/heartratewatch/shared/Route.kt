package com.pedro.heartratewatch.shared

import java.nio.ByteBuffer

/** One GPS position on a route. */
data class RoutePoint(val latitude: Double, val longitude: Double)

/**
 * A session's GPS route, as a compact byte array for sending from the watch to the phone and for
 * saving on the phone: the number of points, then latitude and longitude of each as doubles.
 */
object RouteCodec {

    fun encode(points: List<RoutePoint>): ByteArray {
        val buffer = ByteBuffer.allocate(4 + points.size * 16)
        buffer.putInt(points.size)
        points.forEach { buffer.putDouble(it.latitude).putDouble(it.longitude) }
        return buffer.array()
    }

    fun decode(bytes: ByteArray): List<RoutePoint> {
        if (bytes.size < 4) return emptyList()
        val buffer = ByteBuffer.wrap(bytes)
        val count = buffer.int
        if (count < 0 || bytes.size < 4 + count * 16) return emptyList()
        return List(count) { RoutePoint(buffer.double, buffer.double) }
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

package com.pedro.heartratewatch.mobile

import android.content.Context
import com.pedro.heartratewatch.shared.RouteCodec
import com.pedro.heartratewatch.shared.RoutePoint
import java.io.File

/**
 * GPS routes of outdoor sessions, one small file per session named after its start time, in the
 * app's private storage. Kept out of the session list itself so a long history stays light.
 */
class RouteStore(context: Context) {

    private val dir = File(context.filesDir, "routes").also { it.mkdirs() }

    private fun file(startedAtMillis: Long) = File(dir, "$startedAtMillis.bin")

    fun save(startedAtMillis: Long, encoded: ByteArray) {
        runCatching { file(startedAtMillis).writeBytes(encoded) }
    }

    fun load(startedAtMillis: Long): List<RoutePoint> =
        runCatching { RouteCodec.decode(file(startedAtMillis).readBytes()) }.getOrDefault(emptyList())

    fun delete(startedAtMillis: Long) {
        runCatching { file(startedAtMillis).delete() }
    }

    fun deleteAll() {
        runCatching { dir.listFiles()?.forEach { it.delete() } }
    }
}

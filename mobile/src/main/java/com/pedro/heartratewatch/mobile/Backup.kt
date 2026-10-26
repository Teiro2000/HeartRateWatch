package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One file with everything the app keeps on this phone: the saved data of every part of it (cardio
 * history, workouts, templates and exercises, settings, personal bests, level progress, calories and the food
 * photos) and the saved routes. It's a zip of the app's own data files, so a restore brings back exactly what was saved.
 *
 * Not included: the alert volume (kept in a plain settings file, not the data files) and the data kept on the watch.
 */
object Backup {

    private const val FORMAT = 1
    private const val META = "meta.json"
    private val ALLOWED = Regex("^(datastore/[A-Za-z0-9_.-]+\\.preferences_pb|routes/[0-9]+\\.bin|food_photos/[A-Za-z0-9-]+\\.jpg)$")

    private fun dataFiles(context: Context): List<Pair<String, File>> {
        val datastore = File(context.filesDir, "datastore").listFiles { f -> f.name.endsWith(".preferences_pb") }.orEmpty()
        val routes = File(context.filesDir, "routes").listFiles { f -> f.name.endsWith(".bin") }.orEmpty()
        val photos = File(context.filesDir, FoodPhotos.FOLDER).listFiles { f -> f.name.endsWith(".jpg") }.orEmpty()
        return datastore.map { "datastore/${it.name}" to it } + routes.map { "routes/${it.name}" to it } +
            photos.map { "${FoodPhotos.FOLDER}/${it.name}" to it }
    }

    /** Writes the backup; returns how many data files went in. */
    fun write(context: Context, out: OutputStream): Int {
        val files = dataFiles(context)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(META))
            zip.write(
                JSONObject().put("app", "PulseGuard").put("format", FORMAT)
                    .put("createdAt", System.currentTimeMillis()).toString().toByteArray()
            )
            zip.closeEntry()
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.size
    }

    /**
     * Reads a backup and, if it is a valid one, replaces this phone's data with it. Returns false
     * (changing nothing) for a file that isn't a PulseGuard backup. On success the app restarts, because
     * the screens hold the old data in memory.
     */
    fun restore(context: Context, input: InputStream): Boolean {
        val entries = LinkedHashMap<String, ByteArray>()
        var valid = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val bytes = zip.readBytes()
                if (entry.name == META) {
                    val meta = runCatching { JSONObject(String(bytes)) }.getOrNull()
                    valid = meta?.optString("app") == "PulseGuard" && meta.optInt("format") == FORMAT
                } else if (ALLOWED.matches(entry.name)) {
                    entries[entry.name] = bytes
                }
            }
        }
        if (!valid) return false

        // Only the data this app owns is replaced: everything old goes, then the backup's files are written.
        dataFiles(context).forEach { (_, file) -> file.delete() }
        File(context.filesDir, "datastore").mkdirs()
        File(context.filesDir, "routes").mkdirs()
        File(context.filesDir, FoodPhotos.FOLDER).mkdirs()
        entries.forEach { (name, bytes) -> File(context.filesDir, name).writeBytes(bytes) }

        // The watch needs the restored settings and stats pushed to it once the app is back up (see HomeScreen).
        context.getSharedPreferences(STATE, Context.MODE_PRIVATE).edit().putBoolean(NEEDS_RESYNC, true).commit()
        restartApp(context)
        return true
    }

    /** True once after a restore: the app should push its restored settings and stats to the watch. */
    fun takeNeedsResync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(STATE, Context.MODE_PRIVATE)
        val needed = prefs.getBoolean(NEEDS_RESYNC, false)
        if (needed) prefs.edit().putBoolean(NEEDS_RESYNC, false).apply()
        return needed
    }

    private fun restartApp(context: Context) {
        val app = context.applicationContext
        app.startActivity(Intent(app, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // A fresh process, so nothing keeps showing (or writing back) the data that was just replaced.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 400)
    }

    private const val STATE = "backup_state"
    private const val NEEDS_RESYNC = "needs_resync"
}

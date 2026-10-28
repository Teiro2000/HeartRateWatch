package com.pedro.heartratewatch.mobile

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import com.pedro.heartratewatch.shared.AlertType
import kotlin.math.pow

/**
 * Plays the alert audio on the PHONE when the watch reports a threshold crossing. Because the
 * phone is what's actually connected to your headphones/AirPods, this is what makes "play a
 * break alert in my headset while the app runs on my watch" work -- see the project's
 * requirements.md doc, "Cross-device audio" section, for the reasoning.
 *
 * The sounds are the ones bundled with the app (res/raw), one per alert type; the only thing
 * you can change is how loud they all are (see [volumeDb]).
 *
 * Alerts never cut each other off: one that arrives while another is playing waits its turn. The
 * wait list is deliberately short and expires quickly, since a run changes fast and a cue that
 * plays 30 seconds late describes a situation that's already over -- see [play].
 */
class AlertPlayer(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("custom_sounds", Context.MODE_PRIVATE)

    /** Loudness of every alert in dB relative to the sound's own level: negative is quieter, positive
     * boosts past what the player's volume alone can reach (see [startLocked]). 0 = unchanged. */
    fun volumeDb(): Int {
        if (prefs.contains(VOLUME_KEY)) return prefs.getInt(VOLUME_KEY, 0)
        // From when each alert had its own volume: keep the first one that was changed.
        return AlertType.entries.map { prefs.getInt("volume_db_${it.name}", 0) }.firstOrNull { it != 0 } ?: 0
    }

    fun setVolumeDb(db: Int) {
        prefs.edit().putInt(VOLUME_KEY, db.coerceIn(MIN_VOLUME_DB, MAX_VOLUME_DB)).apply()
    }

    /**
     * Plays [type] now if nothing's playing, otherwise queues it behind the current one instead of
     * cutting it off. The queue stays short on purpose: an alert already playing or waiting is not
     * queued again, only [MAX_QUEUED] wait at once (a newer arrival pushes the oldest out), and
     * anything that's waited longer than [MAX_QUEUE_AGE_MILLIS] is dropped rather than played late.
     */
    fun play(type: AlertType) {
        synchronized(lock) {
            if (currentPlayer == null) {
                if (!startLocked(type)) playNextLocked()
                return
            }
            if (currentType == type || queue.any { it.type == type }) return
            dropStaleLocked()
            if (queue.size >= MAX_QUEUED) queue.removeFirst()
            queue.addLast(Pending(type, System.currentTimeMillis()))
        }
    }

    /** For the Settings test buttons: plays immediately, replacing anything playing or waiting, so
     * a test always responds to the tap and always uses the current sound + volume. */
    fun playNow(type: AlertType) {
        synchronized(lock) {
            queue.clear()
            stopLocked()
            startLocked(type)
        }
    }

    /** Stops whatever alert sound is playing and drops any waiting behind it -- called when the
     * watch says the situation that triggered them has passed. */
    fun stop() {
        synchronized(lock) {
            queue.clear()
            stopLocked()
        }
    }

    private fun startLocked(type: AlertType): Boolean {
        val uri = Uri.parse("android.resource://${context.packageName}/${soundResId(type)}")
        val db = volumeDb()

        val player = MediaPlayer()
        return try {
            player.apply {
                setDataSource(context, uri)
                setOnCompletionListener { finished ->
                    synchronized(lock) {
                        if (currentPlayer === finished) {
                            releaseCurrentLocked()
                            playNextLocked()
                        }
                    }
                }
                setOnErrorListener { failed, _, _ ->
                    synchronized(lock) {
                        if (currentPlayer === failed) {
                            releaseCurrentLocked()
                            playNextLocked()
                        }
                    }
                    true
                }
                prepare()
                if (db <= 0) {
                    val gain = 10.0.pow(db / 20.0).toFloat()
                    setVolume(gain, gain)
                } else {
                    // MediaPlayer's own volume tops out at 1.0, so anything louder needs an
                    // effect on the audio session. If the effect can't be created the alert still
                    // plays, just unboosted.
                    setVolume(1f, 1f)
                    currentEnhancer = runCatching {
                        LoudnessEnhancer(audioSessionId).apply {
                            setTargetGain(db * 100)
                            enabled = true
                        }
                    }.getOrNull()
                }
                currentPlayer = this
                currentType = type
                start()
            }
            true
        } catch (e: Exception) {
            runCatching { player.release() }
            releaseCurrentLocked()
            false
        }
    }

    private fun playNextLocked() {
        dropStaleLocked()
        while (queue.isNotEmpty()) {
            if (startLocked(queue.removeFirst().type)) return
        }
    }

    private fun dropStaleLocked() {
        val cutoff = System.currentTimeMillis() - MAX_QUEUE_AGE_MILLIS
        queue.removeAll { it.queuedAtMillis < cutoff }
    }

    private fun stopLocked() {
        currentPlayer?.let { player -> runCatching { if (player.isPlaying) player.stop() } }
        releaseCurrentLocked()
    }

    private fun releaseCurrentLocked() {
        runCatching { currentEnhancer?.release() }
        currentEnhancer = null
        runCatching { currentPlayer?.release() }
        currentPlayer = null
        currentType = null
    }

    private fun soundResId(type: AlertType): Int = when (type) {
        AlertType.HIGH_HR -> R.raw.alert_high_hr
        AlertType.LOW_HR -> R.raw.alert_low_hr
        AlertType.PACE_TOO_FAST -> R.raw.alert_pace_too_fast
        AlertType.PACE_TOO_SLOW -> R.raw.alert_pace_too_slow
        AlertType.TARGET_REACHED -> R.raw.alert_target_reached
        AlertType.HALFWAY -> R.raw.alert_halfway
    }

    private class Pending(val type: AlertType, val queuedAtMillis: Long)

    companion object {
        private const val VOLUME_KEY = "volume_db"
        const val MIN_VOLUME_DB = -20
        const val MAX_VOLUME_DB = 12

        // Short and quickly expiring on purpose -- see play().
        private const val MAX_QUEUED = 2
        private const val MAX_QUEUE_AGE_MILLIS = 10_000L

        // A new AlertPlayer is constructed per incoming message (see
        // WearMessageListenerService), so playback state has to be shared across instances for
        // stop() and the queue to see what earlier instances started. Everything below is guarded
        // by `lock` -- messages arrive on a background thread while completion callbacks fire on
        // another.
        private val lock = Any()
        private val queue = ArrayDeque<Pending>()
        private var currentPlayer: MediaPlayer? = null
        private var currentEnhancer: LoudnessEnhancer? = null
        private var currentType: AlertType? = null
    }
}

package com.pedro.heartratewatch.mobile

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

private val Context.streakReminderDataStore by preferencesDataStore(name = "streak_reminder")

/** Whether the daily streak reminder is on, and the time of day it goes off. */
data class StreakReminderConfig(val enabled: Boolean = false, val hour: Int = 20, val minute: Int = 0)

class StreakReminderRepository(private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val HOUR = intPreferencesKey("hour")
        val MINUTE = intPreferencesKey("minute")
    }

    val configFlow: Flow<StreakReminderConfig> = context.streakReminderDataStore.data.map {
        val default = StreakReminderConfig()
        StreakReminderConfig(it[Keys.ENABLED] ?: default.enabled, it[Keys.HOUR] ?: default.hour, it[Keys.MINUTE] ?: default.minute)
    }

    suspend fun save(config: StreakReminderConfig) {
        context.streakReminderDataStore.edit {
            it[Keys.ENABLED] = config.enabled
            it[Keys.HOUR] = config.hour
            it[Keys.MINUTE] = config.minute
        }
        StreakReminder.schedule(context, config)
    }

    suspend fun current(): StreakReminderConfig = configFlow.first()
}

/**
 * The daily "keep your streak going" reminder. An alarm goes off at the chosen time each day; the
 * receiver then checks the cardio and gym history and only posts the notification when a streak is
 * alive but today hasn't earned enough XP to count yet (the notification says how much is still needed). The alarm is inexact (it can
 * land a few minutes late while the phone is idle), which avoids needing the exact-alarm permission.
 */
object StreakReminder {
    private const val CHANNEL_ID = "streak_reminder"
    private const val NOTIFICATION_ID = 4001
    private const val REQUEST_CODE = 4002

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        REQUEST_CODE,
        Intent(context, StreakReminderReceiver::class.java).setAction(StreakReminderReceiver.ACTION_REMIND),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Sets (or cancels) the next alarm: today at the chosen time if that's still ahead, else tomorrow. */
    fun schedule(context: Context, config: StreakReminderConfig) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = alarmIntent(context)
        if (!config.enabled) {
            alarms.cancel(intent)
            return
        }
        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        var next = now.toLocalDate().atTime(config.hour, config.minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.atZone(zone).toInstant().toEpochMilli(), intent)
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** What the reminder says: how much XP is still needed to complete today's streak. */
    internal fun reminderText(todayXp: Int): String {
        val needed = (Progression.STREAK_XP - todayXp).coerceAtLeast(1)
        return if (todayXp > 0) "You need $needed more XP to complete today's streak ($todayXp of ${Progression.STREAK_XP} so far)."
        else "You need $needed XP to complete today's streak."
    }

    internal fun notify(context: Context, streak: Int, todayXp: Int) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Streak reminder", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val days = if (streak == 1) "1-day" else "$streak-day"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_streak)
            .setContentTitle("Keep your $days streak going")
            .setContentText(reminderText(todayXp))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }
}

/** Fires the daily alarm, and re-arms it after a reboot or a clock/timezone change. */
class StreakReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val config = StreakReminderRepository(app).current()
                if (config.enabled && intent.action == ACTION_REMIND) {
                    // The same streak the main menu shows: only sessions that were a real effort count.
                    val progress = ProgressionInputs.breakdown(app)
                    if (progress.streakDays > 0 && !progress.todayCounts) StreakReminder.notify(app, progress.streakDays, progress.todayXp)
                }
                StreakReminder.schedule(app, config)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "com.pedro.heartratewatch.mobile.STREAK_REMIND"
    }
}

package com.pedro.heartratewatch.mobile

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.RunSummary
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/**
 * Where a finished cardio session lands, whether it was tracked by the watch or by the phone itself:
 * it is added to History (once -- a session that arrives twice is ignored), its route is saved, and
 * any personal bests it set are recorded and announced.
 */
object SessionIngest {

    suspend fun ingest(context: Context, summary: RunSummary, route: ByteArray?): Boolean {
        val app = context.applicationContext
        val history = RunHistoryRepository(app)
        val before = history.historyFlow.first()
        if (!history.addRun(summary)) return false
        if (route != null && route.isNotEmpty()) RouteStore(app).save(summary.startedAtMillis, route)

        val unit = DisplayDistanceUnitRepository(app).overrideFlow.first()
            ?: SettingsRepository(app).settingsFlow.first().distanceUnit
        val texts = Records.cardio(before, summary, unit)
        if (texts.isNotEmpty()) {
            val now = System.currentTimeMillis()
            RecordsRepository(app).add(texts.map { RecordEvent(now, it) })
            notifyRecords(app, texts)
        }
        return true
    }

    /**
     * Sessions the watch has finished but the phone hasn't picked up yet (the watch was out of range
     * at the time, or this app wasn't running): read them from the Data Layer, add them, and clear
     * them from it. Called whenever the app opens, and the listener service handles new ones live.
     */
    suspend fun pullPending(context: Context) {
        val app = context.applicationContext
        val client = Wearable.getDataClient(app)
        val uri = Uri.Builder().scheme("wear").path(DataLayerPaths.RUN_SUMMARY).build()
        val items = runCatching { client.getDataItems(uri, DataClient.FILTER_PREFIX).await() }.getOrNull() ?: return
        try {
            for (item in items) {
                val map = DataMapItem.fromDataItem(item).dataMap
                val summaryBytes = map.getByteArray("summary") ?: continue
                val summary = runCatching { RunSummary.fromBytes(summaryBytes) }.getOrNull() ?: continue
                ingest(app, summary, map.getByteArray("route"))
                runCatching { client.deleteDataItems(item.uri).await() }
            }
        } finally {
            items.release()
        }
    }

    private fun notifyRecords(context: Context, texts: List<String>) {
        if (!StreakReminder.canNotify(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Personal bests", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, HomeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_streak)
            .setContentTitle(if (texts.size == 1) "New personal best" else "${texts.size} new personal bests")
            .setContentText(texts.joinToString("  |  "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(texts.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
    }

    private const val CHANNEL_ID = "personal_bests"
    private const val NOTIFICATION_ID = 6001
}

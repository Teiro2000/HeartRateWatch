package com.pedro.heartratewatch.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import androidx.wear.tiles.TileService
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.wear.tile.HeartRateTileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Listens for the "/settings/sync" DataItem that the phone's SettingsRepository pushes every
 * time the user changes a setting there, and mirrors it into this watch's own [SettingsStore].
 * Despite the name, also handles the "/dashboard/stats" DataItem the phone's RunHistoryRepository
 * pushes whenever run history changes -- same sync mechanism, just a second path/handler, not
 * worth a whole separate listener service for.
 *
 * This is registered two ways: as a manifest-declared WearableListenerService (this class, so
 * it *can* wake the app from a cold/stopped state), and as a live listener registered from
 * MainActivity while the app is actually open (see MainActivity.dataChangedListener). Testing
 * showed the manifest path alone unreliable -- Play services logs "Failed to deliver message to
 * AppKey[...]" and our code never runs at all, which matches Android's post-Oreo restriction on
 * other processes cold-starting a background service in an app that isn't in the foreground.
 * The live listener sidesteps that because the app registers it itself while already running.
 * [handleDataEvents] is the shared parsing/apply logic both paths call into.
 */
class SettingsSyncListenerService : WearableListenerService() {

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        handleDataEvents(applicationContext, dataEvents)
    }

    companion object {
        fun handleDataEvents(context: Context, dataEvents: DataEventBuffer) {
            Log.d("SettingsSync", "onDataChanged fired with ${dataEvents.count} event(s)")

            dataEvents.filter { it.type == DataEvent.TYPE_CHANGED }.forEach { event ->
                when (event.dataItem.uri.path) {
                    DataLayerPaths.SETTINGS_SYNC -> handleSettingsSync(context, event)
                    DataLayerPaths.DASHBOARD_STATS_SYNC -> handleDashboardStatsSync(context, event)
                }
            }
        }

        private fun handleSettingsSync(context: Context, event: DataEvent) {
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            Log.d("SettingsSync", "Received settings from phone")
            val paceUnit = map.getString("pace_unit")?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS
            val distanceUnit = map.getString("distance_unit")?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS

            // Heart rate / pace / target come once per activity ("<field>_<ACTIVITY>"); the rest is shared.
            val all = ActivityType.entries.mapNotNull { type ->
                if (!map.containsKey("lower_bpm_${type.name}")) return@mapNotNull null
                fun k(name: String) = "${name}_${type.name}"
                type to TrainingSettings(
                    heartRateAlertsEnabled = map.getBoolean(k("hr_alerts_enabled")),
                    paceAlertsEnabled = map.getBoolean(k("pace_alerts_enabled")),
                    lowerThresholdBpm = map.getInt(k("lower_bpm")),
                    upperThresholdBpm = map.getInt(k("upper_bpm")),
                    thresholdMode = map.getString(k("threshold_mode"))?.let {
                        runCatching { ThresholdMode.valueOf(it) }.getOrNull()
                    } ?: ThresholdMode.BPM,
                    lowerThresholdPercent = map.getInt(k("lower_percent")),
                    upperThresholdPercent = map.getInt(k("upper_percent")),
                    manualMaxHrBpm = map.getInt("manual_max_hr").takeIf { it > 0 },
                    fastestPaceSecPerKm = map.getInt(k("fastest_pace_sec_per_km")),
                    slowestPaceSecPerKm = map.getInt(k("slowest_pace_sec_per_km")),
                    warmupSeconds = map.getInt("warmup_seconds"),
                    breakTimerSeconds = map.getInt("break_seconds"),
                    distanceTargetMeters = map.getFloat(k("target_meters")).takeIf { it > 0f },
                    launchStravaOnStart = map.getBoolean(k("launch_strava_on_start")),
                    paceUnit = paceUnit,
                    distanceUnit = distanceUnit
                )
            }.toMap()
            if (all.isEmpty()) return

            CoroutineScope(Dispatchers.IO).launch {
                SettingsStore(context).saveAll(all)
            }
        }

        private fun handleDashboardStatsSync(context: Context, event: DataEvent) {
            val map = DataMapItem.fromDataItem(event.dataItem).dataMap
            val stats = DashboardStats(
                streakDays = map.getInt("streak_days"),
                thisMonthMeters = map.getDouble("this_month_meters"),
                lastWorkoutMillis = map.getLong("last_workout_millis").takeIf { it > 0L }
            )
            CoroutineScope(Dispatchers.IO).launch {
                DashboardStatsStore(context).save(stats)
                // Tiles don't poll -- without this, a stale idle-state tile only picks up new
                // dashboard stats the next time something else happens to re-render it (e.g. a
                // session starting/stopping), which could be a long wait if you're not currently
                // training.
                TileService.getUpdater(context).requestUpdate(HeartRateTileService::class.java)
            }
        }
    }
}
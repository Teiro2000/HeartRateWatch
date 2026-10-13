package com.pedro.heartratewatch.mobile

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DataLayerPaths
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.defaultSettingsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "training_settings")

/**
 * Phone-side settings store, and the actual source of truth for editing: the phone has a real
 * keyboard, so thresholds/break length/target distance get entered here and pushed to the watch
 * over the Data Layer. This uses DataClient (not MessageClient) deliberately -- DataClient
 * items are retained and re-synced automatically once both devices reconnect, which matters if
 * the watch is briefly out of Bluetooth range when a setting changes.
 *
 * Heart rate, pace and target settings are stored separately for each [ActivityType] -- walking
 * and running (or a bike ride) want very different ranges, and swapping between them shouldn't
 * mean retyping numbers. Units, warmup, break length, max heart rate and Strava are shared by all.
 * Running keeps the original unsuffixed storage keys, so existing running settings carry over;
 * every other activity's keys end in `_<ACTIVITY>`.
 */
class SettingsRepository(private val context: Context) {

    private object Shared {
        val MANUAL_MAX_HR = intPreferencesKey("manual_max_hr")
        val WARMUP_SECONDS = intPreferencesKey("warmup_seconds")
        val BREAK_SECONDS = intPreferencesKey("break_seconds")
        val LAUNCH_STRAVA_ON_START = booleanPreferencesKey("launch_strava_on_start")
        val PACE_UNIT = stringPreferencesKey("pace_unit")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    }

    private fun suffix(type: ActivityType) = if (type == ActivityType.RUN) "" else "_${type.name}"

    /** Settings for running -- what the shared fields (units and the like) are read from by screens that don't care which activity. */
    val settingsFlow: Flow<TrainingSettings> = settingsFlow(ActivityType.RUN)

    /** The settings that apply to [type]: its own thresholds/target plus the shared ones. */
    fun settingsFlow(type: ActivityType): Flow<TrainingSettings> =
        context.settingsDataStore.data.map { prefs -> read(prefs, type) }

    private fun read(prefs: Preferences, type: ActivityType): TrainingSettings {
        val d = defaultSettingsFor(type)
        val s = suffix(type)
        return TrainingSettings(
            heartRateAlertsEnabled = prefs[booleanPreferencesKey("hr_alerts_enabled$s")] ?: d.heartRateAlertsEnabled,
            paceAlertsEnabled = prefs[booleanPreferencesKey("pace_alerts_enabled$s")] ?: d.paceAlertsEnabled,
            lowerThresholdBpm = prefs[intPreferencesKey("lower_bpm$s")] ?: d.lowerThresholdBpm,
            upperThresholdBpm = prefs[intPreferencesKey("upper_bpm$s")] ?: d.upperThresholdBpm,
            thresholdMode = prefs[stringPreferencesKey("threshold_mode$s")]?.let {
                runCatching { ThresholdMode.valueOf(it) }.getOrNull()
            } ?: d.thresholdMode,
            lowerThresholdPercent = prefs[intPreferencesKey("lower_percent$s")] ?: d.lowerThresholdPercent,
            upperThresholdPercent = prefs[intPreferencesKey("upper_percent$s")] ?: d.upperThresholdPercent,
            manualMaxHrBpm = prefs[Shared.MANUAL_MAX_HR]?.takeIf { it > 0 },
            fastestPaceSecPerKm = prefs[intPreferencesKey("fastest_pace_sec_per_km$s")] ?: d.fastestPaceSecPerKm,
            slowestPaceSecPerKm = prefs[intPreferencesKey("slowest_pace_sec_per_km$s")] ?: d.slowestPaceSecPerKm,
            warmupSeconds = prefs[Shared.WARMUP_SECONDS] ?: 30,
            breakTimerSeconds = prefs[Shared.BREAK_SECONDS] ?: 30,
            distanceTargetMeters = prefs[floatPreferencesKey("target_meters$s")]?.takeIf { it > 0f },
            launchStravaOnStart = prefs[Shared.LAUNCH_STRAVA_ON_START] ?: false,
            paceUnit = prefs[Shared.PACE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS,
            distanceUnit = prefs[Shared.DISTANCE_UNIT]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
                ?: DistanceUnit.KILOMETERS
        )
    }

    /** Saves [settings] as [type]'s (plus the shared fields) locally on the phone AND pushes everything to the watch. */
    suspend fun save(type: ActivityType, settings: TrainingSettings) {
        context.settingsDataStore.edit { prefs -> write(prefs, type, settings) }
        pushToWatch()
    }

    private fun write(prefs: MutablePreferences, type: ActivityType, settings: TrainingSettings) {
        val s = suffix(type)
        prefs[booleanPreferencesKey("hr_alerts_enabled$s")] = settings.heartRateAlertsEnabled
        prefs[booleanPreferencesKey("pace_alerts_enabled$s")] = settings.paceAlertsEnabled
        prefs[intPreferencesKey("lower_bpm$s")] = settings.lowerThresholdBpm
        prefs[intPreferencesKey("upper_bpm$s")] = settings.upperThresholdBpm
        prefs[stringPreferencesKey("threshold_mode$s")] = settings.thresholdMode.name
        prefs[intPreferencesKey("lower_percent$s")] = settings.lowerThresholdPercent
        prefs[intPreferencesKey("upper_percent$s")] = settings.upperThresholdPercent
        prefs[intPreferencesKey("fastest_pace_sec_per_km$s")] = settings.fastestPaceSecPerKm
        prefs[intPreferencesKey("slowest_pace_sec_per_km$s")] = settings.slowestPaceSecPerKm
        prefs[floatPreferencesKey("target_meters$s")] = settings.distanceTargetMeters ?: -1f
        prefs[Shared.MANUAL_MAX_HR] = settings.manualMaxHrBpm ?: -1
        prefs[Shared.WARMUP_SECONDS] = settings.warmupSeconds
        prefs[Shared.BREAK_SECONDS] = settings.breakTimerSeconds
        prefs[Shared.LAUNCH_STRAVA_ON_START] = settings.launchStravaOnStart
        prefs[Shared.PACE_UNIT] = settings.paceUnit.name
        prefs[Shared.DISTANCE_UNIT] = settings.distanceUnit.name
    }

    /** Pushes every activity's settings (and the shared ones) in a single Data Layer item. */
    private suspend fun pushToWatch() {
        val prefs = context.settingsDataStore.data.first()
        val all = ActivityType.entries.associateWith { read(prefs, it) }
        val shared = all.getValue(ActivityType.RUN)
        val request = PutDataMapRequest.create(DataLayerPaths.SETTINGS_SYNC).apply {
            all.forEach { (type, settings) ->
                fun k(name: String) = "${name}_${type.name}"
                dataMap.putBoolean(k("hr_alerts_enabled"), settings.heartRateAlertsEnabled)
                dataMap.putBoolean(k("pace_alerts_enabled"), settings.paceAlertsEnabled)
                dataMap.putInt(k("lower_bpm"), settings.lowerThresholdBpm)
                dataMap.putInt(k("upper_bpm"), settings.upperThresholdBpm)
                dataMap.putString(k("threshold_mode"), settings.thresholdMode.name)
                dataMap.putInt(k("lower_percent"), settings.lowerThresholdPercent)
                dataMap.putInt(k("upper_percent"), settings.upperThresholdPercent)
                dataMap.putInt(k("fastest_pace_sec_per_km"), settings.fastestPaceSecPerKm)
                dataMap.putInt(k("slowest_pace_sec_per_km"), settings.slowestPaceSecPerKm)
                dataMap.putFloat(k("target_meters"), settings.distanceTargetMeters ?: -1f)
            }
            dataMap.putInt("manual_max_hr", shared.manualMaxHrBpm ?: -1)
            dataMap.putInt("warmup_seconds", shared.warmupSeconds)
            dataMap.putInt("break_seconds", shared.breakTimerSeconds)
            dataMap.putBoolean("launch_strava_on_start", shared.launchStravaOnStart)
            dataMap.putString("pace_unit", shared.paceUnit.name)
            dataMap.putString("distance_unit", shared.distanceUnit.name)
            // A field that always changes, so the DataItem is guaranteed to fire a change event
            // even if every visible setting happens to be identical to the last save.
            dataMap.putLong("updated_at", System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        Log.d("SettingsSync", "Pushing settings for ${all.size} activities to watch")
        Wearable.getDataClient(context).putDataItem(request)
            .addOnSuccessListener { Log.d("SettingsSync", "putDataItem succeeded: ${it.uri}") }
            .addOnFailureListener { Log.e("SettingsSync", "putDataItem failed", it) }
    }
}

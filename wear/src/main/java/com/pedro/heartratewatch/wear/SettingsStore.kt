package com.pedro.heartratewatch.wear

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.ActivityType
import com.pedro.heartratewatch.shared.DistanceUnit
import com.pedro.heartratewatch.shared.ThresholdMode
import com.pedro.heartratewatch.shared.TrainingSettings
import com.pedro.heartratewatch.shared.defaultSettingsFor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "training_settings")

/**
 * Local, on-watch copy of the settings. The *editing* source of truth is meant to be the
 * phone companion app (bigger screen, easier input) -- see
 * com.pedro.heartratewatch.mobile.SettingsRepository -- which pushes changes here via
 * [SettingsSyncListenerService]. This store is what [ExerciseSessionService] reads from when a
 * session starts, so the watch can run a session standalone even if the phone briefly drops
 * out of range mid-run.
 *
 * Heart rate, pace and target settings are kept separately for each [ActivityType] (walking and
 * running want very different ranges); everything else -- units, warmup, break length, max heart
 * rate, Strava -- is shared. Running's values use the original unsuffixed keys; every other
 * activity's keys end in `_<ACTIVITY>`.
 */
class SettingsStore(private val context: Context) {

    private object Shared {
        val MANUAL_MAX_HR = intPreferencesKey("manual_max_hr")
        val WARMUP_SECONDS = intPreferencesKey("warmup_seconds")
        val BREAK_SECONDS = intPreferencesKey("break_seconds")
        val LAUNCH_STRAVA_ON_START = booleanPreferencesKey("launch_strava_on_start")
        val PACE_UNIT = stringPreferencesKey("pace_unit")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
    }

    private fun suffix(type: ActivityType) = if (type == ActivityType.RUN) "" else "_${type.name}"

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

    /** Saves every activity's settings at once (what a phone sync delivers); the shared fields are taken from running's. */
    suspend fun saveAll(all: Map<ActivityType, TrainingSettings>) {
        context.settingsDataStore.edit { prefs ->
            all.forEach { (type, settings) -> write(prefs, type, settings) }
        }
    }

    private fun write(prefs: androidx.datastore.preferences.core.MutablePreferences, type: ActivityType, settings: TrainingSettings) {
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
        // Shared fields: identical in every activity's settings, so writing them each time is harmless.
        prefs[Shared.MANUAL_MAX_HR] = settings.manualMaxHrBpm ?: -1
        prefs[Shared.WARMUP_SECONDS] = settings.warmupSeconds
        prefs[Shared.BREAK_SECONDS] = settings.breakTimerSeconds
        prefs[Shared.LAUNCH_STRAVA_ON_START] = settings.launchStravaOnStart
        prefs[Shared.PACE_UNIT] = settings.paceUnit.name
        prefs[Shared.DISTANCE_UNIT] = settings.distanceUnit.name
    }
}

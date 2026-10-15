package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.progressionSettingsDataStore by preferencesDataStore(name = "progression_settings")

/**
 * What sits behind the level system besides the histories themselves:
 *  - [resetAtFlow]: sessions before it no longer count toward XP, which is how "reset my level" starts
 *    you fresh without touching your history.
 *  - [penaltiesFromFlow]: the first day a missed day can cost a level; set the first time levels are
 *    looked at (so a gap in old history costs nothing) and again whenever the level is reset.
 *  - [bonusXpFlow]: extra XP added by the Settings test button, so level-up moments can be tried.
 *  - [celebratedLevelFlow]: the level the last level-up moment was shown for (-1 = nothing shown yet).
 */
class ProgressionSettingsRepository(private val context: Context) {

    private val resetKey = longPreferencesKey("reset_at_millis")
    private val penaltiesKey = longPreferencesKey("penalties_from_epoch_day")
    private val bonusKey = intPreferencesKey("bonus_xp")
    private val celebratedKey = intPreferencesKey("celebrated_level")

    val resetAtFlow: Flow<Long> = context.progressionSettingsDataStore.data.map { it[resetKey] ?: 0L }

    /** [LocalDate.MAX] until penalties have started, so "not set yet" can be told apart from "not loaded yet". */
    val penaltiesFromFlow: Flow<LocalDate> = context.progressionSettingsDataStore.data.map { prefs ->
        prefs[penaltiesKey]?.let { LocalDate.ofEpochDay(it) } ?: LocalDate.MAX
    }

    val bonusXpFlow: Flow<Int> = context.progressionSettingsDataStore.data.map { it[bonusKey] ?: 0 }

    val celebratedLevelFlow: Flow<Int> = context.progressionSettingsDataStore.data.map { it[celebratedKey] ?: -1 }

    /** Called when the level is first shown: from today on, a missed day costs a level. Does nothing once set. */
    suspend fun ensurePenaltiesStarted() {
        if (context.progressionSettingsDataStore.data.first()[penaltiesKey] != null) return
        context.progressionSettingsDataStore.edit { it[penaltiesKey] = LocalDate.now().toEpochDay() }
    }

    suspend fun setCelebratedLevel(level: Int) {
        context.progressionSettingsDataStore.edit { it[celebratedKey] = level }
    }

    suspend fun addBonusXp(xp: Int) {
        context.progressionSettingsDataStore.edit { it[bonusKey] = (it[bonusKey] ?: 0) + xp }
    }

    suspend fun clearBonusXp() {
        context.progressionSettingsDataStore.edit { it.remove(bonusKey) }
    }

    /** Start over: only sessions from now on count, at level 1, and missed days count from today. */
    suspend fun reset() {
        context.progressionSettingsDataStore.edit {
            it[resetKey] = System.currentTimeMillis()
            it[penaltiesKey] = LocalDate.now().toEpochDay()
            it.remove(bonusKey)
            it.remove(celebratedKey)
        }
    }
}

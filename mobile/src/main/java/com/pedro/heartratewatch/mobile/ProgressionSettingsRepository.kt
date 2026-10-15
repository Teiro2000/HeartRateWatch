package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.progressionSettingsDataStore by preferencesDataStore(name = "progression_settings")

/**
 * Two dates behind the level system. [resetAtFlow]: sessions before it no longer count toward XP, which
 * is how "reset my level" starts you fresh without touching your history. [penaltiesFromFlow]: the first
 * day a missed day can cost a level; it is set the first time levels are looked at (so a gap in old
 * history costs nothing) and again whenever the level is reset.
 */
class ProgressionSettingsRepository(private val context: Context) {

    private val resetKey = longPreferencesKey("reset_at_millis")
    private val penaltiesKey = longPreferencesKey("penalties_from_epoch_day")

    val resetAtFlow: Flow<Long> = context.progressionSettingsDataStore.data.map { it[resetKey] ?: 0L }

    val penaltiesFromFlow: Flow<LocalDate?> = context.progressionSettingsDataStore.data.map { prefs ->
        prefs[penaltiesKey]?.let { LocalDate.ofEpochDay(it) }
    }

    /** Called when the level is first shown: from today on, a missed day costs a level. Does nothing once set. */
    suspend fun ensurePenaltiesStarted() {
        if (context.progressionSettingsDataStore.data.first()[penaltiesKey] != null) return
        context.progressionSettingsDataStore.edit { it[penaltiesKey] = LocalDate.now().toEpochDay() }
    }

    /** Start over: only sessions from now on count, at level 1, and missed days count from today. */
    suspend fun reset() {
        context.progressionSettingsDataStore.edit {
            it[resetKey] = System.currentTimeMillis()
            it[penaltiesKey] = LocalDate.now().toEpochDay()
        }
    }
}

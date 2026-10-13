package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.ActivityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.selectedCardioDataStore by preferencesDataStore(name = "selected_cardio")

/** Which cardio exercise the Cardio menu's "Start Cardio" button starts, picked on the Select Exercise page. */
class SelectedCardioRepository(private val context: Context) {

    private val key = stringPreferencesKey("selected")

    val selectedFlow: Flow<ActivityType> = context.selectedCardioDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { ActivityType.valueOf(it) }.getOrNull() } ?: ActivityType.RUN
    }

    suspend fun select(type: ActivityType) {
        context.selectedCardioDataStore.edit { it[key] = type.name }
    }
}

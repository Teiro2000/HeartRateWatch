package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.pedro.heartratewatch.shared.DistanceUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.displayDistanceUnitDataStore by preferencesDataStore(name = "display_distance_unit")

/**
 * An optional unit for showing distances on the phone (History, Stats, the Cardio menu), chosen on
 * the main Settings screen. When set it wins over the unit picked for the distance target; when
 * null (the default) each screen keeps using the unit it used before. Phone-only: the watch keeps
 * using the target's unit.
 */
class DisplayDistanceUnitRepository(private val context: Context) {

    private val key = stringPreferencesKey("unit")

    val overrideFlow: Flow<DistanceUnit?> = context.displayDistanceUnitDataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { DistanceUnit.valueOf(it) }.getOrNull() }
    }

    /** Null clears the override. */
    suspend fun set(unit: DistanceUnit?) {
        context.displayDistanceUnitDataStore.edit { prefs ->
            if (unit == null) prefs.remove(key) else prefs[key] = unit.name
        }
    }
}

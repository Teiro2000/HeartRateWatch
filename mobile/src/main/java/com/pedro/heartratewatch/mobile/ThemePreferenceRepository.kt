package com.pedro.heartratewatch.mobile

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.themePreferenceDataStore by preferencesDataStore(name = "theme_preference")

/**
 * Phone-only "use a light theme instead of the default Pip-Boy dark theme" toggle -- deliberately
 * local, not part of TrainingSettings, since the watch app and tile always stay dark: there's no
 * "light Pip-Boy" equivalent for them, and a watch face is usually glanced at rather than read at
 * length, where a bright white screen would be worse, not better. Its own separate DataStore
 * (rather than folding into SettingsRepository) also means flipping it can write through
 * immediately without racing the Settings screen's own draft/Save cycle -- see PipBoyTheme.
 */
class ThemePreferenceRepository(private val context: Context) {

    private object Keys {
        val USE_LIGHT_THEME = booleanPreferencesKey("use_light_theme")
    }

    val useLightThemeFlow: Flow<Boolean> = context.themePreferenceDataStore.data.map {
        (it[Keys.USE_LIGHT_THEME] ?: false).also { value -> cached = value }
    }

    /**
     * The saved choice, available immediately. A screen that starts with a guess (dark) and only
     * switches once DataStore answers shows the wrong theme for a split second every time you open
     * a new screen. The value is remembered for the life of the process, so only the very first
     * screen ever waits (a tiny local read).
     */
    fun currentUseLightTheme(): Boolean =
        cached ?: runBlocking { useLightThemeFlow.first() }

    suspend fun setUseLightTheme(useLight: Boolean) {
        context.themePreferenceDataStore.edit { it[Keys.USE_LIGHT_THEME] = useLight }
        cached = useLight
    }

    private companion object {
        @Volatile var cached: Boolean? = null
    }
}

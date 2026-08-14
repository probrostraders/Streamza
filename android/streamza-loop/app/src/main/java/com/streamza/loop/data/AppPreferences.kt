package com.streamza.loop.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "app_settings")
private val DEFAULT_LOOP_KEY = booleanPreferencesKey("default_loop")
private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

/** Small local-only settings, separate from anything server-side: whether a new stream defaults to
 *  looping (most Streamza Loop users run a 24/7 channel, so this saves them a tap every time), and
 *  the app's light/dark appearance (themeMode is one of "system"/"light"/"dark" — see ThemeMode
 *  in ui/theme/Theme.kt for how it's applied). Both live in the Settings screen. */
class AppPreferences(private val context: Context) {
    val defaultLoop = context.settingsStore.data.map { it[DEFAULT_LOOP_KEY] ?: true }
    val themeMode = context.settingsStore.data.map { it[THEME_MODE_KEY] ?: "system" }

    suspend fun setDefaultLoop(value: Boolean) {
        context.settingsStore.edit { it[DEFAULT_LOOP_KEY] = value }
    }

    suspend fun setThemeMode(value: String) {
        context.settingsStore.edit { it[THEME_MODE_KEY] = value }
    }
}

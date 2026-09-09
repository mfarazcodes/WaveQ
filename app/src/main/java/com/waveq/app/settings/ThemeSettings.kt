package com.waveq.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class ThemeMode(val label: String) {
    SYSTEM("System"),
    LIGHT("Light"),
    DARK("Dark"),
}

private val Context.themeDataStore: DataStore<Preferences> by preferencesDataStore(name = "waveq_theme_settings")
private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")

/** Persisted (DataStore) theme choice, mirroring [com.waveq.app.auth.SessionManager]'s shape. */
object ThemeSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode

    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            val prefs = appContext.themeDataStore.data.first()
            val stored = prefs[KEY_THEME_MODE]?.let { name -> runCatching { ThemeMode.valueOf(name) }.getOrNull() }
            if (stored != null) _mode.value = stored
        }
    }

    fun setMode(context: Context, mode: ThemeMode) {
        _mode.value = mode
        val appContext = context.applicationContext
        scope.launch {
            appContext.themeDataStore.edit { prefs -> prefs[KEY_THEME_MODE] = mode.name }
        }
    }
}

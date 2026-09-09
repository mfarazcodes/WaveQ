package com.waveq.app.settings

import android.content.Context
import android.content.SharedPreferences
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

private val Context.displayNameDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "waveq_display_name")

private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")

/** Plain-SharedPreferences mirror of the same value, read synchronously on cold start. */
private const val MIRROR_PREFS = "waveq_display_name_mirror"
private const val MIRROR_KEY = "display_name"

const val MAX_DISPLAY_NAME_LENGTH = 24

/**
 * The name this device announces on the mesh: the sender name on every message,
 * and the endpoint name Nearby advertises.
 *
 * DataStore-backed like [ThemeSettings] and
 * [com.waveq.app.auth.SessionManager], with one addition - a plain
 * SharedPreferences mirror written alongside every DataStore write, read
 * synchronously in [init].
 *
 * The mirror exists because the two call sites that matter have no coroutine to
 * suspend in and cannot wait: [com.waveq.app.mesh.SosBeaconService] stamps the
 * name onto every SOS beacon, and NearbyTransport passes it to
 * `startAdvertising`. With DataStore alone, both would read the default during
 * the async load on a cold start - which is exactly how messages went out as
 * "Anonymous" from a device whose owner had already chosen a name.
 */
object DisplayNameSettings {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _name = MutableStateFlow<String?>(null)
    /** Null means the user has not chosen one yet - the UI must prompt before anything is sent. */
    val name: StateFlow<String?> = _name

    @Volatile private var mirror: SharedPreferences? = null
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE)
        mirror = prefs
        _name.value = prefs.getString(MIRROR_KEY, null)?.takeIf { it.isNotBlank() }

        scope.launch {
            val stored = appContext.displayNameDataStore.data.first()[KEY_DISPLAY_NAME]
                ?.takeIf { it.isNotBlank() }
            if (stored != null && stored != _name.value) {
                _name.value = stored
                prefs.edit().putString(MIRROR_KEY, stored).apply()
            }
        }
    }

    /** Synchronous read, for call sites with no coroutine in scope. Null when unset. */
    fun currentName(): String? =
        _name.value ?: mirror?.getString(MIRROR_KEY, null)?.takeIf { it.isNotBlank() }

    fun isSet(): Boolean = !currentName().isNullOrBlank()

    fun setName(context: Context, value: String) {
        val cleaned = value.trim().take(MAX_DISPLAY_NAME_LENGTH)
        if (cleaned.isBlank()) return
        _name.value = cleaned
        val appContext = context.applicationContext
        // Mirror first and synchronously: a send that happens in the next
        // millisecond must already see the new name.
        (mirror ?: appContext.getSharedPreferences(MIRROR_PREFS, Context.MODE_PRIVATE).also { mirror = it })
            .edit().putString(MIRROR_KEY, cleaned).apply()
        scope.launch {
            appContext.displayNameDataStore.edit { prefs -> prefs[KEY_DISPLAY_NAME] = cleaned }
        }
    }
}

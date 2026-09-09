package com.waveq.app.auth

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

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "waveq_session")

private val KEY_USER_ID = stringPreferencesKey("user_id")
private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
private val KEY_ROLE = stringPreferencesKey("role")

/**
 * Holds the current [Session] as a [StateFlow], persisted via DataStore so it
 * survives process death - e.g. the OS killing the app in the background
 * while [com.waveq.app.mesh.SosBeaconService] is still running and
 * [com.waveq.app.mesh.MeshManager] needs to know the role to gate a send.
 *
 * TODO(auth): role is entirely client-held right now - LoginScreen lets the
 * user pick it directly (or it's fixed by which "Demo as..." button was
 * tapped), and nothing here verifies it against a backend. This is a
 * trivially bypassable client-side gate (flip a toggle, or worst case patch
 * the APK) and MUST be replaced with a role claim read from a verified
 * backend token (e.g. a signed JWT validated server-side) before this ships
 * to real users. [Session]/[UserRole]/[SessionManager] are deliberately
 * already shaped so that swap only touches [signIn] below - no caller of
 * [session] or [currentRole] should need to change.
 */
object SessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** Synchronous best-effort read of the current role, for call sites (like MeshManager) with no coroutine in scope. */
    val currentRole: UserRole? get() = _session.value?.role

    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val appContext = context.applicationContext
        scope.launch {
            val prefs = appContext.sessionDataStore.data.first()
            val userId = prefs[KEY_USER_ID]
            val displayName = prefs[KEY_DISPLAY_NAME]
            val role = prefs[KEY_ROLE]?.let { name -> runCatching { UserRole.valueOf(name) }.getOrNull() }
            if (userId != null && displayName != null && role != null) {
                _session.value = Session(userId, displayName, role)
            }
        }
    }

    fun signIn(context: Context, userId: String, displayName: String, role: UserRole) {
        val session = Session(userId, displayName, role)
        _session.value = session
        val appContext = context.applicationContext
        scope.launch {
            appContext.sessionDataStore.edit { prefs ->
                prefs[KEY_USER_ID] = session.userId
                prefs[KEY_DISPLAY_NAME] = session.displayName
                prefs[KEY_ROLE] = session.role.name
            }
        }
    }

    fun signOut(context: Context) {
        _session.value = null
        val appContext = context.applicationContext
        scope.launch {
            appContext.sessionDataStore.edit { it.clear() }
        }
    }
}

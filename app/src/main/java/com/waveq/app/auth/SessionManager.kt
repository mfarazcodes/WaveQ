package com.waveq.app.auth

import android.content.Context
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.waveq.app.mesh.ChannelCrypto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.security.PrivateKey
import java.security.PublicKey

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore(name = "waveq_session")

private val KEY_USER_ID = stringPreferencesKey("user_id")
private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
private val KEY_ROLE = stringPreferencesKey("role")
private val KEY_OPERATOR_PRIV_KEY = stringPreferencesKey("operator_priv_key")
private val KEY_OPERATOR_PUB_KEY = stringPreferencesKey("operator_pub_key")

object SessionManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    /** Synchronous best-effort read of current role. */
    val currentRole: UserRole? get() = _session.value?.role

    // Cached in memory for synchronous access during packet signing & mesh verification
    @Volatile private var cachedPrivateKey: PrivateKey? = null
    @Volatile private var cachedPublicKey: PublicKey? = null

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

            // Restore cryptographic keys if present
            prefs[KEY_OPERATOR_PRIV_KEY]?.let { privB64 ->
                runCatching {
                    val raw = Base64.decode(privB64, Base64.NO_WRAP)
                    cachedPrivateKey = ChannelCrypto.decodePrivateKey(raw)
                }
            }
            prefs[KEY_OPERATOR_PUB_KEY]?.let { pubB64 ->
                runCatching {
                    val raw = Base64.decode(pubB64, Base64.NO_WRAP)
                    cachedPublicKey = ChannelCrypto.decodePublicKey(raw)
                }
            }

            if (userId != null && displayName != null && role != null) {
                _session.value = Session(userId, displayName, role)
            }
        }
    }

    fun signIn(
        context: Context,
        userId: String,
        displayName: String,
        role: UserRole,
        privateKey: PrivateKey? = null,
        publicKey: PublicKey? = null
    ) {
        val session = Session(userId, displayName, role)
        _session.value = session

        // If signing in as an Operator or Admin, ensure an asymmetric keypair exists
        var priv = privateKey ?: cachedPrivateKey
        var pub = publicKey ?: cachedPublicKey

        if ((role == UserRole.OPERATOR || role == UserRole.ADMIN) && (priv == null || pub == null)) {
            val pair = ChannelCrypto.generateOperatorKeyPair()
            priv = pair.private
            pub = pair.public
        }

        cachedPrivateKey = priv
        cachedPublicKey = pub

        val appContext = context.applicationContext
        scope.launch {
            appContext.sessionDataStore.edit { prefs ->
                prefs[KEY_USER_ID] = session.userId
                prefs[KEY_DISPLAY_NAME] = session.displayName
                prefs[KEY_ROLE] = session.role.name

                if (priv != null && pub != null) {
                    prefs[KEY_OPERATOR_PRIV_KEY] = Base64.encodeToString(priv.encoded, Base64.NO_WRAP)
                    prefs[KEY_OPERATOR_PUB_KEY] = Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
                } else {
                    prefs.remove(KEY_OPERATOR_PRIV_KEY)
                    prefs.remove(KEY_OPERATOR_PUB_KEY)
                }
            }
        }
    }

    fun signOut(context: Context) {
        _session.value = null
        cachedPrivateKey = null
        cachedPublicKey = null
        val appContext = context.applicationContext
        scope.launch {
            appContext.sessionDataStore.edit { it.clear() }
        }
    }

    fun getOperatorPrivateKey(): PrivateKey? = cachedPrivateKey

    fun getOperatorPublicKey(): PublicKey? = cachedPublicKey

    fun getUserRole(): UserRole? = _session.value?.role
}
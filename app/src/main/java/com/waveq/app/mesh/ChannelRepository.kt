package com.waveq.app.mesh

import android.content.Context
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

enum class ChannelType { CITY, SECTOR, FAMILY }

enum class PassphraseStrength { WEAK, MEDIUM, STRONG }

data class ChannelMeta(
    val channelId: String,
    val name: String,
    val type: ChannelType,
    val isEncrypted: Boolean,
    val keyB64: String?,
    val saltB64: String?,
    val joinedAt: Long,
)

private const val PREFS_FILE = "waveq_mesh_channels_secure_prefs"
private const val CHANNELS_KEY = "channels_v1"
private const val MIN_PASSPHRASE_LENGTH = 12
private const val DEFAULT_CITY_NAME = "City-Wide Alerts"

/**
 * Channel membership + key storage.
 *
 * CITY/SECTOR channels are always unencrypted so flood alerts reach every
 * device regardless of membership. FAMILY channels are AES-256-GCM encrypted,
 * keyed by a user passphrase - only the derived key is ever persisted, never
 * the passphrase itself, and it's stored in EncryptedSharedPreferences.
 */
class ChannelRepository(context: Context) {

    private val appContext = context.applicationContext

    private val masterKey = MasterKey.Builder(appContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        appContext,
        PREFS_FILE,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Synchronized
    fun ensureDefaultCityChannel() {
        val cityId = publicChannelId(ChannelType.CITY, DEFAULT_CITY_NAME)
        if (getChannel(cityId) != null) return
        val channel = ChannelMeta(
            channelId = cityId,
            name = DEFAULT_CITY_NAME,
            type = ChannelType.CITY,
            isEncrypted = false,
            keyB64 = null,
            saltB64 = null,
            joinedAt = System.currentTimeMillis(),
        )
        saveChannel(channel)
    }

    @Synchronized
    fun getChannels(): List<ChannelMeta> {
        val raw = prefs.getString(CHANNELS_KEY, null) ?: return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { i -> channelFromJson(array.getJSONObject(i)) }
    }

    @Synchronized
    fun getChannel(channelId: String): ChannelMeta? =
        getChannels().firstOrNull { it.channelId == channelId }

    fun getKey(channelId: String): SecretKey? {
        val channel = getChannel(channelId) ?: return null
        val keyB64 = channel.keyB64 ?: return null
        val raw = Base64.decode(keyB64, Base64.NO_WRAP)
        return SecretKeySpec(raw, KeyProperties.KEY_ALGORITHM_AES)
    }

    fun createOrJoinPublicChannel(type: ChannelType, name: String): ChannelMeta {
        require(type != ChannelType.FAMILY) { "use createOrJoinFamilyChannel for FAMILY channels" }
        val channelId = publicChannelId(type, name)
        getChannel(channelId)?.let { return it }
        val channel = ChannelMeta(
            channelId = channelId,
            name = name,
            type = type,
            isEncrypted = false,
            keyB64 = null,
            saltB64 = null,
            joinedAt = System.currentTimeMillis(),
        )
        saveChannel(channel)
        return channel
    }

    fun createOrJoinFamilyChannel(passphrase: String): Result<ChannelMeta> {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) {
            return Result.failure(
                IllegalArgumentException("Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters"),
            )
        }
        val channelId = ChannelCrypto.channelIdFor(passphrase)
        getChannel(channelId)?.let { return Result.success(it) }

        val salt = ChannelCrypto.deriveSalt(passphrase)
        val key = ChannelCrypto.deriveKey(passphrase, salt)
        val channel = ChannelMeta(
            channelId = channelId,
            name = "Family Channel",
            type = ChannelType.FAMILY,
            isEncrypted = true,
            keyB64 = Base64.encodeToString(key.encoded, Base64.NO_WRAP),
            saltB64 = Base64.encodeToString(salt, Base64.NO_WRAP),
            joinedAt = System.currentTimeMillis(),
        )
        saveChannel(channel)
        return Result.success(channel)
    }

    /**
     * Leaves a family channel and forgets its key.
     *
     * The derived key lives only inside the channel list blob in
     * EncryptedSharedPreferences, so rewriting the list without this entry is
     * what actually destroys it - there is no separate key store to clean up.
     * After this the passphrase would have to be entered again to rejoin.
     *
     * Refuses to leave CITY, which is how flood alerts and risk updates reach
     * this device; there is no way back to it from the UI.
     */
    @Synchronized
    fun leaveChannel(channelId: String): Boolean {
        val channel = getChannel(channelId) ?: return false
        if (channel.type == ChannelType.CITY) return false
        val remaining = getChannels().filterNot { it.channelId == channelId }
        val array = JSONArray()
        remaining.forEach { array.put(channelToJson(it)) }
        prefs.edit().putString(CHANNELS_KEY, array.toString()).apply()
        return true
    }

    fun passphraseStrength(passphrase: String): PassphraseStrength {
        if (passphrase.length < MIN_PASSPHRASE_LENGTH) return PassphraseStrength.WEAK
        val hasLower = passphrase.any { it.isLowerCase() }
        val hasUpper = passphrase.any { it.isUpperCase() }
        val hasDigit = passphrase.any { it.isDigit() }
        val hasSymbol = passphrase.any { !it.isLetterOrDigit() }
        val varietyScore = listOf(hasLower, hasUpper, hasDigit, hasSymbol).count { it }
        return when {
            passphrase.length >= 16 && varietyScore >= 3 -> PassphraseStrength.STRONG
            varietyScore >= 2 -> PassphraseStrength.MEDIUM
            else -> PassphraseStrength.WEAK
        }
    }

    /**
     * Id of the default City-Wide channel. Public because flood alerts and risk
     * updates must both be addressable without the caller having to know how
     * the id is derived - and because both are broadcast there specifically
     * since it is unencrypted, so every device can read them.
     */
    fun cityChannelId(): String = publicChannelId(ChannelType.CITY, DEFAULT_CITY_NAME)

    private fun publicChannelId(type: ChannelType, name: String): String =
        ChannelCrypto.channelIdFor("${type.name}:${name.trim().lowercase()}")

    @Synchronized
    private fun saveChannel(channel: ChannelMeta) {
        val existing = getChannels().filterNot { it.channelId == channel.channelId }
        val all = existing + channel
        val array = JSONArray()
        all.forEach { array.put(channelToJson(it)) }
        prefs.edit().putString(CHANNELS_KEY, array.toString()).apply()
    }

    private fun channelToJson(channel: ChannelMeta): JSONObject = JSONObject().apply {
        put("channelId", channel.channelId)
        put("name", channel.name)
        put("type", channel.type.name)
        put("isEncrypted", channel.isEncrypted)
        put("keyB64", channel.keyB64 ?: JSONObject.NULL)
        put("saltB64", channel.saltB64 ?: JSONObject.NULL)
        put("joinedAt", channel.joinedAt)
    }

    private fun channelFromJson(obj: JSONObject): ChannelMeta = ChannelMeta(
        channelId = obj.getString("channelId"),
        name = obj.getString("name"),
        type = ChannelType.valueOf(obj.getString("type")),
        isEncrypted = obj.getBoolean("isEncrypted"),
        keyB64 = if (obj.isNull("keyB64")) null else obj.getString("keyB64"),
        saltB64 = if (obj.isNull("saltB64")) null else obj.getString("saltB64"),
        joinedAt = obj.getLong("joinedAt"),
    )
}

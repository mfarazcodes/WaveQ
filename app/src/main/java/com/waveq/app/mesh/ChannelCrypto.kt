package com.waveq.app.mesh

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

private const val PBKDF2_ITERATIONS = 120_000
private const val KEY_BITS = 256
private const val GCM_IV_BYTES = 12
private const val GCM_TAG_BITS = 128
private const val SALT_BYTES = 16

// Distinct context strings so the public channelId can never be used to shortcut
// the salt or the key - each is an independent one-way derivation of the passphrase.
private const val KEY_CONTEXT = "waveq-channel-key-v1"
private const val SALT_CONTEXT = "waveq-channel-salt-v1"
private const val ID_CONTEXT = "waveq-channel-id-v1"

data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * AES-256-GCM channel encryption built entirely on javax.crypto.
 *
 * Salt and channelId are both derived deterministically from the passphrase
 * (distinct context strings) rather than randomly generated, so any device can
 * rejoin a FAMILY channel from just the passphrase - there is no invite-code
 * exchange in this app.
 */
object ChannelCrypto {

    fun deriveSalt(passphrase: String): ByteArray =
        sha256(passphrase + SALT_CONTEXT).copyOf(SALT_BYTES)

    fun deriveKey(passphrase: String, channelSalt: ByteArray): SecretKey {
        val spec = PBEKeySpec(
            (passphrase + KEY_CONTEXT).toCharArray(),
            channelSalt,
            PBKDF2_ITERATIONS,
            KEY_BITS,
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val raw = factory.generateSecret(spec).encoded
        return SecretKeySpec(raw, "AES")
    }

    fun channelIdFor(passphrase: String): String {
        val digest = sha256(passphrase + ID_CONTEXT)
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun encrypt(plaintext: ByteArray, key: SecretKey): EncryptedBlob {
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedBlob(iv, ciphertext)
    }

    fun decrypt(blob: EncryptedBlob, key: SecretKey): ByteArray? {
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, blob.iv))
            cipher.doFinal(blob.ciphertext)
        } catch (e: AEADBadTagException) {
            null
        }
    }

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
}

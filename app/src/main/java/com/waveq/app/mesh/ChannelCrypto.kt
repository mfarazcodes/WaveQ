package com.waveq.app.mesh

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
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

// Asymmetric signing configurations for Operators and Admins
private const val SIG_ALGO_ED25519 = "Ed25519"
private const val SIG_ALGO_ECDSA = "SHA256withECDSA"

data class EncryptedBlob(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * Handles AES-256-GCM symmetric channel encryption, Ed25519/ECDSA asymmetric signatures
 * for verified operators, and Proof-of-Work puzzles to mitigate civilian SOS spam.
 */
object ChannelCrypto {

    // ==========================================
    // SYMMETRIC CHANNEL ENCRYPTION (Existing)
    // ==========================================

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

    private fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    // ========================================================
    // OPERATOR & ADMIN ASYMMETRIC CRYPTOGRAPHIC SIGNATURES
    // ========================================================

    /**
     * Generates an asymmetric keypair (Ed25519 preferred, falling back to EC secp256r1).
     * Typically executed on an authorized Operator or Admin device during provisioning.
     */
    fun generateOperatorKeyPair(): KeyPair {
        return try {
            KeyPairGenerator.getInstance(SIG_ALGO_ED25519).generateKeyPair()
        } catch (e: Exception) {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"))
            kpg.generateKeyPair()
        }
    }

    /**
     * Signs broadcast alert bytes using the operator's private key.
     */
    fun signPayload(payload: ByteArray, privateKey: PrivateKey): ByteArray {
        val algorithm = if (privateKey.algorithm.equals("Ed25519", ignoreCase = true) ||
            privateKey.algorithm.equals("EdDSA", ignoreCase = true)) {
            SIG_ALGO_ED25519
        } else {
            SIG_ALGO_ECDSA
        }
        val signer = Signature.getInstance(algorithm)
        signer.initSign(privateKey)
        signer.update(payload)
        return signer.sign()
    }

    /**
     * Reconstructs an operator's public key from raw X.509 bytes.
     */
    fun decodePublicKey(encodedKey: ByteArray): PublicKey {
        val spec = X509EncodedKeySpec(encodedKey)
        return try {
            KeyFactory.getInstance(SIG_ALGO_ED25519).generatePublic(spec)
        } catch (e: Exception) {
            KeyFactory.getInstance("EC").generatePublic(spec)
        }
    }

    /**
     * Reconstructs an operator's private key from PKCS#8 bytes.
     */
    fun decodePrivateKey(encodedKey: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(encodedKey)
        return try {
            KeyFactory.getInstance(SIG_ALGO_ED25519).generatePrivate(spec)
        } catch (e: Exception) {
            KeyFactory.getInstance("EC").generatePrivate(spec)
        }
    }

    /**
     * Verifies if the broadcast payload came from a trusted operator without internet.
     */
    fun verifyAuthority(payload: ByteArray, signature: ByteArray, trustedPublicKey: PublicKey): Boolean {
        return try {
            val algorithm = if (trustedPublicKey.algorithm.equals("Ed25519", ignoreCase = true) ||
                trustedPublicKey.algorithm.equals("EdDSA", ignoreCase = true)) {
                SIG_ALGO_ED25519
            } else {
                SIG_ALGO_ECDSA
            }
            val verifier = Signature.getInstance(algorithm)
            verifier.initVerify(trustedPublicKey)
            verifier.update(payload)
            verifier.verify(signature)
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // SOS ANTI-SPAM: PROOF-OF-WORK (PoW)
    // ==========================================

    /**
     * Computes a Proof-of-Work nonce to bind computational cost to civilian SOS alerts.
     * [targetDifficultyBits] represents required leading zero bits (12 bits = 3 hex zeros, takes ~150-300ms).
     */
    fun solveSosProofOfWork(
        senderId: String,
        timestamp: Long,
        targetDifficultyBits: Int = 12
    ): Long {
        var nonce = 0L
        val prefix = "$senderId:$timestamp:"
        while (true) {
            val candidate = (prefix + nonce).toByteArray(Charsets.UTF_8)
            val hash = sha256(candidate)
            if (checkLeadingZeroBits(hash, targetDifficultyBits)) {
                return nonce
            }
            nonce++
        }
    }

    /**
     * Verifies the Proof-of-Work nonce submitted with an unverified SOS alert before
     * propagating it across the mesh network.
     */
    fun verifySosProofOfWork(
        senderId: String,
        timestamp: Long,
        nonce: Long,
        targetDifficultyBits: Int = 12
    ): Boolean {
        val candidate = "$senderId:$timestamp:$nonce".toByteArray(Charsets.UTF_8)
        val hash = sha256(candidate)
        return checkLeadingZeroBits(hash, targetDifficultyBits)
    }

    private fun checkLeadingZeroBits(hash: ByteArray, requiredBits: Int): Boolean {
        var zeroBits = 0
        for (b in hash) {
            val byteVal = b.toInt() and 0xFF
            if (byteVal == 0) {
                zeroBits += 8
            } else {
                zeroBits += Integer.numberOfLeadingZeros(byteVal) - 24
                break
            }
            if (zeroBits >= requiredBits) return true
        }
        return zeroBits >= requiredBits
    }
}
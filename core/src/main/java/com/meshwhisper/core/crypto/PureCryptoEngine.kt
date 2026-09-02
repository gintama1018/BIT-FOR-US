package com.meshwhisper.core.crypto

import com.meshwhisper.core.protocol.MeshPacket
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedResult(
    val ciphertext: ByteArray,
    val authTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedResult
        return ciphertext.contentEquals(other.ciphertext) && authTag.contentEquals(other.authTag)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

/**
 * Pure JVM/Kotlin Cryptographic Engine for MeshWhisper.
 * Free from Android-specific SDK dependencies and shared across Android, Windows, and macOS.
 */
object PureCryptoEngine {

    val PUBLIC_CHANNEL_SALT = "MESHWHISPER_PUBLIC_SALT_9A8B7C6D5E".toByteArray(Charsets.UTF_8)
    val HKDF_DM_SALT = "MESHWHISPER_DM_SALT_1F2E3D4C5B6A".toByteArray(Charsets.UTF_8)

    private val secureRandom = SecureRandom()
    private val sessionKeyEpochCache = ConcurrentHashMap<String, ByteArray>()

    /**
     * Generates a new random X25519 keypair (Pair(privateKeyBytes, publicKeyBytes)).
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val keyGen = X25519KeyPairGenerator()
        keyGen.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = keyGen.generateKeyPair()

        val privParams = keyPair.private as X25519PrivateKeyParameters
        val pubParams = keyPair.public as X25519PublicKeyParameters

        return Pair(privParams.encoded, pubParams.encoded)
    }

    /**
     * Derives public key from a given 32-byte private key.
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        return privParams.generatePublicKey().encoded
    }

    /**
     * Derives a 64-bit Long Node ID from public key bytes using first 8 bytes of SHA-256.
     */
    fun deriveNodeId(publicKeyBytes: ByteArray): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKeyBytes)
        val buffer = ByteBuffer.wrap(hash)
        return buffer.long
    }

    /**
     * Formats public key fingerprint as truncated visual hex: XX:XX:XX:XX
     */
    fun generateFingerprint(publicKeyBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKeyBytes)
        val hex = bytesToHex(hash).take(16).uppercase()
        return hex.chunked(4).joinToString(":")
    }

    /**
     * Calculates time epoch (1 hour window) for session key rotation.
     */
    fun getEpochForTimestamp(timestampSec: Long): Long = timestampSec / 3600L

    /**
     * Derives a 256-bit AES symmetric key for a specific peer and epoch using X25519 ECDH + HKDF-SHA256.
     */
    fun derivePeerSessionKey(
        myPrivateKey: ByteArray,
        peerPublicKeyBytes: ByteArray,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        val peerNodeId = deriveNodeId(peerPublicKeyBytes)
        val epoch = getEpochForTimestamp(timestampSec)
        val cacheKey = "$peerNodeId:$epoch"

        return sessionKeyEpochCache.getOrPut(cacheKey) {
            val privParams = X25519PrivateKeyParameters(myPrivateKey, 0)
            val pubParams = X25519PublicKeyParameters(peerPublicKeyBytes, 0)

            val agreement = X25519Agreement()
            agreement.init(privParams)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(pubParams, sharedSecret, 0)

            // HKDF-SHA256 expansion to 32 bytes with epoch-bound info string
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            val info = "MESHWHISPER_SESSION_KEY_V1_EPOCH_$epoch".toByteArray(Charsets.UTF_8)
            val params = HKDFParameters(
                sharedSecret,
                HKDF_DM_SALT,
                info
            )
            hkdf.init(params)
            val sessionKey = ByteArray(32)
            hkdf.generateBytes(sessionKey, 0, 32)
            sessionKey
        }
    }

    /**
     * Derives shared Public Channel AES-256 key.
     */
    fun derivePublicChannelKey(): ByteArray {
        return deriveKeyFromMasterSalt(PUBLIC_CHANNEL_SALT, "MESHWHISPER_PUBLIC_V1".toByteArray(Charsets.UTF_8))
    }

    fun invalidateSessionKey(peerNodeId: Long) {
        val prefix = "$peerNodeId:"
        val keysToRemove = sessionKeyEpochCache.keys.filter { it.startsWith(prefix) }
        for (k in keysToRemove) {
            sessionKeyEpochCache.remove(k)
        }
    }

    fun clearAllSessionKeys() {
        sessionKeyEpochCache.clear()
    }

    /**
     * Encrypts plaintext using AES-256-GCM with IV derived deterministically from messageId UUID.
     * Binds Additional Authenticated Data (AAD) into authentication tag.
     */
    fun encrypt(
        plaintext: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): EncryptedResult {
        val iv = extractIvFromUuid(messageId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val encryptedWithTag = cipher.doFinal(plaintext)

        val tagSize = MeshPacket.AUTH_TAG_SIZE
        val ciphertextSize = encryptedWithTag.size - tagSize
        val ciphertext = ByteArray(ciphertextSize)
        val authTag = ByteArray(tagSize)

        System.arraycopy(encryptedWithTag, 0, ciphertext, 0, ciphertextSize)
        System.arraycopy(encryptedWithTag, ciphertextSize, authTag, 0, tagSize)

        return EncryptedResult(ciphertext, authTag)
    }

    /**
     * Decrypts ciphertext and verifies 128-bit AEAD tag + AAD header binding.
     */
    fun decrypt(
        ciphertext: ByteArray,
        authTag: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val iv = extractIvFromUuid(messageId)
        val combined = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(combined)
    }

    fun extractIvFromUuid(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        val fullBytes = buffer.array()
        val iv = ByteArray(12)
        System.arraycopy(fullBytes, 0, iv, 0, 12)
        return iv
    }

    fun deriveKeyFromMasterSalt(salt: ByteArray, info: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters("MASTER_ROOT_KEY_MATERIAL".toByteArray(Charsets.UTF_8), salt, info))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, 32)
        return key
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}

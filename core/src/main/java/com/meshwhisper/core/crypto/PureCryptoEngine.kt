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
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
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

    val PUBLIC_EMERGENCY_CHANNEL_SALT = "MESHWHISPER_PUBLIC_SALT_9A8B7C6D5E".toByteArray(Charsets.UTF_8)
    val PUBLIC_CHANNEL_SALT = PUBLIC_EMERGENCY_CHANNEL_SALT
    val HKDF_DM_SALT = "MESHWHISPER_DM_SALT_1F2E3D4C5B6A".toByteArray(Charsets.UTF_8)
    private const val PUBLIC_EMERGENCY_IKM = "MESHWHISPER_PUBLIC_EMERGENCY_DISASTER_ROOT_V1"

    private val secureRandom = SecureRandom()
    private const val MAX_SESSION_KEY_CACHE_SIZE = 256
    private val sessionKeyEpochCache = object : java.util.LinkedHashMap<String, ByteArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            return size > MAX_SESSION_KEY_CACHE_SIZE
        }
    }

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
     * Derives a 32-byte Ed25519 signing private key from the master identity private seed using domain-separated HKDF-SHA256.
     */
    fun deriveSigningPrivateKey(identitySeed: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(identitySeed, HKDF_DM_SALT, "MESHWHISPER_ED25519_SIGNING_KEY_V1".toByteArray(Charsets.UTF_8)))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    /**
     * Derives a 32-byte Ed25519 signing public key from the master identity private seed.
     */
    fun deriveSigningPublicKey(identitySeed: ByteArray): ByteArray {
        val signingPriv = deriveSigningPrivateKey(identitySeed)
        val privParams = Ed25519PrivateKeyParameters(signingPriv, 0)
        return privParams.generatePublicKey().encoded
    }

    /**
     * Cryptographically signs a message/packet byte payload with the node's Ed25519 identity key.
     * Produces an unforgeable 64-byte Ed25519 digital signature.
     */
    fun sign(identitySeed: ByteArray, data: ByteArray): ByteArray {
        val signingPriv = deriveSigningPrivateKey(identitySeed)
        val privParams = Ed25519PrivateKeyParameters(signingPriv, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verifies an Ed25519 digital signature against the claimed sender's 32-byte signing public key.
     */
    fun verifySignature(signingPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || signingPublicKey.size != 32) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(signingPublicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
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
        val myPubKey = derivePublicKey(myPrivateKey)
        val myNodeId = deriveNodeId(myPubKey)
        val peerNodeId = deriveNodeId(peerPublicKeyBytes)
        val epoch = getEpochForTimestamp(timestampSec)
        
        val id1 = minOf(myNodeId, peerNodeId)
        val id2 = maxOf(myNodeId, peerNodeId)
        val cacheKey = "$id1:$id2:$epoch"
        synchronized(sessionKeyEpochCache) {
            val cached = sessionKeyEpochCache[cacheKey]
            if (cached != null) return cached
        }

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

        synchronized(sessionKeyEpochCache) {
            sessionKeyEpochCache[cacheKey] = sessionKey
        }
        return sessionKey
    }

    /**
     * Derives shared Public Emergency Channel AES-256 key.
     * Open disaster broadcast channel for search & rescue, beacons, and civilian alerts.
     * Spoof prevention is cryptographically guaranteed via Ed25519 identity signatures.
     */
    fun derivePublicChannelKey(): ByteArray {
        return derivePublicEmergencyChannelKey()
    }

    fun derivePublicEmergencyChannelKey(): ByteArray {
        return deriveKeyFromMasterSalt(PUBLIC_CHANNEL_SALT, "MESHWHISPER_PUBLIC_EMERGENCY_V1".toByteArray(Charsets.UTF_8))
    }

    /**
     * Derives a confidential 256-bit AES-GCM channel key for private tactical groups,
     * first responders, and custom mesh channels using PBKDF2-HMAC-SHA256 (100,000 iterations).
     *
     * @param channelName Name of the channel/team (e.g. "TEAM_ALPHA")
     * @param passphrase Secret passphrase known only to authorized team members
     * @param salt Optional 16-byte custom salt; defaults to channel-specific SHA-256 derived salt
     */
    fun deriveTeamChannelKey(
        channelName: String,
        passphrase: String,
        salt: ByteArray? = null
    ): ByteArray {
        val actualSalt = salt ?: deriveChannelSalt(channelName)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), actualSalt, 100_000, 256)
        return factory.generateSecret(spec).encoded
    }

    fun deriveChannelSalt(channelName: String): ByteArray {
        val digest = SHA256Digest()
        val input = "MESHWHISPER_TACTICAL_CHANNEL_SALT_V1:$channelName".toByteArray(Charsets.UTF_8)
        digest.update(input, 0, input.size)
        val hash = ByteArray(32)
        digest.doFinal(hash, 0)
        val outSalt = ByteArray(16)
        System.arraycopy(hash, 0, outSalt, 0, 16)
        return outSalt
    }

    fun invalidateSessionKey(peerNodeId: Long) {
        val target = peerNodeId.toString()
        synchronized(sessionKeyEpochCache) {
            val keysToRemove = sessionKeyEpochCache.keys.filter { key ->
                val parts = key.split(":")
                parts.size >= 2 && (parts[0] == target || parts[1] == target)
            }
            for (k in keysToRemove) {
                sessionKeyEpochCache.remove(k)
            }
        }
    }

    fun clearAllSessionKeys() {
        synchronized(sessionKeyEpochCache) {
            sessionKeyEpochCache.clear()
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM with a fresh CSPRNG 96-bit nonce (NIST SP 800-38D RBG Construction).
     * Nonce is prepended to ciphertext: [12-byte CSPRNG IV][raw ciphertext].
     * Binds Additional Authenticated Data (AAD) into authentication tag.
     */
    fun encrypt(
        plaintext: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null,
        explicitIv: ByteArray? = null
    ): EncryptedResult {
        // Generate an independent 12-byte cryptographically secure random nonce or use explicit
        val iv = explicitIv ?: ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val encryptedWithTag = cipher.doFinal(plaintext)

        val tagSize = MeshPacket.AUTH_TAG_SIZE
        val rawCiphertextSize = encryptedWithTag.size - tagSize
        
        // Output wire ciphertext = [12-byte CSPRNG IV] + [raw ciphertext]
        val outputCiphertext = ByteArray(12 + rawCiphertextSize)
        System.arraycopy(iv, 0, outputCiphertext, 0, 12)
        System.arraycopy(encryptedWithTag, 0, outputCiphertext, 12, rawCiphertextSize)

        val authTag = ByteArray(tagSize)
        System.arraycopy(encryptedWithTag, rawCiphertextSize, authTag, 0, tagSize)

        return EncryptedResult(outputCiphertext, authTag)
    }

    /**
     * Decrypts ciphertext and verifies 128-bit AEAD tag + AAD header binding.
     * Dual-Mode: Parses fresh 12-byte CSPRNG IV from ciphertext prefix, with automatic fallback
     * to legacy UUID-derived IV for backward-compatibility with older packets and tests.
     */
    fun decrypt(
        ciphertext: ByteArray,
        authTag: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        val keySpec = SecretKeySpec(aesKey, "AES")

        // 1. Primary: Extract prepended 12-byte CSPRNG IV (NIST SP 800-38D)
        if (ciphertext.size >= 12) {
            try {
                val iv = ciphertext.copyOfRange(0, 12)
                val rawCiphertext = ciphertext.copyOfRange(12, ciphertext.size)
                val combined = ByteArray(rawCiphertext.size + authTag.size)
                System.arraycopy(rawCiphertext, 0, combined, 0, rawCiphertext.size)
                System.arraycopy(authTag, 0, combined, rawCiphertext.size, authTag.size)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                if (aad != null) {
                    cipher.updateAAD(aad)
                }
                return cipher.doFinal(combined)
            } catch (_: Exception) {
                // Fallback to legacy UUID-derived IV below
            }
        }

        // 2. Legacy Fallback: Extract IV deterministically from UUID
        val legacyIv = extractIvFromUuid(messageId)
        val combined = ByteArray(ciphertext.size + authTag.size)
        System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
        System.arraycopy(authTag, 0, combined, ciphertext.size, authTag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, legacyIv)
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
        hkdf.init(HKDFParameters(PUBLIC_EMERGENCY_IKM.toByteArray(Charsets.UTF_8), salt, info))
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

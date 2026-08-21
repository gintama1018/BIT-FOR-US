package com.meshwhisper.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.meshwhisper.app.protocol.MeshPacket
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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

class CryptoEngine private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshwhisper_identity_prefs", Context.MODE_PRIVATE)

    private val secureRandom = SecureRandom()
    private val sessionKeyCache = ConcurrentHashMap<Long, ByteArray>()

    // Device Identity KeyPair
    val privateKeyBytes: ByteArray
    val publicKeyBytes: ByteArray
    val nodeId: Long
    val nodeIdHex: String
    val publicFingerprint: String

    init {
        val loadedKeys = loadOrGenerateIdentityKeys()
        privateKeyBytes = loadedKeys.first
        publicKeyBytes = loadedKeys.second

        nodeId = deriveNodeId(publicKeyBytes)
        nodeIdHex = String.format("%016X", nodeId)
        publicFingerprint = generateFingerprint(publicKeyBytes)
    }

    private fun loadOrGenerateIdentityKeys(): Pair<ByteArray, ByteArray> {
        val encPrivHex = prefs.getString(PREF_PRIVATE_KEY_ENC, null)
        val ivHex = prefs.getString(PREF_PRIVATE_KEY_IV, null)
        val storedPubHex = prefs.getString(PREF_PUBLIC_KEY, null)
        val legacyPrivHex = prefs.getString(PREF_LEGACY_PRIVATE_KEY, null)

        // 1. Try loading Keystore-encrypted private key
        if (encPrivHex != null && ivHex != null && storedPubHex != null) {
            try {
                val cipherBytes = hexToBytes(encPrivHex)
                val iv = hexToBytes(ivHex)
                val decryptedPriv = decryptWithKeystore(cipherBytes, iv)
                val pubBytes = hexToBytes(storedPubHex)
                return Pair(decryptedPriv, pubBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt identity key with Keystore, checking fallback", e)
            }
        }

        // 2. Migrate legacy unencrypted private key if present
        if (legacyPrivHex != null && storedPubHex != null) {
            val privBytes = hexToBytes(legacyPrivHex)
            val pubBytes = hexToBytes(storedPubHex)
            persistEncryptedPrivateKey(privBytes, pubBytes)
            prefs.edit().remove(PREF_LEGACY_PRIVATE_KEY).apply()
            return Pair(privBytes, pubBytes)
        }

        // 3. Generate new X25519 Identity Keypair
        val keyGen = X25519KeyPairGenerator()
        keyGen.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = keyGen.generateKeyPair()

        val privParams = keyPair.private as X25519PrivateKeyParameters
        val pubParams = keyPair.public as X25519PublicKeyParameters

        val privBytes = privParams.encoded
        val pubBytes = pubParams.encoded

        persistEncryptedPrivateKey(privBytes, pubBytes)
        return Pair(privBytes, pubBytes)
    }

    private fun persistEncryptedPrivateKey(privBytes: ByteArray, pubBytes: ByteArray) {
        val (encBytes, iv) = encryptWithKeystore(privBytes)
        prefs.edit()
            .putString(PREF_PRIVATE_KEY_ENC, bytesToHex(encBytes))
            .putString(PREF_PRIVATE_KEY_IV, bytesToHex(iv))
            .putString(PREF_PUBLIC_KEY, bytesToHex(pubBytes))
            .apply()
    }

    private fun getOrCreateMasterSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(KEYSTORE_ALIAS)) {
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) {
                return entry.secretKey
            }
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun encryptWithKeystore(plaintext: ByteArray): Pair<ByteArray, ByteArray> {
        return try {
            val secretKey = getOrCreateMasterSecretKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            Pair(ciphertext, iv)
        } catch (e: Throwable) {
            // Software fallback for non-Android JVM / unit test environments
            val fallbackKey = deriveKeyFromMasterSalt("SOFTWARE_FALLBACK_KEY".toByteArray(), "AT_REST".toByteArray())
            val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext)
            Pair(ciphertext, iv)
        }
    }

    private fun decryptWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val secretKey = (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        } catch (e: Throwable) {
            // Software fallback for non-Android JVM / unit test environments
            val fallbackKey = deriveKeyFromMasterSalt("SOFTWARE_FALLBACK_KEY".toByteArray(), "AT_REST".toByteArray())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
            cipher.doFinal(ciphertext)
        }
    }

    var alias: String
        get() = prefs.getString(PREF_NODE_ALIAS, "Node-${nodeIdHex.takeLast(4)}") ?: "Node"
        set(value) {
            prefs.edit().putString(PREF_NODE_ALIAS, value).apply()
        }

    /**
     * Shared Mesh Public Channel AES-256 key derived from deterministic mesh master salt.
     */
    val publicChannelKey: ByteArray by lazy {
        deriveKeyFromMasterSalt(PUBLIC_CHANNEL_SALT, "MESHWHISPER_PUBLIC_V1".toByteArray(Charsets.UTF_8))
    }

    /**
     * Derives a 256-bit AES symmetric key for a specific peer using X25519 ECDH + HKDF-SHA256.
     */
    fun derivePeerSessionKey(peerPublicKeyBytes: ByteArray): ByteArray {
        val peerNodeId = deriveNodeId(peerPublicKeyBytes)
        return sessionKeyCache.getOrPut(peerNodeId) {
            val privParams = X25519PrivateKeyParameters(privateKeyBytes, 0)
            val pubParams = X25519PublicKeyParameters(peerPublicKeyBytes, 0)

            val agreement = X25519Agreement()
            agreement.init(privParams)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(pubParams, sharedSecret, 0)

            // HKDF-SHA256 expansion to 32 bytes (256 bits)
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            val params = HKDFParameters(
                sharedSecret,
                HKDF_DM_SALT,
                "MESHWHISPER_SESSION_KEY_V1".toByteArray(Charsets.UTF_8)
            )
            hkdf.init(params)
            val sessionKey = ByteArray(32)
            hkdf.generateBytes(sessionKey, 0, 32)
            sessionKey
        }
    }

    /**
     * Invalidates cached session key for a given peer (used when key rotation occurs).
     */
    fun invalidateSessionKey(peerNodeId: Long) {
        sessionKeyCache.remove(peerNodeId)
    }

    /**
     * Clears all session keys in memory.
     */
    fun clearAllSessionKeys() {
        sessionKeyCache.clear()
    }

    /**
     * Encrypts plaintext using AES-256-GCM.
     * Uses the first 12 bytes of messageId as IV (ensuring unique IV per message).
     * Binds Additional Authenticated Data (AAD) to the ciphertext and auth tag if provided.
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
        val gcmSpec = GCMParameterSpec(128, iv) // 128-bit auth tag

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val encryptedWithTag = cipher.doFinal(plaintext)

        // Split encryptedWithTag into ciphertext and 16-byte authTag
        val tagSize = MeshPacket.AUTH_TAG_SIZE
        val ciphertextSize = encryptedWithTag.size - tagSize
        val ciphertext = ByteArray(ciphertextSize)
        val authTag = ByteArray(tagSize)

        System.arraycopy(encryptedWithTag, 0, ciphertext, 0, ciphertextSize)
        System.arraycopy(encryptedWithTag, ciphertextSize, authTag, 0, tagSize)

        return EncryptedResult(ciphertext, authTag)
    }

    /**
     * Decrypts ciphertext and verifies AEAD auth tag and AAD header binding using AES-256-GCM.
     * Throws an AEADBadTagException if auth tag verification fails, ciphertext has been tampered with,
     * or any authenticated header byte was altered.
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

    companion object {
        private const val TAG = "CryptoEngine"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEYSTORE_ALIAS = "MeshWhisperIdentityMasterKey"
        private const val PREF_PRIVATE_KEY_ENC = "identity_private_key_enc_hex"
        private const val PREF_PRIVATE_KEY_IV = "identity_private_key_iv_hex"
        private const val PREF_LEGACY_PRIVATE_KEY = "identity_private_key_hex"
        private const val PREF_PUBLIC_KEY = "identity_public_key_hex"
        private const val PREF_NODE_ALIAS = "node_alias"

        private val PUBLIC_CHANNEL_SALT = "MESHWHISPER_PUBLIC_SALT_9A8B7C6D5E".toByteArray(Charsets.UTF_8)
        private val HKDF_DM_SALT = "MESHWHISPER_DM_SALT_1F2E3D4C5B6A".toByteArray(Charsets.UTF_8)

        @Volatile
        private var INSTANCE: CryptoEngine? = null

        fun getInstance(context: Context): CryptoEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CryptoEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun deriveNodeId(publicKeyBytes: ByteArray): Long {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(publicKeyBytes)
            val buffer = ByteBuffer.wrap(hash)
            return buffer.long // First 8 bytes as 64-bit Long Node ID
        }

        fun generateFingerprint(publicKeyBytes: ByteArray): String {
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(publicKeyBytes)
            val hex = bytesToHex(hash).take(16).uppercase()
            return hex.chunked(4).joinToString(":")
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

        private fun deriveKeyFromMasterSalt(salt: ByteArray, info: ByteArray): ByteArray {
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
}


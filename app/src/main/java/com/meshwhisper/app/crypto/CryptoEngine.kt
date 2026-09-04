package com.meshwhisper.app.crypto

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.crypto.SecureKeyStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

typealias EncryptedResult = com.meshwhisper.core.crypto.EncryptedResult

class CryptoEngine private constructor(private val context: Context) : SecureKeyStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("meshwhisper_identity_prefs", Context.MODE_PRIVATE)

    private val secureRandom = SecureRandom()

    // Device Identity KeyPair (Dynamic State)
    var privateKeyBytes: ByteArray = ByteArray(0)
        private set
    var publicKeyBytes: ByteArray = ByteArray(0)
        private set
    var nodeId: Long = 0L
        private set
    var nodeIdHex: String = ""
        private set
    var publicFingerprint: String = ""
        private set

    private val _identityVersion = MutableStateFlow(0L)
    val identityVersion: StateFlow<Long> = _identityVersion.asStateFlow()

    init {
        val loadedKeys = loadOrGenerateIdentityKeys()
        applyIdentity(loadedKeys.first, loadedKeys.second)
    }

    private fun applyIdentity(priv: ByteArray, pub: ByteArray) {
        privateKeyBytes = priv
        publicKeyBytes = pub
        nodeId = PureCryptoEngine.deriveNodeId(pub)
        nodeIdHex = java.lang.Long.toUnsignedString(nodeId, 16).padStart(16, '0').uppercase()
        publicFingerprint = PureCryptoEngine.generateFingerprint(pub)
        _identityVersion.value += 1
    }

    /**
     * Fully generates, encrypts, and applies a brand-new cryptographic identity in memory and disk.
     */
    fun regenerateIdentity(): Pair<ByteArray, ByteArray> {
        clearAllSessionKeys()
        val (privBytes, pubBytes) = PureCryptoEngine.generateX25519KeyPair()

        persistEncryptedPrivateKey(privBytes, pubBytes)
        applyIdentity(privBytes, pubBytes)
        return Pair(privBytes, pubBytes)
    }

    private fun loadOrGenerateIdentityKeys(): Pair<ByteArray, ByteArray> {
        val encPrivHex = prefs.getString(PREF_PRIVATE_KEY_ENC, null)
        val ivHex = prefs.getString(PREF_PRIVATE_KEY_IV, null)
        val storedPubHex = prefs.getString(PREF_PUBLIC_KEY, null)
        val legacyPrivHex = prefs.getString(PREF_LEGACY_PRIVATE_KEY, null)

        // 1. Try loading Keystore-encrypted private key
        if (encPrivHex != null && ivHex != null && storedPubHex != null) {
            try {
                val cipherBytes = PureCryptoEngine.hexToBytes(encPrivHex)
                val iv = PureCryptoEngine.hexToBytes(ivHex)
                val decryptedPriv = decryptWithKeystore(cipherBytes, iv)
                val pubBytes = PureCryptoEngine.hexToBytes(storedPubHex)
                return Pair(decryptedPriv, pubBytes)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt identity key with Keystore, generating fresh", e)
            }
        }

        // 2. Migrate legacy unencrypted private key if present
        if (legacyPrivHex != null && storedPubHex != null) {
            val privBytes = PureCryptoEngine.hexToBytes(legacyPrivHex)
            val pubBytes = PureCryptoEngine.hexToBytes(storedPubHex)
            persistEncryptedPrivateKey(privBytes, pubBytes)
            prefs.edit().remove(PREF_LEGACY_PRIVATE_KEY).apply()
            return Pair(privBytes, pubBytes)
        }

        // 3. Generate new X25519 Identity Keypair
        val (privBytes, pubBytes) = PureCryptoEngine.generateX25519KeyPair()
        persistEncryptedPrivateKey(privBytes, pubBytes)
        return Pair(privBytes, pubBytes)
    }

    private fun persistEncryptedPrivateKey(privBytes: ByteArray, pubBytes: ByteArray) {
        val (encBytes, iv) = encryptWithKeystore(privBytes)
        prefs.edit()
            .putString(PREF_PRIVATE_KEY_ENC, PureCryptoEngine.bytesToHex(encBytes))
            .putString(PREF_PRIVATE_KEY_IV, PureCryptoEngine.bytesToHex(iv))
            .putString(PREF_PUBLIC_KEY, PureCryptoEngine.bytesToHex(pubBytes))
            .commit() // Synchronous disk flush
    }

    private fun isAndroidKeyStoreAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            true
        } catch (e: Throwable) {
            false
        }
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
        if (!isAndroidKeyStoreAvailable()) {
            throw SecurityException("Hardware AndroidKeyStore is not available on this device; unauthenticated identity key storage is prohibited")
        }

        val secretKey = getOrCreateMasterSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(plaintext)
        return Pair(ciphertext, cipher.iv)
    }

    private fun decryptWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        if (!isAndroidKeyStoreAvailable()) {
            throw SecurityException("Hardware AndroidKeyStore is not available on this device; unauthenticated identity key storage is prohibited")
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val secretKey = (keyStore.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    // SecureKeyStorage interface implementation
    override fun getPrivateKey(): ByteArray? = privateKeyBytes.takeIf { it.isNotEmpty() }
    override fun storePrivateKey(privateKey: ByteArray) {
        val pub = PureCryptoEngine.derivePublicKey(privateKey)
        persistEncryptedPrivateKey(privateKey, pub)
        applyIdentity(privateKey, pub)
    }

    override fun readAlias(): String? = alias
    override fun writeAlias(alias: String) {
        this.alias = alias
    }

    override fun readPublicChannelKey(): ByteArray? = getActiveBroadcastKey()
    override fun writePublicChannelKey(key: ByteArray) {
        cachedActiveChannelKey = key
    }

    override fun clearAll() {
        resetIdentityKeys()
    }

    var alias: String
        get() = prefs.getString(PREF_NODE_ALIAS, "Node-${nodeIdHex.takeLast(4)}") ?: "Node"
        set(value) {
            prefs.edit().putString(PREF_NODE_ALIAS, value).apply()
        }

    val publicChannelKey: ByteArray by lazy {
        PureCryptoEngine.derivePublicChannelKey()
    }

    private val _activeChannelNameFlow = MutableStateFlow(
        prefs.getString(PREF_ACTIVE_CHANNEL_NAME, DEFAULT_PUBLIC_CHANNEL_NAME) ?: DEFAULT_PUBLIC_CHANNEL_NAME
    )
    val activeChannelNameFlow: StateFlow<String> = _activeChannelNameFlow.asStateFlow()

    private val _isChannelConfidentialFlow = MutableStateFlow(
        !loadEncryptedChannelPassphrase().isNullOrBlank()
    )
    val isChannelConfidentialFlow: StateFlow<Boolean> = _isChannelConfidentialFlow.asStateFlow()

    var activeChannelName: String
        get() = _activeChannelNameFlow.value
        private set(value) {
            _activeChannelNameFlow.value = value
            prefs.edit().putString(PREF_ACTIVE_CHANNEL_NAME, value).apply()
        }

    var activeChannelPassphrase: String?
        get() = loadEncryptedChannelPassphrase()
        private set(value) {
            saveEncryptedChannelPassphrase(value)
            _isChannelConfidentialFlow.value = !value.isNullOrBlank()
        }

    @Volatile
    private var cachedActiveChannelKey: ByteArray? = null

    fun setActiveChannel(name: String, passphrase: String?) {
        val cleanName = name.trim().ifEmpty { DEFAULT_PUBLIC_CHANNEL_NAME }
        val cleanPass = passphrase?.trim()?.ifEmpty { null }
        activeChannelName = cleanName
        activeChannelPassphrase = cleanPass
        cachedActiveChannelKey = if (cleanPass != null) {
            PureCryptoEngine.deriveTeamChannelKey(cleanName, cleanPass)
        } else {
            publicChannelKey
        }
    }

    fun getActiveBroadcastKey(): ByteArray {
        cachedActiveChannelKey?.let { return it }
        val pass = activeChannelPassphrase
        val key = if (!pass.isNullOrBlank()) {
            PureCryptoEngine.deriveTeamChannelKey(activeChannelName, pass)
        } else {
            publicChannelKey
        }
        cachedActiveChannelKey = key
        return key
    }

    fun isCurrentChannelConfidential(): Boolean = _isChannelConfidentialFlow.value

    fun resetToPublicEmergencyChannel() {
        setActiveChannel(DEFAULT_PUBLIC_CHANNEL_NAME, null)
    }

    fun generateChannelQr(channelName: String, passphrase: String): String {
        return "meshwhisper://channel?name=${android.net.Uri.encode(channelName)}&pass=${android.net.Uri.encode(passphrase)}"
    }

    private fun saveEncryptedChannelPassphrase(passphrase: String?) {
        if (passphrase == null) {
            prefs.edit()
                .remove(PREF_ENCRYPTED_CHANNEL_PASSPHRASE)
                .remove(PREF_ENCRYPTED_CHANNEL_IV)
                .apply()
            return
        }
        try {
            val (ciphertext, iv) = encryptWithKeystore(passphrase.toByteArray(Charsets.UTF_8))
            prefs.edit()
                .putString(PREF_ENCRYPTED_CHANNEL_PASSPHRASE, bytesToHex(ciphertext))
                .putString(PREF_ENCRYPTED_CHANNEL_IV, bytesToHex(iv))
                .apply()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to encrypt channel passphrase in Keystore", e)
        }
    }

    private fun loadEncryptedChannelPassphrase(): String? {
        val encHex = prefs.getString(PREF_ENCRYPTED_CHANNEL_PASSPHRASE, null) ?: return null
        val ivHex = prefs.getString(PREF_ENCRYPTED_CHANNEL_IV, null) ?: return null
        return try {
            val ciphertext = hexToBytes(encHex)
            val iv = hexToBytes(ivHex)
            val plainBytes = decryptWithKeystore(ciphertext, iv)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to decrypt channel passphrase from Keystore", e)
            null
        }
    }

    fun getEpochForTimestamp(timestampSec: Long): Long = PureCryptoEngine.getEpochForTimestamp(timestampSec)

    fun derivePeerSessionKey(
        peerPublicKeyBytes: ByteArray,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        return PureCryptoEngine.derivePeerSessionKey(privateKeyBytes, peerPublicKeyBytes, timestampSec)
    }

    fun invalidateSessionKey(peerNodeId: Long) {
        PureCryptoEngine.invalidateSessionKey(peerNodeId)
    }

    fun clearAllSessionKeys() {
        PureCryptoEngine.clearAllSessionKeys()
    }

    fun resetIdentityKeys() {
        clearAllSessionKeys()
        resetToPublicEmergencyChannel()
        prefs.edit()
            .remove(PREF_PRIVATE_KEY_ENC)
            .remove(PREF_PRIVATE_KEY_IV)
            .remove(PREF_PUBLIC_KEY)
            .remove(PREF_LEGACY_PRIVATE_KEY)
            .remove(PREF_ACTIVE_CHANNEL_NAME)
            .remove(PREF_ENCRYPTED_CHANNEL_PASSPHRASE)
            .remove(PREF_ENCRYPTED_CHANNEL_IV)
            .commit() // Synchronous flush to prevent race before killProcess
    }

    val signingPublicKey: ByteArray by lazy {
        PureCryptoEngine.deriveSigningPublicKey(privateKeyBytes)
    }

    val signingPublicKeyHex: String by lazy {
        PureCryptoEngine.bytesToHex(signingPublicKey)
    }

    fun sign(data: ByteArray): ByteArray {
        return PureCryptoEngine.sign(privateKeyBytes, data)
    }

    fun verifySignature(signingPublicKeyBytes: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        return PureCryptoEngine.verifySignature(signingPublicKeyBytes, data, signature)
    }

    fun encrypt(
        plaintext: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): EncryptedResult {
        return PureCryptoEngine.encrypt(plaintext, messageId, aesKey, aad)
    }

    fun decrypt(
        ciphertext: ByteArray,
        authTag: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        return PureCryptoEngine.decrypt(ciphertext, authTag, messageId, aesKey, aad)
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
        const val DEFAULT_PUBLIC_CHANNEL_NAME = "Public Emergency Channel"
        private const val PREF_ACTIVE_CHANNEL_NAME = "active_channel_name"
        private const val PREF_ENCRYPTED_CHANNEL_PASSPHRASE = "enc_channel_pass"
        private const val PREF_ENCRYPTED_CHANNEL_IV = "enc_channel_iv"

        @Volatile
        private var INSTANCE: CryptoEngine? = null

        fun getInstance(context: Context): CryptoEngine {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CryptoEngine(context.applicationContext).also { INSTANCE = it }
            }
        }

        fun deriveNodeId(publicKeyBytes: ByteArray): Long = PureCryptoEngine.deriveNodeId(publicKeyBytes)

        fun generateFingerprint(publicKeyBytes: ByteArray): String = PureCryptoEngine.generateFingerprint(publicKeyBytes)

        fun extractIvFromUuid(uuid: UUID): ByteArray = PureCryptoEngine.extractIvFromUuid(uuid)

        fun bytesToHex(bytes: ByteArray): String = PureCryptoEngine.bytesToHex(bytes)

        fun hexToBytes(hex: String): ByteArray = PureCryptoEngine.hexToBytes(hex)
    }
}

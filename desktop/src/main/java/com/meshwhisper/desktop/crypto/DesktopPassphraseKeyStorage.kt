package com.meshwhisper.desktop.crypto

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.crypto.SecureKeyStorage
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Desktop Key Storage implementation using PBKDF2-HMAC-SHA256 passphrase-derived master encryption.
 * Stores encrypted private keys, keyVersion, alias, public channel keys, and media-at-rest root file keys
 * in ~/.meshwhisper/identity.vault.
 *
 * Enforces Phase P7 / P9 Fail-Closed Invariant:
 * - If identity.vault exists, any decryption or corruption failure MUST throw SecurityException.
 * - NEVER generate replacement keys or overwrite an existing unreadable vault.
 */
class DesktopPassphraseKeyStorage(
    private val vaultDirectory: File = File(System.getProperty("user.home"), ".meshwhisper"),
    passphrase: CharArray = "MeshWhisperDefaultDesktopPassphrase2026!".toCharArray()
) : SecureKeyStorage {

    private val vaultFile = File(vaultDirectory, "identity.vault")
    private val secureRandom = SecureRandom()
    private val masterKey: ByteArray

    private var cachedPrivateKey: ByteArray? = null
    private var cachedKeyVersion: Long = 1L
    private var cachedAlias: String? = null
    private var cachedPublicChannelKey: ByteArray? = null
    private var cachedMediaFileKey: ByteArray? = null

    init {
        if (!vaultDirectory.exists()) {
            vaultDirectory.mkdirs()
        }
        val salt = loadOrCreateSalt()
        masterKey = deriveMasterKey(passphrase, salt)
        loadVault()
    }

    private fun loadOrCreateSalt(): ByteArray {
        val saltFile = File(vaultDirectory, "master.salt")
        return if (saltFile.exists() && saltFile.length() == 32L) {
            saltFile.readBytes()
        } else {
            val newSalt = ByteArray(32).also { secureRandom.nextBytes(it) }
            saltFile.writeBytes(newSalt)
            newSalt
        }
    }

    private fun deriveMasterKey(passphrase: CharArray, salt: ByteArray): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec: KeySpec = PBEKeySpec(passphrase, salt, 100_000, 256)
        return factory.generateSecret(spec).encoded
    }

    private fun loadVault() {
        if (!vaultFile.exists()) return

        if (vaultFile.length() < 28) {
            throw SecurityException("identity.vault is corrupted (file size ${vaultFile.length()} < 28 bytes). Refusing to generate replacement keys or overwrite vault.")
        }

        try {
            val fileBytes = vaultFile.readBytes()
            val buf = ByteBuffer.wrap(fileBytes)
            val iv = ByteArray(12)
            buf.get(iv)
            val ciphertext = ByteArray(buf.remaining())
            buf.get(ciphertext)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
            val decryptedBytes = cipher.doFinal(ciphertext)

            val decBuf = ByteBuffer.wrap(decryptedBytes)
            val privLen = decBuf.get().toInt() and 0xFF
            if (privLen > 0) {
                val priv = ByteArray(privLen)
                decBuf.get(priv)
                cachedPrivateKey = priv
            }
            val aliasLen = decBuf.get().toInt() and 0xFF
            if (aliasLen > 0) {
                val aliasBytes = ByteArray(aliasLen)
                decBuf.get(aliasBytes)
                cachedAlias = String(aliasBytes, Charsets.UTF_8)
            }
            val pubKeyLen = decBuf.get().toInt() and 0xFF
            if (pubKeyLen > 0) {
                val pubKeyBytes = ByteArray(pubKeyLen)
                decBuf.get(pubKeyBytes)
                cachedPublicChannelKey = pubKeyBytes
            }

            if (decBuf.remaining() >= 8) {
                cachedKeyVersion = maxOf(1L, decBuf.getLong())
            } else {
                cachedKeyVersion = 1L
            }

            if (decBuf.remaining() >= 1) {
                val mediaKeyLen = decBuf.get().toInt() and 0xFF
                if (mediaKeyLen > 0 && decBuf.remaining() >= mediaKeyLen) {
                    val mediaKeyBytes = ByteArray(mediaKeyLen)
                    decBuf.get(mediaKeyBytes)
                    cachedMediaFileKey = mediaKeyBytes
                }
            }
        } catch (e: Exception) {
            throw SecurityException("Failed to decrypt existing identity.vault. Refusing to regenerate keys or overwrite vault.", e)
        }
    }

    @Synchronized
    private fun saveVault() {
        try {
            val priv = cachedPrivateKey ?: ByteArray(0)
            val aliasBytes = (cachedAlias ?: "DesktopNode").toByteArray(Charsets.UTF_8)
            val pubKey = cachedPublicChannelKey ?: ByteArray(0)
            val mediaKey = cachedMediaFileKey ?: ByteArray(0)

            val plainSize = 1 + priv.size + 1 + aliasBytes.size + 1 + pubKey.size + 8 + 1 + mediaKey.size
            val plainBuf = ByteBuffer.allocate(plainSize)
            plainBuf.put(priv.size.toByte())
            if (priv.isNotEmpty()) plainBuf.put(priv)
            plainBuf.put(aliasBytes.size.toByte())
            if (aliasBytes.isNotEmpty()) plainBuf.put(aliasBytes)
            plainBuf.put(pubKey.size.toByte())
            if (pubKey.isNotEmpty()) plainBuf.put(pubKey)
            plainBuf.putLong(cachedKeyVersion)
            plainBuf.put(mediaKey.size.toByte())
            if (mediaKey.isNotEmpty()) plainBuf.put(mediaKey)

            val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plainBuf.array())

            val fileBuf = ByteBuffer.allocate(iv.size + ciphertext.size)
            fileBuf.put(iv)
            fileBuf.put(ciphertext)

            // Write atomically via tmp file
            val tmpFile = File(vaultDirectory, "identity.vault.tmp")
            tmpFile.writeBytes(fileBuf.array())
            if (!tmpFile.renameTo(vaultFile)) {
                tmpFile.copyTo(vaultFile, overwrite = true)
                tmpFile.delete()
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to persist identity.vault: ${e.message}", e)
        }
    }

    override fun getPrivateKey(): ByteArray? = cachedPrivateKey

    override fun storePrivateKey(privateKey: ByteArray) {
        cachedPrivateKey = privateKey
        saveVault()
    }

    fun getKeyVersion(): Long = cachedKeyVersion

    fun storeKeyVersion(version: Long) {
        cachedKeyVersion = maxOf(1L, version)
        saveVault()
    }

    fun getMediaFileKey(): ByteArray? = cachedMediaFileKey

    fun storeMediaFileKey(key: ByteArray) {
        cachedMediaFileKey = key
        saveVault()
    }

    fun getIdentityHash(): ByteArray? {
        val priv = cachedPrivateKey ?: return null
        val ikPub = DesktopCryptoEngine.deriveSigningPublicKey(priv)
        return DesktopCryptoEngine.deriveIdentityHash(ikPub)
    }

    fun getNodeId64(): Long? {
        val hash = getIdentityHash() ?: return null
        return DesktopCryptoEngine.deriveNodeId64(hash)
    }

    override fun readAlias(): String? = cachedAlias

    override fun writeAlias(alias: String) {
        cachedAlias = alias
        saveVault()
    }

    override fun readPublicChannelKey(): ByteArray? = cachedPublicChannelKey

    override fun writePublicChannelKey(key: ByteArray) {
        cachedPublicChannelKey = key
        saveVault()
    }

    override fun clearAll() {
        cachedPrivateKey = null
        cachedKeyVersion = 1L
        cachedAlias = null
        cachedPublicChannelKey = null
        cachedMediaFileKey = null
        if (vaultFile.exists()) {
            vaultFile.delete()
        }
    }
}

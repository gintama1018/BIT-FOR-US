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
 * Stores encrypted private keys, alias, and public channel keys in ~/.meshwhisper/identity.vault.
 */
class DesktopPassphraseKeyStorage(
    private val vaultDirectory: File = File(System.getProperty("user.home"), ".meshwhisper"),
    passphrase: CharArray = "MeshWhisperDefaultDesktopPassphrase2026!".toCharArray()
) : SecureKeyStorage {

    private val vaultFile = File(vaultDirectory, "identity.vault")
    private val secureRandom = SecureRandom()
    private val masterKey: ByteArray

    private var cachedPrivateKey: ByteArray? = null
    private var cachedAlias: String? = null
    private var cachedPublicChannelKey: ByteArray? = null

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
        if (!vaultFile.exists() || vaultFile.length() < 28) return // 12B IV + 16B Tag minimum

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
        } catch (_: Exception) {}
    }

    @Synchronized
    private fun saveVault() {
        try {
            val priv = cachedPrivateKey ?: ByteArray(0)
            val aliasBytes = (cachedAlias ?: "DesktopNode").toByteArray(Charsets.UTF_8)
            val pubKey = cachedPublicChannelKey ?: ByteArray(0)

            val plainSize = 1 + priv.size + 1 + aliasBytes.size + 1 + pubKey.size
            val plainBuf = ByteBuffer.allocate(plainSize)
            plainBuf.put(priv.size.toByte())
            if (priv.isNotEmpty()) plainBuf.put(priv)
            plainBuf.put(aliasBytes.size.toByte())
            if (aliasBytes.isNotEmpty()) plainBuf.put(aliasBytes)
            plainBuf.put(pubKey.size.toByte())
            if (pubKey.isNotEmpty()) plainBuf.put(pubKey)

            val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"), GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plainBuf.array())

            val fileBuf = ByteBuffer.allocate(iv.size + ciphertext.size)
            fileBuf.put(iv)
            fileBuf.put(ciphertext)
            vaultFile.writeBytes(fileBuf.array())
        } catch (_: Exception) {}
    }

    override fun getPrivateKey(): ByteArray? = cachedPrivateKey

    override fun storePrivateKey(privateKey: ByteArray) {
        cachedPrivateKey = privateKey
        saveVault()
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
        cachedAlias = null
        cachedPublicChannelKey = null
        if (vaultFile.exists()) {
            vaultFile.delete()
        }
    }
}

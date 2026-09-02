package com.meshwhisper.core.crypto

/**
 * Platform-independent key storage contract for MeshWhisper.
 * Android implements via AndroidKeyStore + SharedPreferences.
 * Desktop implements via PBKDF2-HMAC-SHA256 passphrase-wrapped local vault.
 */
interface SecureKeyStorage {
    fun getPrivateKey(): ByteArray?
    fun storePrivateKey(privateKey: ByteArray)

    fun readAlias(): String?
    fun writeAlias(alias: String)

    fun readPublicChannelKey(): ByteArray?
    fun writePublicChannelKey(key: ByteArray)

    fun clearAll()
}

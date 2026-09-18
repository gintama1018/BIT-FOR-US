package com.meshwhisper.app.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.meshwhisper.app.BuildConfig
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.core.crypto.PureCryptoEngine
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media At-Rest Security Manager.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §C-27 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §11.1–§11.3.
 *
 * Requirements:
 * - Master key managed under AndroidKeyStore alias "MeshWhisperMediaMasterKey".
 * - In release builds, AndroidKeyStore unavailability fails closed (SecurityException).
 * - Wraps a persistent 32-byte media file key saved in SharedPreferences.
 * - Every media file on disk is encrypted with AES-256-GCM under HKDF(fileKey, salt=fileId, info="MW/FILE/v2")
 *   with a 12-byte random IV prefix.
 * - Legacy media migration reads plaintext directly into memory and writes encrypted temporary files (.enc.tmp),
 *   never writing any plaintext artifact to disk.
 */
class MediaAtRestManager(private val context: Context) {

    companion object {
        private const val TAG = "MediaAtRestManager"
        const val MEDIA_MASTER_KEY_ALIAS = "MeshWhisperMediaMasterKey"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFS_MEDIA_SECURITY = "meshwhisper_media_security_prefs"
        private const val PREF_WRAPPED_FILE_KEY_ENC = "media_file_key_enc_hex"
        private const val PREF_WRAPPED_FILE_KEY_IV = "media_file_key_iv_hex"
        private val FILE_HKDF_INFO = "MW/FILE/v2".toByteArray(Charsets.UTF_8)
        private val MAGIC_HEADER = "MWMEDIA1".toByteArray(Charsets.US_ASCII) // 8 bytes magic header for at-rest file identification
    }

    private val lock = Any()
    @Volatile
    private var cachedMediaFileKey: ByteArray? = null

    private fun isAndroidKeyStoreAvailable(): Boolean {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun getOrCreateMediaMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        if (keyStore.containsAlias(MEDIA_MASTER_KEY_ALIAS)) {
            val entry = keyStore.getEntry(MEDIA_MASTER_KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
            if (entry != null) return entry.secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            MEDIA_MASTER_KEY_ALIAS,
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

    private fun encryptWithMasterKey(rawBytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (!isAndroidKeyStoreAvailable()) {
            if (!BuildConfig.DEBUG) {
                throw SecurityException("AndroidKeyStore unavailable in release build. Refusing software key fallback.")
            }
            val fallbackKey = "SOFTWARE_FALLBACK_MEDIA_MASTER_K".toByteArray(Charsets.UTF_8).take(32).toByteArray()
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
            return Pair(cipher.doFinal(rawBytes), iv)
        }

        val secretKey = getOrCreateMediaMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val ciphertext = cipher.doFinal(rawBytes)
        return Pair(ciphertext, cipher.iv)
    }

    private fun decryptWithMasterKey(ciphertext: ByteArray, iv: ByteArray): ByteArray {
        if (!isAndroidKeyStoreAvailable()) {
            if (!BuildConfig.DEBUG) {
                throw SecurityException("AndroidKeyStore unavailable in release build. Refusing software key fallback.")
            }
            val fallbackKey = "SOFTWARE_FALLBACK_MEDIA_MASTER_K".toByteArray(Charsets.UTF_8).take(32).toByteArray()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }

        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)
        val secretKey = (keyStore.getEntry(MEDIA_MASTER_KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Obtains the 32-byte media file key.
     * If an existing encrypted key is persisted, it is decrypted.
     * If decryption fails, fails closed without regenerating a replacement key.
     */
    fun getOrCreateMediaFileKey(): ByteArray {
        cachedMediaFileKey?.let { return it }
        return synchronized(lock) {
            cachedMediaFileKey?.let { return it }

            val prefs = context.getSharedPreferences(PREFS_MEDIA_SECURITY, Context.MODE_PRIVATE)
            val encHex = prefs.getString(PREF_WRAPPED_FILE_KEY_ENC, null)
            val ivHex = prefs.getString(PREF_WRAPPED_FILE_KEY_IV, null)

            if (encHex != null && ivHex != null) {
                try {
                    val encBytes = CryptoEngine.hexToBytes(encHex)
                    val ivBytes = CryptoEngine.hexToBytes(ivHex)
                    val rawKey = decryptWithMasterKey(encBytes, ivBytes)
                    cachedMediaFileKey = rawKey
                    return rawKey
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt existing media file key — FAILING CLOSED", e)
                    throw SecurityException("Failed to decrypt existing media file key from Keystore. Refusing to regenerate key.", e)
                }
            }

            // Fresh installation: generate new 32-byte key
            val newFileKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val (encBytes, ivBytes) = encryptWithMasterKey(newFileKey)
            prefs.edit()
                .putString(PREF_WRAPPED_FILE_KEY_ENC, CryptoEngine.bytesToHex(encBytes))
                .putString(PREF_WRAPPED_FILE_KEY_IV, CryptoEngine.bytesToHex(ivBytes))
                .commit()

            cachedMediaFileKey = newFileKey
            newFileKey
        }
    }

    /**
     * Derives per-file 32-byte AES-GCM key using HKDF(fileKey, salt=fileId, info="MW/FILE/v2").
     */
    private fun derivePerFileKey(fileId: String): ByteArray {
        val rootKey = getOrCreateMediaFileKey()
        val salt = fileId.toByteArray(Charsets.UTF_8)
        return PureCryptoEngine.hkdf(
            ikm = rootKey,
            salt = salt,
            info = FILE_HKDF_INFO,
            outputLength = 32
        )
    }

    /**
     * Encrypts plaintext bytes and writes to [destinationFile] atomically.
     * Wire file format on disk:
     * [8 bytes MAGIC_HEADER] + [12 bytes IV] + [Ciphertext + 16 bytes GCM Auth Tag]
     */
    fun encryptAndWriteMediaFile(fileId: String, plaintextBytes: ByteArray, destinationFile: File) {
        val fileKey = derivePerFileKey(fileId)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintextBytes)

        val parentDir = destinationFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.enc.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(MAGIC_HEADER)
                fos.write(iv)
                fos.write(ciphertext)
                fos.flush()
            }
            if (!tempFile.renameTo(destinationFile)) {
                // If rename fails (e.g. cross-filesystem), copy and delete temp
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Reads and decrypts an encrypted media file from disk into memory.
     */
    fun readAndDecryptMediaFile(fileId: String, sourceFile: File): ByteArray {
        if (!sourceFile.exists()) {
            throw java.io.FileNotFoundException("Media file not found: ${sourceFile.absolutePath}")
        }
        val fileBytes = sourceFile.readBytes()
        if (fileBytes.size < MAGIC_HEADER.size + 12 + 16) {
            throw IllegalStateException("Corrupted or incomplete encrypted media file: ${sourceFile.name}")
        }

        // Verify magic header
        val header = fileBytes.copyOfRange(0, MAGIC_HEADER.size)
        if (!header.contentEquals(MAGIC_HEADER)) {
            throw IllegalStateException("Invalid magic header on media file: ${sourceFile.name}")
        }

        val iv = fileBytes.copyOfRange(MAGIC_HEADER.size, MAGIC_HEADER.size + 12)
        val ciphertext = fileBytes.copyOfRange(MAGIC_HEADER.size + 12, fileBytes.size)

        val fileKey = derivePerFileKey(fileId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    /**
     * Returns true if the file begins with the P7 encrypted media magic header.
     */
    fun isMediaFileEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_HEADER.size) return false
        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(MAGIC_HEADER.size)
                val read = fis.read(buf)
                read == MAGIC_HEADER.size && buf.contentEquals(MAGIC_HEADER)
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * One-time media re-encryption pass (T-MIG-06 / C-27).
     * Re-encrypts legacy plaintext media files in place without writing temporary plaintext to disk.
     */
    fun migrateLegacyMediaDirectories() {
        val dirs = listOf(
            File(context.filesDir, "media"),
            File(context.filesDir, "avatars")
        )

        for (dir in dirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isFile && !f.name.endsWith(".enc.tmp") && !isMediaFileEncrypted(f)) {
                    try {
                        val fileId = f.name
                        val plaintext = f.readBytes()
                        encryptAndWriteMediaFile(fileId, plaintext, f)
                        Log.i(TAG, "Successfully migrated legacy plaintext media file: ${f.name}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to re-encrypt legacy media file: ${f.name}", e)
                    }
                }
            }
        }
    }
}

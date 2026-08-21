package com.meshwhisper.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.dao.MessageDao
import com.meshwhisper.app.data.dao.PacketLogDao
import com.meshwhisper.app.data.dao.PeerDao
import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.dao.StoreForwardDao
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.app.data.model.StoreForwardEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Database(
    entities = [
        PeerEntity::class,
        MessageEntity::class,
        StoreForwardEntity::class,
        PacketLogEntity::class,
        ProcessedPacketEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun storeForwardDao(): StoreForwardDao
    abstract fun packetLogDao(): PacketLogDao
    abstract fun processedPacketDao(): ProcessedPacketDao

    companion object {
        private const val TAG = "MeshDatabase"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DB_KEYSTORE_ALIAS = "MeshWhisperDbMasterKey"
        private const val PREFS_DB_SECURITY = "meshwhisper_db_security_prefs"
        private const val PREF_DB_KEY_ENC = "db_passphrase_enc_hex"
        private const val PREF_DB_KEY_IV = "db_passphrase_iv_hex"

        @Volatile
        private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): MeshDatabase {
            val dbPassphrase = getOrCreateDatabasePassphrase(appContext)
            val supportFactory = SupportOpenHelperFactory(dbPassphrase)

            return Room.databaseBuilder(
                appContext,
                MeshDatabase::class.java,
                "meshwhisper_encrypted_db"
            )
                .openHelperFactory(supportFactory)
                .fallbackToDestructiveMigration()
                .build()
        }

        private fun getOrCreateDatabasePassphrase(context: Context): ByteArray {
            val prefs = context.getSharedPreferences(PREFS_DB_SECURITY, Context.MODE_PRIVATE)
            val encHex = prefs.getString(PREF_DB_KEY_ENC, null)
            val ivHex = prefs.getString(PREF_DB_KEY_IV, null)

            if (encHex != null && ivHex != null) {
                try {
                    val cipherBytes = CryptoEngine.hexToBytes(encHex)
                    val iv = CryptoEngine.hexToBytes(ivHex)
                    return decryptDbKeyWithKeystore(cipherBytes, iv)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to decrypt DB passphrase from Keystore, generating new", e)
                }
            }

            // Generate fresh 256-bit random key for SQLCipher
            val random = SecureRandom()
            val rawKey = ByteArray(32)
            random.nextBytes(rawKey)

            val (encBytes, iv) = encryptDbKeyWithKeystore(rawKey)
            prefs.edit()
                .putString(PREF_DB_KEY_ENC, CryptoEngine.bytesToHex(encBytes))
                .putString(PREF_DB_KEY_IV, CryptoEngine.bytesToHex(iv))
                .apply()

            return rawKey
        }

        private fun getOrCreateDbMasterKey(): SecretKey {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)

            if (keyStore.containsAlias(DB_KEYSTORE_ALIAS)) {
                val entry = keyStore.getEntry(DB_KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) return entry.secretKey
            }

            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                DB_KEYSTORE_ALIAS,
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

        private fun encryptDbKeyWithKeystore(rawKey: ByteArray): Pair<ByteArray, ByteArray> {
            return try {
                val secretKey = getOrCreateDbMasterKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val ciphertext = cipher.doFinal(rawKey)
                Pair(ciphertext, cipher.iv)
            } catch (e: Throwable) {
                // Software fallback for JVM Unit Test environments
                val fallbackKey = "SOFTWARE_FALLBACK_DB_MASTER_KEY".toByteArray(Charsets.UTF_8).take(32).toByteArray()
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
                Pair(cipher.doFinal(rawKey), iv)
            }
        }

        private fun decryptDbKeyWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                val secretKey = (keyStore.getEntry(DB_KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext)
            } catch (e: Throwable) {
                val fallbackKey = "SOFTWARE_FALLBACK_DB_MASTER_KEY".toByteArray(Charsets.UTF_8).take(32).toByteArray()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
                cipher.doFinal(ciphertext)
            }
        }
    }
}


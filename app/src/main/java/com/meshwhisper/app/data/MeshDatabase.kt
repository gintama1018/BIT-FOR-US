package com.meshwhisper.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.dao.LocationDao
import com.meshwhisper.app.data.dao.MessageDao
import com.meshwhisper.app.data.dao.PacketLogDao
import com.meshwhisper.app.data.dao.PeerDao
import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.dao.StoreForwardDao
import com.meshwhisper.app.data.dao.TopologyEdgeDao
import com.meshwhisper.app.data.model.LastKnownLocationEntity
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.app.data.model.StoreForwardEntity
import com.meshwhisper.app.data.model.TopologyEdgeEntity
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
        ProcessedPacketEntity::class,
        TopologyEdgeEntity::class,
        LastKnownLocationEntity::class
    ],
    version = 10,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun messageDao(): MessageDao
    abstract fun storeForwardDao(): StoreForwardDao
    abstract fun packetLogDao(): PacketLogDao
    abstract fun processedPacketDao(): ProcessedPacketDao
    abstract fun topologyEdgeDao(): TopologyEdgeDao
    abstract fun locationDao(): LocationDao

    companion object {
        private const val TAG = "MeshDatabase"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val DB_KEYSTORE_ALIAS = "MeshWhisperDbMasterKey"
        private const val PREFS_DB_SECURITY = "meshwhisper_db_security_prefs"
        private const val PREF_DB_KEY_ENC = "db_passphrase_enc_hex"
        private const val PREF_DB_KEY_IV = "db_passphrase_iv_hex"

        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN avatarUri TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN avatarHash INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE peers ADD COLUMN isMuted INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN originalFileName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE messages ADD COLUMN mediaPreviewBase64 TEXT DEFAULT NULL")
            }
        }

        // Migration 7→8: Remove the dead `retryCount` column from store_forward_queue.
        // SQLite doesn't support DROP COLUMN on older APIs, so we recreate the table.
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS store_forward_queue_new (
                        messageId TEXT NOT NULL PRIMARY KEY,
                        recipientId INTEGER NOT NULL,
                        packetData BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO store_forward_queue_new (messageId, recipientId, packetData, createdAt, expiresAt)
                    SELECT messageId, recipientId, packetData, createdAt, expiresAt FROM store_forward_queue
                """.trimIndent())
                db.execSQL("DROP TABLE store_forward_queue")
                db.execSQL("ALTER TABLE store_forward_queue_new RENAME TO store_forward_queue")
            }
        }

        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN isSos INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS last_known_locations (
                        nodeId INTEGER NOT NULL PRIMARY KEY,
                        alias TEXT NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        accuracyMeters REAL NOT NULL DEFAULT 0.0,
                        timestamp INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE peers ADD COLUMN isVerified INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var INSTANCE: MeshDatabase? = null

        fun getInstance(context: Context): MeshDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(appContext: Context): MeshDatabase {
            try {
                System.loadLibrary("sqlcipher")
            } catch (t: Throwable) {
                Log.e(TAG, "Error loading sqlcipher native library: ${t.message}", t)
            }
            val dbPassphrase = getOrCreateDatabasePassphrase(appContext)
            val supportFactory = SupportOpenHelperFactory(dbPassphrase)

            return Room.databaseBuilder(
                appContext,
                MeshDatabase::class.java,
                "meshwhisper_encrypted_db"
            )
                .openHelperFactory(supportFactory)
                .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
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

        private fun isAndroidKeyStoreAvailable(): Boolean {
            return try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                keyStore.load(null)
                true
            } catch (e: Throwable) {
                false
            }
        }

        private fun encryptDbKeyWithKeystore(rawKey: ByteArray): Pair<ByteArray, ByteArray> {
            if (!isAndroidKeyStoreAvailable()) {
                // Software fallback for JVM Unit Test environments
                val fallbackKey = "SOFTWARE_FALLBACK_DB_MASTER_KEY".toByteArray(Charsets.UTF_8).take(32).toByteArray()
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
                return Pair(cipher.doFinal(rawKey), iv)
            }

            val secretKey = getOrCreateDbMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val ciphertext = cipher.doFinal(rawKey)
            return Pair(ciphertext, cipher.iv)
        }

        private fun decryptDbKeyWithKeystore(ciphertext: ByteArray, iv: ByteArray): ByteArray {
            if (!isAndroidKeyStoreAvailable()) {
                val fallbackKey = "SOFTWARE_FALLBACK_DB_MASTER_KEY".toByteArray(Charsets.UTF_8).take(32).toByteArray()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fallbackKey, "AES"), GCMParameterSpec(128, iv))
                return cipher.doFinal(ciphertext)
            }

            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val secretKey = (keyStore.getEntry(DB_KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            return cipher.doFinal(ciphertext)
        }

        /**
         * Performs a hard panic wipe in the correct sequence:
         *   1. Closes the Room DB connection (flush WAL, release file handles)
         *   2. Deletes the SQLCipher encrypted DB file from disk
         *   3. Deletes the Keystore master key so the DB passphrase is permanently unrecoverable
         *   4. Clears the encrypted DB passphrase from SharedPreferences
         *
         * After calling this, call [resetDbSingleton] and then kill the process
         * (e.g. Process.killProcess(Process.myPid())) so Room's static singleton
         * is not used against a now-deleted database file.
         *
         * IMPORTANT: Do NOT continue using the [db] object after this call.
         */
        suspend fun performHardWipe(context: Context, db: MeshDatabase): Boolean {
            return try {
                // Step 1: Close the Room database (flushes WAL, releases file locks)
                try {
                    db.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to close DB before wipe (continuing anyway)", e)
                }

                // Step 2: Delete the SQLCipher database file and its WAL/SHM siblings
                val dbDir = context.getDatabasePath("meshwhisper_encrypted_db")
                val dbFiles = listOf(
                    dbDir,
                    java.io.File(dbDir.path + "-wal"),
                    java.io.File(dbDir.path + "-shm"),
                    java.io.File(dbDir.path + "-journal")
                )
                for (f in dbFiles) {
                    if (f.exists()) {
                        val deleted = f.delete()
                        Log.i(TAG, "Hard wipe: deleted ${f.name} = $deleted")
                    }
                }

                // Step 3: Delete DB master key from AndroidKeyStore so old ciphertext is permanently unreadable
                try {
                    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                    keyStore.load(null)
                    if (keyStore.containsAlias(DB_KEYSTORE_ALIAS)) {
                        keyStore.deleteEntry(DB_KEYSTORE_ALIAS)
                        Log.i(TAG, "Hard wipe: deleted Keystore alias $DB_KEYSTORE_ALIAS")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete DB Keystore key (continuing)", e)
                }

                // Step 4: Clear the encrypted DB passphrase prefs
                try {
                    val prefs = context.getSharedPreferences(PREFS_DB_SECURITY, Context.MODE_PRIVATE)
                    prefs.edit().clear().commit() // commit() not apply() — we need synchronous flush
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to clear DB security prefs", e)
                }

                Log.i(TAG, "Hard wipe complete — DB file deleted, Keystore key destroyed, prefs cleared")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Hard wipe failed", e)
                false
            }
        }

        /**
         * Clears the in-process Room singleton so that [getInstance] will rebuild
         * a fresh database on next call. Must be called after [performHardWipe].
         */
        fun resetDbSingleton() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}



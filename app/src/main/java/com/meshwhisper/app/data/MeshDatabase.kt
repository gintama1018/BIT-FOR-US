package com.meshwhisper.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.meshwhisper.app.BuildConfig
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.dao.IdentityDao
import com.meshwhisper.app.data.dao.LocationDao
import com.meshwhisper.app.data.dao.MessageDao
import com.meshwhisper.app.data.dao.PacketLogDao
import com.meshwhisper.app.data.dao.PeerDao
import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.dao.ProfileDao
import com.meshwhisper.app.data.dao.StoreForwardDao
import com.meshwhisper.app.data.dao.TopologyEdgeDao
import com.meshwhisper.app.data.model.BreadcrumbHistoryEntity
import com.meshwhisper.app.data.model.IdentityEntity
import com.meshwhisper.app.data.model.LastKnownLocationEntity
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.app.data.model.ProfileEntity
import com.meshwhisper.app.data.model.StoreForwardEntity
import com.meshwhisper.app.data.model.TopologyEdgeEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
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
        LastKnownLocationEntity::class,
        ProfileEntity::class,
        IdentityEntity::class,
        BreadcrumbHistoryEntity::class
    ],
    version = 13,
    exportSchema = true
)
abstract class MeshDatabase : RoomDatabase() {

    abstract fun peerDao(): PeerDao
    abstract fun profileDao(): ProfileDao
    abstract fun messageDao(): MessageDao
    abstract fun storeForwardDao(): StoreForwardDao
    abstract fun packetLogDao(): PacketLogDao
    abstract fun processedPacketDao(): ProcessedPacketDao
    abstract fun topologyEdgeDao(): TopologyEdgeDao
    abstract fun locationDao(): LocationDao
    abstract fun identityDao(): IdentityDao

    companion object {
        private const val TAG = "MeshDatabase"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DB_KEYSTORE_ALIAS = "MeshWhisperDbMasterKey"
        private const val PREFS_DB_SECURITY = "meshwhisper_db_security_prefs"
        private const val PREF_DB_KEY_ENC = "db_passphrase_enc_hex"
        private const val PREF_DB_KEY_IV = "db_passphrase_iv_hex"

        sealed class MigrationFailureState {
            object None : MigrationFailureState()
            data class Failed(val error: Throwable, val details: String) : MigrationFailureState()
        }

        @Volatile
        var migrationFailureState: MigrationFailureState = MigrationFailureState.None
            private set

        fun exportDiagnostics(context: Context): String {
            val dbFile = context.getDatabasePath("meshwhisper_encrypted_db")
            val dbExists = dbFile.exists()
            val dbSize = if (dbExists) dbFile.length() else 0L
            val ksAvailable = isAndroidKeyStoreAvailable()
            val prefs = context.getSharedPreferences(PREFS_DB_SECURITY, Context.MODE_PRIVATE)
            val hasEncKey = prefs.contains(PREF_DB_KEY_ENC)
            val state = migrationFailureState
            val sb = java.lang.StringBuilder()
            sb.appendLine("=== MESHWHISPER STORAGE DIAGNOSTICS ===")
            sb.appendLine("Timestamp: ${System.currentTimeMillis()}")
            sb.appendLine("Database File: ${dbFile.absolutePath} (exists=$dbExists, size=$dbSize bytes)")
            sb.appendLine("AndroidKeyStore Available: $ksAvailable")
            sb.appendLine("Encrypted DB Key Persisted: $hasEncKey")
            sb.appendLine("Current Failure State: $state")
            if (state is MigrationFailureState.Failed) {
                sb.appendLine("Failure Details: ${state.details}")
                sb.appendLine("Exception StackTrace:")
                sb.appendLine(state.error.stackTraceToString())
            }
            return sb.toString()
        }

        suspend fun eraseAndStartFresh(context: Context) {
            performHardWipe(context, INSTANCE, killProcess = false)
            resetDbSingleton()
            migrationFailureState = MigrationFailureState.None
        }

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

        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS profiles (
                        nodeId INTEGER NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        bio TEXT NOT NULL DEFAULT '',
                        avatarHashHex TEXT NOT NULL DEFAULT '',
                        avatarUri TEXT DEFAULT NULL,
                        version INTEGER NOT NULL DEFAULT 1,
                        signature BLOB DEFAULT NULL,
                        updatedAt INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT OR IGNORE INTO profiles (nodeId, displayName, bio, avatarHashHex, avatarUri, version, updatedAt)
                    SELECT nodeId, alias, '', '', avatarUri, 1, lastSeen FROM peers
                """.trimIndent())
            }
        }

        /**
         * Room Migration 11 → 12.
         * Specified in NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §11.3 and C-21:
         * 1. Create `identities` table
         * 2. Add `identityHashHex` (nullable) and `trustState` (default `LEGACY_UNVERIFIED`) to `peers`
         * 3. Add `state`, `fromIdentityHashHex`, `toIdentityHashHex` to `topology_edges`
         * 4. UPDATE messages SET status='EXPIRED' WHERE messageId IN (SELECT messageId FROM store_forward_queue)
         * 5. DELETE FROM store_forward_queue
         * 6. DELETE FROM processed_packets; DELETE FROM topology_edges; DELETE FROM packet_logs
         */
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // 1. Create identities table
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS identities (
                        identityHashHex TEXT NOT NULL PRIMARY KEY,
                        ikPubHex TEXT NOT NULL,
                        ekPubHex TEXT NOT NULL,
                        keyVersion INTEGER NOT NULL DEFAULT 1,
                        lastAnnounceCounter INTEGER NOT NULL DEFAULT 0,
                        trustState TEXT NOT NULL DEFAULT 'SEEN',
                        nodeId64 INTEGER NOT NULL DEFAULT 0,
                        alias TEXT DEFAULT NULL,
                        createdAt INTEGER NOT NULL,
                        lastSeenAt INTEGER NOT NULL
                    )
                """.trimIndent())

                // 2. Add identityHashHex and trustState to peers
                db.execSQL("ALTER TABLE peers ADD COLUMN identityHashHex TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN trustState TEXT NOT NULL DEFAULT 'LEGACY_UNVERIFIED'")

                // 3. Add state, fromIdentityHashHex, toIdentityHashHex to topology_edges
                db.execSQL("ALTER TABLE topology_edges ADD COLUMN state TEXT NOT NULL DEFAULT 'STAGED'")
                db.execSQL("ALTER TABLE topology_edges ADD COLUMN fromIdentityHashHex TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE topology_edges ADD COLUMN toIdentityHashHex TEXT DEFAULT NULL")

                // 4. Targeted C-21 store_forward_queue migration
                db.execSQL("UPDATE messages SET status = 'EXPIRED' WHERE messageId IN (SELECT messageId FROM store_forward_queue)")
                db.execSQL("DELETE FROM store_forward_queue")

                // 5. Truncate unauthenticated/stale v1 caches
                db.execSQL("DELETE FROM processed_packets")
                db.execSQL("DELETE FROM topology_edges")
                db.execSQL("DELETE FROM packet_logs")
            }
        }

        /**
         * Room Migration 12 → 13.
         * 1. Add sequenceNumber, receivedTimestamp, altitude, batteryPercent, triggerType, note to `last_known_locations`.
         * 2. Add shareLocationWithContact to `peers`.
         * 3. Create `breadcrumb_history` table with unique index on (nodeId, sequenceNumber).
         */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN sequenceNumber INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN receivedTimestamp INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN altitude REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN batteryPercent INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN triggerType INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE last_known_locations ADD COLUMN note TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE peers ADD COLUMN shareLocationWithContact INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS breadcrumb_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        nodeId INTEGER NOT NULL,
                        sequenceNumber INTEGER NOT NULL,
                        latitude REAL NOT NULL,
                        longitude REAL NOT NULL,
                        altitude REAL NOT NULL DEFAULT 0.0,
                        accuracyMeters REAL NOT NULL DEFAULT 0.0,
                        batteryPercent INTEGER NOT NULL DEFAULT -1,
                        triggerType INTEGER NOT NULL DEFAULT 1,
                        sentTimestamp INTEGER NOT NULL,
                        receivedTimestamp INTEGER NOT NULL,
                        note TEXT DEFAULT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_breadcrumb_history_nodeId_sequenceNumber ON breadcrumb_history(nodeId, sequenceNumber)")
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

            try {
                val dbPassphrase = getOrCreateDatabasePassphrase(appContext)
                val supportFactory = SupportOpenHelperFactory(dbPassphrase)

                return Room.databaseBuilder(
                    appContext,
                    MeshDatabase::class.java,
                    "meshwhisper_encrypted_db"
                )
                    .openHelperFactory(supportFactory)
                    .addMigrations(
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13
                    )
                    // Explicitly NO fallbackToDestructiveMigration() per C-20
                    .build()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open or migrate database — entering C-20 failure path", t)
                migrationFailureState = MigrationFailureState.Failed(t, "Database initialization failed: ${t.message}")
                throw t
            }
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
                    Log.e(TAG, "Failed to decrypt existing DB passphrase from Keystore — FAILING CLOSED per C-20", e)
                    val err = SecurityException("Failed to decrypt existing database key from Keystore. Refusing replacement key generation (C-20 fail closed).", e)
                    migrationFailureState = MigrationFailureState.Failed(err, "Existing DB key decryption failed: ${e.message}")
                    throw err
                }
            }

            // Only generate fresh random key for genuinely new / uninitialized database
            val random = SecureRandom()
            val rawKey = ByteArray(32)
            random.nextBytes(rawKey)

            val (encBytes, iv) = encryptDbKeyWithKeystore(rawKey)
            prefs.edit()
                .putString(PREF_DB_KEY_ENC, CryptoEngine.bytesToHex(encBytes))
                .putString(PREF_DB_KEY_IV, CryptoEngine.bytesToHex(iv))
                .commit()

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
                if (!BuildConfig.DEBUG) {
                    throw SecurityException("AndroidKeyStore unavailable in release build. Software fallback forbidden (S-6/T-KEY-01).")
                }
                // Software fallback only for JVM Unit Test / Debug environments
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
                if (!BuildConfig.DEBUG) {
                    throw SecurityException("AndroidKeyStore unavailable in release build. Software fallback forbidden (S-6/T-KEY-01).")
                }
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
         * Performs a hard panic wipe in the exact C-26 monotonic order:
         *   1. notificationManager.cancelAll()
         *   2. delete Keystore alias MeshWhisperDbMasterKey
         *   3. delete Keystore alias MeshWhisperIdentityMasterKey
         *   4. delete Keystore alias MeshWhisperMediaMasterKey
         *   5. close DB; delete meshwhisper_encrypted_db{,-wal,-shm,-journal}
         *   6. deleteRecursively filesDir/media, filesDir/avatars
         *   7. clear ALL SharedPreferences files in shared_prefs/ (commit)
         *   8. Process.killProcess(Process.myPid())
         *
         * Executed under NonCancellable. After Step 2, the DB is permanently undecryptable
         * even if steps 5+ are interrupted.
         */
        suspend fun performHardWipe(context: Context, db: MeshDatabase? = null, killProcess: Boolean = true): Boolean {
            return withContext(NonCancellable) {
                try {
                    // Step 1: cancel all notifications
                    try {
                        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                        nm?.cancelAll()
                        Log.i(TAG, "Hard wipe Step 1: notifications cancelled")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 1 (continuing)", e)
                    }

                    // Step 2: delete Keystore alias MeshWhisperDbMasterKey
                    try {
                        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                        keyStore.load(null)
                        if (keyStore.containsAlias(DB_KEYSTORE_ALIAS)) {
                            keyStore.deleteEntry(DB_KEYSTORE_ALIAS)
                            Log.i(TAG, "Hard wipe Step 2: deleted $DB_KEYSTORE_ALIAS")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 2 (continuing)", e)
                    }

                    // Step 3: delete Keystore alias MeshWhisperIdentityMasterKey
                    try {
                        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                        keyStore.load(null)
                        val identityAlias = "MeshWhisperIdentityMasterKey"
                        if (keyStore.containsAlias(identityAlias)) {
                            keyStore.deleteEntry(identityAlias)
                            Log.i(TAG, "Hard wipe Step 3: deleted $identityAlias")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 3 (continuing)", e)
                    }

                    // Step 4: delete Keystore alias MeshWhisperMediaMasterKey
                    try {
                        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
                        keyStore.load(null)
                        val mediaAlias = "MeshWhisperMediaMasterKey"
                        if (keyStore.containsAlias(mediaAlias)) {
                            keyStore.deleteEntry(mediaAlias)
                            Log.i(TAG, "Hard wipe Step 4: deleted $mediaAlias")
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 4 (continuing)", e)
                    }

                    // Step 5: close DB; delete meshwhisper_encrypted_db{,-wal,-shm,-journal}
                    try {
                        val activeDb = db ?: synchronized(this) { INSTANCE }
                        activeDb?.close()
                        resetDbSingleton()
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed closing DB (continuing)", e)
                    }
                    val dbDir = context.getDatabasePath("meshwhisper_encrypted_db")
                    val dbFiles = listOf(
                        dbDir,
                        File(dbDir.path + "-wal"),
                        File(dbDir.path + "-shm"),
                        File(dbDir.path + "-journal")
                    )
                    for (f in dbFiles) {
                        if (f.exists()) {
                            f.delete()
                        }
                    }
                    Log.i(TAG, "Hard wipe Step 5: DB closed and database files deleted")

                    // Step 6: deleteRecursively filesDir/media, filesDir/avatars
                    try {
                        File(context.filesDir, "media").deleteRecursively()
                        File(context.filesDir, "avatars").deleteRecursively()
                        Log.i(TAG, "Hard wipe Step 6: media and avatars deleted")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 6 (continuing)", e)
                    }

                    // Step 7: clear ALL SharedPreferences files in shared_prefs/ (commit)
                    try {
                        val sharedPrefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
                        if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                            val prefFiles = sharedPrefsDir.listFiles() ?: emptyArray()
                            for (prefFile in prefFiles) {
                                if (prefFile.name.endsWith(".xml")) {
                                    val prefName = prefFile.name.removeSuffix(".xml")
                                    context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                                        .edit().clear().commit()
                                }
                            }
                            sharedPrefsDir.deleteRecursively()
                        }
                        Log.i(TAG, "Hard wipe Step 7: shared_prefs cleared")
                    } catch (e: Throwable) {
                        Log.e(TAG, "Failed Step 7 (continuing)", e)
                    }

                    // Step 8: Process.killProcess(Process.myPid())
                    if (killProcess) {
                        Log.w(TAG, "Hard wipe Step 8: terminating process")
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    true
                } catch (e: Throwable) {
                    Log.e(TAG, "Hard wipe error", e)
                    false
                }
            }
        }

        fun resetDbSingleton() {
            synchronized(this) {
                INSTANCE = null
            }
        }

        fun getActiveDbSingleton(): MeshDatabase? {
            return synchronized(this) {
                INSTANCE
            }
        }
    }
}

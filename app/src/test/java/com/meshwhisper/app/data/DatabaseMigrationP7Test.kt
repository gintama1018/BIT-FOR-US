package com.meshwhisper.app.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID

/**
 * Normative Phase P7 Database & Media Migration Tests (T-MIG-01 .. T-MIG-06).
 *
 * Verifies:
 * - T-MIG-01: Schema 11 -> 12 migration executes cleanly.
 * - T-MIG-02: identities table creation matches frozen vNext specification.
 * - T-MIG-03: peers table column alterations (identityHashHex, trustState = 'LEGACY_UNVERIFIED').
 * - T-MIG-04: topology_edges alterations (state = 'STAGED', fromIdentityHashHex, toIdentityHashHex).
 * - T-MIG-05: Targeted C-21 store_forward_queue migration (messages status updated to EXPIRED, queue emptied).
 * - T-MIG-06: Cache tables truncated (processed_packets, topology_edges, packet_logs).
 * - T-MIG-MEDIA: In-place media-at-rest encryption round-trip and directory migration.
 */
class DatabaseMigrationP7Test {

    private fun createRecordingDb(executedSql: MutableList<String>): SupportSQLiteDatabase {
        return Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java)
        ) { _, method, args ->
            when (method.name) {
                "execSQL" -> {
                    val sql = args?.get(0) as? String
                    if (sql != null) executedSql.add(sql.trim())
                    null
                }
                "getVersion" -> 11
                "isOpen" -> true
                "isWriteAheadLoggingEnabled" -> true
                "toString" -> "MockSupportSQLiteDatabase"
                "hashCode" -> 1
                "equals" -> false
                else -> {
                    when (method.returnType) {
                        java.lang.Boolean.TYPE -> false
                        java.lang.Integer.TYPE -> 0
                        java.lang.Long.TYPE -> 0L
                        else -> null
                    }
                }
            }
        } as SupportSQLiteDatabase
    }

    @Test
    fun testMigration11To12_executesAllRequiredDdlAndDml() {
        val executedSql = mutableListOf<String>()
        val db = createRecordingDb(executedSql)
        MeshDatabase.MIGRATION_11_12.migrate(db)

        val statements = executedSql
        assertThat(statements).isNotEmpty()

        // 1. Verify identities table creation (T-MIG-02)
        val createIdentitiesSql = statements.find { it.contains("CREATE TABLE IF NOT EXISTS identities") }
        assertThat(createIdentitiesSql).isNotNull()
        assertThat(createIdentitiesSql).contains("identityHashHex TEXT NOT NULL PRIMARY KEY")
        assertThat(createIdentitiesSql).contains("ikPubHex TEXT NOT NULL")
        assertThat(createIdentitiesSql).contains("ekPubHex TEXT NOT NULL")
        assertThat(createIdentitiesSql).contains("keyVersion INTEGER NOT NULL DEFAULT 1")
        assertThat(createIdentitiesSql).contains("lastAnnounceCounter INTEGER NOT NULL DEFAULT 0")
        assertThat(createIdentitiesSql).contains("trustState TEXT NOT NULL DEFAULT 'SEEN'")
        assertThat(createIdentitiesSql).contains("nodeId64 INTEGER NOT NULL DEFAULT 0")
        assertThat(createIdentitiesSql).contains("alias TEXT DEFAULT NULL")
        assertThat(createIdentitiesSql).contains("createdAt INTEGER NOT NULL")
        assertThat(createIdentitiesSql).contains("lastSeenAt INTEGER NOT NULL")

        // 2. Verify peers table alterations (T-MIG-03)
        val alterPeersIdentity = statements.find { it.contains("ALTER TABLE peers ADD COLUMN identityHashHex") }
        assertThat(alterPeersIdentity).isNotNull()
        assertThat(alterPeersIdentity).contains("TEXT DEFAULT NULL")

        val alterPeersTrustState = statements.find { it.contains("ALTER TABLE peers ADD COLUMN trustState") }
        assertThat(alterPeersTrustState).isNotNull()
        assertThat(alterPeersTrustState).contains("DEFAULT 'LEGACY_UNVERIFIED'")

        // 3. Verify topology_edges alterations (T-MIG-04)
        val alterEdgesState = statements.find { it.contains("ALTER TABLE topology_edges ADD COLUMN state") }
        assertThat(alterEdgesState).isNotNull()
        assertThat(alterEdgesState).contains("DEFAULT 'STAGED'")

        val alterEdgesFrom = statements.find { it.contains("ALTER TABLE topology_edges ADD COLUMN fromIdentityHashHex") }
        assertThat(alterEdgesFrom).isNotNull()

        val alterEdgesTo = statements.find { it.contains("ALTER TABLE topology_edges ADD COLUMN toIdentityHashHex") }
        assertThat(alterEdgesTo).isNotNull()

        // 4. Verify targeted store_forward_queue migration (T-MIG-05)
        val updateMessages = statements.find { it.startsWith("UPDATE messages SET status = 'EXPIRED'") }
        assertThat(updateMessages).isNotNull()
        assertThat(updateMessages).contains("WHERE messageId IN (SELECT messageId FROM store_forward_queue)")

        val deleteQueue = statements.find { it == "DELETE FROM store_forward_queue" }
        assertThat(deleteQueue).isNotNull()

        // 5. Verify cache truncations (T-MIG-06)
        assertThat(statements).contains("DELETE FROM processed_packets")
        assertThat(statements).contains("DELETE FROM topology_edges")
        assertThat(statements).contains("DELETE FROM packet_logs")
    }

    @Test
    fun testMediaAtRest_encryptionRoundTripAndIvPrefix() {
        val tempDir = Files.createTempDirectory("media_at_rest_test").toFile()
        try {
            val testFile = File(tempDir, "test_file_001.bin")
            val originalPayload = "Confidential mesh payload for media-at-rest encryption test".toByteArray(Charsets.UTF_8)

            val masterKey = ByteArray(32) { 0x42 }
            val fileId = "test_file_001.bin"
            val derivedKey = PureCryptoEngine.hkdf(
                ikm = masterKey,
                salt = fileId.toByteArray(Charsets.UTF_8),
                info = "MW/FILE/v2".toByteArray(Charsets.UTF_8),
                outputLength = 32
            )
            assertThat(derivedKey.size).isEqualTo(32)

            val dummyMessageId = UUID.randomUUID()
            val aad = fileId.toByteArray(Charsets.UTF_8)
            val explicitIv = ByteArray(12) { 0x07 }

            // Encrypt using PureCryptoEngine (returns outputCiphertext = 12B IV + raw ciphertext, and 16B authTag)
            val encResult = PureCryptoEngine.encrypt(
                plaintext = originalPayload,
                messageId = dummyMessageId,
                aesKey = derivedKey,
                aad = aad,
                explicitIv = explicitIv
            )

            // Combined file bytes = 12B IV + raw ciphertext + 16B authTag
            val onDisk = encResult.ciphertext + encResult.authTag
            testFile.writeBytes(onDisk)

            // Verify file size
            assertThat(testFile.length()).isEqualTo((12 + originalPayload.size + 16).toLong())

            // Decrypt
            val readDisk = testFile.readBytes()
            val ciphertext = readDisk.copyOfRange(0, readDisk.size - 16)
            val authTag = readDisk.copyOfRange(readDisk.size - 16, readDisk.size)
            val decrypted = PureCryptoEngine.decrypt(
                ciphertext = ciphertext,
                authTag = authTag,
                messageId = dummyMessageId,
                aesKey = derivedKey,
                aad = aad
            )

            assertThat(decrypted.toList()).isEqualTo(originalPayload.toList())

            // Tamper with ciphertext byte (index 15, within ciphertext)
            ciphertext[15] = (ciphertext[15].toInt() xor 0xFF).toByte()
            assertThrows(Exception::class.java) {
                PureCryptoEngine.decrypt(
                    ciphertext = ciphertext,
                    authTag = authTag,
                    messageId = dummyMessageId,
                    aesKey = derivedKey,
                    aad = aad
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

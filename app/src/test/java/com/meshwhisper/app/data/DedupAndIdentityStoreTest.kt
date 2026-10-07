package com.meshwhisper.app.data

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.app.router.RoomPacketStore
import org.junit.Test
import java.util.UUID

class DedupAndIdentityStoreTest {

    private class FakeProcessedPacketDao : ProcessedPacketDao {
        val records = mutableMapOf<String, Long>()

        override suspend fun hasSeen(messageId: String): Int =
            if (records.containsKey(messageId)) 1 else 0

        override suspend fun markSeen(packet: ProcessedPacketEntity): Long {
            return if (records.putIfAbsent(packet.messageId, packet.timestamp) == null) {
                1L
            } else {
                -1L
            }
        }

        override suspend fun purgeOld(cutoffTime: Long): Int {
            val count = records.count { it.value < cutoffTime }
            records.entries.removeIf { it.value < cutoffTime }
            return count
        }

        override suspend fun getRecentMessageIds(limit: Int): List<String> =
            records.keys.take(limit)

        override suspend fun pruneExcessRows(maxRows: Int): Int {
            if (records.size <= maxRows) return 0
            val excess = records.size - maxRows
            val keys = records.keys.take(excess).toList()
            for (k in keys) records.remove(k)
            return excess
        }

        override suspend fun count(): Int = records.size

        override suspend fun deleteAll() {
            records.clear()
        }
    }

    @Test
    fun testRoomPacketStoreInMemoryPreAuthDedupAndPersistence() {
        val fakeDao = FakeProcessedPacketDao()
        val store = RoomPacketStore(fakeDao)
        val msgId = UUID.randomUUID()
        val typeCode = 0x04.toByte()

        // S3 pre-auth check: not seen initially
        assertThat(store.isSeen(msgId, typeCode)).isFalse()

        // S7 post-auth commit
        val committed = store.commitSeen(msgId, typeCode, 1000L)
        assertThat(committed).isTrue()

        // S3 pre-auth check now returns true directly from memory
        assertThat(store.isSeen(msgId, typeCode)).isTrue()

        // Second commit attempt is rejected
        val duplicateCommit = store.commitSeen(msgId, typeCode, 1000L)
        assertThat(duplicateCommit).isFalse()

        // Verify state is stored in persistent DAO
        assertThat(fakeDao.records).containsKey("$msgId:$typeCode")

        // Restart simulation: new RoomPacketStore preloads existing records
        val restartedStore = RoomPacketStore(fakeDao)
        assertThat(restartedStore.isSeen(msgId, typeCode)).isTrue()
    }
}

package com.meshwhisper.app.router

import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.core.protocol.ResourceLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Room database-backed PacketStore for Android.
 * Implements post-auth atomic commitment to processed_packets Room table.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and Phase P3 Security Amendment 6.
 *
 * Enforces:
 * 1. Bounded in-memory LRU cache up to [ResourceLimits.MAX_PROCESSED_PACKETS_ROWS] (50,000) entries with automatic eviction.
 * 2. Pre-auth S3 [isSeen] lookup executes entirely in-memory (zero blocking disk I/O, prevents pre-auth SQLCipher DoS).
 * 3. Post-auth S7 [commitSeen] executes atomic persistence to Room.
 */
class RoomPacketStore(
    private val processedPacketDao: ProcessedPacketDao
) : PacketStore {
    private val inMemoryLru = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > ResourceLimits.MAX_PROCESSED_PACKETS_ROWS
            }
        }
    )

    init {
        // Preload recent processed message IDs into bounded LRU to preserve dedup across restarts without disk queries in S3
        runCatching {
            runBlocking(Dispatchers.IO) {
                val recent = processedPacketDao.getRecentMessageIds(ResourceLimits.MAX_PROCESSED_PACKETS_ROWS)
                for (id in recent) {
                    inMemoryLru[id] = true
                }
            }
        }
    }

    override fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean {
        val key = "$messageId:$typeCode"
        if (inMemoryLru.put(key, true) != null) return false

        return runBlocking(Dispatchers.IO) {
            val entity = ProcessedPacketEntity(messageId = key, timestamp = timestampSec)
            val rowId = processedPacketDao.markSeen(entity)
            rowId != -1L
        }
    }

    override fun isSeen(messageId: UUID, typeCode: Byte): Boolean {
        val key = "$messageId:$typeCode"
        return inMemoryLru.containsKey(key)
    }

    override fun getSeenCount(): Int = inMemoryLru.size
}

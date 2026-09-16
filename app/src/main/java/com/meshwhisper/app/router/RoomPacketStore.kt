package com.meshwhisper.app.router

import com.meshwhisper.app.data.dao.ProcessedPacketDao
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.core.protocol.PacketStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Room database-backed PacketStore for Android.
 * Implements post-auth atomic commitment to processed_packets Room table.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and Phase P3 Security Amendment 6.
 */
class RoomPacketStore(
    private val processedPacketDao: ProcessedPacketDao
) : PacketStore {
    private val inMemorySet = ConcurrentHashMap.newKeySet<String>()

    override fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean {
        val key = "$messageId:$typeCode"
        if (!inMemorySet.add(key)) return false

        return runBlocking(Dispatchers.IO) {
            val entity = ProcessedPacketEntity(messageId = key, timestamp = timestampSec)
            val rowId = processedPacketDao.markSeen(entity)
            rowId != -1L
        }
    }

    override fun isSeen(messageId: UUID, typeCode: Byte): Boolean {
        val key = "$messageId:$typeCode"
        if (inMemorySet.contains(key)) return true
        return runBlocking(Dispatchers.IO) {
            processedPacketDao.hasSeen(key) > 0
        }
    }

    override fun getSeenCount(): Int = inMemorySet.size
}

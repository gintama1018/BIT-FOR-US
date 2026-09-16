package com.meshwhisper.desktop.router

import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.desktop.db.DesktopDatabase
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop database-backed PacketStore for Desktop module.
 * Implements post-auth atomic commitment to desktop processed_packets table.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and Phase P3 Security Amendment 6.
 */
class DesktopPacketStore(
    private val database: DesktopDatabase
) : PacketStore {
    private val inMemorySet = ConcurrentHashMap.newKeySet<String>()

    override fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean {
        val key = "$messageId:$typeCode"
        if (!inMemorySet.add(key)) return false
        if (database.isPacketSeen(key)) return false

        database.markPacketSeen(key, timestampSec)
        return true
    }

    override fun isSeen(messageId: UUID, typeCode: Byte): Boolean {
        val key = "$messageId:$typeCode"
        return inMemorySet.contains(key) || database.isPacketSeen(key)
    }

    override fun getSeenCount(): Int = inMemorySet.size
}

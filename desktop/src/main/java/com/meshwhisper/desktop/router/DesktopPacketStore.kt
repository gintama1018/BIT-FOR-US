package com.meshwhisper.desktop.router

import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.core.protocol.ResourceLimits
import com.meshwhisper.desktop.db.DesktopDatabase
import java.util.Collections
import java.util.LinkedHashMap
import java.util.UUID

/**
 * Desktop database-backed PacketStore for Desktop module.
 * Implements post-auth atomic commitment to desktop processed_packets table.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and Phase P3 Security Amendment 6.
 *
 * Enforces:
 * 1. Bounded in-memory LRU cache up to [ResourceLimits.MAX_PROCESSED_PACKETS_ROWS] (50,000) entries with automatic eviction.
 * 2. Pre-auth S3 [isSeen] lookup executes entirely in-memory (zero blocking disk I/O, prevents pre-auth SQL DoS).
 * 3. Post-auth S7 [commitSeen] executes atomic persistence to SQLite.
 */
class DesktopPacketStore(
    private val database: DesktopDatabase
) : PacketStore {
    private val inMemoryLru = Collections.synchronizedMap(
        object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                return size > ResourceLimits.MAX_PROCESSED_PACKETS_ROWS
            }
        }
    )

    init {
        runCatching {
            val recent = database.getRecentSeenPacketKeys(ResourceLimits.MAX_PROCESSED_PACKETS_ROWS)
            for (key in recent) {
                inMemoryLru[key] = true
            }
        }
    }

    override fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean {
        val key = "$messageId:$typeCode"
        if (inMemoryLru.put(key, true) != null) return false
        if (database.isPacketSeen(key)) return false

        database.markPacketSeen(key, timestampSec)
        return true
    }

    override fun isSeen(messageId: UUID, typeCode: Byte): Boolean {
        val key = "$messageId:$typeCode"
        return inMemoryLru.containsKey(key)
    }

    override fun getSeenCount(): Int = inMemoryLru.size
}

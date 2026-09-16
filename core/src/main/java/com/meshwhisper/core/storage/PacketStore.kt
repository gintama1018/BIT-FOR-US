package com.meshwhisper.core.storage

import java.util.concurrent.ConcurrentHashMap

/**
 * Storage abstraction for packet deduplication and store-and-forward persistence.
 * Decouples core packet processing from Android Room database.
 * Specified in NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §10.
 */
interface PacketStore {
    /**
     * Atomically marks a packet as seen.
     * Returns true if the packet was newly inserted, or false if already seen (atomic deduplication).
     */
    fun markSeen(dedupKey: String, timestampSec: Long): Boolean

    /**
     * Checks if a packet has already been seen.
     */
    fun hasSeen(dedupKey: String): Boolean

    /**
     * Purges records older than cutoffTimestampSec.
     * Returns the count of deleted records.
     */
    fun purgeOld(cutoffTimestampSec: Long): Int

    /**
     * Clears all stored records.
     */
    fun clear()
}

class InMemoryPacketStore : PacketStore {
    private val seen = ConcurrentHashMap<String, Long>()

    override fun markSeen(dedupKey: String, timestampSec: Long): Boolean {
        return seen.putIfAbsent(dedupKey, timestampSec) == null
    }

    override fun hasSeen(dedupKey: String): Boolean {
        return seen.containsKey(dedupKey)
    }

    override fun purgeOld(cutoffTimestampSec: Long): Int {
        var count = 0
        val it = seen.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry.value < cutoffTimestampSec) {
                it.remove()
                count++
            }
        }
        return count
    }

    override fun clear() {
        seen.clear()
    }
}

package com.meshwhisper.core.protocol

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for post-authentication packet persistence and dedup commitment.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and Phase P3 Security Amendment 6.
 *
 * Invariant I-10:
 * S0-S7 are strictly read-only.
 * Only after all authentication and validation stages pass does PacketPipeline invoke [commitSeen].
 */
interface PacketStore {
    /**
     * Atomically commits a packet's (messageId, typeCode) to persistent storage.
     * Returns true if newly committed, or false if already persisted.
     */
    fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean

    /**
     * Read-only query to check if a (messageId, typeCode) has already been committed.
     * Invoked during S3 Pre-auth Dedup. Does NOT mutate state.
     */
    fun isSeen(messageId: UUID, typeCode: Byte): Boolean

    /**
     * Total number of committed dedup records (useful for zero-state-mutation assertions in tests).
     */
    fun getSeenCount(): Int
}

/**
 * In-memory thread-safe PacketStore implementation for unit tests and memory-only environments.
 */
class InMemoryPacketStore : PacketStore {
    private val seenEntries = ConcurrentHashMap.newKeySet<String>()

    private fun key(messageId: UUID, typeCode: Byte): String = "$messageId:$typeCode"

    override fun commitSeen(messageId: UUID, typeCode: Byte, timestampSec: Long): Boolean {
        return seenEntries.add(key(messageId, typeCode))
    }

    override fun isSeen(messageId: UUID, typeCode: Byte): Boolean {
        return seenEntries.contains(key(messageId, typeCode))
    }

    override fun getSeenCount(): Int = seenEntries.size

    fun clear() {
        seenEntries.clear()
    }
}

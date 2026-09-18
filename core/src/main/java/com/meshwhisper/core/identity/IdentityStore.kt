package com.meshwhisper.core.identity

import com.meshwhisper.core.protocol.ResourceLimits
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Interface for peer identity persistence and lookup.
 * Backed by Room on Android (:app), SQL on Desktop (:desktop), and InMemoryIdentityStore in tests.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2 and NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.1–§2.7, C-23.
 */
interface IdentityStore {
    fun get(identityHash: ByteArray): PeerIdentity?
    fun getByNodeId64(id: Long): PeerIdentity?
    fun getAllByNodeId64(id: Long): List<PeerIdentity>
    fun upsert(identity: PeerIdentity)
    fun all(): List<PeerIdentity>
}

class InMemoryIdentityStore(
    private val maxIdentities: Int = ResourceLimits.MAX_IDENTITIES_PEERS
) : IdentityStore {
    private val byHash = ConcurrentHashMap<String, PeerIdentity>()
    private val byNodeId = ConcurrentHashMap<Long, CopyOnWriteArrayList<PeerIdentity>>()
    private val insertionOrder = ConcurrentLinkedQueue<String>()

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    override fun get(identityHash: ByteArray): PeerIdentity? = byHash[hex(identityHash)]

    override fun getByNodeId64(id: Long): PeerIdentity? {
        val list = byNodeId[id] ?: return null
        return if (list.size == 1) list.first() else null
    }

    override fun getAllByNodeId64(id: Long): List<PeerIdentity> = byNodeId[id]?.toList() ?: emptyList()

    @Synchronized
    override fun upsert(identity: PeerIdentity) {
        val hashKey = hex(identity.identityHash)
        val prev = byHash[hashKey]

        if (prev == null && byHash.size >= maxIdentities) {
            // Evict oldest unverified peer identity
            val iterator = insertionOrder.iterator()
            while (iterator.hasNext()) {
                val candidateKey = iterator.next()
                val candidate = byHash[candidateKey]
                if (candidate != null &&
                    candidate.trustState != TrustState.VERIFIED &&
                    candidate.trustState != TrustState.CONFLICTED &&
                    candidate.trustState != TrustState.BLOCKED
                ) {
                    iterator.remove()
                    byHash.remove(candidateKey)
                    if (candidate.nodeId64 != 0L) {
                        byNodeId[candidate.nodeId64]?.removeIf { it.identityHash.contentEquals(candidate.identityHash) }
                    }
                    break
                }
            }
        }

        byHash[hashKey] = identity
        if (prev == null) {
            insertionOrder.add(hashKey)
        }

        if (prev != null && prev.nodeId64 != identity.nodeId64 && prev.nodeId64 != 0L) {
            byNodeId[prev.nodeId64]?.removeIf { it.identityHash.contentEquals(identity.identityHash) }
        }

        if (identity.nodeId64 != 0L) {
            val list = byNodeId.computeIfAbsent(identity.nodeId64) { CopyOnWriteArrayList() }
            list.removeIf { it.identityHash.contentEquals(identity.identityHash) }
            list.add(identity)
        }
    }

    override fun all(): List<PeerIdentity> = byHash.values.toList()

    fun clear() {
        byHash.clear()
        byNodeId.clear()
        insertionOrder.clear()
    }
}

package com.meshwhisper.app.crypto

import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.protocol.KeyProvider
import com.meshwhisper.core.protocol.PacketPipeline
import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory for creating PacketPipeline in Android app environment.
 * Isolates IdentityStore and KeyProvider from router/ layer to satisfy T-ARCH-01.
 */
object AppPipelineFactory {
    fun create(
        cryptoEngine: CryptoEngine,
        database: MeshDatabase,
        packetStore: PacketStore,
        clock: Clock,
        dedupCache: LruDedupCache<String, Long>,
        peerPublicKeyCache: ConcurrentHashMap<Long, ByteArray>,
        identityRepository: com.meshwhisper.app.identity.IdentityRepository? = null
    ): PacketPipeline {
        val baseIdentityStore = identityRepository?.identityStore ?: InMemoryIdentityStore()

        val identityStore = object : com.meshwhisper.core.identity.IdentityStore {
            override fun get(identityHash: ByteArray): PeerIdentity? {
                val existing = baseIdentityStore.get(identityHash)
                if (existing != null) return existing
                return runBlocking(Dispatchers.IO) {
                    try {
                        val hexHash = com.meshwhisper.core.crypto.PureCryptoEngine.bytesToHex(identityHash)
                        val entity = database.identityDao().getByIdentityHash(hexHash)
                        if (entity != null) {
                            val ikPub = com.meshwhisper.core.crypto.PureCryptoEngine.hexToBytes(entity.ikPubHex)
                            val ekPub = com.meshwhisper.core.crypto.PureCryptoEngine.hexToBytes(entity.ekPubHex)
                            val trust = try { TrustState.valueOf(entity.trustState) } catch (_: Exception) { TrustState.SEEN }
                            val id = PeerIdentity(
                                identityHash = identityHash,
                                ikPub = ikPub,
                                ekPub = ekPub,
                                keyVersion = entity.keyVersion,
                                lastAnnounceCounter = entity.lastAnnounceCounter,
                                trustState = trust,
                                nodeId64 = entity.nodeId64
                            )
                            baseIdentityStore.upsert(id)
                            id
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            override fun getByNodeId64(id: Long): PeerIdentity? {
                val existing = baseIdentityStore.getByNodeId64(id)
                if (existing != null) return existing
                return runBlocking(Dispatchers.IO) {
                    try {
                        val entity = database.identityDao().getByNodeId64(id)
                        if (entity != null) {
                            val ikPub = com.meshwhisper.core.crypto.PureCryptoEngine.hexToBytes(entity.ikPubHex)
                            val ekPub = com.meshwhisper.core.crypto.PureCryptoEngine.hexToBytes(entity.ekPubHex)
                            val idHash = com.meshwhisper.core.crypto.PureCryptoEngine.hexToBytes(entity.identityHashHex)
                            val trust = try { TrustState.valueOf(entity.trustState) } catch (_: Exception) { TrustState.SEEN }
                            val peerId = PeerIdentity(
                                identityHash = idHash,
                                ikPub = ikPub,
                                ekPub = ekPub,
                                keyVersion = entity.keyVersion,
                                lastAnnounceCounter = entity.lastAnnounceCounter,
                                trustState = trust,
                                nodeId64 = entity.nodeId64
                            )
                            baseIdentityStore.upsert(peerId)
                            peerId
                        } else {
                            val peer = database.peerDao().getPeerById(id)
                            if (peer != null) {
                                val ikPub = CryptoEngine.hexToBytes(peer.publicKeyHex)
                                val idHash = com.meshwhisper.core.crypto.PureCryptoEngine.deriveIdentityHash(ikPub)
                                val trust = try { TrustState.valueOf(peer.trustState) } catch (_: Exception) { TrustState.SEEN }
                                val peerId = PeerIdentity(
                                    identityHash = idHash,
                                    ikPub = ikPub,
                                    ekPub = ikPub,
                                    keyVersion = 1,
                                    lastAnnounceCounter = 0,
                                    trustState = trust,
                                    nodeId64 = peer.nodeId
                                )
                                baseIdentityStore.upsert(peerId)
                                peerId
                            } else null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }

            override fun getAllByNodeId64(id: Long): List<PeerIdentity> {
                val list = baseIdentityStore.getAllByNodeId64(id)
                if (list.isNotEmpty()) return list
                val single = getByNodeId64(id)
                return if (single != null) listOf(single) else emptyList()
            }

            override fun upsert(identity: PeerIdentity) {
                baseIdentityStore.upsert(identity)
            }

            override fun all(): List<PeerIdentity> = baseIdentityStore.all()
        }

        // Seed own identity
        identityStore.upsert(
            PeerIdentity(
                identityHash = cryptoEngine.identityHash,
                ikPub = cryptoEngine.ikPublicKeyBytes,
                ekPub = cryptoEngine.ekPublicKeyBytes,
                keyVersion = cryptoEngine.keyVersion,
                lastAnnounceCounter = cryptoEngine.announceCounter,
                trustState = TrustState.VERIFIED,
                nodeId64 = cryptoEngine.nodeId64
            )
        )

        val keyProvider = object : KeyProvider {
            override fun getPublicChannelKey(): ByteArray = cryptoEngine.publicChannelKey
            override fun getActiveChannelKey(): ByteArray? = cryptoEngine.getActiveBroadcastKey()
            override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? {
                val cached = peerPublicKeyCache[peerNodeId]
                val pubKey = if (cached != null) {
                    cached
                } else {
                    runBlocking(Dispatchers.IO) {
                        try {
                            val peer = database.peerDao().getPeerById(peerNodeId)
                            if (peer != null) {
                                val pk = CryptoEngine.hexToBytes(peer.publicKeyHex)
                                peerPublicKeyCache[peerNodeId] = pk
                                pk
                            } else null
                        } catch (_: Exception) {
                            null
                        }
                    }
                } ?: return null

                return cryptoEngine.derivePeerSessionKey(pubKey, timestampSec)
            }
        }

        return PacketPipeline(
            localNodeId64 = cryptoEngine.nodeId64,
            identityStore = identityStore,
            packetStore = packetStore,
            keyProvider = keyProvider,
            clock = clock,
            dedupCache = dedupCache
        )
    }
}

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
        val identityStore = identityRepository?.identityStore ?: InMemoryIdentityStore()

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

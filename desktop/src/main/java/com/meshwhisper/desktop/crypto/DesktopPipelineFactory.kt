package com.meshwhisper.desktop.crypto

import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.protocol.KeyProvider
import com.meshwhisper.core.protocol.PacketPipeline
import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import com.meshwhisper.desktop.db.DesktopDatabase

/**
 * Factory for creating PacketPipeline in Desktop environment.
 * Isolates IdentityStore and KeyProvider from router/ layer to satisfy T-ARCH-01.
 */
object DesktopPipelineFactory {
    fun create(
        myNodeId: Long,
        myIdentityHash: ByteArray,
        myPublicKey: ByteArray,
        myPrivateKey: ByteArray,
        currentKeyVersion: Long,
        packetStore: PacketStore,
        database: DesktopDatabase,
        clock: Clock,
        dedupCache: LruDedupCache<String, Long>
    ): PacketPipeline {
        val ikPub = DesktopCryptoEngine.deriveSigningPublicKey(myPrivateKey)
        val identityStore = InMemoryIdentityStore()
        identityStore.upsert(
            PeerIdentity(
                identityHash = myIdentityHash,
                ikPub = ikPub,
                ekPub = myPublicKey,
                keyVersion = currentKeyVersion,
                lastAnnounceCounter = 0L,
                trustState = TrustState.VERIFIED,
                nodeId64 = myNodeId
            )
        )

        val keyProvider = object : KeyProvider {
            override fun getPublicChannelKey(): ByteArray = DesktopCryptoEngine.derivePublicChannelKey()
            override fun getActiveChannelKey(): ByteArray? = null
            override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? {
                val peer = database.getPeer(peerNodeId) ?: return null
                val peerPubKey = DesktopCryptoEngine.hexToBytes(peer.publicKeyHex)
                return DesktopCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestampSec)
            }
        }

        return PacketPipeline(
            localNodeId64 = myNodeId,
            identityStore = identityStore,
            packetStore = packetStore,
            keyProvider = keyProvider,
            clock = clock,
            dedupCache = dedupCache
        )
    }
}

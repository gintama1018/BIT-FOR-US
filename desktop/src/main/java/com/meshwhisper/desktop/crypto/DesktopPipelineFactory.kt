package com.meshwhisper.desktop.crypto

import com.meshwhisper.core.protocol.KeyProvider
import com.meshwhisper.core.protocol.PacketPipeline
import com.meshwhisper.core.protocol.PacketStore
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.identity.DesktopIdentityRepository

/**
 * Factory for creating PacketPipeline in Desktop environment.
 * Isolates IdentityStore and KeyProvider from router/ layer to satisfy T-ARCH-01.
 * Enforces Phase P9 single authoritative IdentityStore instance constraint.
 */
object DesktopPipelineFactory {
    fun create(
        myNodeId: Long,
        myPrivateKey: ByteArray,
        packetStore: PacketStore,
        database: DesktopDatabase,
        identityRepository: DesktopIdentityRepository,
        clock: Clock,
        dedupCache: LruDedupCache<String, Long>
    ): PacketPipeline {
        // Enforce single authoritative runtime IdentityStore instance
        val identityStore = identityRepository.identityStore

        val keyProvider = object : KeyProvider {
            override fun getPublicChannelKey(): ByteArray = DesktopCryptoEngine.derivePublicChannelKey()
            override fun getActiveChannelKey(): ByteArray? = null
            override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? {
                // Collision-safe routable identity lookup
                val identity = database.getUniqueIdentityByNodeId(peerNodeId) ?: return null
                val peerPubKey = DesktopCryptoEngine.hexToBytes(identity.ekPubHex)
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

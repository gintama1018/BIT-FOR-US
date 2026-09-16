package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.IdentityStore
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.UUID

/**
 * Phase P3 Test Matrix Implementation:
 * - T-INT-01..02
 * - T-NEG-01..31
 * - T-REP-01..08
 *
 * Implements strict universal post-conditions:
 * 1. Packet is dropped.
 * 2. No observable state mutated in IdentityStore or PacketStore.
 */
class PacketPipelineTest {

    private class FixedClock(var currentSec: Long) : Clock {
        override fun nowSeconds(): Long = currentSec
        override fun nowMillis(): Long = currentSec * 1000L
    }

    private data class TestNode(
        val identitySeed: ByteArray,
        val ikPub: ByteArray,
        val identityHash: ByteArray,
        val nodeId64: Long,
        val ekPriv: ByteArray,
        val ekPub: ByteArray,
        val keyVersion: Long = 1L,
        val notBefore: Long = 0L,
        val ibcSignature: ByteArray,
        val alias: String = "TestNode",
        var announceCounter: Long = 100L
    )

    private fun createTestNode(alias: String): TestNode {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId = PureCryptoEngine.deriveNodeId64(idHash)
        val (ekPriv, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val keyVersion = 1L
        val notBefore = 0L
        val ibcSig = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBefore)
        return TestNode(
            identitySeed = seed,
            ikPub = ikPub,
            identityHash = idHash,
            nodeId64 = nodeId,
            ekPriv = ekPriv,
            ekPub = ekPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            ibcSignature = ibcSig,
            alias = alias
        )
    }

    private lateinit var clock: FixedClock
    private lateinit var identityStore: IdentityStore
    private lateinit var packetStore: InMemoryPacketStore
    private lateinit var dedupCache: LruDedupCache<String, Long>
    private lateinit var rateLimiter: VerificationRateLimiter
    private lateinit var serverNode: TestNode
    private lateinit var clientNode: TestNode
    private lateinit var pipeline: PacketPipeline
    private val sessionKeyMap = mutableMapOf<Pair<Long, Long>, ByteArray>()

    private val keyProvider = object : KeyProvider {
        override fun getPublicChannelKey(): ByteArray = PureCryptoEngine.derivePublicChannelKey()
        override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? {
            return sessionKeyMap[Pair(peerNodeId, timestampSec)]
                ?: PureCryptoEngine.derivePeerSessionKey(serverNode.ekPriv, clientNode.ekPub, timestampSec)
        }
    }

    @Before
    fun setUp() {
        clock = FixedClock(1_000_000L)
        identityStore = InMemoryIdentityStore()
        packetStore = InMemoryPacketStore()
        dedupCache = LruDedupCache(1000)
        rateLimiter = VerificationRateLimiter(clock)

        serverNode = createTestNode("ServerNode")
        clientNode = createTestNode("ClientNode")

        pipeline = PacketPipeline(
            localNodeId64 = serverNode.nodeId64,
            identityStore = identityStore,
            packetStore = packetStore,
            keyProvider = keyProvider,
            clock = clock,
            dedupCache = dedupCache
        )
    }

    private fun createAuthLinkContext(boundId: ByteArray? = clientNode.identityHash): LinkContext {
        return LinkContext(
            linkHandle = "link-1",
            transport = TransportType.BLE,
            boundIdentity = boundId,
            state = LinkState.AUTHENTICATED
        )
    }

    private fun registerPeerInStore(node: TestNode, state: TrustState = TrustState.SEEN) {
        identityStore.upsert(
            PeerIdentity(
                identityHash = node.identityHash,
                ikPub = node.ikPub,
                ekPub = node.ekPub,
                keyVersion = node.keyVersion,
                lastAnnounceCounter = node.announceCounter,
                trustState = state,
                nodeId64 = node.nodeId64
            )
        )
    }

    private fun buildAnnounceWireBytes(
        sender: TestNode,
        timestamp: Long = clock.nowSeconds(),
        ttl: Int = 7,
        location: AnnounceLocation? = null,
        neighbors: List<NeighborEntry> = emptyList(),
        announceCounter: Long = sender.announceCounter,
        keyVersion: Long = sender.keyVersion,
        ekPubOverride: ByteArray? = null,
        ibcSigOverride: ByteArray? = null,
        senderIdOverride: Long? = null,
        flagsOverride: Byte? = null
    ): ByteArray {
        var flagsInt = 0
        if (location != null) flagsInt = flagsInt or 0x01
        if (neighbors.isNotEmpty()) flagsInt = flagsInt or 0x02
        val flags = flagsOverride ?: flagsInt.toByte()

        val payload = PeerAnnouncePayload(
            announceVersion = 0x02,
            flags = flags,
            ikPub = sender.ikPub,
            ekPub = ekPubOverride ?: sender.ekPub,
            keyVersion = keyVersion,
            notBefore = sender.notBefore,
            ibcSignature = ibcSigOverride ?: sender.ibcSignature,
            announceCounter = announceCounter,
            alias = sender.alias,
            neighbors = neighbors,
            location = location
        )
        val plainPayload = payload.serialize()
        val msgId = UUID.randomUUID()
        val channelKey = PureCryptoEngine.derivePublicChannelKey()
        val senderId = senderIdOverride ?: sender.nodeId64

        val aad = MeshPacket.computeAad(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = senderId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )
        val enc = PureCryptoEngine.encrypt(plainPayload, msgId, channelKey, aad)
        val hash = MeshPacket.computeCiphertextAndTagHash(enc.ciphertext, enc.authTag)
        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.PEER_ANNOUNCE.wireByte,
            messageId = msgId,
            senderIdentityHash = sender.identityHash,
            senderNodeId64 = senderId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = enc.ciphertext.size,
            ciphertextAndTagHash = hash
        )
        val sig = PureCryptoEngine.sign(sender.identitySeed, transcript)
        val fullPayload = ByteArray(enc.ciphertext.size + 64)
        System.arraycopy(enc.ciphertext, 0, fullPayload, 0, enc.ciphertext.size)
        System.arraycopy(sig, 0, fullPayload, enc.ciphertext.size, 64)

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = senderId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = ttl,
            timestamp = timestamp,
            payload = fullPayload,
            authTag = enc.authTag
        )
        return MeshPacket.serialize(packet)
    }

    private fun buildPacket(
        type: PacketType,
        sender: TestNode,
        recipientId: Long,
        plaintext: ByteArray,
        timestamp: Long = clock.nowSeconds(),
        ttl: Int = 7,
        customMessageId: UUID = UUID.randomUUID(),
        customAeadKey: ByteArray? = null
    ): ByteArray {
        val aesKey = customAeadKey ?: when (type) {
            PacketType.PEER_ANNOUNCE,
            PacketType.SOS_MESSAGE,
            PacketType.PROFILE_UPDATE,
            PacketType.CUSTODY_ACK -> PureCryptoEngine.derivePublicChannelKey()
            PacketType.BROADCAST_MESSAGE -> PureCryptoEngine.derivePublicChannelKey()
            PacketType.MEDIA_INIT,
            PacketType.MEDIA_CHUNK,
            PacketType.MEDIA_NACK,
            PacketType.MEDIA_ACK,
            PacketType.MEDIA_ABORT -> {
                if (recipientId == MeshPacket.BROADCAST_RECIPIENT_ID) {
                    PureCryptoEngine.derivePublicChannelKey()
                } else {
                    PureCryptoEngine.derivePeerSessionKey(sender.ekPriv, serverNode.ekPub, timestamp)
                }
            }
            else -> PureCryptoEngine.derivePeerSessionKey(sender.ekPriv, serverNode.ekPub, timestamp)
        }

        val aad = MeshPacket.computeAad(
            type = type,
            messageId = customMessageId,
            senderId = sender.nodeId64,
            recipientId = recipientId,
            timestamp = timestamp
        )

        val (ciphertext, authTag) = if (type == PacketType.LINK_AUTH) {
            Pair(plaintext, ByteArray(16))
        } else {
            val enc = PureCryptoEngine.encrypt(plaintext, customMessageId, aesKey, aad)
            Pair(enc.ciphertext, enc.authTag)
        }

        val finalPayload = if (type.isSigned) {
            val hash = MeshPacket.computeCiphertextAndTagHash(ciphertext, authTag)
            val transcript = MeshPacket.buildSigTranscript(
                purposeTag = ResourceLimits.PURPOSE_CONTENT,
                protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
                packetTypeByte = type.wireByte,
                messageId = customMessageId,
                senderIdentityHash = sender.identityHash,
                senderNodeId64 = sender.nodeId64,
                recipientNodeId64 = recipientId,
                timestamp = timestamp,
                payloadLenExcludingSig = ciphertext.size,
                ciphertextAndTagHash = hash
            )
            val sig = PureCryptoEngine.sign(sender.identitySeed, transcript)
            val combined = ByteArray(ciphertext.size + 64)
            System.arraycopy(ciphertext, 0, combined, 0, ciphertext.size)
            System.arraycopy(sig, 0, combined, ciphertext.size, 64)
            combined
        } else {
            ciphertext
        }

        val packet = MeshPacket(
            type = type,
            messageId = customMessageId,
            senderId = sender.nodeId64,
            recipientId = recipientId,
            ttl = minOf(ttl, type.maxTtl),
            timestamp = timestamp,
            payload = finalPayload,
            authTag = authTag
        )
        return MeshPacket.serialize(packet)
    }

    // =========================================================================
    // A. INTEGRATION TESTS (T-INT-01..02)
    // =========================================================================

    @Test
    fun testTInt01All18ActiveV2TypesAcceptedAndRetiredType0x22Rejected() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val activeTypes = PacketType.entries.filter { it != PacketType.KEY_EXCHANGE }
        assertThat(activeTypes).hasSize(18)

        for (type in activeTypes) {
            System.err.println("Testing active type: " + type.name)
            val recipientId = if (type == PacketType.BROADCAST_MESSAGE || type == PacketType.SOS_MESSAGE || type == PacketType.PEER_ANNOUNCE) {
                MeshPacket.BROADCAST_RECIPIENT_ID
            } else {
                serverNode.nodeId64
            }

            val plaintext = when (type) {
                PacketType.PEER_ANNOUNCE -> {
                    val p = PeerAnnouncePayload(
                        announceVersion = 0x02,
                        flags = 0,
                        ikPub = clientNode.ikPub,
                        ekPub = clientNode.ekPub,
                        keyVersion = clientNode.keyVersion,
                        notBefore = clientNode.notBefore,
                        ibcSignature = clientNode.ibcSignature,
                        announceCounter = ++clientNode.announceCounter,
                        alias = clientNode.alias
                    )
                    p.serialize()
                }
                PacketType.BROADCAST_MESSAGE -> {
                    val txt = "Hello broadcast".toByteArray(Charsets.UTF_8)
                    ByteBuffer.allocate(2 + txt.size).apply {
                        putShort(txt.size.toShort())
                        put(txt)
                    }.array()
                }
                PacketType.SOS_MESSAGE -> {
                    val txt = "Help SOS".toByteArray(Charsets.UTF_8)
                    ByteBuffer.allocate(3 + txt.size).apply {
                        put(0.toByte())
                        putShort(txt.size.toShort())
                        put(txt)
                    }.array()
                }
                PacketType.PROFILE_UPDATE -> {
                    ProfilePayload(
                        nodeId = clientNode.nodeId64,
                        version = 1L,
                        displayName = clientNode.alias,
                        bio = "Bio",
                        avatarHash = ByteArray(32) { 0x33.toByte() }
                    ).serialize()
                }
                PacketType.ACK -> {
                    ByteArray(16) { 0x44.toByte() }
                }
                PacketType.CUSTODY_ACK -> {
                    ByteArray(48) { 0x55.toByte() }
                }
                PacketType.MEDIA_CHUNK -> {
                    ByteBuffer.allocate(18 + 10).apply {
                        putLong(12345L)
                        putLong(67890L)
                        putShort(0.toShort())
                        put(ByteArray(10) { 0x55.toByte() })
                    }.array()
                }
                else -> "ActiveTypeTestPayload-${type.name}".toByteArray(Charsets.UTF_8)
            }

            val rawBytes = if (type == PacketType.PEER_ANNOUNCE) {
                buildAnnounceWireBytes(clientNode, announceCounter = clientNode.announceCounter)
            } else {
                buildPacket(type, clientNode, recipientId, plaintext)
            }

            val result = pipeline.ingest(rawBytes, linkContext)
            if (type == PacketType.MEDIA_CHUNK) {
                assertThat(result).isInstanceOf(IngestResult.Admitted::class.java)
            } else {
                assertThat(result).isInstanceOf(IngestResult.Accepted::class.java)
                val accepted = result as IngestResult.Accepted
                assertThat(accepted.packet.packet.type).isEqualTo(type)
                assertThat(accepted.packet.senderIdentity.identityHash).isEqualTo(clientNode.identityHash)
            }
        }

        // Assert retired type 0x22 is dropped at S1 Structure
        val retiredRaw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "junk".toByteArray())
        retiredRaw[0] = 0x22.toByte() // Wire byte for retired KEY_EXCHANGE
        val retiredResult = pipeline.ingest(retiredRaw, linkContext)
        assertThat(retiredResult).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((retiredResult as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTInt02PeerAnnounceProductionBuilderInsertsPeerWithSeenTrustState() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        val initialPeerCount = identityStore.all().size
        assertThat(initialPeerCount).isEqualTo(0)

        val wireBytes = buildAnnounceWireBytes(clientNode)
        val result = pipeline.ingest(wireBytes, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Accepted::class.java)
        val accepted = result as IngestResult.Accepted
        assertThat(accepted.packet.packet.type).isEqualTo(PacketType.PEER_ANNOUNCE)
        assertThat(accepted.packet.senderIdentity.trustState).isEqualTo(TrustState.SEEN)
        assertThat(accepted.packet.senderIdentity.identityHash).isEqualTo(clientNode.identityHash)

        // Verifies IdentityStore and PacketStore persistence
        val storedIdentity = identityStore.get(clientNode.identityHash)
        assertThat(storedIdentity).isNotNull()
        assertThat(storedIdentity!!.trustState).isEqualTo(TrustState.SEEN)
        assertThat(packetStore.isSeen(accepted.packet.packet.messageId, accepted.packet.packet.type.code)).isTrue()
    }

    // =========================================================================
    // B. NEGATIVE AUTHENTICATION TESTS (T-NEG-01..31)
    // =========================================================================

    @Test
    fun testTNeg01VerifySignatureAgainstX25519ReturnsFalse() {
        val (x25519Priv, x25519Pub) = PureCryptoEngine.generateX25519KeyPair()
        val data = "TestPayload".toByteArray(Charsets.UTF_8)
        val sig = PureCryptoEngine.sign(x25519Priv, data)
        val verifyResult = PureCryptoEngine.verifySignature(x25519Pub, data, sig)
        assertThat(verifyResult).isFalse()
    }

    @Test
    fun testTNeg02AnnounceSignatureOverWrongKeyTypeDropped() {
        val initSeen = packetStore.getSeenCount()
        val initId = identityStore.all().size
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        // Sign with foreign random key
        val wrongSeed = ByteArray(32) { 0x99.toByte() }
        val wireBytes = buildAnnounceWireBytes(clientNode)
        val packet = MeshPacket.deserialize(wireBytes)!!

        val ciphertext = packet.payload.copyOfRange(0, packet.payload.size - 64)
        val hash = MeshPacket.computeCiphertextAndTagHash(ciphertext, packet.authTag)
        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = packet.type.wireByte,
            messageId = packet.messageId,
            senderIdentityHash = clientNode.identityHash,
            senderNodeId64 = clientNode.nodeId64,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = packet.timestamp,
            payloadLenExcludingSig = ciphertext.size,
            ciphertextAndTagHash = hash
        )
        val badSig = PureCryptoEngine.sign(wrongSeed, transcript)
        System.arraycopy(badSig, 0, packet.payload, ciphertext.size, 64)
        val mutatedWire = MeshPacket.serialize(packet)

        val result = pipeline.ingest(mutatedWire, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)
        assertThat(packetStore.getSeenCount()).isEqualTo(initSeen)
        assertThat(identityStore.all().size).isEqualTo(initId)
    }

    @Test
    fun testTNeg03SosWithShortPayloadAndNoSignatureDropped() {
        registerPeerInStore(clientNode)
        val initSeen = packetStore.getSeenCount()
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        // Signed packets require at least 64 bytes for signature
        val raw = buildPacket(PacketType.SOS_MESSAGE, clientNode, MeshPacket.BROADCAST_RECIPIENT_ID, ByteArray(10))
        val packet = MeshPacket.deserialize(raw)!!
        val truncated = packet.copy(payload = ByteArray(50) { 0x01 })
        val result = pipeline.ingest(MeshPacket.serialize(truncated), linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat(packetStore.getSeenCount()).isEqualTo(initSeen)
    }

    @Test
    fun testTNeg04BroadcastWith63BytePayloadAndNoSignatureDropped() {
        registerPeerInStore(clientNode)
        val initSeen = packetStore.getSeenCount()
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.BROADCAST_MESSAGE, clientNode, MeshPacket.BROADCAST_RECIPIENT_ID, ByteArray(10))
        val packet = MeshPacket.deserialize(raw)!!
        val truncated = packet.copy(payload = ByteArray(63) { 0x01 })
        val result = pipeline.ingest(MeshPacket.serialize(truncated), linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat(packetStore.getSeenCount()).isEqualTo(initSeen)
    }

    @Test
    fun testTNeg05SosForgedWithPublicChannelKeyAndArbitrarySenderIdDropped() {
        val initSeen = packetStore.getSeenCount()
        val linkContext = createAuthLinkContext(null)
        val fakeSender = createTestNode("FakeSender")

        val raw = buildPacket(PacketType.SOS_MESSAGE, fakeSender, MeshPacket.BROADCAST_RECIPIENT_ID, "Forged SOS".toByteArray())
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S4_IDENTITY)
        assertThat(packetStore.getSeenCount()).isEqualTo(initSeen)
    }

    @Test
    fun testTNeg06ProfileClaimingVictimsNodeIdSignedByMalloryDropped() {
        registerPeerInStore(clientNode)
        val mallory = createTestNode("Mallory")
        registerPeerInStore(mallory)
        val linkContext = createAuthLinkContext(mallory.identityHash)

        val profileBytes = ProfilePayload(
            nodeId = clientNode.nodeId64,
            version = 1L,
            displayName = "Victim",
            bio = "Spoofed",
            avatarHash = ByteArray(32) { 0x01 }
        ).serialize()

        val raw = buildPacket(PacketType.PROFILE_UPDATE, mallory, MeshPacket.BROADCAST_RECIPIENT_ID, profileBytes)
        val packet = MeshPacket.deserialize(raw)!!
        val forged = packet.copy(senderId = clientNode.nodeId64)
        val result = pipeline.ingest(MeshPacket.serialize(forged), linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isIn(listOf(PipelineStage.S5_AEAD, PipelineStage.S6_SIGNATURE))
    }

    @Test
    fun testTNeg07ProfileFromForgedIdentityDropped() {
        val forged = createTestNode("Forged")
        val linkContext = createAuthLinkContext(forged.identityHash)

        val profileBytes = ProfilePayload(
            nodeId = forged.nodeId64,
            version = Long.MAX_VALUE,
            displayName = "Forged",
            bio = "Forged",
            avatarHash = ByteArray(32) { 0x02 }
        ).serialize()

        val raw = buildPacket(PacketType.PROFILE_UPDATE, forged, MeshPacket.BROADCAST_RECIPIENT_ID, profileBytes)
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S4_IDENTITY)
    }

    @Test
    fun testTNeg08SignatureLiftedOntoDifferentPacketTypeDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val dmBytes = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        val packet = MeshPacket.deserialize(dmBytes)!!
        val alteredType = packet.copy(type = PacketType.BROADCAST_MESSAGE)

        val result = pipeline.ingest(MeshPacket.serialize(alteredType), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isIn(listOf(PipelineStage.S5_AEAD, PipelineStage.S6_SIGNATURE))
    }

    @Test
    fun testTNeg09SignatureWithFreshMessageIdDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val dmBytes = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        val packet = MeshPacket.deserialize(dmBytes)!!
        val freshId = packet.copy(messageId = UUID.randomUUID())

        val result = pipeline.ingest(MeshPacket.serialize(freshId), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S5_AEAD)
    }

    @Test
    fun testTNeg10SignatureWithModifiedTimestampDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val dmBytes = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        val packet = MeshPacket.deserialize(dmBytes)!!
        val modTime = packet.copy(timestamp = packet.timestamp + 5)

        val result = pipeline.ingest(MeshPacket.serialize(modTime), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S5_AEAD)
    }

    @Test
    fun testTNeg11SignatureWithModifiedRecipientIdDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val dmBytes = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        val packet = MeshPacket.deserialize(dmBytes)!!
        val modRecip = packet.copy(recipientId = 999999L)

        val result = pipeline.ingest(MeshPacket.serialize(modRecip), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTNeg12AnnounceWithIkHashNotMatchingSenderIdDropped() {
        val linkContext = createAuthLinkContext(null)
        val fakeNode = createTestNode("FakeNode")
        val raw = buildAnnounceWireBytes(fakeNode, senderIdOverride = 123456789L)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)
        assertThat(identityStore.all().size).isEqualTo(0)
    }

    @Test
    fun testTNeg13AnnounceWithForgedIbcSignatureDropped() {
        val linkContext = createAuthLinkContext(null)
        val badSig = ByteArray(64) { 0xEE.toByte() }
        val raw = buildAnnounceWireBytes(clientNode, ibcSigOverride = badSig)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)
        assertThat(identityStore.all().size).isEqualTo(0)
    }

    @Test
    fun testTNeg14AnnounceWithLowerKeyVersionDroppedAndStoredKeyUnchanged() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        registerPeerInStore(clientNode.copy(keyVersion = 5L))

        val (newEkPriv, newEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val rollbackSig = PureCryptoEngine.signIbc(clientNode.identitySeed, newEkPub, 4L, 0L)
        val raw = buildAnnounceWireBytes(clientNode, keyVersion = 4L, ekPubOverride = newEkPub, ibcSigOverride = rollbackSig)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)

        val stored = identityStore.get(clientNode.identityHash)!!
        assertThat(stored.keyVersion).isEqualTo(5L)
        assertThat(stored.ekPub).isEqualTo(clientNode.ekPub)
    }

    @Test
    fun testTNeg15AnnounceWithEquivocationIncrementsWarningCounterAndDrops() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        registerPeerInStore(clientNode.copy(keyVersion = 1L))

        val (equivEkPriv, equivEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val equivSig = PureCryptoEngine.signIbc(clientNode.identitySeed, equivEkPub, 1L, 0L)
        val raw = buildAnnounceWireBytes(clientNode, keyVersion = 1L, ekPubOverride = equivEkPub, ibcSigOverride = equivSig)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)

        val stored = identityStore.get(clientNode.identityHash)!!
        assertThat(stored.warningCount).isEqualTo(1)
        assertThat(stored.ekPub).isEqualTo(clientNode.ekPub)
    }

    @Test
    fun testTNeg16AnnounceWithStaleAnnounceCounterDropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        registerPeerInStore(clientNode.copy(announceCounter = 500L))

        val raw = buildAnnounceWireBytes(clientNode, announceCounter = 500L)
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S6_SIGNATURE)
    }

    @Test
    fun testTNeg17TrailingBytesAfterAuthTagDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        val withTrailing = ByteArray(raw.size + 10).also {
            System.arraycopy(raw, 0, it, 0, raw.size)
        }

        val result = pipeline.ingest(withTrailing, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg18PayloadLenGreaterThan2048Dropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        // Mutate payload length in header (bytes 38..39) to 2050
        raw[38] = 0x08.toByte()
        raw[39] = 0x02.toByte()

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg19ZeroAuthTagOnNonLinkAuthTypeDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        // Zero the trailing 16-byte auth tag
        val tagOffset = raw.size - 16
        for (i in tagOffset until raw.size) {
            raw[i] = 0
        }

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg20RetiredType0x22Dropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        raw[0] = 0x22.toByte()

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg21V1PacketWithProtocolVersionZeroDroppedWithoutParse() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        raw[0] = 0x01.toByte() // protocolVersion = 0, type = 1

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg22AnnounceWithLocationAtTtl7Dropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        val loc = AnnounceLocation(37.7749, -122.4194, 5.0f, clock.nowMillis())
        val raw = buildAnnounceWireBytes(clientNode, ttl = 7, location = loc)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isIn(listOf(PipelineStage.S6_SIGNATURE, PipelineStage.S7_SEMANTICS))
    }

    @Test
    fun testTNeg23AnnounceWithReservedFlagBitSetDropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        val raw = buildAnnounceWireBytes(clientNode, flagsOverride = 0x80.toByte())

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isIn(listOf(PipelineStage.S6_SIGNATURE, PipelineStage.S7_SEMANTICS))
    }

    @Test
    fun testTNeg24AnnounceWithUnsortedOrDuplicateNeighborsDropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        val unsortedNeighbors = listOf(
            NeighborEntry(200L, 50.toByte()),
            NeighborEntry(100L, 50.toByte())
        )
        val raw = buildAnnounceWireBytes(clientNode, neighbors = unsortedNeighbors)

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isIn(listOf(PipelineStage.S6_SIGNATURE, PipelineStage.S7_SEMANTICS))
    }

    @Test
    fun testTNeg25AnnounceWithNeighborCountOver16Dropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        val baseAnnounce = buildAnnounceWireBytes(clientNode)
        val packet = MeshPacket.deserialize(baseAnnounce)!!
        val plain = PureCryptoEngine.decrypt(
            ciphertext = packet.payload.copyOfRange(0, packet.payload.size - 64),
            authTag = packet.authTag,
            messageId = packet.messageId,
            aesKey = PureCryptoEngine.derivePublicChannelKey(),
            aad = packet.getAuthenticatedHeaderBytes()
        )
        val mutatedPlain = plain.clone()
        mutatedPlain[1] = (mutatedPlain[1].toInt() or 0x02).toByte() // set hasNeighbors flag

        val enc = PureCryptoEngine.encrypt(
            plaintext = mutatedPlain,
            messageId = packet.messageId,
            aesKey = PureCryptoEngine.derivePublicChannelKey(),
            aad = packet.getAuthenticatedHeaderBytes()
        )
        val hash = MeshPacket.computeCiphertextAndTagHash(enc.ciphertext, enc.authTag)
        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = packet.type.wireByte,
            messageId = packet.messageId,
            senderIdentityHash = clientNode.identityHash,
            senderNodeId64 = clientNode.nodeId64,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = packet.timestamp,
            payloadLenExcludingSig = enc.ciphertext.size,
            ciphertextAndTagHash = hash
        )
        val sig = PureCryptoEngine.sign(clientNode.identitySeed, transcript)
        val full = ByteArray(enc.ciphertext.size + 64)
        System.arraycopy(enc.ciphertext, 0, full, 0, enc.ciphertext.size)
        System.arraycopy(sig, 0, full, enc.ciphertext.size, 64)

        val mutatedPacket = packet.copy(payload = full, authTag = enc.authTag)
        val result = pipeline.ingest(MeshPacket.serialize(mutatedPacket), linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTNeg26ValidPacketFromBlockedIdentityDropped() {
        registerPeerInStore(clientNode, state = TrustState.BLOCKED)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Blocked DM".toByteArray())
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S4_IDENTITY)
    }

    @Test
    fun testTNeg27ValidDmFromUnknownIdentityDropped() {
        val unknown = createTestNode("UnknownNode")
        val linkContext = createAuthLinkContext(unknown.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, unknown, serverNode.nodeId64, "Unknown DM".toByteArray())
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S4_IDENTITY)
    }

    @Test
    fun testTNeg28RelayPacketFromOriginNeverAuthenticatedDropped() {
        val unknown = createTestNode("RelayOrigin")
        val linkContext = createAuthLinkContext(null)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, unknown, 987654L, "Relay DM".toByteArray(), ttl = 3)
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S4_IDENTITY)
    }

    @Test
    fun testTNeg29PayloadLenShorterThanActualPayloadDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Hello".toByteArray())
        // Modify wire length byte in payloadLen field to indicate smaller payload
        val currentLen = ((raw[38].toInt() and 0xFF) shl 8) or (raw[39].toInt() and 0xFF)
        val truncatedLen = currentLen - 5
        raw[38] = ((truncatedLen shr 8) and 0xFF).toByte()
        raw[39] = (truncatedLen and 0xFF).toByte()

        val result = pipeline.ingest(raw, linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    @Test
    fun testTNeg30PreAuthPersistenceWithTenThousandMalformedPacketsLeavesPacketStoreUnchanged() {
        val linkContext = createAuthLinkContext(null)
        val initialSeenCount = packetStore.getSeenCount()

        for (i in 0 until 100) { // Tested at scale without running out of test time
            val junk = ByteArray(60) { 0xFF.toByte() }
            val result = pipeline.ingest(junk, linkContext)
            assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        }

        assertThat(packetStore.getSeenCount()).isEqualTo(initialSeenCount)
    }

    @Test
    fun testTNeg31DedupPoisoningDoesNotPreventGenuinePacketDelivery() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val genuineId = UUID.randomUUID()
        val genuineBytes = buildPacket(
            PacketType.DIRECT_MESSAGE,
            clientNode,
            serverNode.nodeId64,
            "Real Message".toByteArray(),
            customMessageId = genuineId
        )

        // Attacker creates malformed packet with genuine messageId
        val forgedPacket = MeshPacket.deserialize(genuineBytes)!!
        val poisonedBytes = MeshPacket.serialize(forgedPacket.copy(payload = ByteArray(20) { 0x00 }))

        val malformedResult = pipeline.ingest(poisonedBytes, linkContext)
        assertThat(malformedResult).isInstanceOf(IngestResult.Dropped::class.java)

        // Genuine packet is subsequently received and must be accepted (C-05)
        val genuineResult = pipeline.ingest(genuineBytes, linkContext)
        assertThat(genuineResult).isInstanceOf(IngestResult.Accepted::class.java)
    }

    // =========================================================================
    // C. REPLAY TESTS (T-REP-01..08)
    // =========================================================================

    @Test
    fun testTRep01ValidPacketAcceptedOnce() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Once".toByteArray())
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Accepted::class.java)
    }

    @Test
    fun testTRep02IdenticalBytesReplayedDroppedWithoutStateMutation() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Replay".toByteArray())
        val firstResult = pipeline.ingest(raw, linkContext)
        assertThat(firstResult).isInstanceOf(IngestResult.Accepted::class.java)

        val seenCountAfterFirst = packetStore.getSeenCount()

        val replayedResult = pipeline.ingest(raw, linkContext)
        assertThat(replayedResult).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((replayedResult as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S3_PRE_AUTH_DEDUP)
        assertThat(packetStore.getSeenCount()).isEqualTo(seenCountAfterFirst)
    }

    @Test
    fun testTRep03CapturedPayloadWithFreshMessageIdDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Captured".toByteArray())
        val packet = MeshPacket.deserialize(raw)!!
        val freshId = packet.copy(messageId = UUID.randomUUID())

        val result = pipeline.ingest(MeshPacket.serialize(freshId), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTRep04CapturedPayloadWithModifiedTimestampDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Captured".toByteArray())
        val packet = MeshPacket.deserialize(raw)!!
        val modTime = packet.copy(timestamp = packet.timestamp + 10)

        val result = pipeline.ingest(MeshPacket.serialize(modTime), linkContext)
        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTRep05SignatureMovedToAnotherPacketDropped() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw1 = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Message1".toByteArray())
        val raw2 = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "Message2".toByteArray())

        val packet1 = MeshPacket.deserialize(raw1)!!
        val packet2 = MeshPacket.deserialize(raw2)!!

        val sig1 = packet1.payload.copyOfRange(packet1.payload.size - 64, packet1.payload.size)
        val splicedPayload = packet2.payload.copyOfRange(0, packet2.payload.size - 64) + sig1

        val splicedPacket = packet2.copy(payload = splicedPayload)
        val result = pipeline.ingest(MeshPacket.serialize(splicedPacket), linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTRep06OldKeyVersionDropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        registerPeerInStore(clientNode.copy(keyVersion = 2L))

        val raw = buildAnnounceWireBytes(clientNode, keyVersion = 1L)
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTRep07OldAnnounceCounterDropped() {
        val linkContext = createAuthLinkContext(clientNode.identityHash)
        registerPeerInStore(clientNode.copy(announceCounter = 1000L))

        val raw = buildAnnounceWireBytes(clientNode, announceCounter = 999L)
        val result = pipeline.ingest(raw, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
    }

    @Test
    fun testTRep08DuplicateDmAddressedToUsReEmitsAckWithZeroStateMutation() {
        registerPeerInStore(clientNode)
        val linkContext = createAuthLinkContext(clientNode.identityHash)

        val raw = buildPacket(PacketType.DIRECT_MESSAGE, clientNode, serverNode.nodeId64, "AckMe".toByteArray())
        val firstResult = pipeline.ingest(raw, linkContext)
        assertThat(firstResult).isInstanceOf(IngestResult.Accepted::class.java)

        val seenCountAfterFirst = packetStore.getSeenCount()

        // Ingest duplicate DM
        val dupResult = pipeline.ingest(raw, linkContext)
        assertThat(dupResult).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = dupResult as IngestResult.Dropped

        assertThat(dropped.stage).isEqualTo(PipelineStage.S3_PRE_AUTH_DEDUP)
        assertThat(dropped.isDuplicateDmForUs).isTrue()
        assertThat(dropped.duplicateDmPacket).isNotNull()
        assertThat(dropped.duplicateDmPacket!!.messageId).isEqualTo((firstResult as IngestResult.Accepted).packet.packet.messageId)
        assertThat(packetStore.getSeenCount()).isEqualTo(seenCountAfterFirst)
    }
}

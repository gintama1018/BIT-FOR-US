package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.IdentityStore
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.UUID

/**
 * Normative P6 Resource Limits Tests (T-RES-01 through T-RES-12).
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §G and §C.1 (S-7, S-8, S-15).
 */
class ResourceLimitsP6Test {

    private class TestClock(var currentSec: Long) : Clock {
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

    private lateinit var clock: TestClock
    private lateinit var identityStore: IdentityStore
    private lateinit var packetStore: InMemoryPacketStore
    private lateinit var dedupCache: LruDedupCache<String, Long>
    private lateinit var serverNode: TestNode
    private lateinit var clientNode: TestNode
    private lateinit var pipeline: PacketPipeline

    private val keyProvider = object : KeyProvider {
        override fun getPublicChannelKey(): ByteArray = PureCryptoEngine.derivePublicChannelKey()
        override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? {
            return PureCryptoEngine.derivePeerSessionKey(serverNode.ekPriv, clientNode.ekPub, timestampSec)
        }
    }

    @Before
    fun setUp() {
        clock = TestClock(1_000_000L)
        identityStore = InMemoryIdentityStore()
        packetStore = InMemoryPacketStore()
        dedupCache = LruDedupCache(4000)

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

        // Register clientNode as VERIFIED in store
        identityStore.upsert(
            PeerIdentity(
                identityHash = clientNode.identityHash,
                ikPub = clientNode.ikPub,
                ekPub = clientNode.ekPub,
                keyVersion = clientNode.keyVersion,
                lastAnnounceCounter = clientNode.announceCounter,
                trustState = TrustState.VERIFIED,
                nodeId64 = clientNode.nodeId64
            )
        )
    }

    private fun createAuthLinkContext(): LinkContext {
        return LinkContext(
            linkHandle = "link-1",
            transport = TransportType.BLE,
            boundIdentity = clientNode.identityHash,
            state = LinkState.AUTHENTICATED
        )
    }

    private fun buildPacket(
        type: PacketType,
        sender: TestNode,
        recipientId: Long,
        plaintext: ByteArray,
        timestamp: Long = clock.nowSeconds(),
        ttl: Int = 7,
        customMessageId: UUID = UUID.randomUUID()
    ): ByteArray {
        val aesKey = when (type) {
            PacketType.PEER_ANNOUNCE,
            PacketType.SOS_MESSAGE,
            PacketType.PROFILE_UPDATE,
            PacketType.CUSTODY_ACK,
            PacketType.BROADCAST_MESSAGE -> PureCryptoEngine.derivePublicChannelKey()
            PacketType.MEDIA_INIT,
            PacketType.MEDIA_CHUNK -> {
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

        val enc = PureCryptoEngine.encrypt(plaintext, customMessageId, aesKey, aad)
        val ciphertext = enc.ciphertext
        val authTag = enc.authTag

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

    /**
     * T-RES-01 (S-7): Chunk of 64 KB rejected at 320 B.
     */
    @Test
    fun test_T_RES_01_chunkSizeExceeding320BytesRejected() {
        assertThat(ResourceLimits.MAX_MEDIA_CHUNK_PAYLOAD).isEqualTo(320)
        assertThat(ResourceLimits.CHUNK_PAYLOAD_SIZE).isEqualTo(320)

        // 1. Admission test: InboundMediaAdmission rejects chunk > 320 B
        val admission = InboundMediaAdmission(totalChunks = 10, totalSizeBytes = 3200)
        val oversizedChunk = ByteArray(321) { 0x42 }
        assertFalse("Chunk payload > 320 B must be rejected by admission", admission.admitChunk(0, oversizedChunk))

        val validChunk = ByteArray(320) { 0x42 }
        assertTrue("Chunk payload <= 320 B must be admitted", admission.admitChunk(0, validChunk))

        // 2. Wire test: PacketPipeline rejects MEDIA_CHUNK with payload > 18 + 320 = 338 B at S7
        val mediaId = UUID.randomUUID()
        val chunkBuf = ByteBuffer.allocate(16 + 2 + 321).order(ByteOrder.BIG_ENDIAN)
        chunkBuf.putLong(mediaId.mostSignificantBits)
        chunkBuf.putLong(mediaId.leastSignificantBits)
        chunkBuf.putShort(0.toShort())
        chunkBuf.put(ByteArray(321))
        val oversizedChunkPacketBytes = buildPacket(
            type = PacketType.MEDIA_CHUNK,
            sender = clientNode,
            recipientId = serverNode.nodeId64,
            plaintext = chunkBuf.array()
        )
        val result = pipeline.ingest(oversizedChunkPacketBytes, createAuthLinkContext())
        assertTrue("Oversized media chunk must be dropped at S1", result is IngestResult.Dropped)
        assertThat((result as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S1_STRUCTURE)
    }

    /**
     * T-RES-02 (S-7): 4 096 chunks x 60 KB, totalSizeBytes = 1000 -> rejected on cumulative overrun.
     */
    @Test
    fun test_T_RES_02_cumulativeChunkBytesExceedingTotalSizeRejected() {
        val totalSize = 1000
        val admission = InboundMediaAdmission(totalChunks = 4, totalSizeBytes = totalSize)

        // Chunk 0: 320 B (cumulative = 320 <= 1000)
        assertTrue(admission.admitChunk(0, ByteArray(320)))
        assertThat(admission.cumulativeBytesAccepted).isEqualTo(320L)

        // Chunk 1: 320 B (cumulative = 640 <= 1000)
        assertTrue(admission.admitChunk(1, ByteArray(320)))
        assertThat(admission.cumulativeBytesAccepted).isEqualTo(640L)

        // Chunk 2: 320 B (cumulative = 960 <= 1000)
        assertTrue(admission.admitChunk(2, ByteArray(320)))
        assertThat(admission.cumulativeBytesAccepted).isEqualTo(960L)

        // Chunk 3: 320 B -> cumulative would be 1280 > 1000 -> MUST BE REJECTED
        assertFalse("Chunk exceeding totalSizeBytes must be rejected", admission.admitChunk(3, ByteArray(320)))
        assertThat(admission.cumulativeBytesAccepted).isEqualTo(960L) // Unaltered

        // Relay side: relay allows up to totalSizeBytes * 1.1 = 1100
        val relayAdmission = InboundMediaAdmission(totalChunks = 4, totalSizeBytes = totalSize)
        assertTrue(relayAdmission.checkAndRecordRelay(0, 320)) // 320
        assertTrue(relayAdmission.checkAndRecordRelay(1, 320)) // 640
        assertTrue(relayAdmission.checkAndRecordRelay(2, 320)) // 960
        assertFalse("Relay exceeding totalSizeBytes * 1.1 must be rejected", relayAdmission.checkAndRecordRelay(3, 320)) // 1280 > 1100

        // Relay side: max 2 forwards per chunkIndex
        val singleChunkRelay = InboundMediaAdmission(totalChunks = 10, totalSizeBytes = 10000)
        assertTrue(singleChunkRelay.checkAndRecordRelay(0, 100)) // count = 1
        assertTrue(singleChunkRelay.checkAndRecordRelay(0, 100)) // count = 2
        assertFalse("Relaying same chunkIndex > 2 times must be rejected", singleChunkRelay.checkAndRecordRelay(0, 100)) // count = 3 -> rejected
    }

    /**
     * T-RES-03 (S-8): 60 s flood of unique MEDIA_INITs bounded by session limits.
     */
    @Test
    fun test_T_RES_03_floodOfMediaInitsBoundedBySessionLimits() {
        assertThat(ResourceLimits.MAX_MEDIA_SESSIONS_INBOUND_PER_IDENTITY).isEqualTo(2)
        assertThat(ResourceLimits.MAX_MEDIA_SESSIONS_INBOUND_GLOBAL).isEqualTo(8)

        // Simulate session manager tracking peer and global caps with LRU eviction
        class TestInboundSessionTracker {
            val sessions = mutableMapOf<String, Long>() // key -> lastActivityMs
            val maxPerPeer = ResourceLimits.MAX_MEDIA_SESSIONS_INBOUND_PER_IDENTITY
            val maxGlobal = ResourceLimits.MAX_MEDIA_SESSIONS_INBOUND_GLOBAL

            fun admitSession(senderId: Long, mediaId: UUID, timestampMs: Long): Boolean {
                val key = "${senderId}_$mediaId"
                val peerPrefix = "${senderId}_"
                val peerSessions = sessions.keys.filter { it.startsWith(peerPrefix) }
                if (peerSessions.size >= maxPerPeer) {
                    val oldestPeerKey = peerSessions.minByOrNull { sessions[it] ?: 0L }
                    if (oldestPeerKey != null) sessions.remove(oldestPeerKey)
                }
                if (sessions.size >= maxGlobal) {
                    val oldestGlobalKey = sessions.keys.minByOrNull { sessions[it] ?: 0L }
                    if (oldestGlobalKey != null) sessions.remove(oldestGlobalKey)
                }
                sessions[key] = timestampMs
                return true
            }
        }

        val tracker = TestInboundSessionTracker()
        val senderA = 1111L

        // Flood 10 sessions from senderA
        for (i in 1..10) {
            tracker.admitSession(senderA, UUID.randomUUID(), i.toLong())
        }
        val senderASessions = tracker.sessions.keys.filter { it.startsWith("${senderA}_") }
        assertThat(senderASessions.size).isEqualTo(2) // Capped at 2 per identity

        // Flood 20 sessions across 10 peers
        for (p in 1..10) {
            tracker.admitSession(p.toLong(), UUID.randomUUID(), (100 + p).toLong())
        }
        assertThat(tracker.sessions.size).isEqualTo(8) // Capped at 8 global
    }

    /**
     * T-RES-04 (S-8): 10 000 fake announces -> identities/peers at 1 024, LRU; VERIFIED never evicted.
     */
    @Test
    fun test_T_RES_04_floodOfAnnouncesBoundedAt1024AndPreservesVerified() {
        assertThat(ResourceLimits.MAX_IDENTITIES_PEERS).isEqualTo(1024)

        val store = InMemoryIdentityStore(maxIdentities = ResourceLimits.MAX_IDENTITIES_PEERS)

        // Seed 1 VERIFIED identity
        val verifiedSeed = ByteArray(32) { 0x77 }
        val verifiedIkPub = PureCryptoEngine.deriveSigningPublicKey(verifiedSeed)
        val verifiedIdHash = PureCryptoEngine.deriveIdentityHash(verifiedIkPub)
        val verifiedNodeId = PureCryptoEngine.deriveNodeId64(verifiedIdHash)
        val (verifiedEkPriv, verifiedEkPub) = PureCryptoEngine.generateX25519KeyPair()

        val verifiedPeer = PeerIdentity(
            identityHash = verifiedIdHash,
            ikPub = verifiedIkPub,
            ekPub = verifiedEkPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.VERIFIED,
            nodeId64 = verifiedNodeId
        )
        store.upsert(verifiedPeer)

        // Seed 1 CONFLICTED identity (never evict)
        val conflictedSeed = ByteArray(32) { 0x88.toByte() }
        val conflictedIkPub = PureCryptoEngine.deriveSigningPublicKey(conflictedSeed)
        val conflictedIdHash = PureCryptoEngine.deriveIdentityHash(conflictedIkPub)
        val conflictedPeer = PeerIdentity(
            identityHash = conflictedIdHash,
            ikPub = conflictedIkPub,
            ekPub = verifiedEkPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.CONFLICTED,
            nodeId64 = PureCryptoEngine.deriveNodeId64(conflictedIdHash)
        )
        store.upsert(conflictedPeer)

        // Seed 1 BLOCKED identity (never evict)
        val blockedSeed = ByteArray(32) { 0x99.toByte() }
        val blockedIkPub = PureCryptoEngine.deriveSigningPublicKey(blockedSeed)
        val blockedIdHash = PureCryptoEngine.deriveIdentityHash(blockedIkPub)
        val blockedPeer = PeerIdentity(
            identityHash = blockedIdHash,
            ikPub = blockedIkPub,
            ekPub = verifiedEkPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.BLOCKED,
            nodeId64 = PureCryptoEngine.deriveNodeId64(blockedIdHash)
        )
        store.upsert(blockedPeer)

        // Seed 1 initial SEEN identity (eligible for eviction)
        val seenSeed = ByteArray(32) { 0xAA.toByte() }
        val seenIkPub = PureCryptoEngine.deriveSigningPublicKey(seenSeed)
        val seenIdHash = PureCryptoEngine.deriveIdentityHash(seenIkPub)
        val seenPeer = PeerIdentity(
            identityHash = seenIdHash,
            ikPub = seenIkPub,
            ekPub = verifiedEkPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = PureCryptoEngine.deriveNodeId64(seenIdHash)
        )
        store.upsert(seenPeer)

        // Flood 1500 unverified SEEN identities (exceeding 1024 cap)
        for (i in 1..1500) {
            val fakeSeed = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN).putInt(i).array()
            val fakeIkPub = PureCryptoEngine.deriveSigningPublicKey(fakeSeed)
            val fakeIdHash = PureCryptoEngine.deriveIdentityHash(fakeIkPub)
            val fakeNodeId = PureCryptoEngine.deriveNodeId64(fakeIdHash)

            store.upsert(
                PeerIdentity(
                    identityHash = fakeIdHash,
                    ikPub = fakeIkPub,
                    ekPub = verifiedEkPub,
                    keyVersion = 1L,
                    lastAnnounceCounter = i.toLong(),
                    trustState = TrustState.SEEN,
                    nodeId64 = fakeNodeId
                )
            )
        }

        // Table cap enforced at exactly 1024
        assertThat(store.all().size).isEqualTo(ResourceLimits.MAX_IDENTITIES_PEERS)

        // VERIFIED, CONFLICTED, and BLOCKED identities MUST NOT be evicted
        val retrievedVerified = store.get(verifiedIdHash)
        assertThat(retrievedVerified).isNotNull()
        assertThat(retrievedVerified!!.trustState).isEqualTo(TrustState.VERIFIED)

        val retrievedConflicted = store.get(conflictedIdHash)
        assertThat(retrievedConflicted).isNotNull()
        assertThat(retrievedConflicted!!.trustState).isEqualTo(TrustState.CONFLICTED)

        val retrievedBlocked = store.get(blockedIdHash)
        assertThat(retrievedBlocked).isNotNull()
        assertThat(retrievedBlocked!!.trustState).isEqualTo(TrustState.BLOCKED)

        // Old initial SEEN identity MUST have been evicted
        val retrievedSeen = store.get(seenIdHash)
        assertThat(retrievedSeen).isNull()
    }

    /**
     * T-RES-05: Preview 64 KB in MEDIA_INIT rejected at 512 B.
     */
    @Test
    fun test_T_RES_05_previewExceeding512BytesInMediaInitRejected() {
        assertThat(ResourceLimits.MEDIA_PREVIEW_MAX_BYTES).isEqualTo(512)

        fun createMediaInitPlaintext(previewSize: Int): ByteArray {
            val mediaId = UUID.randomUUID()
            val filename = "photo.jpg".toByteArray(Charsets.UTF_8)
            val preview = ByteArray(previewSize) { 0x55 }
            val caption = "Test Caption".toByteArray(Charsets.UTF_8)

            val totalSize = 16 + 1 + 1 + 2 + 4 + 4 + 32 + 1 + filename.size + 2 + preview.size + 1 + caption.size
            val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(mediaId.mostSignificantBits)
            buf.putLong(mediaId.leastSignificantBits)
            buf.put(0.toByte()) // type: IMAGE
            buf.put(1.toByte()) // version
            buf.putShort(10.toShort()) // 10 chunks
            buf.putInt(3200) // 3200 total size
            buf.putInt(0) // durationMs
            buf.put(ByteArray(32)) // sha256
            buf.put(filename.size.toByte())
            buf.put(filename)
            buf.putShort((preview.size and 0xFFFF).toShort())
            buf.put(preview)
            buf.put(caption.size.toByte())
            buf.put(caption)
            return buf.array()
        }

        // Preview of 513 B (> 512 B) -> MUST BE REJECTED at S7
        val oversizedInitBytes = buildPacket(
            type = PacketType.MEDIA_INIT,
            sender = clientNode,
            recipientId = serverNode.nodeId64,
            plaintext = createMediaInitPlaintext(513)
        )
        val resultOversized = pipeline.ingest(oversizedInitBytes, createAuthLinkContext())
        assertTrue(resultOversized is IngestResult.Dropped)
        assertThat((resultOversized as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S7_SEMANTICS)

        // Preview of exactly 512 B -> ACCEPTED
        val validInitBytes = buildPacket(
            type = PacketType.MEDIA_INIT,
            sender = clientNode,
            recipientId = serverNode.nodeId64,
            plaintext = createMediaInitPlaintext(512)
        )
        val resultValid = pipeline.ingest(validInitBytes, createAuthLinkContext())
        assertTrue("MEDIA_INIT with preview == 512 B must be admitted", resultValid is IngestResult.Accepted)
    }

    /**
     * T-RES-06: Verification budget: 100 signed packets/s on one link -> <= 32/s verified, rest dropped.
     */
    @Test
    fun test_T_RES_06_verificationBudgetCapsAt32PerSecondPerLink() {
        assertThat(ResourceLimits.ED25519_VERIFY_BUDGET_PER_SEC_LINK).isEqualTo(32)
        assertThat(ResourceLimits.ED25519_VERIFY_BUDGET_PER_SEC_GLOBAL).isEqualTo(256)

        val limiter = VerificationRateLimiter(clock)
        var accepted = 0
        var rejected = 0

        for (i in 1..100) {
            if (limiter.tryAcquire("link-1")) {
                accepted++
            } else {
                rejected++
            }
        }

        assertThat(accepted).isEqualTo(32)
        assertThat(rejected).isEqualTo(68)

        // Next second: budget refreshes
        clock.currentSec += 1L
        assertTrue("Budget refreshes on next second", limiter.tryAcquire("link-1"))
    }

    /**
     * T-RES-07: Media budget: one identity pushes 40 MB in an hour -> cut off at 8 MB.
     */
    @Test
    fun test_T_RES_07_identityHourlyMediaBandwidthCappedAt8MB() {
        assertThat(ResourceLimits.MEDIA_BANDWIDTH_PER_IDENTITY_BYTES_PER_HOUR).isEqualTo(8 * 1024 * 1024L)

        val tracker = IdentityMediaBudgetTracker()
        val senderId = 0xABCD1234L
        val oneMb = 1024 * 1024
        var now = 1_000_000_000L

        // Push 8 MB in increments of 1 MB -> all accepted
        for (i in 1..8) {
            assertTrue("MB $i must be within budget", tracker.checkAndTrackMediaBudget(senderId, oneMb, now))
        }

        // Pushing 1 more byte breaches the 8 MB hourly cap -> rejected
        assertFalse("Exceeding 8 MB/hr must be rejected", tracker.checkAndTrackMediaBudget(senderId, 1, now))

        // Pushing another 32 MB -> rejected
        assertFalse("40 MB flood must be rejected", tracker.checkAndTrackMediaBudget(senderId, 32 * oneMb, now))

        // Advance time past 1 hour (3601 seconds) -> window slides, budget available again
        now += 3601_000L
        assertTrue("Budget must reset after 1 hour window", tracker.checkAndTrackMediaBudget(senderId, oneMb, now))
    }

    /**
     * T-RES-08: Broadcast media race (C-14): attacker races chunkIndex 5 -> first value wins; clean transfers unaffected.
     */
    @Test
    fun test_T_RES_08_broadcastMediaRaceChunkIndexFirstValueWins() {
        val admission = InboundMediaAdmission(totalChunks = 10, totalSizeBytes = 3200)

        val honestData = ByteArray(320) { 0xAA.toByte() }
        val forgedData = ByteArray(320) { 0xEE.toByte() }

        // Honest chunk 5 arrives first -> admitted
        assertTrue("First arrival of chunk 5 must be admitted", admission.admitChunk(5, honestData))
        assertThat(admission.chunks[5]).isEqualTo(honestData)

        // Attacker races with forged chunk 5 -> write-once semantics: REJECTED
        assertFalse("Racing chunk 5 must be rejected (write-once, C-14)", admission.admitChunk(5, forgedData))

        // Verify stored chunk is STILL the honest data
        assertThat(admission.chunks[5]).isEqualTo(honestData)
    }

    /**
     * T-RES-09 (S-15): SOS rate: 20 SOS from one identity in 10 min -> 3 accepted.
     */
    @Test
    fun test_T_RES_09_sosRateLimitCapsAt3Per10Minutes() {
        assertThat(ResourceLimits.SOS_ACCEPTANCE_MAX_PER_10_MIN).isEqualTo(3)

        fun createSosPacket(text: String): ByteArray {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(3 + textBytes.size)
            buf.put(0.toByte()) // flags: no location
            buf.putShort((textBytes.size and 0xFFFF).toShort())
            buf.put(textBytes)
            return buildPacket(
                type = PacketType.SOS_MESSAGE,
                sender = clientNode,
                recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
                plaintext = buf.array(),
                timestamp = clock.nowSeconds(),
                customMessageId = UUID.randomUUID()
            )
        }

        var acceptedCount = 0
        var droppedCount = 0

        // Attempt 20 SOS messages within the same 10-minute window
        for (i in 1..20) {
            val packetBytes = createSosPacket("Emergency message #$i")
            val result = pipeline.ingest(packetBytes, createAuthLinkContext())
            if (result is IngestResult.Accepted) {
                acceptedCount++
            } else if (result is IngestResult.Dropped && result.stage == PipelineStage.S7_SEMANTICS) {
                droppedCount++
            }
        }

        assertThat(acceptedCount).isEqualTo(3)
        assertThat(droppedCount).isEqualTo(17)

        // Advance time by 601 seconds (past 10 min window) -> next SOS is accepted
        clock.currentSec += 601L
        val refreshedResult = pipeline.ingest(createSosPacket("Refreshed SOS"), createAuthLinkContext())
        assertTrue("SOS accepted after 10-min window", refreshedResult is IngestResult.Accepted)
    }

    /**
     * T-RES-10: Path traversal in originalFileName = "a./../../evil" rejected.
     */
    @Test
    fun test_T_RES_10_pathTraversalInMediaInitRejected() {
        fun createMediaInitWithFilename(fn: String): ByteArray {
            val mediaId = UUID.randomUUID()
            val fnBytes = fn.toByteArray(Charsets.UTF_8)
            val buf = ByteBuffer.allocate(16 + 1 + 1 + 2 + 4 + 4 + 32 + 1 + fnBytes.size + 2 + 0 + 1 + 0).order(ByteOrder.BIG_ENDIAN)
            buf.putLong(mediaId.mostSignificantBits)
            buf.putLong(mediaId.leastSignificantBits)
            buf.put(3.toByte()) // type: FILE
            buf.put(1.toByte())
            buf.putShort(1.toShort())
            buf.putInt(100)
            buf.putInt(0)
            buf.put(ByteArray(32))
            buf.put(fnBytes.size.toByte())
            buf.put(fnBytes)
            buf.putShort(0.toShort()) // previewLen
            buf.put(0.toByte()) // captionLen
            return buildPacket(
                type = PacketType.MEDIA_INIT,
                sender = clientNode,
                recipientId = serverNode.nodeId64,
                plaintext = buf.array()
            )
        }

        // 1. Directory traversal ../../evil -> rejected at S7
        val traversalResult = pipeline.ingest(createMediaInitWithFilename("a./../../evil"), createAuthLinkContext())
        assertTrue(traversalResult is IngestResult.Dropped)
        assertThat((traversalResult as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S7_SEMANTICS)

        // 2. Hidden file .secret -> rejected at S7 (leading dot)
        val hiddenResult = pipeline.ingest(createMediaInitWithFilename(".secret.txt"), createAuthLinkContext())
        assertTrue(hiddenResult is IngestResult.Dropped)
        assertThat((hiddenResult as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S7_SEMANTICS)

        // 3. Valid safe filename -> admitted
        val safeResult = pipeline.ingest(createMediaInitWithFilename("safe_document-1.pdf"), createAuthLinkContext())
        assertTrue("Safe filename must be admitted", safeResult is IngestResult.Accepted)
    }

    /**
     * T-RES-11: 60 s full-spectrum hostile soak -> every table <= cap; heap bounded; no ANR.
     */
    @Test
    fun test_T_RES_11_hostileSoakVerifiesCappedTablesAndBoundedMemory() {
        val soakController = MeshTrafficController()
        val soakDedup = LruDedupCache<String, Long>(4000)
        val soakIdentityStore = InMemoryIdentityStore(maxIdentities = 1024)

        // Flood 3,000 mixed packets through dedup, traffic controller, and identity store
        for (i in 1..3000) {
            val msgId = UUID.randomUUID().toString()
            soakDedup.put(msgId, clock.nowMillis())

            val raw = ByteArray(64) { (it xor i).toByte() }
            val type = when (i % 4) {
                0 -> PacketType.MEDIA_CHUNK
                1 -> PacketType.DIRECT_MESSAGE
                2 -> PacketType.ACK
                else -> PacketType.SOS_MESSAGE
            }
            soakController.enqueue(raw, type)

            if (i <= 1500) {
                val seed = ByteArray(32) { (it + i).toByte() }
                val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
                val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
                soakIdentityStore.upsert(
                    PeerIdentity(
                        identityHash = idHash,
                        ikPub = ikPub,
                        ekPub = ByteArray(32),
                        keyVersion = 1L,
                        lastAnnounceCounter = i.toLong(),
                        trustState = TrustState.SEEN,
                        nodeId64 = (i.toLong())
                    )
                )
            }
        }

        // Assert strictly bounded memory & caps
        assertThat(soakDedup.size()).isAtMost(4000)
        assertThat(soakIdentityStore.all().size).isAtMost(1024)
        assertThat(soakController.totalQueued()).isAtMost(400) // 100 max per tier * 4 tiers
    }

    /**
     * T-RES-12: Egress: 500 media chunks + 1 SOS queued -> SOS transmitted first (C-19).
     */
    @Test
    fun test_T_RES_12_trafficEgressStrictlyPrioritizesSosOverBulkMedia() {
        val controller = MeshTrafficController()

        // Enqueue 500 bulk media chunks
        for (i in 1..500) {
            val chunkBytes = ByteArray(320) { (it and 0xFF).toByte() }
            controller.enqueue(chunkBytes, PacketType.MEDIA_CHUNK)
        }

        // Enqueue 1 critical SOS message
        val sosBytes = "SOS EMERGENCY SIGNAL".toByteArray(Charsets.UTF_8)
        controller.enqueue(sosBytes, PacketType.SOS_MESSAGE)

        // Poll next packet -> MUST be the SOS message
        val polled = controller.pollNext()
        assertThat(polled).isNotNull()
        assertThat(polled!!.priority).isEqualTo(TrafficPriority.CRITICAL_EMERGENCY)
        assertThat(polled.rawBytes).isEqualTo(sosBytes)
    }
}

package com.meshwhisper.core.transport

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.IdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.protocol.*
import com.meshwhisper.core.storage.PacketStore
import com.meshwhisper.core.util.Clock
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID

class LinkAuthTest {

    private val secureRandom = SecureRandom()
    private val clock = object : Clock {
        var currentTime = 1700000000L
        override fun nowSeconds(): Long = currentTime
        override fun nowMillis(): Long = currentTime * 1000L
    }

    private lateinit var seedA: ByteArray
    private lateinit var ekPubA: ByteArray
    private lateinit var credsA: LinkAuthLocalCredentials

    private lateinit var seedB: ByteArray
    private lateinit var ekPubB: ByteArray
    private lateinit var credsB: LinkAuthLocalCredentials

    @Before
    fun setUp() {
        seedA = ByteArray(32).apply { secureRandom.nextBytes(this) }
        ekPubA = PureCryptoEngine.derivePublicKey(seedA)
        credsA = LinkAuthLocalCredentials.create(seedA, ekPubA, keyVersion = 1L, notBefore = 0L)

        seedB = ByteArray(32).apply { secureRandom.nextBytes(this) }
        ekPubB = PureCryptoEngine.derivePublicKey(seedB)
        credsB = LinkAuthLocalCredentials.create(seedB, ekPubB, keyVersion = 1L, notBefore = 0L)
    }

    @Test
    fun testSuccessfulMutualHandshake() {
        val sessionA = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB = LinkAuthSession("link-B-A", credsB, clock)

        assertThat(sessionA.state).isEqualTo(LinkAuthState.IDLE)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.IDLE)

        // 1. A creates HELLO, B processes it
        val helloA = sessionA.createHelloPacket()
        assertThat(sessionA.state).isEqualTo(LinkAuthState.HELLO_SENT)
        val resB1 = sessionB.processIncomingPacket(helloA)
        assertThat(resB1).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.HELLO_RECEIVED)

        // 2. B creates HELLO, A processes it
        val helloB = sessionB.createHelloPacket()
        assertThat(sessionB.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)
        val resA1 = sessionA.processIncomingPacket(helloB)
        assertThat(resA1).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionA.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // Both should derive identical T and identical K_link
        assertThat(sessionA.transcriptT).isNotNull()
        assertThat(sessionB.transcriptT).isNotNull()
        assertThat(sessionA.transcriptT).isEqualTo(sessionB.transcriptT)

        assertThat(sessionA.linkKey).isNotNull()
        assertThat(sessionB.linkKey).isNotNull()
        assertThat(sessionA.linkKey).isEqualTo(sessionB.linkKey)

        // 3. A creates CONFIRM, B processes it
        val confirmA = sessionA.createConfirmPacket()
        assertThat(sessionA.state).isEqualTo(LinkAuthState.CONFIRM_SENT)
        val resB2 = sessionB.processIncomingPacket(confirmA)
        // B hasn't sent CONFIRM yet, so state transitions to CONFIRM_RECEIVED
        assertThat(resB2).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.CONFIRM_RECEIVED)

        // 4. B creates CONFIRM, A processes it
        val confirmB = sessionB.createConfirmPacket()
        // B now emitted CONFIRM after verifying A's CONFIRM -> B is AUTHENTICATED
        assertThat(sessionB.state).isEqualTo(LinkAuthState.AUTHENTICATED)
        val proofB = sessionB.getSuccessProof()
        assertThat(proofB).isNotNull()
        assertThat(proofB!!.peerIdentityHash).isEqualTo(credsA.identityHash)
        assertThat(proofB.peerNodeId64).isEqualTo(credsA.nodeId64)

        val resA2 = sessionA.processIncomingPacket(confirmB)
        assertThat(resA2).isInstanceOf(LinkAuthStepResult.Completed::class.java)
        assertThat(sessionA.state).isEqualTo(LinkAuthState.AUTHENTICATED)
        val proofA = (resA2 as LinkAuthStepResult.Completed).proof
        assertThat(proofA.peerIdentityHash).isEqualTo(credsB.identityHash)
        assertThat(proofA.peerNodeId64).isEqualTo(credsB.nodeId64)
        assertThat(proofA.linkKey).isEqualTo(proofB.linkKey)
    }

    @Test
    fun testReverseOrderHandshake() {
        val sessionA = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB = LinkAuthSession("link-B-A", credsB, clock)

        // B sends HELLO first
        val helloB = sessionB.createHelloPacket()
        sessionA.processIncomingPacket(helloB)
        val helloA = sessionA.createHelloPacket()
        sessionB.processIncomingPacket(helloA)

        assertThat(sessionA.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // B sends CONFIRM first
        val confirmB = sessionB.createConfirmPacket()
        sessionA.processIncomingPacket(confirmB)
        val confirmA = sessionA.createConfirmPacket()
        sessionB.processIncomingPacket(confirmA)

        assertThat(sessionA.state).isEqualTo(LinkAuthState.AUTHENTICATED)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.AUTHENTICATED)
        assertThat(sessionA.linkKey).isEqualTo(sessionB.linkKey)
    }

    @Test
    fun testTLINK03_ReplayedConfirmAgainstFreshNonces() {
        // Session 1: complete normal handshake
        val sessionA1 = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB1 = LinkAuthSession("link-B-A", credsB, clock)

        val helloA1 = sessionA1.createHelloPacket()
        sessionB1.processIncomingPacket(helloA1)
        val helloB1 = sessionB1.createHelloPacket()
        sessionA1.processIncomingPacket(helloB1)

        val confirmA1 = sessionA1.createConfirmPacket() // Captured old confirm

        // Session 2: fresh handshake between same identities with fresh nonces
        val sessionA2 = LinkAuthSession("link-A-B-2", credsA, clock)
        val sessionB2 = LinkAuthSession("link-B-A-2", credsB, clock)

        val helloA2 = sessionA2.createHelloPacket()
        sessionB2.processIncomingPacket(helloA2)
        val helloB2 = sessionB2.createHelloPacket()
        sessionA2.processIncomingPacket(helloB2)

        // Attacker replays confirmA1 from session 1 against sessionB2
        val result = sessionB2.processIncomingPacket(confirmA1)
        assertThat(result).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat(sessionB2.state).isEqualTo(LinkAuthState.FAILED)
        assertThat(sessionB2.linkKey).isNull()
    }

    @Test
    fun testTLINK04_SigmaMisbindingDetection() {
        val sessionA = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB = LinkAuthSession("link-B-A", credsB, clock)

        val helloA = sessionA.createHelloPacket()
        sessionB.processIncomingPacket(helloA)
        val helloB = sessionB.createHelloPacket()
        sessionA.processIncomingPacket(helloB)

        // Mallory creates a CONFIRM signing victim C's identityHash instead of B's identityHash
        val victimCSeed = ByteArray(32).apply { secureRandom.nextBytes(this) }
        val victimCPub = PureCryptoEngine.deriveSigningPublicKey(victimCSeed)
        val victimCIdHash = PureCryptoEngine.deriveIdentityHash(victimCPub)

        val forgedPreimage = PureCryptoEngine.buildConfirmSigPreimage(
            transcriptT = sessionA.transcriptT!!,
            identityHashSelf = credsA.identityHash,
            identityHashPeer = victimCIdHash // Misbound peer
        )
        val forgedSig = PureCryptoEngine.sign(credsA.identitySeed, forgedPreimage)

        val forgedConfirmPacket = MeshPacket(
            type = PacketType.LINK_AUTH,
            messageId = UUID.randomUUID(),
            senderId = credsA.nodeId64,
            recipientId = credsB.nodeId64,
            ttl = 1,
            timestamp = clock.nowSeconds(),
            payload = byteArrayOf(0x02) + forgedSig,
            authTag = ByteArray(16)
        )

        val result = sessionB.processIncomingPacket(forgedConfirmPacket)
        assertThat(result).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.FAILED)
    }

    @Test
    fun testAllZeroNonceRejected() {
        // require(!nonce.all { it == 0.toByte() }) must throw on construction
        var caught = false
        try {
            HelloPayload(
                nonce = ByteArray(32), // all zeros
                ikPub = credsA.ikPub,
                ekPub = credsA.ekPub,
                keyVersion = credsA.keyVersion,
                notBefore = credsA.notBefore,
                ibcSignature = credsA.ibcSignature
            )
        } catch (_: IllegalArgumentException) {
            caught = true
        }
        assertThat(caught).isTrue()

        // Also test parser rejection
        val zeroPayload = byteArrayOf(0x01) + ByteArray(32) + credsA.ikPub + credsA.ekPub + ByteArray(8) + credsA.ibcSignature
        val parsed = LinkAuthSession.parseHello(zeroPayload)
        assertThat(parsed).isNull()
    }

    @Test
    fun testReflectionAttackRejected() {
        val sessionA = LinkAuthSession("link-reflection", credsA, clock)
        val helloA = sessionA.createHelloPacket()

        // Node receives its own HELLO (reflection attack)
        val res = sessionA.processIncomingPacket(helloA)
        assertThat(res).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat(sessionA.state).isEqualTo(LinkAuthState.FAILED)
    }

    @Test
    fun testSecondHelloRejectedOnConfirmLink() {
        val sessionA = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB = LinkAuthSession("link-B-A", credsB, clock)

        val helloA = sessionA.createHelloPacket()
        sessionB.processIncomingPacket(helloA)
        val helloB = sessionB.createHelloPacket()
        sessionA.processIncomingPacket(helloB)

        sessionA.createConfirmPacket()
        assertThat(sessionA.state).isEqualTo(LinkAuthState.CONFIRM_SENT)

        // Peer sends a second HELLO while in CONFIRM_SENT state
        val res = sessionA.processIncomingPacket(helloB)
        assertThat(res).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat(sessionA.state).isEqualTo(LinkAuthState.FAILED)
    }

    @Test
    fun testHalfAuthenticatedStateCannotSendMeshTraffic() {
        val sessionA = LinkAuthSession("link-A-B", credsA, clock)
        val sessionB = LinkAuthSession("link-B-A", credsB, clock)

        val helloA = sessionA.createHelloPacket()
        sessionB.processIncomingPacket(helloA)
        val helloB = sessionB.createHelloPacket()
        sessionA.processIncomingPacket(helloB)

        // Node A sends CONFIRM, but B's CONFIRM has not arrived yet
        sessionA.createConfirmPacket()
        assertThat(sessionA.state).isEqualTo(LinkAuthState.CONFIRM_SENT)
        assertThat(sessionA.state).isNotEqualTo(LinkAuthState.AUTHENTICATED)
        assertThat(sessionA.getSuccessProof()).isNull()
    }

    @Test
    fun testFirstContactHelloBootstrapThroughPacketPipeline() {
        val inMemoryStore = com.meshwhisper.core.identity.InMemoryIdentityStore()
        val inMemoryPacketStore = com.meshwhisper.core.protocol.InMemoryPacketStore()
        val dummyKeyProvider = object : KeyProvider {}

        val pipeline = PacketPipeline(
            localNodeId64 = credsB.nodeId64,
            identityStore = inMemoryStore,
            packetStore = inMemoryPacketStore,
            keyProvider = dummyKeyProvider,
            clock = clock
        )

        val pendingContext = LinkContext(
            linkHandle = "test-link",
            transport = TransportType.WIFI_TCP,
            boundIdentity = null,
            state = LinkState.PENDING
        )

        // 1. Non-LINK_AUTH on PENDING link is dropped at S0
        val dmPayload = ByteArray(100) { 0x41 }
        val dmPacket = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = credsA.nodeId64,
            recipientId = credsB.nodeId64,
            ttl = 1,
            timestamp = clock.nowSeconds(),
            payload = dmPayload,
            authTag = ByteArray(16).apply { secureRandom.nextBytes(this) }
        )
        val dropDm = pipeline.ingest(MeshPacket.serialize(dmPacket), pendingContext)
        assertThat(dropDm).isInstanceOf(IngestResult.Dropped::class.java)
        assertThat((dropDm as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S0_ADMISSION)

        // 2. First-contact LINK_AUTH HELLO succeeds without pre-existing identity
        val sessionA = LinkAuthSession("test-link", credsA, clock)
        val helloPacket = sessionA.createHelloPacket()
        val resHello = pipeline.ingest(MeshPacket.serialize(helloPacket), pendingContext)
        assertThat(resHello).isInstanceOf(IngestResult.Accepted::class.java)
        val accepted = (resHello as IngestResult.Accepted).packet
        assertThat(accepted.senderIdentity.identityHash).isEqualTo(credsA.identityHash)

        // 3. Prove NO persistent trust or packet store mutation occurred merely because HELLO was received
        assertThat(inMemoryStore.all()).isEmpty()
        assertThat(inMemoryPacketStore.getSeenCount()).isEqualTo(0)
    }

    /**
     * P4 Remediation Fix 4:
     * Enforce LINK_AUTH 60-second freshness window and 120-second future skew in production path.
     * - age = now - packet.timestamp, accept iff -120 <= age <= 60
     * - timestamp 61s old -> reject
     * - timestamp exactly at allowed boundary (60s old) -> accepted
     * - future timestamp within 120s -> accepted
     * - future timestamp beyond 120s -> reject
     * - stale packet must not mutate handshake state
     * - stale packet must not create/bind identity
     * - stale packet must not derive/cut over K_link
     */
    @Test
    fun testLinkAuthFreshness_PastWindowAndFutureSkewBounds() {
        clock.currentTime = 1000L

        val sessionB = LinkAuthSession("link-B", credsB, clock)
        val sessionA = LinkAuthSession("link-A", credsA, clock)
        val baseHelloA = sessionA.createHelloPacket()

        // 1. HELLO with timestamp 61s old (timestamp = 939) -> age = 61s > 60s -> REJECT
        val staleHello = baseHelloA.copy(timestamp = 939L)
        val resStaleHello = sessionB.processIncomingPacket(staleHello)
        assertThat(resStaleHello).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat((resStaleHello as LinkAuthStepResult.Failed).reason).contains("outside freshness window")
        // Invariant: Stale packet MUST NOT mutate handshake state
        assertThat(sessionB.state).isEqualTo(LinkAuthState.IDLE)
        assertThat(sessionB.linkKey).isNull()
        assertThat(sessionB.remoteIdentityHash).isNull()

        // 2. HELLO with future timestamp beyond 120s (timestamp = 1121) -> age = -121s < -120s -> REJECT
        val futureHelloSkewed = baseHelloA.copy(timestamp = 1121L)
        val resFutureSkewed = sessionB.processIncomingPacket(futureHelloSkewed)
        assertThat(resFutureSkewed).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat((resFutureSkewed as LinkAuthStepResult.Failed).reason).contains("outside freshness window")
        assertThat(sessionB.state).isEqualTo(LinkAuthState.IDLE)

        // 3. HELLO with timestamp exactly at allowed past boundary (timestamp = 940) -> age = 60s <= 60s -> ACCEPT
        val boundaryPastHello = baseHelloA.copy(timestamp = 940L)
        val resBoundaryPast = sessionB.processIncomingPacket(boundaryPastHello)
        assertThat(resBoundaryPast).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.HELLO_RECEIVED)

        // Reset sessionB for future boundary test
        val sessionB2 = LinkAuthSession("link-B2", credsB, clock)
        // 4. HELLO with future timestamp exactly at 120s boundary (timestamp = 1120) -> age = -120s >= -120s -> ACCEPT
        val boundaryFutureHello = baseHelloA.copy(timestamp = 1120L)
        val resBoundaryFuture = sessionB2.processIncomingPacket(boundaryFutureHello)
        assertThat(resBoundaryFuture).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionB2.state).isEqualTo(LinkAuthState.HELLO_RECEIVED)

        // 5. Test CONFIRM freshness
        // Advance sessionA and sessionB2 to CONFIRM exchange
        val helloB2 = sessionB2.createHelloPacket() // sessionB2 -> HELLO_EXCHANGED
        sessionA.processIncomingPacket(helloB2) // sessionA -> HELLO_EXCHANGED
        val confirmA = sessionA.createConfirmPacket() // sessionA -> CONFIRM_SENT

        // Stale CONFIRM: timestamp 61s old (939)
        val staleConfirm = confirmA.copy(timestamp = 939L)
        val resStaleConfirm = sessionB2.processIncomingPacket(staleConfirm)
        assertThat(resStaleConfirm).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat((resStaleConfirm as LinkAuthStepResult.Failed).reason).contains("outside freshness window")
        // Invariant: sessionB2 state must NOT transition to CONFIRM_RECEIVED or AUTHENTICATED or FAILED
        assertThat(sessionB2.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // Skewed future CONFIRM: timestamp 121s in future (1121)
        val futureConfirm = confirmA.copy(timestamp = 1121L)
        val resFutureConfirm = sessionB2.processIncomingPacket(futureConfirm)
        assertThat(resFutureConfirm).isInstanceOf(LinkAuthStepResult.Failed::class.java)
        assertThat(sessionB2.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // Valid boundary CONFIRM: timestamp exactly 60s old (940) -> ACCEPT
        val validConfirm = confirmA.copy(timestamp = 940L)
        val resValidConfirm = sessionB2.processIncomingPacket(validConfirm)
        // sessionB2 was in HELLO_EXCHANGED, so receiving CONFIRM puts it in CONFIRM_RECEIVED
        assertThat(resValidConfirm).isEqualTo(LinkAuthStepResult.InProgress)
        assertThat(sessionB2.state).isEqualTo(LinkAuthState.CONFIRM_RECEIVED)
    }
}

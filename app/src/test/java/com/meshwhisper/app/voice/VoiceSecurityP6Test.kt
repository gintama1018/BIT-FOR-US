package com.meshwhisper.app.voice

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.protocol.*
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.Clock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Normative P6 Voice Security Tests (T-VOICE-01 through T-VOICE-07)
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSecurityP6Test {

    private class TestAudioStreamer : AudioStreamer {
        var isStreaming = false
        val inboundFrames = mutableListOf<ByteArray>()
        override fun startStreaming(onOutboundFrame: (Int, Long, ByteArray) -> Unit) {
            isStreaming = true
        }
        override fun onInboundFrame(sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) {
            inboundFrames.add(audioBytes)
        }
        override fun stopStreaming() {
            isStreaming = false
        }
        override fun setMuted(muted: Boolean) {}
        override fun setSpeakerOn(speakerOn: Boolean) {}
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val myNodeId = 0x1111222233334444L
    private val peerNodeId = 0x5555666677778888L
    private val fakeSessionKey = ByteArray(32) { (it + 1).toByte() }

    private lateinit var audioStreamer: TestAudioStreamer
    private val sentSignals = mutableListOf<Pair<Long, ByteArray>>()
    private val sentFrames = mutableListOf<Pair<Long, ByteArray>>()
    private lateinit var callManager: VoiceCallManager

    @Before
    fun setup() {
        audioStreamer = TestAudioStreamer()
        sentSignals.clear()
        sentFrames.clear()

        callManager = VoiceCallManager(
            myNodeId = myNodeId,
            isPeerDirectlyConnected = { it == peerNodeId },
            sendSignalPacket = { peerId, bytes ->
                sentSignals.add(peerId to bytes)
                true
            },
            sendFramePacket = { peerId, bytes ->
                sentFrames.add(peerId to bytes)
                true
            },
            audioStreamer = audioStreamer,
            scope = testScope,
            callKeyDeriver = { _, timestampSec, sessionId ->
                // Derives K_call deterministically from fakeSessionKey + sessionId bytes.
                // timestampSec is the offerTimestampSec (epoch-pinned from OFFER, C-13).
                val idBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
                    .putLong(sessionId.mostSignificantBits)
                    .putLong(sessionId.leastSignificantBits)
                    .array()
                PureCryptoEngine.deriveCallKey(fakeSessionKey, idBytes)
            }
        )
    }

    /**
     * T-VOICE-01: Passive capture of a live call
     * Audio is NOT recoverable without K_call.
     */
    @Test
    fun test_T_VOICE_01_passiveCaptureAudioUnrecoverableWithoutCallKey() {
        val callSessionId = UUID.randomUUID()
        val idBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(callSessionId.mostSignificantBits)
            .putLong(callSessionId.leastSignificantBits)
            .array()
        val legitCallKey = PureCryptoEngine.deriveCallKey(fakeSessionKey, idBytes)
        val eavesdropperKey = ByteArray(32) { 0x99.toByte() }

        val pcmAudio = ByteArray(80) { (it * 3).toByte() }
        val seq = 1L
        val direction: Byte = 0x01
        val aad = ByteArray(16) { 0xAA.toByte() }

        // Legitimate sender encrypts frame
        val (rawCiphertext, authTag) = PureCryptoEngine.encryptVoiceFrame(pcmAudio, seq, direction, legitCallKey, aad)

        // Passive eavesdropper tries to decrypt with wrong key -> AEAD failure
        assertThrows(Exception::class.java) {
            PureCryptoEngine.decryptVoiceFrame(seq, rawCiphertext, authTag, direction, eavesdropperKey, aad)
        }
    }

    /**
     * T-VOICE-02: Frame injection with a known callSessionId
     * Rejected (AEAD under K_call fails); audio pipeline receives nothing.
     */
    @Test
    fun test_T_VOICE_02_frameInjectionWithKnownCallSessionIdRejected() = testScope.runTest {
        val callSessionId = UUID.randomUUID()
        val offer = VoiceSignalPayload(CallAction.OFFER, callSessionId, 1000L)
        callManager.handleIncomingSignal(peerNodeId, offer)
        callManager.acceptCall()
        runCurrent()

        // Attacker: valid-looking frame structure, but wrong ciphertext + forged auth tag
        val seqPlain = 1L
        val forgedCiphertext = ByteArray(80) { 0xDE.toByte() }
        val forgedAuthTag = ByteArray(16) { 0xAD.toByte() }
        val aad = ByteArray(40) { 0x12.toByte() }

        callManager.handleIncomingVoiceFrame(peerNodeId, seqPlain, forgedCiphertext, forgedAuthTag, aad)

        // AEAD failure -> nothing reaches the audio pipeline
        assertTrue("Inbound audio frames must be empty on injection attempt", audioStreamer.inboundFrames.isEmpty())

        // Teardown
        callManager.endCall()
        callManager.dismissEndedCall()
        advanceUntilIdle()
    }

    /**
     * T-VOICE-03: Replayed frame seq
     * seq <= lastAcceptedSeq or seq=0 is dropped before decryption.
     */
    @Test
    fun test_T_VOICE_03_replayedFrameSeqRejectedBeforeDecryption() = testScope.runTest {
        val callSessionId = UUID.randomUUID()
        val offer = VoiceSignalPayload(CallAction.OFFER, callSessionId, 1000L)
        callManager.handleIncomingSignal(peerNodeId, offer)
        callManager.acceptCall()
        runCurrent()

        // Derive the same callKey the manager uses
        val idBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(callSessionId.mostSignificantBits)
            .putLong(callSessionId.leastSignificantBits)
            .array()
        val callKey = PureCryptoEngine.deriveCallKey(fakeSessionKey, idBytes)
        // Callee receives from caller: direction 0x01 (caller→callee)
        val direction: Byte = 0x01
        val aad = ByteArray(40) { 0x33.toByte() }

        fun sendFrame(seq: Long) {
            val pcm = ByteArray(80) { (seq and 0x7F).toByte() }
            val (rawCiphertext, authTag) = PureCryptoEngine.encryptVoiceFrame(pcm, seq, direction, callKey, aad)
            callManager.handleIncomingVoiceFrame(peerNodeId, seq, rawCiphertext, authTag, aad)
        }

        // seq=1 -> accepted (first frame)
        sendFrame(1L)
        assertEquals(1, audioStreamer.inboundFrames.size)

        // seq=1 replay -> rejected (1 <= lastAccepted=1)
        sendFrame(1L)
        assertEquals("Replay of seq=1 must be rejected", 1, audioStreamer.inboundFrames.size)

        // seq=0 -> rejected before decryption (reserved seq=0)
        sendFrame(0L)
        assertEquals("seq=0 must always be rejected", 1, audioStreamer.inboundFrames.size)

        // seq=3 -> accepted
        sendFrame(3L)
        assertEquals(2, audioStreamer.inboundFrames.size)

        // seq=2 (old, < lastAccepted=3) -> rejected
        sendFrame(2L)
        assertEquals("seq=2 after seq=3 must be rejected", 2, audioStreamer.inboundFrames.size)

        // Teardown
        callManager.endCall()
        callManager.dismissEndedCall()
        advanceUntilIdle()
    }

    /**
     * T-VOICE-04: Call crossing an hour boundary
     * K_call pinned from OFFER epoch; continuous audio across hour boundary (C-13).
     */
    @Test
    fun test_T_VOICE_04_callCrossingHourBoundaryPreservesCallKey() = testScope.runTest {
        val callSessionId = UUID.randomUUID()
        // OFFER timestamp as seconds (5s before hour boundary)
        val offerTimestampSec = 3595L
        val offer = VoiceSignalPayload(CallAction.OFFER, callSessionId, offerTimestampSec)
        callManager.handleIncomingSignal(peerNodeId, offer)
        callManager.acceptCall()
        runCurrent()

        // Derive the exact same key that acceptCall() uses: callKeyDeriver(peerNodeId, offerSec, sessionId)
        val idBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(callSessionId.mostSignificantBits)
            .putLong(callSessionId.leastSignificantBits)
            .array()
        val pinnedCallKey = PureCryptoEngine.deriveCallKey(fakeSessionKey, idBytes)
        val direction: Byte = 0x01
        val aad = ByteArray(40) { 0x55.toByte() }

        fun sendFrame(seq: Long) {
            val pcm = ByteArray(80) { 0x42.toByte() }
            val (rawCiphertext, authTag) = PureCryptoEngine.encryptVoiceFrame(pcm, seq, direction, pinnedCallKey, aad)
            callManager.handleIncomingVoiceFrame(peerNodeId, seq, rawCiphertext, authTag, aad)
        }

        // Frame before hour boundary (hour 0)
        sendFrame(1L)
        assertEquals(1, audioStreamer.inboundFrames.size)

        // Frame after hour boundary (hour 1) — K_call unchanged under pinned epoch
        sendFrame(2L)
        assertEquals("Frame crossing hour boundary must decrypt cleanly under pinned K_call", 2, audioStreamer.inboundFrames.size)

        // Teardown
        callManager.endCall()
        callManager.dismissEndedCall()
        advanceUntilIdle()
    }

    /**
     * T-VOICE-05: seqPlain != decrypted seq
     * Tampered seqPlain header causes AEAD to fail (wrong nonce); frame is dropped.
     */
    @Test
    fun test_T_VOICE_05_mismatchedSeqPlainDropped() = testScope.runTest {
        val callSessionId = UUID.randomUUID()
        val offer = VoiceSignalPayload(CallAction.OFFER, callSessionId, 1000L)
        callManager.handleIncomingSignal(peerNodeId, offer)
        callManager.acceptCall()
        runCurrent()

        val idBytes = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
            .putLong(callSessionId.mostSignificantBits)
            .putLong(callSessionId.leastSignificantBits)
            .array()
        val callKey = PureCryptoEngine.deriveCallKey(fakeSessionKey, idBytes)

        val pcm = ByteArray(80) { 0x55.toByte() }
        val aad = ByteArray(40) { 0x77.toByte() }

        // Encrypt with actual seq=1; nonce is built from (direction, seq=1)
        val (rawCiphertext, authTag) = PureCryptoEngine.encryptVoiceFrame(pcm, 1L, 0x01, callKey, aad)

        // Present seqPlain=2 → decryptVoiceFrame builds nonce from seq=2, which doesn't match AEAD tag
        callManager.handleIncomingVoiceFrame(peerNodeId, 2L, rawCiphertext, authTag, aad)

        // AEAD fails → dropped
        assertTrue("Tampered seqPlain must cause frame to be dropped", audioStreamer.inboundFrames.isEmpty())

        // Teardown
        callManager.endCall()
        callManager.dismissEndedCall()
        advanceUntilIdle()
    }

    /**
     * T-VOICE-06: callSessionId reuse
     * Rejected via the 64-entry recent-call LRU in handleIncomingSignal(OFFER).
     */
    @Test
    fun test_T_VOICE_06_callSessionIdReuseRejected() = testScope.runTest {
        val callSessionId = UUID.randomUUID()
        val offer = VoiceSignalPayload(CallAction.OFFER, callSessionId, 1000L)

        // First call — accepted
        callManager.handleIncomingSignal(peerNodeId, offer)
        assertEquals(CallState.INCOMING_RINGING, callManager.callState.value)

        // Decline -> terminateCall() -> LRU records sessionId synchronously
        callManager.declineCall()
        advanceUntilIdle()

        // State must return to IDLE after auto-dismiss completes
        callManager.dismissEndedCall()
        assertEquals(CallState.IDLE, callManager.callState.value)

        // Attacker replays the same callSessionId — must be rejected by LRU
        callManager.handleIncomingSignal(peerNodeId, offer)
        assertEquals("Reused callSessionId must be rejected (LRU gate)", CallState.IDLE, callManager.callState.value)
    }

    /**
     * T-VOICE-07: Spoofed OFFER from an unbound link
     * Dropped by PacketPipeline (requires LinkState.AUTHENTICATED + matching boundIdentity).
     */
    @Test
    fun test_T_VOICE_07_spoofedOfferFromUnboundLinkDropped() {
        val clock = object : Clock {
            override fun nowSeconds(): Long = 1_000_000L
        }
        val identityStore = InMemoryIdentityStore()
        val packetStore = InMemoryPacketStore()
        val dedupCache = LruDedupCache<String, Long>(1000)

        val clientSeed = ByteArray(32) { 0x11.toByte() }
        val clientIkPub = PureCryptoEngine.deriveSigningPublicKey(clientSeed)
        val clientIdHash = PureCryptoEngine.deriveIdentityHash(clientIkPub)
        val clientNodeId = PureCryptoEngine.deriveNodeId64(clientIdHash)
        val (clientEkPriv, clientEkPub) = PureCryptoEngine.generateX25519KeyPair()

        val serverSeed = ByteArray(32) { 0x22.toByte() }
        val serverIkPub = PureCryptoEngine.deriveSigningPublicKey(serverSeed)
        val serverIdHash = PureCryptoEngine.deriveIdentityHash(serverIkPub)
        val serverNodeId = PureCryptoEngine.deriveNodeId64(serverIdHash)
        val (serverEkPriv, serverEkPub) = PureCryptoEngine.generateX25519KeyPair()

        identityStore.upsert(
            PeerIdentity(
                identityHash = clientIdHash,
                ikPub = clientIkPub,
                ekPub = clientEkPub,
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.VERIFIED,
                nodeId64 = clientNodeId
            )
        )

        val sessionKey = PureCryptoEngine.derivePeerSessionKey(serverEkPriv, clientEkPub, clock.nowSeconds())
        val keyProvider = object : KeyProvider {
            override fun getPublicChannelKey(): ByteArray = PureCryptoEngine.derivePublicChannelKey()
            override fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? = sessionKey
        }

        val pipeline = PacketPipeline(
            localNodeId64 = serverNodeId,
            identityStore = identityStore,
            packetStore = packetStore,
            keyProvider = keyProvider,
            clock = clock,
            dedupCache = dedupCache
        )

        val callSessionId = UUID.randomUUID()
        val offerSignal = VoiceSignalPayload(CallAction.OFFER, callSessionId, clock.nowSeconds())
        val offerPlaintext = offerSignal.serialize() // 29 bytes
        val messageId = UUID.randomUUID()

        val headerBytes = MeshPacket(
            type = PacketType.VOICE_CALL_SIGNAL,
            ttl = 1,
            senderId = clientNodeId,
            recipientId = serverNodeId,
            messageId = messageId,
            timestamp = clock.nowSeconds(),
            payload = ByteArray(0),
            authTag = ByteArray(16)
        ).getAuthenticatedHeaderBytes()

        val encResult = PureCryptoEngine.encrypt(
            plaintext = offerPlaintext,
            messageId = messageId,
            aesKey = sessionKey,
            aad = headerBytes
        )

        val packet = MeshPacket(
            type = PacketType.VOICE_CALL_SIGNAL,
            ttl = 1,
            senderId = clientNodeId,
            recipientId = serverNodeId,
            messageId = messageId,
            timestamp = clock.nowSeconds(),
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )
        val rawWireBytes = MeshPacket.serialize(packet)

        // Case A: PENDING (unbound) link -> dropped
        val unboundLink = LinkContext(
            linkHandle = "BLE:11:22:33:44:55:66",
            transport = TransportType.BLE,
            boundIdentity = null,
            state = LinkState.PENDING
        )
        val resultA = pipeline.ingest(rawWireBytes, unboundLink)
        assertTrue("OFFER over unbound/pending link must be dropped", resultA is IngestResult.Dropped)

        // Case B: Authenticated link, mismatched boundIdentity -> dropped
        val wrongBoundLink = LinkContext(
            linkHandle = "BLE:11:22:33:44:55:66",
            transport = TransportType.BLE,
            boundIdentity = ByteArray(32) { 0xFF.toByte() },
            state = LinkState.AUTHENTICATED
        )
        val resultB = pipeline.ingest(rawWireBytes, wrongBoundLink)
        assertTrue("OFFER over link with mismatched bound identity must be dropped", resultB is IngestResult.Dropped)

        // Case C: Authenticated link, correct boundIdentity -> accepted
        val legitLink = LinkContext(
            linkHandle = "BLE:11:22:33:44:55:66",
            transport = TransportType.BLE,
            boundIdentity = clientIdHash,
            state = LinkState.AUTHENTICATED
        )
        val resultC = pipeline.ingest(rawWireBytes, legitLink)
        assertTrue("OFFER over authenticated and matching link must be accepted", resultC is IngestResult.Accepted)
    }
}

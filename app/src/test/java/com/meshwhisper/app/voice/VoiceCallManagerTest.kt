package com.meshwhisper.app.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceCallManagerTest {

    private class FakeAudioStreamer : AudioStreamer {
        var isStreaming = false
        var mutedState = false
        var speakerOnState = false
        val inboundFrames = mutableListOf<ByteArray>()
        var outboundCallback: ((sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) -> Unit)? = null

        override fun startStreaming(onOutboundFrame: (sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) -> Unit) {
            isStreaming = true
            outboundCallback = onOutboundFrame
        }

        override fun onInboundFrame(sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) {
            inboundFrames.add(audioBytes)
        }

        override fun stopStreaming() {
            isStreaming = false
            outboundCallback = null
        }

        override fun setMuted(muted: Boolean) {
            mutedState = muted
        }

        override fun setSpeakerOn(speakerOn: Boolean) {
            speakerOnState = speakerOn
        }
    }

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val myNodeId = 0x1111222233334444L
    private val peerDirectId = 0x5555666677778888L
    private val peerRelayId = 0x7777AAAABBBBCCCCL

    private val directNeighbors = mutableSetOf<Long>()
    private val sentSignals = mutableListOf<Pair<Long, ByteArray>>()
    private val sentFrames = mutableListOf<Pair<Long, ByteArray>>()
    private lateinit var fakeAudioStreamer: FakeAudioStreamer
    private lateinit var callManager: VoiceCallManager

    @Before
    fun setup() {
        directNeighbors.clear()
        directNeighbors.add(peerDirectId)
        sentSignals.clear()
        sentFrames.clear()
        fakeAudioStreamer = FakeAudioStreamer()

        callManager = VoiceCallManager(
            myNodeId = myNodeId,
            isPeerDirectlyConnected = { directNeighbors.contains(it) },
            sendSignalPacket = { peerId, bytes ->
                sentSignals.add(peerId to bytes)
                true
            },
            sendFramePacket = { peerId, bytes ->
                sentFrames.add(peerId to bytes)
                true
            },
            audioStreamer = fakeAudioStreamer,
            scope = testScope
        )
    }

    @After
    fun tearDown() {
        callManager.endCall()
        callManager.dismissEndedCall()
    }

    @Test
    fun testSignalPayloadSerializationRoundtrip() {
        val sessionId = UUID.randomUUID()
        val original = VoiceSignalPayload(
            action = CallAction.OFFER,
            sessionId = sessionId,
            timestamp = 1700000000L
        )

        val bytes = original.serialize()
        assertEquals(VoiceSignalPayload.PAYLOAD_SIZE, bytes.size)
        assertEquals(25, bytes.size)

        val deserialized = VoiceSignalPayload.deserialize(bytes)
        assertNotNull(deserialized)
        assertEquals(CallAction.OFFER, deserialized?.action)
        assertEquals(sessionId, deserialized?.sessionId)
        assertEquals(1700000000L, deserialized?.timestamp)
    }

    @Test
    fun testFramePayloadSerializationRoundtrip() {
        val sessionId = UUID.randomUUID()
        val dummyAudio = ByteArray(80) { (it % 128).toByte() }
        val original = VoiceFramePayload(
            sessionId = sessionId,
            sequenceNumber = 42,
            timestamp = 1700000005L,
            audioData = dummyAudio
        )

        val bytes = original.serialize()
        assertEquals(VoiceFramePayload.HEADER_SIZE + 80, bytes.size)
        assertEquals(108, bytes.size)

        val deserialized = VoiceFramePayload.deserialize(bytes)
        assertNotNull(deserialized)
        assertEquals(sessionId, deserialized?.sessionId)
        assertEquals(42, deserialized?.sequenceNumber)
        assertEquals(1700000005L, deserialized?.timestamp)
        assertArrayEquals(dummyAudio, deserialized?.audioData)
    }

    @Test
    fun testStartCallDirectPeerSuccess() = testScope.runTest {
        val started = callManager.startCall(peerDirectId)
        assertTrue(started)
        assertEquals(CallState.OUTGOING_RINGING, callManager.callState.value)
        assertNotNull(callManager.activeCallInfo.value)
        assertEquals(peerDirectId, callManager.activeCallInfo.value?.peerNodeId)
        assertTrue(callManager.activeCallInfo.value?.isCaller == true)

        testDispatcher.scheduler.runCurrent()

        assertEquals(1, sentSignals.size)
        assertEquals(peerDirectId, sentSignals[0].first)
        val payload = VoiceSignalPayload.deserialize(sentSignals[0].second)
        assertEquals(CallAction.OFFER, payload?.action)
    }

    @Test
    fun testStartCallNonDirectPeerFails() = testScope.runTest {
        // Calling peer that is NOT directly connected (e.g. multi-hop relay peer)
        val started = callManager.startCall(peerRelayId)
        assertFalse(started)
        assertEquals(CallState.IDLE, callManager.callState.value)
        assertNull(callManager.activeCallInfo.value)
        assertEquals(0, sentSignals.size)
    }

    @Test
    fun testIncomingOfferAndAcceptFlow() = testScope.runTest {
        val sessionId = UUID.randomUUID()
        val offerSignal = VoiceSignalPayload(
            action = CallAction.OFFER,
            sessionId = sessionId,
            timestamp = 1000L
        )

        // Receive incoming offer from directly connected peer
        callManager.handleIncomingSignal(peerDirectId, offerSignal)
        assertEquals(CallState.INCOMING_RINGING, callManager.callState.value)
        assertFalse(callManager.activeCallInfo.value?.isCaller ?: true)

        // Accept call
        callManager.acceptCall()
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallState.CONNECTED, callManager.callState.value)
        assertTrue(fakeAudioStreamer.isStreaming)

        // Verify ANSWER signal sent
        val answerSignal = sentSignals.mapNotNull { VoiceSignalPayload.deserialize(it.second) }
            .firstOrNull { it.action == CallAction.ANSWER }
        assertNotNull(answerSignal)
        assertEquals(sessionId, answerSignal?.sessionId)

        callManager.endCall()
        callManager.dismissEndedCall()
    }

    @Test
    fun testIncomingOfferDeclineFlow() = testScope.runTest {
        val sessionId = UUID.randomUUID()
        val offerSignal = VoiceSignalPayload(
            action = CallAction.OFFER,
            sessionId = sessionId,
            timestamp = 1000L
        )

        callManager.handleIncomingSignal(peerDirectId, offerSignal)
        assertEquals(CallState.INCOMING_RINGING, callManager.callState.value)

        // Decline call
        callManager.declineCall()
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallState.ENDED, callManager.callState.value)
        assertEquals(CallEndReason.DECLINED, callManager.activeCallInfo.value?.endReason)
        assertFalse(fakeAudioStreamer.isStreaming)

        val declineSignal = sentSignals.mapNotNull { VoiceSignalPayload.deserialize(it.second) }
            .firstOrNull { it.action == CallAction.DECLINE }
        assertNotNull(declineSignal)
        assertEquals(sessionId, declineSignal?.sessionId)

        callManager.dismissEndedCall()
    }

    @Test
    fun testBusyRejectionWhenAlreadyInCall() = testScope.runTest {
        // Start outgoing call with peerDirectId
        callManager.startCall(peerDirectId)
        testDispatcher.scheduler.runCurrent()

        val otherPeerId = 0x9999L
        directNeighbors.add(otherPeerId)

        // Other peer tries to call while we are already ringing/calling
        val thirdPartyOffer = VoiceSignalPayload(
            action = CallAction.OFFER,
            sessionId = UUID.randomUUID(),
            timestamp = 2000L
        )
        callManager.handleIncomingSignal(otherPeerId, thirdPartyOffer)
        testDispatcher.scheduler.runCurrent()

        // Active call should not be disrupted
        assertEquals(CallState.OUTGOING_RINGING, callManager.callState.value)
        assertEquals(peerDirectId, callManager.activeCallInfo.value?.peerNodeId)

        // BUSY signal sent back to otherPeerId
        val busySignal = sentSignals.firstOrNull { it.first == otherPeerId }
        assertNotNull(busySignal)
        val deserialized = VoiceSignalPayload.deserialize(busySignal!!.second)
        assertEquals(CallAction.BUSY, deserialized?.action)

        callManager.endCall()
        callManager.dismissEndedCall()
    }

    @Test
    fun testDirectLinkDisconnectTerminatesCall() = testScope.runTest {
        val sessionId = UUID.randomUUID()
        callManager.handleIncomingSignal(peerDirectId, VoiceSignalPayload(CallAction.OFFER, sessionId, 1000L))
        callManager.acceptCall()
        testDispatcher.scheduler.runCurrent()

        assertTrue(fakeAudioStreamer.isStreaming)
        assertEquals(CallState.CONNECTED, callManager.callState.value)

        // Link drops (peer walked out of range or disconnected)
        callManager.onDirectPeerDisconnected(peerDirectId)
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallState.ENDED, callManager.callState.value)
        assertEquals(CallEndReason.LINK_LOST, callManager.activeCallInfo.value?.endReason)
        assertFalse(fakeAudioStreamer.isStreaming)

        callManager.dismissEndedCall()
    }

    @Test
    fun testVoiceFrameForwarding() = testScope.runTest {
        val sessionId = UUID.randomUUID()
        callManager.handleIncomingSignal(peerDirectId, VoiceSignalPayload(CallAction.OFFER, sessionId, 1000L))
        callManager.acceptCall()
        testDispatcher.scheduler.runCurrent()

        val audioSample = ByteArray(80) { 0x55 }
        val frame = VoiceFramePayload(sessionId, sequenceNumber = 1, timestamp = 1020L, audioData = audioSample)

        callManager.handleIncomingVoiceFrame(peerDirectId, frame)
        assertEquals(1, fakeAudioStreamer.inboundFrames.size)
        assertArrayEquals(audioSample, fakeAudioStreamer.inboundFrames[0])

        callManager.endCall()
        callManager.dismissEndedCall()
    }

    @Test
    fun testOutgoingRingingTimeout() = testScope.runTest {
        callManager.startCall(peerDirectId)
        testDispatcher.scheduler.runCurrent()
        assertEquals(CallState.OUTGOING_RINGING, callManager.callState.value)

        // Advance past 30 seconds
        testDispatcher.scheduler.advanceTimeBy(VoiceCallManager.RINGING_TIMEOUT_MS + 100L)
        testDispatcher.scheduler.runCurrent()

        assertEquals(CallState.ENDED, callManager.callState.value)
        assertEquals(CallEndReason.TIMEOUT, callManager.activeCallInfo.value?.endReason)
        callManager.dismissEndedCall()
    }

    @Test
    fun testMuteAndSpeakerToggles() = testScope.runTest {
        assertFalse(callManager.isMuted.value)
        assertFalse(fakeAudioStreamer.mutedState)

        callManager.toggleMute()
        assertTrue(callManager.isMuted.value)
        assertTrue(fakeAudioStreamer.mutedState)

        assertFalse(callManager.isSpeakerOn.value)
        assertFalse(fakeAudioStreamer.speakerOnState)

        callManager.toggleSpeaker()
        assertTrue(callManager.isSpeakerOn.value)
        assertTrue(fakeAudioStreamer.speakerOnState)
    }
}

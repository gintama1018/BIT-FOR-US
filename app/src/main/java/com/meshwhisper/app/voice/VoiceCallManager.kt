package com.meshwhisper.app.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Controller and state machine managing 1-hop real-time voice calls.
 * Enforces direct 1-hop constraints, session lifecycles, timeouts, and link loss detection.
 */
class VoiceCallManager(
    val myNodeId: Long,
    private val isPeerDirectlyConnected: (peerId: Long) -> Boolean,
    private val sendSignalPacket: suspend (peerId: Long, signalBytes: ByteArray) -> Boolean,
    private val sendFramePacket: suspend (peerId: Long, frameBytes: ByteArray) -> Boolean,
    private val audioStreamer: AudioStreamer,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main),
    private val callKeyDeriver: (peerId: Long, timestampSec: Long, sessionId: UUID) -> ByteArray? = { _, _, _ -> ByteArray(32) { 0x42 } }
) {
    private val _callState = MutableStateFlow(CallState.IDLE)
    val callState: StateFlow<CallState> = _callState.asStateFlow()

    private val _activeCallInfo = MutableStateFlow<ActiveCallInfo?>(null)
    val activeCallInfo: StateFlow<ActiveCallInfo?> = _activeCallInfo.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    private val voiceIoScope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    private val _callDurationSeconds = MutableStateFlow(0L)
    val callDurationSeconds: StateFlow<Long> = _callDurationSeconds.asStateFlow()

    // 64-entry recent-call LRU (NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.13, T-VOICE-06)
    private val recentCallLru = object : java.util.LinkedHashMap<UUID, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, Long>?): Boolean {
            return size > 64
        }
    }

    // Sequence counters: outgoing starts at 0 (first frame is 1), incoming strictly increases (seq=0 rejected)
    private val outgoingVoiceSeq = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var lastAcceptedIncomingSeq = 0L

    private var timeoutJob: Job? = null
    private var durationJob: Job? = null
    private var autoDismissJob: Job? = null
    private var outboundWorkerJob: Job? = null

    companion object {
        const val RINGING_TIMEOUT_MS = 30_000L
    }

    private fun logInfo(msg: String) {
        try {
            android.util.Log.i("VoiceCallManager", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }

    private fun logWarn(msg: String) {
        try {
            android.util.Log.w("VoiceCallManager", msg)
        } catch (_: Throwable) {
            // JVM unit test fallback
        }
    }

    /**
     * Initiates an outgoing call to a directly connected peer.
     * Fails immediately if peer is not directly connected or if already in a call.
     */
    fun startCall(peerNodeId: Long): Boolean {
        autoDismissJob?.cancel()
        if (!isPeerDirectlyConnected(peerNodeId)) {
            logWarn("Cannot call peer $peerNodeId: not directly connected (1-hop required)")
            return false
        }

        if (_callState.value != CallState.IDLE) {
            logWarn("Cannot start call: already in state ${_callState.value}")
            return false
        }

        val sessionId = UUID.randomUUID()
        val nowMs = System.currentTimeMillis()
        val offerSec = nowMs / 1000L
        val derivedCallKey = callKeyDeriver(peerNodeId, offerSec, sessionId)
        val info = ActiveCallInfo(
            sessionId = sessionId,
            peerNodeId = peerNodeId,
            isCaller = true,
            callState = CallState.OUTGOING_RINGING,
            offerTimestampSec = offerSec,
            callKey = derivedCallKey
        )
        _activeCallInfo.value = info
        _callState.value = CallState.OUTGOING_RINGING
        _isMuted.value = false
        audioStreamer.setMuted(false)
        _isSpeakerOn.value = true
        audioStreamer.setSpeakerOn(true)

        scope.launch {
            val signal = VoiceSignalPayload(
                action = CallAction.OFFER,
                sessionId = sessionId,
                timestamp = nowMs
            )
            val signalBytes = signal.serialize()
            repeat(3) {
                if (_callState.value == CallState.OUTGOING_RINGING) {
                    sendSignalPacket(peerNodeId, signalBytes)
                    delay(50L)
                }
            }
        }

        // Outgoing Ringing Timeout
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(RINGING_TIMEOUT_MS)
            if (_callState.value == CallState.OUTGOING_RINGING) {
                val current = _activeCallInfo.value
                if (current != null) {
                    sendSignalPacket(
                        peerNodeId,
                        VoiceSignalPayload(CallAction.HANGUP, current.sessionId, System.currentTimeMillis()).serialize()
                    )
                }
                terminateCall(CallEndReason.TIMEOUT)
            }
        }

        return true
    }

    /**
     * Accepts an incoming ringing call.
     */
    fun acceptCall() {
        val current = _activeCallInfo.value ?: return
        if (_callState.value != CallState.INCOMING_RINGING) return

        timeoutJob?.cancel()

        // Derive K_call pinned to the OFFER epoch (C-13: key is stable across hour boundaries)
        val offerEpoch = if (current.offerTimestampSec > 0L) current.offerTimestampSec else (System.currentTimeMillis() / 1000L)
        val derivedCallKey = current.callKey ?: callKeyDeriver(current.peerNodeId, offerEpoch, current.sessionId)

        _callState.value = CallState.CONNECTED
        _isSpeakerOn.value = true
        audioStreamer.setSpeakerOn(true)
        _activeCallInfo.value = current.copy(
            callState = CallState.CONNECTED,
            connectedAtMs = System.currentTimeMillis(),
            callKey = derivedCallKey
        )

        scope.launch {
            val signal = VoiceSignalPayload(
                action = CallAction.ANSWER,
                sessionId = current.sessionId,
                timestamp = System.currentTimeMillis()
            )
            val signalBytes = signal.serialize()
            repeat(4) {
                sendSignalPacket(current.peerNodeId, signalBytes)
                delay(35L)
            }
        }

        startAudioPipeline(current.sessionId, current.peerNodeId)
    }

    /**
     * Declines an incoming ringing call.
     */
    fun declineCall() {
        val current = _activeCallInfo.value ?: return
        if (_callState.value != CallState.INCOMING_RINGING) return

        timeoutJob?.cancel()
        scope.launch {
            val signal = VoiceSignalPayload(
                action = CallAction.DECLINE,
                sessionId = current.sessionId,
                timestamp = System.currentTimeMillis()
            )
            val signalBytes = signal.serialize()
            repeat(2) {
                sendSignalPacket(current.peerNodeId, signalBytes)
                delay(40L)
            }
        }
        terminateCall(CallEndReason.DECLINED)
    }

    /**
     * Ends an active or ringing call.
     */
    fun endCall() {
        val current = _activeCallInfo.value ?: return
        timeoutJob?.cancel()
        scope.launch {
            val signal = VoiceSignalPayload(
                action = CallAction.HANGUP,
                sessionId = current.sessionId,
                timestamp = System.currentTimeMillis()
            )
            val signalBytes = signal.serialize()
            repeat(2) {
                sendSignalPacket(current.peerNodeId, signalBytes)
                delay(40L)
            }
        }
        terminateCall(CallEndReason.NORMAL)
    }

    /**
     * Handles incoming protocol signaling packet from the mesh.
     */
    fun handleIncomingSignal(senderId: Long, payload: VoiceSignalPayload) {
        when (payload.action) {
            CallAction.OFFER -> {
                // If already handling this exact call session, ignore duplicate OFFER packet
                val current = _activeCallInfo.value
                if (current != null && current.sessionId == payload.sessionId) {
                    return
                }

                // T-VOICE-06: Reject reused callSessionId via 64-entry recent-call LRU
                val alreadySeen = synchronized(recentCallLru) { recentCallLru.containsKey(payload.sessionId) }
                if (alreadySeen) {
                    logWarn("Dropping OFFER from $senderId: callSessionId ${payload.sessionId} already in LRU (reuse rejected)")
                    return
                }

                // If already in another call, reject with BUSY
                if (_callState.value != CallState.IDLE) {
                    scope.launch {
                        val busySignal = VoiceSignalPayload(
                            action = CallAction.BUSY,
                            sessionId = payload.sessionId,
                            timestamp = System.currentTimeMillis()
                        )
                        sendSignalPacket(senderId, busySignal.serialize())
                    }
                    return
                }

                // Verify peer is directly connected (1-hop constraint)
                if (!isPeerDirectlyConnected(senderId)) {
                    logWarn("Dropping call OFFER from $senderId: not directly connected")
                    return
                }

                // OFFER timestamp (seconds) — pins K_call epoch (C-13)
                val offerSec = if (payload.timestamp > 1_000_000_000_000L) {
                    payload.timestamp / 1000L // ms → s
                } else {
                    payload.timestamp // already seconds
                }
                val derivedCallKey = callKeyDeriver(senderId, offerSec, payload.sessionId)

                val info = ActiveCallInfo(
                    sessionId = payload.sessionId,
                    peerNodeId = senderId,
                    isCaller = false,
                    callState = CallState.INCOMING_RINGING,
                    offerTimestampSec = offerSec,
                    callKey = derivedCallKey
                )
                _activeCallInfo.value = info
                _callState.value = CallState.INCOMING_RINGING

                timeoutJob?.cancel()
                timeoutJob = scope.launch {
                    delay(RINGING_TIMEOUT_MS)
                    if (_callState.value == CallState.INCOMING_RINGING) {
                        terminateCall(CallEndReason.TIMEOUT)
                    }
                }
            }

            CallAction.ANSWER -> {
                val current = _activeCallInfo.value ?: return
                if (_callState.value == CallState.OUTGOING_RINGING &&
                    current.sessionId == payload.sessionId &&
                    current.peerNodeId == senderId
                ) {
                    timeoutJob?.cancel()
                    val offerEpoch = if (current.offerTimestampSec > 0L) current.offerTimestampSec else (payload.timestamp / 1000L)
                    val derivedCallKey = current.callKey ?: callKeyDeriver(current.peerNodeId, offerEpoch, current.sessionId)

                    _callState.value = CallState.CONNECTED
                    _isSpeakerOn.value = true
                    audioStreamer.setSpeakerOn(true)
                    _activeCallInfo.value = current.copy(
                        callState = CallState.CONNECTED,
                        connectedAtMs = System.currentTimeMillis(),
                        callKey = derivedCallKey
                    )
                    startAudioPipeline(current.sessionId, senderId)
                }
            }

            CallAction.DECLINE -> {
                val current = _activeCallInfo.value ?: return
                if (current.sessionId == payload.sessionId && current.peerNodeId == senderId) {
                    terminateCall(CallEndReason.DECLINED)
                }
            }

            CallAction.BUSY -> {
                val current = _activeCallInfo.value ?: return
                if (current.sessionId == payload.sessionId && current.peerNodeId == senderId) {
                    terminateCall(CallEndReason.BUSY)
                }
            }

            CallAction.HANGUP -> {
                val current = _activeCallInfo.value ?: return
                if (current.sessionId == payload.sessionId && current.peerNodeId == senderId) {
                    terminateCall(CallEndReason.NORMAL)
                }
            }
        }
    }

    /**
     * Handles an incoming authenticated VOICE_FRAME with raw crypto fields.
     */
    fun handleIncomingVoiceFrame(
        senderId: Long,
        seqPlain: Long,
        rawCiphertext: ByteArray,
        authTag: ByteArray,
        aad: ByteArray
    ) {
        val current = _activeCallInfo.value ?: return
        if (current.peerNodeId != senderId) return
        val callKey = current.callKey ?: return

        if (seqPlain == 0L) {
            logWarn("Rejecting voice frame with reserved seq=0")
            return
        }
        if (seqPlain <= lastAcceptedIncomingSeq) {
            logWarn("Rejecting out-of-order or duplicate voice frame seqPlain $seqPlain <= lastAccepted $lastAcceptedIncomingSeq")
            return
        }

        // Implicit ANSWER failsafe: if caller is still in OUTGOING_RINGING when peer's audio arrives
        if (_callState.value == CallState.OUTGOING_RINGING) {
            logInfo("Voice frame received from peer $senderId while OUTGOING_RINGING; auto-answering call")
            timeoutJob?.cancel()
            _callState.value = CallState.CONNECTED
            _isSpeakerOn.value = true
            audioStreamer.setSpeakerOn(true)
            val offerEpoch = if (current.offerTimestampSec > 0L) current.offerTimestampSec else (System.currentTimeMillis() / 1000L)
            val derivedCallKey = current.callKey ?: callKeyDeriver(senderId, offerEpoch, current.sessionId)
            _activeCallInfo.value = current.copy(
                callState = CallState.CONNECTED,
                connectedAtMs = System.currentTimeMillis(),
                callKey = derivedCallKey
            )
            startAudioPipeline(current.sessionId, senderId)
        }

        if (_callState.value != CallState.CONNECTED) return

        val incomingDirection: Byte = if (current.isCaller) 0x02 else 0x01

        val audioData = try {
            com.meshwhisper.core.crypto.PureCryptoEngine.decryptVoiceFrame(
                seqPlain = seqPlain,
                rawCiphertext = rawCiphertext,
                authTag = authTag,
                direction = incomingDirection,
                callKey = callKey,
                aad = aad
            )
        } catch (e: Exception) {
            logWarn("Voice frame AEAD decryption failed: ${e.message}")
            return
        }

        lastAcceptedIncomingSeq = seqPlain
        audioStreamer.onInboundFrame((seqPlain and 0x7FFFFFFF).toInt(), System.currentTimeMillis(), audioData)
    }

    /**
     * Handles an incoming authenticated VOICE_FRAME packet from the mesh router.
     */
    fun handleIncomingVoicePacket(authPacket: com.meshwhisper.core.protocol.AuthenticatedPacket) {
        val packet = authPacket.packet
        val payload = packet.payload
        val current = _activeCallInfo.value
        val callKey = current?.callKey

        if (payload.size >= 8 && callKey != null) {
            val seqPlain = java.nio.ByteBuffer.wrap(payload, 0, 8).order(java.nio.ByteOrder.BIG_ENDIAN).long
            val rawCiphertext = payload.copyOfRange(8, payload.size)
            val authTag = packet.authTag
            val aad = packet.getAuthenticatedHeaderBytes()
            handleIncomingVoiceFrame(packet.senderId, seqPlain, rawCiphertext, authTag, aad)
            return
        }

        // Fallback: If callKey was not yet established or payload is unencrypted VoiceFramePayload
        val frame = VoiceFramePayload.deserialize(payload)
            ?: (if (authPacket.decryptedPayload.isNotEmpty()) VoiceFramePayload.deserialize(authPacket.decryptedPayload) else null)
        if (frame != null) {
            handleIncomingVoiceFrame(packet.senderId, frame)
        }
    }

    /**
     * Feeds an incoming real-time audio frame into playback.
     */
    fun handleIncomingVoiceFrame(senderId: Long, frame: VoiceFramePayload) {
        val current = _activeCallInfo.value ?: return
        if (current.peerNodeId == senderId) {
            val seqLong = frame.sequenceNumber.toLong()
            if (seqLong == 0L) {
                logWarn("Rejecting voice frame with reserved seq=0")
                return
            }
            if (seqLong <= lastAcceptedIncomingSeq) {
                logWarn("Rejecting out-of-order or duplicate voice frame seq ${frame.sequenceNumber} <= lastAccepted $lastAcceptedIncomingSeq")
                return
            }
            // Implicit ANSWER failsafe: if caller is still in OUTGOING_RINGING when peer's audio arrives,
            // immediately transition caller to CONNECTED so ringing stops and audio streaming starts.
            if (_callState.value == CallState.OUTGOING_RINGING) {
                logInfo("Voice frame received from peer $senderId while OUTGOING_RINGING; auto-answering call")
                timeoutJob?.cancel()
                _callState.value = CallState.CONNECTED
                _isSpeakerOn.value = true
                audioStreamer.setSpeakerOn(true)
                val offerEpoch = if (current.offerTimestampSec > 0L) current.offerTimestampSec else (System.currentTimeMillis() / 1000L)
                val derivedCallKey = current.callKey ?: callKeyDeriver(senderId, offerEpoch, frame.sessionId)
                _activeCallInfo.value = current.copy(
                    sessionId = frame.sessionId,
                    callState = CallState.CONNECTED,
                    connectedAtMs = System.currentTimeMillis(),
                    callKey = derivedCallKey
                )
                startAudioPipeline(frame.sessionId, senderId)
            }
            if (_callState.value == CallState.CONNECTED) {
                lastAcceptedIncomingSeq = seqLong
                audioStreamer.onInboundFrame(frame.sequenceNumber, frame.timestamp, frame.audioData)
            }
        }
    }

    /**
     * Called when a direct BLE or Wi-Fi peer disconnects.
     * Automatically ends any active call with that peer due to link loss.
     */
    fun onDirectPeerDisconnected(peerNodeId: Long) {
        val current = _activeCallInfo.value ?: return
        if (current.peerNodeId == peerNodeId && _callState.value != CallState.IDLE) {
            logInfo("Direct link to peer $peerNodeId lost; terminating call")
            terminateCall(CallEndReason.LINK_LOST)
        }
    }

    fun toggleMute() {
        val newMuted = !_isMuted.value
        _isMuted.value = newMuted
        audioStreamer.setMuted(newMuted)
    }

    fun toggleSpeaker() {
        val newSpeaker = !_isSpeakerOn.value
        _isSpeakerOn.value = newSpeaker
        audioStreamer.setSpeakerOn(newSpeaker)
    }

    fun dismissEndedCall() {
        autoDismissJob?.cancel()
        if (_callState.value == CallState.ENDED) {
            _callState.value = CallState.IDLE
            _activeCallInfo.value = null
            _callDurationSeconds.value = 0L
            _isSpeakerOn.value = false
            audioStreamer.setSpeakerOn(false)
        }
    }


    private fun startAudioPipeline(sessionId: UUID, peerNodeId: Long) {
        val current = _activeCallInfo.value
        val callKey = current?.callKey
        val isCaller = current?.isCaller ?: true
        val outboundDirection: Byte = if (isCaller) 0x01 else 0x02

        val outboundChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 64)
        outboundWorkerJob?.cancel()
        outboundWorkerJob = voiceIoScope.launch {
            for (frameBytes in outboundChannel) {
                try {
                    sendFramePacket(peerNodeId, frameBytes)
                } catch (_: Exception) {}
            }
        }

        audioStreamer.startStreaming { _, _, audioBytes ->
            val seq = outgoingVoiceSeq.incrementAndGet()
            if (callKey != null) {
                val nowSec = System.currentTimeMillis() / 1000L
                val msgId = UUID.randomUUID()
                val aad = com.meshwhisper.app.protocol.MeshPacket.computeAad(
                    type = com.meshwhisper.app.protocol.PacketType.VOICE_FRAME,
                    messageId = msgId,
                    senderId = myNodeId,
                    recipientId = peerNodeId,
                    timestamp = nowSec
                )
                val (rawCiphertext, authTag) = com.meshwhisper.core.crypto.PureCryptoEngine.encryptVoiceFrame(
                    audioData = audioBytes,
                    seq = seq,
                    direction = outboundDirection,
                    callKey = callKey,
                    aad = aad
                )
                val payload = java.nio.ByteBuffer.allocate(8 + rawCiphertext.size).order(java.nio.ByteOrder.BIG_ENDIAN)
                    .putLong(seq)
                    .put(rawCiphertext)
                    .array()
                val packet = com.meshwhisper.app.protocol.MeshPacket(
                    type = com.meshwhisper.app.protocol.PacketType.VOICE_FRAME,
                    messageId = msgId,
                    senderId = myNodeId,
                    recipientId = peerNodeId,
                    ttl = 1,
                    timestamp = nowSec,
                    payload = payload,
                    authTag = authTag
                )
                outboundChannel.trySend(com.meshwhisper.app.protocol.MeshPacket.serialize(packet))
            } else {
                val payload = VoiceFramePayload(
                    sequenceNumber = seq,
                    audioData = audioBytes
                )
                outboundChannel.trySend(payload.serialize())
            }
        }

        // Duration tracking coroutine
        durationJob?.cancel()
        durationJob = scope.launch {
            _callDurationSeconds.value = 0L
            while (isActive && _callState.value == CallState.CONNECTED) {
                delay(1000L)
                _callDurationSeconds.value += 1L
            }
        }
    }

    private fun terminateCall(reason: CallEndReason) {
        timeoutJob?.cancel()
        durationJob?.cancel()
        outboundWorkerJob?.cancel()
        outboundWorkerJob = null
        autoDismissJob?.cancel()
        audioStreamer.stopStreaming()

        val current = _activeCallInfo.value
        if (current != null) {
            synchronized(recentCallLru) {
                recentCallLru[current.sessionId] = System.currentTimeMillis()
            }
            _activeCallInfo.value = current.copy(
                callState = CallState.ENDED,
                endReason = reason
            )
        }
        _callState.value = CallState.ENDED

        // Auto-dismiss after 4 seconds if user doesn't dismiss manually
        autoDismissJob = scope.launch {
            delay(4000L)
            if (_callState.value == CallState.ENDED) {
                dismissEndedCall()
            }
        }
    }
}

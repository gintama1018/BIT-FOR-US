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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
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
        val info = ActiveCallInfo(
            sessionId = sessionId,
            peerNodeId = peerNodeId,
            isCaller = true,
            callState = CallState.OUTGOING_RINGING
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
                timestamp = System.currentTimeMillis()
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

        _callState.value = CallState.CONNECTED
        _isSpeakerOn.value = true
        audioStreamer.setSpeakerOn(true)
        _activeCallInfo.value = current.copy(
            callState = CallState.CONNECTED,
            connectedAtMs = System.currentTimeMillis()
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

                val info = ActiveCallInfo(
                    sessionId = payload.sessionId,
                    peerNodeId = senderId,
                    isCaller = false,
                    callState = CallState.INCOMING_RINGING
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
                    _callState.value = CallState.CONNECTED
                    _isSpeakerOn.value = true
                    audioStreamer.setSpeakerOn(true)
                    _activeCallInfo.value = current.copy(
                        callState = CallState.CONNECTED,
                        connectedAtMs = System.currentTimeMillis()
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
     * Feeds an incoming real-time audio frame into playback.
     */
    fun handleIncomingVoiceFrame(senderId: Long, frame: VoiceFramePayload) {
        val current = _activeCallInfo.value ?: return
        if (current.peerNodeId == senderId) {
            // Implicit ANSWER failsafe: if caller is still in OUTGOING_RINGING when peer's audio arrives,
            // immediately transition caller to CONNECTED so ringing stops and audio streaming starts.
            if (_callState.value == CallState.OUTGOING_RINGING) {
                logInfo("Voice frame received from peer $senderId while OUTGOING_RINGING; auto-answering call")
                timeoutJob?.cancel()
                _callState.value = CallState.CONNECTED
                _isSpeakerOn.value = true
                audioStreamer.setSpeakerOn(true)
                _activeCallInfo.value = current.copy(
                    sessionId = frame.sessionId,
                    callState = CallState.CONNECTED,
                    connectedAtMs = System.currentTimeMillis()
                )
                startAudioPipeline(frame.sessionId, senderId)
            }
            if (_callState.value == CallState.CONNECTED) {
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
        val outboundChannel = kotlinx.coroutines.channels.Channel<ByteArray>(capacity = 64)
        outboundWorkerJob?.cancel()
        outboundWorkerJob = voiceIoScope.launch {
            for (frameBytes in outboundChannel) {
                try {
                    sendFramePacket(peerNodeId, frameBytes)
                } catch (_: Exception) {}
            }
        }

        audioStreamer.startStreaming { seq, ts, audioBytes ->
            val payload = VoiceFramePayload(
                sessionId = sessionId,
                sequenceNumber = seq,
                timestamp = ts,
                audioData = audioBytes
            )
            outboundChannel.trySend(payload.serialize())
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

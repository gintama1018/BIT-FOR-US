package com.meshwhisper.app.voice

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

enum class CallAction(val code: Byte) {
    OFFER(0x01),
    ANSWER(0x02),
    DECLINE(0x03),
    HANGUP(0x04),
    BUSY(0x05);

    companion object {
        fun fromCode(code: Byte): CallAction? = entries.firstOrNull { it.code == code }
    }
}

enum class CallState {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTED,
    ENDED
}

enum class CallEndReason {
    NORMAL,
    DECLINED,
    BUSY,
    TIMEOUT,
    LINK_LOST,
    FAILED
}

data class ActiveCallInfo(
    val sessionId: UUID,
    val peerNodeId: Long,
    val isCaller: Boolean,
    val callState: CallState,
    val callKey: ByteArray? = null,
    val startedAtMs: Long = System.currentTimeMillis(),
    val connectedAtMs: Long? = null,
    val endReason: CallEndReason? = null,
    /** Unix timestamp (seconds) from the OFFER signal — used to pin K_call epoch (C-13). */
    val offerTimestampSec: Long = 0L
)

/**
 * Binary signaling packet for voice call setup and teardown.
 * Total size: exactly 29 bytes (NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.13).
 * plaintext = action(1) ‖ callSessionId(16) ‖ signalSeq(4) ‖ timestampMs(8)
 */
data class VoiceSignalPayload(
    val action: CallAction,
    val sessionId: UUID,
    val signalSeq: Int = 1,
    val timestamp: Long
) {
    constructor(action: CallAction, sessionId: UUID, timestamp: Long) : this(action, sessionId, 1, timestamp)

    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(TOTAL_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(action.code)
        buffer.putLong(sessionId.mostSignificantBits)
        buffer.putLong(sessionId.leastSignificantBits)
        buffer.putInt(signalSeq)
        buffer.putLong(timestamp)
        return buffer.array()
    }

    companion object {
        const val TOTAL_SIZE = 29
        const val PAYLOAD_SIZE = TOTAL_SIZE

        fun deserialize(bytes: ByteArray): VoiceSignalPayload? {
            if (bytes.size != TOTAL_SIZE) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val actionCode = buffer.get()
                val action = CallAction.fromCode(actionCode) ?: return null
                val mostSig = buffer.getLong()
                val leastSig = buffer.getLong()
                val signalSeq = buffer.getInt()
                val ts = buffer.getLong()
                VoiceSignalPayload(
                    action = action,
                    sessionId = UUID(mostSig, leastSig),
                    signalSeq = signalSeq,
                    timestamp = ts
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Binary payload for 1-hop real-time compressed voice frames.
 * Supports both vNext (seqPlain(8) ‖ rawCiphertext) and legacy tests.
 */
data class VoiceFramePayload(
    val sessionId: UUID = UUID(0L, 0L),
    val sequenceNumber: Int = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val audioData: ByteArray = ByteArray(0)
) {
    constructor(sequenceNumber: Long, audioData: ByteArray) : this(
        sessionId = UUID(0L, 0L),
        sequenceNumber = (sequenceNumber and 0x7FFFFFFF).toInt(),
        timestamp = System.currentTimeMillis(),
        audioData = audioData
    )

    fun serialize(): ByteArray {
        val totalSize = HEADER_SIZE + audioData.size
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(sessionId.mostSignificantBits)
        buffer.putLong(sessionId.leastSignificantBits)
        buffer.putInt(sequenceNumber)
        buffer.putLong(timestamp)
        buffer.put(audioData)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VoiceFramePayload
        return sessionId == other.sessionId &&
                sequenceNumber == other.sequenceNumber &&
                timestamp == other.timestamp &&
                audioData.contentEquals(other.audioData)
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + sequenceNumber
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + audioData.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 28 // 16 (UUID) + 4 (Seq) + 8 (Timestamp)

        fun deserialize(bytes: ByteArray): VoiceFramePayload? {
            if (bytes.size < HEADER_SIZE) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val mostSig = buffer.getLong()
                val leastSig = buffer.getLong()
                val seq = buffer.getInt()
                val ts = buffer.getLong()
                val audioBytes = ByteArray(buffer.remaining())
                buffer.get(audioBytes)

                VoiceFramePayload(
                    sessionId = UUID(mostSig, leastSig),
                    sequenceNumber = seq,
                    timestamp = ts,
                    audioData = audioBytes
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

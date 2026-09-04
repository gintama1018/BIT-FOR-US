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
    val startedAtMs: Long = System.currentTimeMillis(),
    val connectedAtMs: Long? = null,
    val endReason: CallEndReason? = null
)

/**
 * Binary signaling packet for voice call setup and teardown.
 * Total size: exactly 25 bytes.
 */
data class VoiceSignalPayload(
    val action: CallAction,
    val sessionId: UUID,
    val timestamp: Long
) {
    fun serialize(): ByteArray {
        val buffer = ByteBuffer.allocate(PAYLOAD_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(action.code)
        buffer.putLong(sessionId.mostSignificantBits)
        buffer.putLong(sessionId.leastSignificantBits)
        buffer.putLong(timestamp)
        return buffer.array()
    }

    companion object {
        const val PAYLOAD_SIZE = 25

        fun deserialize(bytes: ByteArray): VoiceSignalPayload? {
            if (bytes.size < PAYLOAD_SIZE) return null
            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                val actionCode = buffer.get()
                val action = CallAction.fromCode(actionCode) ?: return null
                val mostSig = buffer.getLong()
                val leastSig = buffer.getLong()
                val timestamp = buffer.getLong()
                VoiceSignalPayload(
                    action = action,
                    sessionId = UUID(mostSig, leastSig),
                    timestamp = timestamp
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/**
 * Binary payload for 1-hop real-time compressed voice frames.
 * Overhead: 28 bytes + audioData size (typically 80 bytes for 20ms ADPCM = 108 bytes total).
 */
data class VoiceFramePayload(
    val sessionId: UUID,
    val sequenceNumber: Int,
    val timestamp: Long,
    val audioData: ByteArray
) {
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

package com.meshwhisper.app.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Binary Packet specification for MeshWhisper offline BLE mesh communication.
 * Total overhead: exactly 56 bytes.
 */
enum class PacketType(val code: Byte) {
    BROADCAST_MESSAGE(0x00),
    DIRECT_MESSAGE(0x01),
    KEY_EXCHANGE(0x02),
    ACK(0x03),
    PEER_ANNOUNCE(0x04),
    MEDIA_INIT(0x05),
    MEDIA_CHUNK(0x06);

    companion object {
        fun fromCode(code: Byte): PacketType? {
            return entries.firstOrNull { it.code == code }
        }
    }
}

data class MeshPacket(
    val type: PacketType,
    val messageId: UUID,
    val senderId: Long,
    val recipientId: Long,
    val ttl: Int,
    val timestamp: Long,
    val payload: ByteArray,
    val authTag: ByteArray = ByteArray(AUTH_TAG_SIZE)
) {
    companion object {
        const val BROADCAST_RECIPIENT_ID: Long = -1L // 0xFFFFFFFFFFFFFFFFL in unsigned
        const val DEFAULT_TTL: Int = 7
        const val MEDIA_TTL: Int = 4 // Lower TTL to protect flood mesh bandwidth
        const val HEADER_SIZE: Int = 40
        const val AUTH_TAG_SIZE: Int = 16
        const val OVERHEAD_SIZE: Int = HEADER_SIZE + AUTH_TAG_SIZE // 56 bytes
        const val MAX_PAYLOAD_SIZE: Int = 2048 // Suitable for BLE MTU fragment/chunking
        const val CHUNK_PAYLOAD_SIZE: Int = 1800 // Payload byte slice per MEDIA_CHUNK

        /**
         * Serializes a MeshPacket into a compact binary byte array.
         */
        fun serialize(packet: MeshPacket): ByteArray {
            val payloadLen = packet.payload.size
            require(payloadLen <= MAX_PAYLOAD_SIZE) { "Payload size ($payloadLen) exceeds MAX ($MAX_PAYLOAD_SIZE)" }
            require(packet.authTag.size == AUTH_TAG_SIZE) { "Auth tag must be exactly $AUTH_TAG_SIZE bytes" }

            val totalSize = OVERHEAD_SIZE + payloadLen
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

            // Header (40 bytes)
            buffer.put(packet.type.code)
            buffer.putLong(packet.messageId.mostSignificantBits)
            buffer.putLong(packet.messageId.leastSignificantBits)
            buffer.putLong(packet.senderId)
            buffer.putLong(packet.recipientId)
            buffer.put((packet.ttl and 0xFF).toByte())
            buffer.putInt((packet.timestamp and 0xFFFFFFFFL).toInt())
            buffer.putShort((payloadLen and 0xFFFF).toShort())

            // Payload (N bytes)
            buffer.put(packet.payload)

            // AEAD Auth Tag (16 bytes)
            buffer.put(packet.authTag)

            return buffer.array()
        }

        /**
         * Parses a raw byte array into a MeshPacket.
         * Returns null if the byte array is malformed or too short.
         */
        fun deserialize(bytes: ByteArray): MeshPacket? {
            if (bytes.size < OVERHEAD_SIZE) return null

            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

                val typeByte = buffer.get()
                val type = PacketType.fromCode(typeByte) ?: return null

                val mostSig = buffer.getLong()
                val leastSig = buffer.getLong()
                val messageId = UUID(mostSig, leastSig)

                val senderId = buffer.getLong()
                val recipientId = buffer.getLong()
                val ttl = buffer.get().toInt() and 0xFF
                val timestamp = buffer.getInt().toLong() and 0xFFFFFFFFL
                val payloadLen = buffer.getShort().toInt() and 0xFFFF

                if (buffer.remaining() < payloadLen + AUTH_TAG_SIZE) {
                    return null
                }

                val payload = ByteArray(payloadLen)
                buffer.get(payload)

                val authTag = ByteArray(AUTH_TAG_SIZE)
                buffer.get(authTag)

                MeshPacket(
                    type = type,
                    messageId = messageId,
                    senderId = senderId,
                    recipientId = recipientId,
                    ttl = ttl,
                    timestamp = timestamp,
                    payload = payload,
                    authTag = authTag
                )
            } catch (e: Exception) {
                null
            }
        }
        fun computeAad(
            type: PacketType,
            messageId: UUID,
            senderId: Long,
            recipientId: Long,
            timestamp: Long
        ): ByteArray {
            val buffer = ByteBuffer.allocate(37).order(ByteOrder.BIG_ENDIAN)
            buffer.put(type.code)
            buffer.putLong(messageId.mostSignificantBits)
            buffer.putLong(messageId.leastSignificantBits)
            buffer.putLong(senderId)
            buffer.putLong(recipientId)
            buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())
            return buffer.array()
        }
    }

    /**
     * Serializes immutable header fields into canonical bytes used as AEAD Additional Authenticated Data (AAD).
     * Binds message type, message ID, sender ID, recipient ID, and timestamp to the AES-GCM authentication tag.
     * Any tampering with these fields by intermediary relay nodes will cause AEAD decryption failure.
     */
    fun getAuthenticatedHeaderBytes(): ByteArray {
        return computeAad(type, messageId, senderId, recipientId, timestamp)
    }

    /**
     * Creates a copy of this packet with decremented TTL for multi-hop relay.
     */
    fun decrementTtl(): MeshPacket {
        return copy(ttl = maxOf(0, ttl - 1))
    }

    val isBroadcast: Boolean
        get() = recipientId == BROADCAST_RECIPIENT_ID

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshPacket

        if (type != other.type) return false
        if (messageId != other.messageId) return false
        if (senderId != other.senderId) return false
        if (recipientId != other.recipientId) return false
        if (ttl != other.ttl) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (!authTag.contentEquals(other.authTag)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + senderId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

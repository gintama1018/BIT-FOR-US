package com.meshwhisper.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Binary Packet type specification for MeshWhisper offline mesh communication.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.2.
 */
enum class PacketType(
    val code: Byte,         // 5-bit type code (0x00 to 0x12)
    val maxTtl: Int,        // Canonical MAX_TTL from frozen spec §2.11
    val isSigned: Boolean,  // Whether this type carries trailing 64-byte hopSignature
    val pastWindowSec: Long // Freshness past-window in seconds from §2.10
) {
    BROADCAST_MESSAGE(0x00, maxTtl = 7, isSigned = true, pastWindowSec = 600L),
    DIRECT_MESSAGE(0x01, maxTtl = 7, isSigned = true, pastWindowSec = 86400L),

    @Deprecated("Retired in vNext protocol (0x22 rejected by codec)")
    KEY_EXCHANGE(0x02, maxTtl = 0, isSigned = false, pastWindowSec = 0L),

    ACK(0x03, maxTtl = 7, isSigned = true, pastWindowSec = 86400L),
    PEER_ANNOUNCE(0x04, maxTtl = 7, isSigned = true, pastWindowSec = 600L),
    MEDIA_INIT(0x05, maxTtl = 4, isSigned = true, pastWindowSec = 600L),
    MEDIA_CHUNK(0x06, maxTtl = 4, isSigned = false, pastWindowSec = 600L),
    AVATAR_REQUEST(0x07, maxTtl = 1, isSigned = false, pastWindowSec = 120L),
    TYPING_INDICATOR(0x08, maxTtl = 1, isSigned = false, pastWindowSec = 30L),
    MEDIA_NACK(0x09, maxTtl = 4, isSigned = true, pastWindowSec = 600L),
    MEDIA_ACK(0x0A, maxTtl = 4, isSigned = true, pastWindowSec = 600L),
    MEDIA_ABORT(0x0B, maxTtl = 4, isSigned = true, pastWindowSec = 600L),
    SOS_MESSAGE(0x0C, maxTtl = 7, isSigned = true, pastWindowSec = 600L),
    PROFILE_UPDATE(0x0D, maxTtl = 7, isSigned = true, pastWindowSec = 600L),
    PROFILE_REQUEST(0x0E, maxTtl = 1, isSigned = false, pastWindowSec = 120L),
    VOICE_CALL_SIGNAL(0x0F, maxTtl = 1, isSigned = false, pastWindowSec = 30L),
    VOICE_FRAME(0x10, maxTtl = 1, isSigned = false, pastWindowSec = 30L),
    LINK_AUTH(0x11, maxTtl = 1, isSigned = false, pastWindowSec = 60L),
    CUSTODY_ACK(0x12, maxTtl = 1, isSigned = true, pastWindowSec = 600L);

    val wireByte: Byte
        get() = ((ResourceLimits.PROTOCOL_VERSION shl 5) or (code.toInt() and 0x1F)).toByte()

    companion object {
        fun fromWireByte(wireByte: Byte): PacketType? {
            val unsigned = wireByte.toInt() and 0xFF
            val version = unsigned ushr 5
            if (version != ResourceLimits.PROTOCOL_VERSION) return null
            val typeCode = (unsigned and 0x1F).toByte()
            if (typeCode == KEY_EXCHANGE.code) return null // Retired 0x22 rejected
            return entries.firstOrNull { it.code == typeCode && it != KEY_EXCHANGE }
        }

        fun fromCode(code: Byte): PacketType? {
            return entries.firstOrNull { it.code == code }
        }
    }
}

/**
 * Common wire frame for MeshWhisper vNext.
 * Overhead: 40 bytes header + 16 bytes AEAD auth tag = exactly 56 bytes.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.1.
 */
data class MeshPacket(
    val type: PacketType,
    val messageId: UUID,
    val senderId: Long,
    val recipientId: Long,
    val ttl: Int,
    val timestamp: Long,
    val payload: ByteArray,
    val authTag: ByteArray = ByteArray(AUTH_TAG_SIZE),
    val protocolVersion: Int = ResourceLimits.PROTOCOL_VERSION
) {
    companion object {
        const val BROADCAST_RECIPIENT_ID: Long = -1L // 0xFFFFFFFFFFFFFFFFL
        const val DEFAULT_TTL: Int = 7
        const val MEDIA_TTL: Int = 4
        const val MEDIA_DIRECT_TTL: Int = 4
        const val HEADER_SIZE: Int = ResourceLimits.HEADER_SIZE // 40
        const val AUTH_TAG_SIZE: Int = ResourceLimits.AUTH_TAG_SIZE // 16
        const val OVERHEAD_SIZE: Int = ResourceLimits.OVERHEAD_SIZE // 56
        const val MAX_PAYLOAD_SIZE: Int = ResourceLimits.MAX_PAYLOAD_SIZE // 2048
        const val MAX_PACKET_SIZE: Int = ResourceLimits.MAX_PACKET_SIZE // 2104
        const val CHUNK_PAYLOAD_SIZE: Int = ResourceLimits.CHUNK_PAYLOAD_SIZE // 320

        const val FUTURE_SKEW_SEC: Long = ResourceLimits.FUTURE_SKEW_SEC // 120s

        /**
         * Serializes a MeshPacket into canonical vNext binary wire format.
         * Type byte is encoded as (protocolVersion << 5) | typeCode.
         * TTL is clamped to MAX_TTL[type].
         */
        fun serialize(packet: MeshPacket): ByteArray {
            val payloadLen = packet.payload.size
            require(payloadLen <= MAX_PAYLOAD_SIZE) { "Payload size ($payloadLen) exceeds MAX ($MAX_PAYLOAD_SIZE)" }
            require(packet.authTag.size == AUTH_TAG_SIZE) { "Auth tag must be exactly $AUTH_TAG_SIZE bytes" }

            val clampedTtl = minOf(packet.ttl, packet.type.maxTtl)
            val totalSize = OVERHEAD_SIZE + payloadLen
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

            // Header (40 bytes)
            val wireTypeByte = ((packet.protocolVersion shl 5) or (packet.type.code.toInt() and 0x1F)).toByte()
            buffer.put(wireTypeByte)
            buffer.putLong(packet.messageId.mostSignificantBits)
            buffer.putLong(packet.messageId.leastSignificantBits)
            buffer.putLong(packet.senderId)
            buffer.putLong(packet.recipientId)
            buffer.put((clampedTtl and 0xFF).toByte())
            buffer.putInt((packet.timestamp and 0xFFFFFFFFL).toInt())
            buffer.putShort((payloadLen and 0xFFFF).toShort())

            // Payload (N bytes)
            buffer.put(packet.payload)

            // AEAD Auth Tag (16 bytes)
            buffer.put(packet.authTag)

            return buffer.array()
        }

        /**
         * Parses a raw byte array into a MeshPacket with strict vNext validation:
         * - protocolVersion == 1
         * - payloadLen <= 2048
         * - totalSize == 56 + payloadLen exactly (no trailing bytes)
         * - rejects retired type 0x22
         * - clamps TTL to MAX_TTL[type]
         * Never throws, returns null on invalid bytes.
         */
        fun deserialize(bytes: ByteArray): MeshPacket? {
            if (bytes.size < OVERHEAD_SIZE || bytes.size > MAX_PACKET_SIZE) return null

            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

                val rawTypeByte = buffer.get()
                val rawTypeInt = rawTypeByte.toInt() and 0xFF
                val version = rawTypeInt ushr 5
                if (version != ResourceLimits.PROTOCOL_VERSION) return null // Reject protocolVersion != 1

                val typeCode = (rawTypeInt and 0x1F).toByte()
                if (typeCode == PacketType.KEY_EXCHANGE.code) return null // Reject retired type 0x22

                val type = PacketType.fromCode(typeCode) ?: return null
                if (type == PacketType.KEY_EXCHANGE) return null

                val mostSig = buffer.getLong()
                val leastSig = buffer.getLong()
                val messageId = UUID(mostSig, leastSig)

                val senderId = buffer.getLong()
                val recipientId = buffer.getLong()
                val rawTtl = buffer.get().toInt() and 0xFF
                val timestamp = buffer.getInt().toLong() and 0xFFFFFFFFL
                val payloadLen = buffer.getShort().toInt() and 0xFFFF

                if (payloadLen > MAX_PAYLOAD_SIZE) return null

                // Strict exact size check: totalSize == 56 + payloadLen exactly (no trailing bytes)
                if (bytes.size != OVERHEAD_SIZE + payloadLen) return null
                if (buffer.remaining() != payloadLen + AUTH_TAG_SIZE) return null

                val payload = ByteArray(payloadLen)
                buffer.get(payload)

                val authTag = ByteArray(AUTH_TAG_SIZE)
                buffer.get(authTag)

                if (buffer.hasRemaining()) return null

                val clampedTtl = minOf(rawTtl, type.maxTtl)

                MeshPacket(
                    type = type,
                    messageId = messageId,
                    senderId = senderId,
                    recipientId = recipientId,
                    ttl = clampedTtl,
                    timestamp = timestamp,
                    payload = payload,
                    authTag = authTag,
                    protocolVersion = version
                )
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Computes 37-byte AEAD Additional Authenticated Data (AAD).
         * Preserved byte-identical to v1:
         * [1B type.code] [16B messageId] [8B senderId] [8B recipientId] [4B timestamp]
         */
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

        /**
         * Computes SHA-256(ciphertext || authTag) for use in SIG_TRANSCRIPT.
         */
        fun computeCiphertextAndTagHash(ciphertext: ByteArray, authTag: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(ciphertext)
            md.update(authTag)
            return md.digest()
        }

        /**
         * Builds the canonical 115-byte SIG_TRANSCRIPT for Ed25519 hop signatures.
         * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.8.
         */
        fun buildSigTranscript(
            purposeTag: Byte = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion: Byte = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte: Byte,
            messageId: UUID,
            senderIdentityHash: ByteArray,
            senderNodeId64: Long,
            recipientNodeId64: Long,
            timestamp: Long,
            payloadLenExcludingSig: Int,
            ciphertextAndTagHash: ByteArray
        ): ByteArray {
            require(senderIdentityHash.size == 32) { "senderIdentityHash must be 32 bytes" }
            require(ciphertextAndTagHash.size == 32) { "ciphertextAndTagHash must be 32 bytes" }
            require(payloadLenExcludingSig in 0..65535) { "payloadLenExcludingSig must fit in u16" }

            val buffer = ByteBuffer.allocate(ResourceLimits.SIG_TRANSCRIPT_SIZE).order(ByteOrder.BIG_ENDIAN)
            buffer.put("MW/SIG/v2".toByteArray(Charsets.US_ASCII)) // 9 bytes
            buffer.put(0x00.toByte())                               // 1 byte separator
            buffer.put(purposeTag)                                  // 1 byte (0x02 CONTENT | 0x03 IBC | 0x04 LINK)
            buffer.put(protocolVersion)                             // 1 byte (u8, value 1)
            buffer.put(packetTypeByte)                              // 1 byte (wire type byte, e.g. 0x24)
            buffer.putLong(messageId.mostSignificantBits)           // 8 bytes
            buffer.putLong(messageId.leastSignificantBits)          // 8 bytes
            buffer.put(senderIdentityHash)                          // 32 bytes
            buffer.putLong(senderNodeId64)                          // 8 bytes
            buffer.putLong(recipientNodeId64)                       // 8 bytes
            buffer.putInt((timestamp and 0xFFFFFFFFL).toInt())      // 4 bytes
            buffer.putShort((payloadLenExcludingSig and 0xFFFF).toShort()) // 2 bytes
            buffer.put(ciphertextAndTagHash)                        // 32 bytes

            return buffer.array() // exactly 115 bytes
        }
    }

    /**
     * Serializes immutable header fields into canonical bytes used as AEAD Additional Authenticated Data (AAD).
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
        if (protocolVersion != other.protocolVersion) return false
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
        result = 31 * result + protocolVersion
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

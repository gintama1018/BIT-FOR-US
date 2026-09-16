package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Random
import java.util.UUID

/**
 * Phase P1 Core Protocol Codec Verification Test Suite.
 * Validates canonical wire serialization/deserialization, strict parser validation,
 * golden vectors (T-WIRE-01..18), trailing-byte rejection (T-WIRE-20),
 * oversized payloadLen rejection (T-WIRE-21), fuzzing (T-FUZZ-01),
 * round-trip properties (T-PROP-01), 115-byte transcript (T-PROP-02),
 * and TTL clamping (T-PROP-04).
 *
 * Normative references:
 * - NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.8, §2.9, §2.10, §2.11, §3.1, §3.2
 * - NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §9.1, §10 (Phase 1)
 * - NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.4
 */
class CoreProtocolCodecTest {

    private val fixedMsgId = UUID(0x1122334455667788L, -0x6655443322110100L) // msb: 1122334455667788, lsb: 99aabbccddeeff00
    private val fixedSenderId = 0x0102030405060708L
    private val fixedRecipientId = 0x090a0b0c0d0e0f10L
    private val fixedTimestamp = 0x66846600L // 1720000000
    private val fixedPayload = "test".toByteArray(Charsets.US_ASCII)
    private val defaultAuthTag = byteArrayOf(
        0xa0.toByte(), 0xa1.toByte(), 0xa2.toByte(), 0xa3.toByte(),
        0xa4.toByte(), 0xa5.toByte(), 0xa6.toByte(), 0xa7.toByte(),
        0xa8.toByte(), 0xa9.toByte(), 0xaa.toByte(), 0xab.toByte(),
        0xac.toByte(), 0xad.toByte(), 0xae.toByte(), 0xaf.toByte()
    )
    private val zeroAuthTag = ByteArray(16)

    private fun loadVectorHex(name: String): String {
        val stream = javaClass.getResourceAsStream("/vectors/$name")
            ?: throw IllegalArgumentException("Vector resource not found: $name")
        return stream.bufferedReader().use { it.readText().trim() }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val result = ByteArray(clean.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = clean.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    // --- GOLDEN VECTORS T-WIRE-01..18 ---

    private data class VectorSpec(
        val filename: String,
        val type: PacketType,
        val expectedWireTypeByte: Byte,
        val expectedTtl: Int,
        val authTag: ByteArray
    )

    private val activeVectors = listOf(
        VectorSpec("t_wire_01.hex", PacketType.BROADCAST_MESSAGE, 0x20, 7, defaultAuthTag),
        VectorSpec("t_wire_02.hex", PacketType.DIRECT_MESSAGE, 0x21, 7, defaultAuthTag),
        VectorSpec("t_wire_03.hex", PacketType.ACK, 0x23, 7, defaultAuthTag),
        VectorSpec("t_wire_04.hex", PacketType.PEER_ANNOUNCE, 0x24, 7, defaultAuthTag),
        VectorSpec("t_wire_05.hex", PacketType.MEDIA_INIT, 0x25, 4, defaultAuthTag),
        VectorSpec("t_wire_06.hex", PacketType.MEDIA_CHUNK, 0x26, 4, defaultAuthTag),
        VectorSpec("t_wire_07.hex", PacketType.AVATAR_REQUEST, 0x27, 1, defaultAuthTag),
        VectorSpec("t_wire_08.hex", PacketType.TYPING_INDICATOR, 0x28, 1, defaultAuthTag),
        VectorSpec("t_wire_09.hex", PacketType.MEDIA_NACK, 0x29, 4, defaultAuthTag),
        VectorSpec("t_wire_10.hex", PacketType.MEDIA_ACK, 0x2a, 4, defaultAuthTag),
        VectorSpec("t_wire_11.hex", PacketType.MEDIA_ABORT, 0x2b, 4, defaultAuthTag),
        VectorSpec("t_wire_12.hex", PacketType.SOS_MESSAGE, 0x2c, 7, defaultAuthTag),
        VectorSpec("t_wire_13.hex", PacketType.PROFILE_UPDATE, 0x2d, 7, defaultAuthTag),
        VectorSpec("t_wire_14.hex", PacketType.PROFILE_REQUEST, 0x2e, 1, defaultAuthTag),
        VectorSpec("t_wire_15.hex", PacketType.VOICE_CALL_SIGNAL, 0x2f, 1, defaultAuthTag),
        VectorSpec("t_wire_16.hex", PacketType.VOICE_FRAME, 0x30, 1, defaultAuthTag),
        VectorSpec("t_wire_17.hex", PacketType.LINK_AUTH, 0x31, 1, zeroAuthTag),
        VectorSpec("t_wire_18.hex", PacketType.CUSTODY_ACK, 0x32, 1, defaultAuthTag)
    )

    @Test
    fun testGoldenVectorsT_WIRE_01_to_18() {
        for (spec in activeVectors) {
            val hex = loadVectorHex(spec.filename)
            val expectedBytes = hexToBytes(hex)

            val packet = MeshPacket(
                type = spec.type,
                messageId = fixedMsgId,
                senderId = fixedSenderId,
                recipientId = fixedRecipientId,
                ttl = spec.expectedTtl,
                timestamp = fixedTimestamp,
                payload = fixedPayload,
                authTag = spec.authTag,
                protocolVersion = 1
            )

            // 1. Serialization byte-exact check
            val serialized = MeshPacket.serialize(packet)
            assertThat(bytesToHex(serialized)).isEqualTo(hex)
            assertThat(serialized).isEqualTo(expectedBytes)

            // 2. Deserialization correctness
            val deserialized = MeshPacket.deserialize(expectedBytes)
            assertThat(deserialized).isNotNull()
            assertThat(deserialized).isEqualTo(packet)
            assertThat(deserialized!!.type).isEqualTo(spec.type)
            assertThat(deserialized.ttl).isEqualTo(spec.expectedTtl)
            assertThat(deserialized.protocolVersion).isEqualTo(1)
        }
    }

    @Test
    fun testGoldenVectorsSingleByteMutationFailsOrMutates() {
        // Tampering any byte in the golden wire frame must either fail decode (null)
        // or mutate the resulting field/be clamped, never silently accept an unaltered packet.
        for (spec in activeVectors) {
            val wireBytes = hexToBytes(loadVectorHex(spec.filename))
            for (i in wireBytes.indices) {
                val mutated = wireBytes.copyOf()
                mutated[i] = (mutated[i].toInt() xor 0xFF).toByte()

                val result = MeshPacket.deserialize(mutated)
                if (result != null) {
                    if (i == 33) {
                        // Byte 33 is TTL. The codec strictly clamps raw TTL to MAX_TTL[type].
                        assertThat(result.ttl).isEqualTo(spec.expectedTtl)
                    } else {
                        // Any other byte mutated must change the deserialized packet
                        val reEncoded = MeshPacket.serialize(result)
                        assertThat(reEncoded.contentEquals(wireBytes)).isFalse()
                    }
                }
            }
        }
    }

    // --- T-WIRE-20: TRAILING-BYTE REJECTION ---

    @Test
    fun testTrailingBytesRejectedT_WIRE_20() {
        for (spec in activeVectors) {
            val validWire = hexToBytes(loadVectorHex(spec.filename))

            // 1 trailing byte
            val plusOne = validWire.copyOf(validWire.size + 1)
            plusOne[validWire.size] = 0x00
            assertThat(MeshPacket.deserialize(plusOne)).isNull()

            // 16 trailing bytes
            val plusSixteen = validWire.copyOf(validWire.size + 16)
            assertThat(MeshPacket.deserialize(plusSixteen)).isNull()
        }
    }

    // --- T-WIRE-21: OVERSIZED PAYLOADLEN REJECTION ---

    @Test
    fun testOversizedPayloadLenRejectedT_WIRE_21() {
        val validWire = hexToBytes(loadVectorHex("t_wire_01.hex"))

        // Create wire frame with payloadLen = 2049 (> MAX_PAYLOAD_SIZE 2048)
        val oversizedWire = validWire.copyOf(ResourceLimits.OVERHEAD_SIZE + 2049)
        val buffer = ByteBuffer.wrap(oversizedWire).order(ByteOrder.BIG_ENDIAN)
        buffer.position(38) // payloadLen offset
        buffer.putShort(2049.toShort())

        assertThat(MeshPacket.deserialize(oversizedWire)).isNull()

        // Create wire frame declaring 0xFFFF (65535) payloadLen
        val maxU16Wire = validWire.copyOf()
        val buf2 = ByteBuffer.wrap(maxU16Wire).order(ByteOrder.BIG_ENDIAN)
        buf2.position(38)
        buf2.putShort(0xFFFF.toShort())
        assertThat(MeshPacket.deserialize(maxU16Wire)).isNull()
    }

    // --- T-PROP-01: ROUND-TRIP PROPERTY TEST ---

    @Test
    fun testProtocolRoundTripPropertyT_PROP_01() {
        val random = Random(42L)
        val testPayloadSizes = listOf(0, 1, 4, 16, 64, 320, 1024, 2048)

        for (type in PacketType.entries) {
            if (type == PacketType.KEY_EXCHANGE) continue // Retired

            for (payloadSize in testPayloadSizes) {
                val payload = ByteArray(payloadSize)
                random.nextBytes(payload)

                val authTag = if (type == PacketType.LINK_AUTH) ByteArray(16) else ByteArray(16).apply { random.nextBytes(this) }
                val initialTtl = random.nextInt(type.maxTtl + 1)
                val msgId = UUID.randomUUID()
                val senderId = random.nextLong()
                val recipientId = random.nextLong()
                val timestamp = (random.nextInt(Int.MAX_VALUE).toLong()) and 0xFFFFFFFFL

                val packet = MeshPacket(
                    type = type,
                    messageId = msgId,
                    senderId = senderId,
                    recipientId = recipientId,
                    ttl = initialTtl,
                    timestamp = timestamp,
                    payload = payload,
                    authTag = authTag,
                    protocolVersion = 1
                )

                val wire = MeshPacket.serialize(packet)
                assertThat(wire.size).isEqualTo(ResourceLimits.OVERHEAD_SIZE + payloadSize)

                val parsed = MeshPacket.deserialize(wire)
                assertThat(parsed).isNotNull()
                assertThat(parsed).isEqualTo(packet)
            }
        }
    }

    // --- T-PROP-02: DETERMINISTIC 115-BYTE TRANSCRIPT TEST ---

    @Test
    fun testDeterministic115ByteTranscriptT_PROP_02() {
        val messageId = UUID(0x1234567890abcdefL, -0x1234567890abcdefL)
        val senderIdentityHash = ByteArray(32) { (it + 1).toByte() }
        val senderNodeId64 = 0x0102030405060708L
        val recipientNodeId64 = 0x090a0b0c0d0e0f10L
        val timestamp = 1720000000L
        val payloadLenExcludingSig = 4
        val ciphertextAndTagHash = ByteArray(32) { (it * 2).toByte() }

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = 1,
            packetTypeByte = 0x24.toByte(), // PEER_ANNOUNCE
            messageId = messageId,
            senderIdentityHash = senderIdentityHash,
            senderNodeId64 = senderNodeId64,
            recipientNodeId64 = recipientNodeId64,
            timestamp = timestamp,
            payloadLenExcludingSig = payloadLenExcludingSig,
            ciphertextAndTagHash = ciphertextAndTagHash
        )

        // Exactly 115 bytes
        assertThat(transcript.size).isEqualTo(ResourceLimits.SIG_TRANSCRIPT_SIZE)
        assertThat(transcript.size).isEqualTo(115)

        // Deterministic: second call gives byte-for-byte identical output
        val transcript2 = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = 1,
            packetTypeByte = 0x24.toByte(),
            messageId = messageId,
            senderIdentityHash = senderIdentityHash,
            senderNodeId64 = senderNodeId64,
            recipientNodeId64 = recipientNodeId64,
            timestamp = timestamp,
            payloadLenExcludingSig = payloadLenExcludingSig,
            ciphertextAndTagHash = ciphertextAndTagHash
        )
        assertThat(transcript).isEqualTo(transcript2)

        // Layout verification
        val buf = ByteBuffer.wrap(transcript).order(ByteOrder.BIG_ENDIAN)
        val prefix = ByteArray(9)
        buf.get(prefix)
        assertThat(String(prefix, Charsets.US_ASCII)).isEqualTo("MW/SIG/v2")
        assertThat(buf.get()).isEqualTo(0x00.toByte()) // Separator
        assertThat(buf.get()).isEqualTo(ResourceLimits.PURPOSE_CONTENT) // 0x02
        assertThat(buf.get()).isEqualTo(1.toByte()) // protocolVersion u8
        assertThat(buf.get()).isEqualTo(0x24.toByte()) // packetTypeByte
        assertThat(buf.getLong()).isEqualTo(messageId.mostSignificantBits)
        assertThat(buf.getLong()).isEqualTo(messageId.leastSignificantBits)

        val parsedHash = ByteArray(32)
        buf.get(parsedHash)
        assertThat(parsedHash).isEqualTo(senderIdentityHash)

        assertThat(buf.getLong()).isEqualTo(senderNodeId64)
        assertThat(buf.getLong()).isEqualTo(recipientNodeId64)
        assertThat(buf.getInt().toLong() and 0xFFFFFFFFL).isEqualTo(timestamp)
        assertThat(buf.getShort().toInt() and 0xFFFF).isEqualTo(payloadLenExcludingSig)

        val parsedCtHash = ByteArray(32)
        buf.get(parsedCtHash)
        assertThat(parsedCtHash).isEqualTo(ciphertextAndTagHash)
        assertThat(buf.hasRemaining()).isFalse()

        // Helper method verification
        val dummyCiphertext = "test_cipher".toByteArray(Charsets.UTF_8)
        val dummyTag = ByteArray(16) { 0x55.toByte() }
        val computedHash = MeshPacket.computeCiphertextAndTagHash(dummyCiphertext, dummyTag)
        assertThat(computedHash.size).isEqualTo(32)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(dummyCiphertext)
        md.update(dummyTag)
        assertThat(computedHash).isEqualTo(md.digest())
    }

    // --- T-PROP-04: TTL CLAMP PROPERTY TEST ---

    @Test
    fun testTtlClampedPropertyT_PROP_04() {
        for (type in PacketType.entries) {
            if (type == PacketType.KEY_EXCHANGE) continue

            for (rawTtl in 0..255) {
                val wire = ByteBuffer.allocate(ResourceLimits.OVERHEAD_SIZE).order(ByteOrder.BIG_ENDIAN).apply {
                    put(type.wireByte)
                    putLong(1L) // msgId msb
                    putLong(2L) // msgId lsb
                    putLong(3L) // senderId
                    putLong(4L) // recipientId
                    put((rawTtl and 0xFF).toByte())
                    putInt(1000)
                    putShort(0) // payloadLen = 0
                    put(ByteArray(16)) // authTag
                }.array()

                val packet = MeshPacket.deserialize(wire)
                assertThat(packet).isNotNull()
                assertThat(packet!!.ttl).isAtMost(type.maxTtl)
                assertThat(packet.ttl).isEqualTo(minOf(rawTtl, type.maxTtl))
            }
        }
    }

    // --- T-FUZZ-01: PARSER FUZZING ---

    @Test
    fun testParserFuzzNeverThrowsT_FUZZ_01() {
        val random = Random(99999L)
        val iterations = 100_000

        for (i in 0 until iterations) {
            // Test lengths from 0 to 2200 bytes (spanning underflow, normal, overflow)
            val len = random.nextInt(2200)
            val bytes = ByteArray(len)
            random.nextBytes(bytes)

            // Codec must never throw an unhandled exception and must not leak allocations
            val packet = MeshPacket.deserialize(bytes)
            if (packet != null) {
                // If it parsed, all invariants must hold
                assertThat(packet.protocolVersion).isEqualTo(1)
                assertThat(packet.type).isNotEqualTo(PacketType.KEY_EXCHANGE)
                assertThat(packet.payload.size).isAtMost(ResourceLimits.MAX_PAYLOAD_SIZE)
                assertThat(packet.authTag.size).isEqualTo(ResourceLimits.AUTH_TAG_SIZE)
                assertThat(packet.ttl).isAtMost(packet.type.maxTtl)
                assertThat(bytes.size).isEqualTo(ResourceLimits.OVERHEAD_SIZE + packet.payload.size)
            }
        }
    }

    // --- PARSER STRICT REJECTION CHECKS ---

    @Test
    fun testRejectRetiredKeyExchange0x22() {
        val wire = hexToBytes(loadVectorHex("t_wire_01.hex"))
        // Wire type byte for retired 0x22: (1 << 5) | 0x02 = 0x22
        wire[0] = 0x22
        assertThat(MeshPacket.deserialize(wire)).isNull()
        assertThat(PacketType.fromWireByte(0x22)).isNull()
    }

    @Test
    fun testRejectV1PacketsVersionZero() {
        // v1 type bytes are 0x00 to 0x1F (protocolVersion == 0)
        for (v1Code in 0x00..0x1F) {
            val wire = hexToBytes(loadVectorHex("t_wire_01.hex"))
            wire[0] = v1Code.toByte()
            assertThat(MeshPacket.deserialize(wire)).isNull()
            assertThat(PacketType.fromWireByte(v1Code.toByte())).isNull()
        }
    }

    @Test
    fun testRejectInvalidProtocolVersions() {
        // Protocol versions 2 to 7 should be rejected
        for (version in 2..7) {
            val wireByte = ((version shl 5) or 0x01).toByte()
            val wire = hexToBytes(loadVectorHex("t_wire_01.hex"))
            wire[0] = wireByte
            assertThat(MeshPacket.deserialize(wire)).isNull()
            assertThat(PacketType.fromWireByte(wireByte)).isNull()
        }
    }

    @Test
    fun testTruncatedPacketsRejected() {
        val wire = hexToBytes(loadVectorHex("t_wire_01.hex"))
        for (truncateLen in 0 until ResourceLimits.OVERHEAD_SIZE) {
            val truncated = wire.copyOf(truncateLen)
            assertThat(MeshPacket.deserialize(truncated)).isNull()
        }
    }

    // --- AAD COMPUTATION PRESERVATION ---

    @Test
    fun testComputeAadByteIdenticalToV1() {
        val msgId = UUID(0x0123456789abcdefL, -0x102030405060708L)
        val senderId = 0x1122334455667788L
        val recipientId = 0x2233445566778899L
        val timestamp = 1720000000L

        val aad = MeshPacket.computeAad(
            PacketType.DIRECT_MESSAGE,
            msgId,
            senderId,
            recipientId,
            timestamp
        )

        // Exactly 37 bytes
        assertThat(aad.size).isEqualTo(37)

        // Exact layout: [1B type.code] [16B msgId] [8B senderId] [8B recipientId] [4B timestamp]
        val buf = ByteBuffer.wrap(aad).order(ByteOrder.BIG_ENDIAN)
        assertThat(buf.get()).isEqualTo(PacketType.DIRECT_MESSAGE.code) // 0x01
        assertThat(buf.getLong()).isEqualTo(msgId.mostSignificantBits)
        assertThat(buf.getLong()).isEqualTo(msgId.leastSignificantBits)
        assertThat(buf.getLong()).isEqualTo(senderId)
        assertThat(buf.getLong()).isEqualTo(recipientId)
        assertThat(buf.getInt().toLong() and 0xFFFFFFFFL).isEqualTo(timestamp)
        assertThat(buf.hasRemaining()).isFalse()
    }

    // --- CANONICAL TABLES AND LIMITS ---

    @Test
    fun testCanonicalMaxTtlTable() {
        assertThat(PacketType.BROADCAST_MESSAGE.maxTtl).isEqualTo(7)
        assertThat(PacketType.DIRECT_MESSAGE.maxTtl).isEqualTo(7)
        assertThat(PacketType.ACK.maxTtl).isEqualTo(7)
        assertThat(PacketType.PEER_ANNOUNCE.maxTtl).isEqualTo(7)
        assertThat(PacketType.SOS_MESSAGE.maxTtl).isEqualTo(7)
        assertThat(PacketType.PROFILE_UPDATE.maxTtl).isEqualTo(7)

        assertThat(PacketType.MEDIA_INIT.maxTtl).isEqualTo(4)
        assertThat(PacketType.MEDIA_CHUNK.maxTtl).isEqualTo(4)
        assertThat(PacketType.MEDIA_NACK.maxTtl).isEqualTo(4)
        assertThat(PacketType.MEDIA_ACK.maxTtl).isEqualTo(4)
        assertThat(PacketType.MEDIA_ABORT.maxTtl).isEqualTo(4)

        assertThat(PacketType.AVATAR_REQUEST.maxTtl).isEqualTo(1)
        assertThat(PacketType.PROFILE_REQUEST.maxTtl).isEqualTo(1)
        assertThat(PacketType.TYPING_INDICATOR.maxTtl).isEqualTo(1)
        assertThat(PacketType.VOICE_CALL_SIGNAL.maxTtl).isEqualTo(1)
        assertThat(PacketType.VOICE_FRAME.maxTtl).isEqualTo(1)
        assertThat(PacketType.LINK_AUTH.maxTtl).isEqualTo(1)
        assertThat(PacketType.CUSTODY_ACK.maxTtl).isEqualTo(1)
    }

    @Test
    fun testCanonicalFreshnessWindowTable() {
        assertThat(ResourceLimits.FUTURE_SKEW_SEC).isEqualTo(120L)
        assertThat(MeshPacket.FUTURE_SKEW_SEC).isEqualTo(120L)

        assertThat(PacketType.DIRECT_MESSAGE.pastWindowSec).isEqualTo(86400L)
        assertThat(PacketType.ACK.pastWindowSec).isEqualTo(86400L)

        assertThat(PacketType.PEER_ANNOUNCE.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.BROADCAST_MESSAGE.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.SOS_MESSAGE.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.PROFILE_UPDATE.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.CUSTODY_ACK.pastWindowSec).isEqualTo(600L)

        assertThat(PacketType.MEDIA_INIT.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.MEDIA_CHUNK.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.MEDIA_NACK.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.MEDIA_ACK.pastWindowSec).isEqualTo(600L)
        assertThat(PacketType.MEDIA_ABORT.pastWindowSec).isEqualTo(600L)

        assertThat(PacketType.LINK_AUTH.pastWindowSec).isEqualTo(60L)

        assertThat(PacketType.PROFILE_REQUEST.pastWindowSec).isEqualTo(120L)
        assertThat(PacketType.AVATAR_REQUEST.pastWindowSec).isEqualTo(120L)

        assertThat(PacketType.VOICE_CALL_SIGNAL.pastWindowSec).isEqualTo(30L)
        assertThat(PacketType.VOICE_FRAME.pastWindowSec).isEqualTo(30L)
        assertThat(PacketType.TYPING_INDICATOR.pastWindowSec).isEqualTo(30L)
    }

    @Test
    fun testSignedTypesProperty() {
        // Types that must carry a 64-byte hopSignature outside AEAD
        assertThat(PacketType.BROADCAST_MESSAGE.isSigned).isTrue()
        assertThat(PacketType.DIRECT_MESSAGE.isSigned).isTrue()
        assertThat(PacketType.ACK.isSigned).isTrue()
        assertThat(PacketType.PEER_ANNOUNCE.isSigned).isTrue()
        assertThat(PacketType.MEDIA_INIT.isSigned).isTrue()
        assertThat(PacketType.MEDIA_NACK.isSigned).isTrue()
        assertThat(PacketType.MEDIA_ACK.isSigned).isTrue()
        assertThat(PacketType.MEDIA_ABORT.isSigned).isTrue()
        assertThat(PacketType.SOS_MESSAGE.isSigned).isTrue()
        assertThat(PacketType.PROFILE_UPDATE.isSigned).isTrue()
        assertThat(PacketType.CUSTODY_ACK.isSigned).isTrue()

        // Unsigned types (ttl=1 or exempt like MEDIA_CHUNK)
        assertThat(PacketType.MEDIA_CHUNK.isSigned).isFalse()
        assertThat(PacketType.AVATAR_REQUEST.isSigned).isFalse()
        assertThat(PacketType.TYPING_INDICATOR.isSigned).isFalse()
        assertThat(PacketType.PROFILE_REQUEST.isSigned).isFalse()
        assertThat(PacketType.VOICE_CALL_SIGNAL.isSigned).isFalse()
        assertThat(PacketType.VOICE_FRAME.isSigned).isFalse()
        assertThat(PacketType.LINK_AUTH.isSigned).isFalse()
    }
}

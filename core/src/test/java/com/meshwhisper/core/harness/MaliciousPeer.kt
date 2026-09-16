package com.meshwhisper.core.harness

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Adversarial participant with a hostile API.
 * Not a mock. Generates real adversarial packets and exercising attacks identified in audit findings.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
class MaliciousPeer(
    val nodeId: String = "mallory",
    var transport: FakeTransport? = null
) {
    val keyPair = PureCryptoEngine.generateX25519KeyPair()
    val privateKey: ByteArray = keyPair.first
    val publicKey: ByteArray = keyPair.second
    val signingPublicKey: ByteArray = PureCryptoEngine.deriveSigningPublicKey(privateKey)
    var currentSpoofedNodeId: Long? = null

    fun spoofNodeId(victim: Long): MaliciousPeer {
        currentSpoofedNodeId = victim
        return this
    }

    fun replay(captured: ByteArray): ByteArray {
        return captured.clone()
    }

    fun replayWithFreshMessageId(captured: ByteArray, newId: UUID = UUID.randomUUID()): ByteArray {
        val packet = MeshPacket.deserialize(captured) ?: return captured
        val freshPacket = packet.copy(messageId = newId)
        return MeshPacket.serialize(freshPacket)
    }

    fun replayWithTimestamp(captured: ByteArray, newTimestamp: Long): ByteArray {
        val packet = MeshPacket.deserialize(captured) ?: return captured
        val updatedPacket = packet.copy(timestamp = newTimestamp)
        return MeshPacket.serialize(updatedPacket)
    }

    fun omitSignature(packetBytes: ByteArray): ByteArray {
        val packet = MeshPacket.deserialize(packetBytes) ?: return packetBytes
        // Strip trailing 64 bytes if present
        val stripped = if (packet.payload.size >= 64) {
            packet.payload.copyOfRange(0, packet.payload.size - 64)
        } else {
            packet.payload
        }
        return MeshPacket.serialize(packet.copy(payload = stripped))
    }

    fun signWithForeignKey(packetBytes: ByteArray, foreignPrivKey: ByteArray): ByteArray {
        val packet = MeshPacket.deserialize(packetBytes) ?: return packetBytes
        val unsigned = if (packet.payload.size >= 64) {
            packet.payload.copyOfRange(0, packet.payload.size - 64)
        } else {
            packet.payload
        }
        val foreignSig = PureCryptoEngine.sign(foreignPrivKey, unsigned)
        val newPayload = ByteArray(unsigned.size + foreignSig.size)
        System.arraycopy(unsigned, 0, newPayload, 0, unsigned.size)
        System.arraycopy(foreignSig, 0, newPayload, unsigned.size, foreignSig.size)
        return MeshPacket.serialize(packet.copy(payload = newPayload))
    }

    fun liftSignature(fromPacketBytes: ByteArray, intoPacketBytes: ByteArray): ByteArray {
        val fromPacket = MeshPacket.deserialize(fromPacketBytes) ?: return intoPacketBytes
        val intoPacket = MeshPacket.deserialize(intoPacketBytes) ?: return intoPacketBytes
        if (fromPacket.payload.size < 64) return intoPacketBytes
        val liftedSig = fromPacket.payload.copyOfRange(fromPacket.payload.size - 64, fromPacket.payload.size)

        val targetUnsigned = if (intoPacket.payload.size >= 64) {
            intoPacket.payload.copyOfRange(0, intoPacket.payload.size - 64)
        } else {
            intoPacket.payload
        }
        val splicedPayload = ByteArray(targetUnsigned.size + liftedSig.size)
        System.arraycopy(targetUnsigned, 0, splicedPayload, 0, targetUnsigned.size)
        System.arraycopy(liftedSig, 0, splicedPayload, targetUnsigned.size, liftedSig.size)
        return MeshPacket.serialize(intoPacket.copy(payload = splicedPayload))
    }

    fun mutateTranscriptField(payloadBytes: ByteArray, fieldOffset: Int, value: Byte): ByteArray {
        val copy = payloadBytes.clone()
        if (fieldOffset in copy.indices) {
            copy[fieldOffset] = value
        }
        return copy
    }

    fun truncate(packetBytes: ByteArray, n: Int): ByteArray {
        return packetBytes.copyOf(maxOf(0, packetBytes.size - n))
    }

    fun pad(packetBytes: ByteArray, n: Int): ByteArray {
        val padded = ByteArray(packetBytes.size + n)
        System.arraycopy(packetBytes, 0, padded, 0, packetBytes.size)
        return padded
    }

    fun zeroAuthTag(packetBytes: ByteArray): ByteArray {
        val packet = MeshPacket.deserialize(packetBytes) ?: return packetBytes
        return MeshPacket.serialize(packet.copy(authTag = ByteArray(16)))
    }

    fun oversizeFrame(bytes: Int): ByteArray {
        return ByteArray(bytes) { 0xAA.toByte() }
    }

    fun oversizePayloadLen(packetBytes: ByteArray, bogusLen: Int = 3000): ByteArray {
        val packet = MeshPacket.deserialize(packetBytes) ?: return packetBytes
        val raw = MeshPacket.serialize(packet)
        if (raw.size >= 40) {
            val buf = ByteBuffer.wrap(raw)
            buf.position(38) // Offset of payloadLen in MeshPacket header
            buf.putShort(bogusLen.toShort())
        }
        return raw
    }

    fun floodUniqueMessageIds(count: Int): List<ByteArray> {
        return (0 until count).map {
            val p = MeshPacket(
                type = PacketType.DIRECT_MESSAGE,
                messageId = UUID.randomUUID(),
                senderId = currentSpoofedNodeId ?: 0xDEADBEEFL,
                recipientId = 0xCAFEBABEL,
                ttl = 7,
                timestamp = System.currentTimeMillis() / 1000L,
                payload = "Flood message $it".toByteArray(Charsets.UTF_8),
                authTag = ByteArray(16)
            )
            MeshPacket.serialize(p)
        }
    }

    fun claimEdge(a: Long, b: Long): ByteArray {
        val buffer = ByteBuffer.allocate(17)
        buffer.put(0x01.toByte()) // 1 neighbor
        buffer.putLong(b)
        val dummySig = ByteArray(64)
        val payload = ByteArray(buffer.capacity() + dummySig.size)
        System.arraycopy(buffer.array(), 0, payload, 0, buffer.capacity())
        val p = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = UUID.randomUUID(),
            senderId = a,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = 7,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = payload,
            authTag = ByteArray(16)
        )
        return MeshPacket.serialize(p)
    }

    fun claimEdgeUnilaterally(victim: Long): ByteArray {
        return claimEdge(currentSpoofedNodeId ?: 0x11112222L, victim)
    }

    var greyholeDropRatio: Double = 0.0
    fun greyhole(dropRatio: Double) {
        greyholeDropRatio = dropRatio
    }

    fun halfOpenConnect(count: Int): List<String> {
        return (0 until count).map { "half_open_peer_$it" }
    }

    fun hijackNodeId(victim: Long): Long {
        currentSpoofedNodeId = victim
        return victim
    }

    fun rollbackKeyVersion(currentVersion: Int): Int {
        return maxOf(0, currentVersion - 1)
    }

    fun rollbackKeyVersion(currentVersion: Long): Long {
        return maxOf(0L, currentVersion - 1L)
    }

    fun rollbackAnnounceCounter(currentCounter: Long): Long {
        return maxOf(0L, currentCounter - 1L)
    }

    fun equivocateEkAtSameKeyVersion(keyVersion: Int): Pair<ByteArray, ByteArray> =
        equivocateEkAtSameKeyVersion(keyVersion.toLong())

    fun equivocateEkAtSameKeyVersion(keyVersion: Long = 1L): Pair<ByteArray, ByteArray> {
        val ek1 = PureCryptoEngine.generateX25519KeyPair().second
        val ek2 = PureCryptoEngine.generateX25519KeyPair().second
        return Pair(ek1, ek2)
    }

    fun collideNodeId64(victimNodeId: Long): ByteArray {
        // Return public key claiming same nodeId
        return publicKey.clone()
    }

    fun oversizeChunk(bytes: Int): ByteArray {
        return ByteArray(bytes) { 0x55.toByte() }
    }

    fun declareSmallSendLarge(): ByteArray {
        // Declares small length in payload header but attaches oversized body
        val buf = ByteBuffer.allocate(4 + 1024)
        buf.putInt(64) // Claims 64 bytes
        buf.put(ByteArray(1024) { 0x77.toByte() })
        return buf.array()
    }

    fun raceChunkIndex(idx: Int, junk: ByteArray): ByteArray {
        val buf = ByteBuffer.allocate(4 + junk.size)
        buf.putInt(idx)
        buf.put(junk)
        return buf.array()
    }

    fun replayVoiceSeq(seq: Int): ByteArray {
        val buf = ByteBuffer.allocate(4 + 32)
        buf.putInt(seq)
        buf.put(ByteArray(32) { 0x12.toByte() })
        return buf.array()
    }

    fun replayLinkConfirm(captured: ByteArray): ByteArray {
        return captured.clone()
    }

    fun sendV1Packet(): ByteArray {
        // v1 packet with protocolVersion 0 (type byte < 0x20)
        val p = MeshPacket(
            type = PacketType.DIRECT_MESSAGE, // type.code is 0x02
            messageId = UUID.randomUUID(),
            senderId = 0x12345678L,
            recipientId = 0x87654321L,
            ttl = 7,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = "v1 legacy test".toByteArray(Charsets.UTF_8),
            authTag = ByteArray(16)
        )
        return MeshPacket.serialize(p)
    }

    fun announceWithLocationAtTtl7(): ByteArray {
        val p = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = UUID.randomUUID(),
            senderId = currentSpoofedNodeId ?: 0xABCDL,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = 7, // Invalid in vNext: location with TTL 7 must drop (C-03)
            timestamp = System.currentTimeMillis() / 1000L,
            payload = "LOCATION_DATA".toByteArray(Charsets.UTF_8),
            authTag = ByteArray(16)
        )
        return MeshPacket.serialize(p)
    }

    fun pathTraversalFileName(): String {
        return "../../evil_payload.apk"
    }
}

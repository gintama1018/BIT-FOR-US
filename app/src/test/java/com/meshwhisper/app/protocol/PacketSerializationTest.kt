package com.meshwhisper.app.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class PacketSerializationTest {

    @Test
    fun testSerializationAndDeserialization() {
        val msgId = UUID.randomUUID()
        val senderId = 0x1122334455667788L
        val recipientId = 0x123456789ABCDEF0L
        val ttl = 7
        val timestamp = 1720000000L
        val payload = "Hello Mesh Network!".toByteArray(Charsets.UTF_8)
        val authTag = ByteArray(16) { it.toByte() }

        val original = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = senderId,
            recipientId = recipientId,
            ttl = ttl,
            timestamp = timestamp,
            payload = payload,
            authTag = authTag
        )

        val serialized = MeshPacket.serialize(original)

        // Verify total size = 56 bytes overhead + payload length
        assertThat(serialized.size).isEqualTo(56 + payload.size)

        val deserialized = MeshPacket.deserialize(serialized)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized).isEqualTo(original)
        assertThat(deserialized?.type).isEqualTo(PacketType.DIRECT_MESSAGE)
        assertThat(deserialized?.messageId).isEqualTo(msgId)
        assertThat(deserialized?.senderId).isEqualTo(senderId)
        assertThat(deserialized?.recipientId).isEqualTo(recipientId)
        assertThat(deserialized?.ttl).isEqualTo(ttl)
        assertThat(deserialized?.timestamp).isEqualTo(timestamp)
        assertThat(deserialized?.payload).isEqualTo(payload)
        assertThat(deserialized?.authTag).isEqualTo(authTag)
    }

    @Test
    fun testTtlDecrement() {
        val original = MeshPacket(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = 123L,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = 5,
            timestamp = 1000L,
            payload = byteArrayOf(1, 2, 3)
        )

        val decremented = original.decrementTtl()
        assertThat(decremented.ttl).isEqualTo(4)

        val zeroTtl = decremented.copy(ttl = 0).decrementTtl()
        assertThat(zeroTtl.ttl).isEqualTo(0)
    }

    @Test
    fun testTruncatedPacketReturnsNull() {
        val bytes = ByteArray(30) // Less than minimum 56 bytes
        val result = MeshPacket.deserialize(bytes)
        assertThat(result).isNull()
    }

    @Test
    fun testAvatarRequestAndTypingIndicatorSerialization() {
        val avatarReq = MeshPacket(
            type = PacketType.AVATAR_REQUEST,
            messageId = UUID.randomUUID(),
            senderId = 100L,
            recipientId = 200L,
            ttl = 7,
            timestamp = 1000L,
            payload = ByteArray(0)
        )
        val serializedReq = MeshPacket.serialize(avatarReq)
        val deserializedReq = MeshPacket.deserialize(serializedReq)
        assertThat(deserializedReq?.type).isEqualTo(PacketType.AVATAR_REQUEST)

        val typing = MeshPacket(
            type = PacketType.TYPING_INDICATOR,
            messageId = UUID.randomUUID(),
            senderId = 100L,
            recipientId = 200L,
            ttl = 1,
            timestamp = 1000L,
            payload = byteArrayOf(1)
        )
        val serializedTyping = MeshPacket.serialize(typing)
        val deserializedTyping = MeshPacket.deserialize(serializedTyping)
        assertThat(deserializedTyping?.type).isEqualTo(PacketType.TYPING_INDICATOR)
        assertThat(deserializedTyping?.payload).isEqualTo(byteArrayOf(1))
    }

    @Test
    fun testMediaReliabilityPacketsSerialization() {
        val msgId = UUID.randomUUID()
        val nackPacket = MeshPacket(
            type = PacketType.MEDIA_NACK,
            messageId = msgId,
            senderId = 100L,
            recipientId = 200L,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = 1000L,
            payload = byteArrayOf(0, 1, 0, 5)
        )
        val serNack = MeshPacket.serialize(nackPacket)
        val deserNack = MeshPacket.deserialize(serNack)
        assertThat(deserNack?.type).isEqualTo(PacketType.MEDIA_NACK)
        assertThat(deserNack?.ttl).isEqualTo(MeshPacket.MEDIA_DIRECT_TTL)

        val ackPacket = MeshPacket(
            type = PacketType.MEDIA_ACK,
            messageId = msgId,
            senderId = 100L,
            recipientId = 200L,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = 1000L,
            payload = ByteArray(16)
        )
        val serAck = MeshPacket.serialize(ackPacket)
        val deserAck = MeshPacket.deserialize(serAck)
        assertThat(deserAck?.type).isEqualTo(PacketType.MEDIA_ACK)

        val abortPacket = MeshPacket(
            type = PacketType.MEDIA_ABORT,
            messageId = msgId,
            senderId = 100L,
            recipientId = 200L,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = 1000L,
            payload = ByteArray(16)
        )
        val serAbort = MeshPacket.serialize(abortPacket)
        val deserAbort = MeshPacket.deserialize(serAbort)
        assertThat(deserAbort?.type).isEqualTo(PacketType.MEDIA_ABORT)
    }
}

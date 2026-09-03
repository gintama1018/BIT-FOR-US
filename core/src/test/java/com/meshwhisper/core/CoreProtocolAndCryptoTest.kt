package com.meshwhisper.core

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.router.LruDedupCache
import org.junit.Test
import java.util.UUID

class CoreProtocolAndCryptoTest {

    @Test
    fun testMeshPacketSerializationAndDeserialization() {
        val msgId = UUID.randomUUID()
        val payload = "Hello Cross-Platform Mesh!".toByteArray(Charsets.UTF_8)
        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = 0x1122334455667788L,
            recipientId = 0x778899AABBCCDDEEL,
            ttl = 7,
            timestamp = 1720000000L,
            payload = payload,
            authTag = ByteArray(16) { 0xAA.toByte() }
        )

        val serialized = MeshPacket.serialize(packet)
        assertThat(serialized.size).isEqualTo(56 + payload.size)

        val deserialized = MeshPacket.deserialize(serialized)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized).isEqualTo(packet)
    }

    @Test
    fun testAadComputation() {
        val msgId = UUID.randomUUID()
        val senderId = 0x12345678L
        val recipientId = 0x87654321L
        val ts = 1720000000L

        val aad = MeshPacket.computeAad(PacketType.SOS_MESSAGE, msgId, senderId, recipientId, ts)
        assertThat(aad.size).isEqualTo(37)
    }

    @Test
    fun testPureCryptoEngineX25519AndAead() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val (bobPriv, bobPub) = PureCryptoEngine.generateX25519KeyPair()

        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)
        val bobNodeId = PureCryptoEngine.deriveNodeId(bobPub)
        assertThat(aliceNodeId).isNotEqualTo(bobNodeId)

        val ts = 1720000000L
        val aliceSessionKey = PureCryptoEngine.derivePeerSessionKey(alicePriv, bobPub, ts)
        val bobSessionKey = PureCryptoEngine.derivePeerSessionKey(bobPriv, alicePub, ts)
        assertThat(aliceSessionKey).isEqualTo(bobSessionKey)

        val msgId = UUID.randomUUID()
        val plaintext = "Secret SAR Coordinates".toByteArray(Charsets.UTF_8)
        val aad = MeshPacket.computeAad(PacketType.DIRECT_MESSAGE, msgId, aliceNodeId, bobNodeId, ts)

        val encResult = PureCryptoEngine.encrypt(plaintext, msgId, aliceSessionKey, aad)
        assertThat(encResult.ciphertext.size).isEqualTo(12 + plaintext.size) // 12-byte CSPRNG IV prefix + ciphertext
        assertThat(encResult.authTag.size).isEqualTo(16)

        val decrypted = PureCryptoEngine.decrypt(encResult.ciphertext, encResult.authTag, msgId, bobSessionKey, aad)
        assertThat(String(decrypted, Charsets.UTF_8)).isEqualTo("Secret SAR Coordinates")
    }

    @Test
    fun testLruDedupCacheEviction() {
        val cache = LruDedupCache<String, Long>(maxEntries = 3)
        cache.put("msg1", 100L)
        cache.put("msg2", 200L)
        cache.put("msg3", 300L)

        assertThat(cache.size()).isEqualTo(3)
        assertThat(cache.containsKey("msg1")).isTrue()

        // Access msg1 so msg2 becomes oldest
        cache.get("msg1")
        cache.put("msg4", 400L)

        assertThat(cache.size()).isEqualTo(3)
        assertThat(cache.containsKey("msg2")).isFalse() // Evicted
        assertThat(cache.containsKey("msg1")).isTrue()
        assertThat(cache.containsKey("msg3")).isTrue()
        assertThat(cache.containsKey("msg4")).isTrue()
    }
}

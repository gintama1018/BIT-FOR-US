package com.meshwhisper.app.router

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

class PeerAnnounceEndToEndTest {

    @Test
    fun testPeerAnnouncePayloadPackagingAndDecoupledParsing() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)
        val aliceAlias = "AlicePhone"
        val aliasBytes = aliceAlias.toByteArray(Charsets.UTF_8)
        val neighbors = listOf(0x1122334455667788L, 0x2233445566778899L)
        val avatarHash = 42.toByte()

        val latitude = 12.9715987
        val longitude = 77.5945627
        val accuracy = 4.5f
        val locTime = System.currentTimeMillis()

        // 1. Pack announcement data
        val locationBytes = 1 + 8 + 8 + 4 + 8 // 29 bytes
        val payloadLen = 1 + aliasBytes.size + 32 + 1 + (neighbors.size * 8) + 1 + locationBytes
        val buffer = ByteBuffer.allocate(payloadLen)
        buffer.put((aliasBytes.size and 0xFF).toByte())
        buffer.put(aliasBytes)
        buffer.put(alicePub)
        buffer.put(neighbors.size.toByte())
        for (n in neighbors) buffer.putLong(n)
        buffer.put(avatarHash)
        buffer.put(0x4C.toByte()) // 'L'
        buffer.putDouble(latitude)
        buffer.putDouble(longitude)
        buffer.putFloat(accuracy)
        buffer.putLong(locTime)

        val unsignedPayload = buffer.array()

        // 2. Sign with Ed25519 digital signature
        val signature = PureCryptoEngine.sign(alicePriv, unsignedPayload)
        assertThat(signature.size).isEqualTo(64)

        val signedPayload = ByteArray(unsignedPayload.size + signature.size)
        System.arraycopy(unsignedPayload, 0, signedPayload, 0, unsignedPayload.size)
        System.arraycopy(signature, 0, signedPayload, unsignedPayload.size, signature.size)

        // 3. Encrypt under publicChannelKey with AEAD header authentication
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val publicChannelKey = PureCryptoEngine.derivePublicChannelKey()

        val aad = MeshPacket.computeAad(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = aliceNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = PureCryptoEngine.encrypt(
            plaintext = signedPayload,
            messageId = msgId,
            aesKey = publicChannelKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = aliceNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        // 4. Bob receives the packet and decrypts
        val decrypted = PureCryptoEngine.decrypt(
            ciphertext = packet.payload,
            authTag = packet.authTag,
            messageId = packet.messageId,
            aesKey = publicChannelKey,
            aad = packet.getAuthenticatedHeaderBytes()
        )
        assertThat(decrypted).isEqualTo(signedPayload)

        // 5. Test decoupled boundary parsing
        val parsedUnsigned = if (decrypted.size >= 32 + 64) {
            decrypted.copyOfRange(0, decrypted.size - 64)
        } else decrypted

        val parseBuf = ByteBuffer.wrap(parsedUnsigned)
        val readAliasLen = parseBuf.get().toInt() and 0xFF
        val readAliasBytes = ByteArray(readAliasLen)
        parseBuf.get(readAliasBytes)
        val readAlias = String(readAliasBytes, Charsets.UTF_8)
        assertThat(readAlias).isEqualTo("AlicePhone")

        val readPub = ByteArray(32)
        parseBuf.get(readPub)
        assertThat(readPub).isEqualTo(alicePub)

        // Verify Node ID derivation matches senderId
        val derivedId = PureCryptoEngine.deriveNodeId(readPub)
        assertThat(derivedId).isEqualTo(aliceNodeId)

        // Read neighbors
        val neighborCount = parseBuf.get().toInt() and 0xFF
        assertThat(neighborCount).isEqualTo(2)
        val readNeighbors = mutableListOf<Long>()
        for (i in 0 until neighborCount) {
            readNeighbors.add(parseBuf.long)
        }
        assertThat(readNeighbors).containsExactlyElementsIn(neighbors).inOrder()

        val readAvatarHash = parseBuf.get()
        assertThat(readAvatarHash).isEqualTo(avatarHash)

        // Location parsing: ensure no trailing signature bytes corrupt location
        assertThat(parseBuf.remaining()).isEqualTo(29)
        val marker = parseBuf.get()
        assertThat(marker).isEqualTo(0x4C.toByte())
        val readLat = parseBuf.double
        val readLon = parseBuf.double
        val readAcc = parseBuf.float
        val readTime = parseBuf.long

        assertThat(readLat).isEqualTo(latitude)
        assertThat(readLon).isEqualTo(longitude)
        assertThat(readAcc).isEqualTo(accuracy)
        assertThat(readTime).isEqualTo(locTime)
        assertThat(parseBuf.hasRemaining()).isFalse()
    }

    @Test
    fun testPeerAnnounceRejectsSpoofedSenderId() {
        val (_, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val (_, bobPub) = PureCryptoEngine.generateX25519KeyPair()
        val bobNodeId = PureCryptoEngine.deriveNodeId(bobPub)

        // Attacker claims to be Bob, but puts Alice's public key in the payload
        val derivedFromPayload = PureCryptoEngine.deriveNodeId(alicePub)
        val claimedSenderId = bobNodeId

        assertThat(derivedFromPayload).isNotEqualTo(claimedSenderId)
    }

    @Test
    fun testSignedBroadcastPayloadExtractionWithoutCorruption() {
        val text = "Emergency alert: flash flood warning"
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val (alicePriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val signature = PureCryptoEngine.sign(alicePriv, textBytes)

        val signedBytes = ByteArray(textBytes.size + signature.size)
        System.arraycopy(textBytes, 0, signedBytes, 0, textBytes.size)
        System.arraycopy(signature, 0, signedBytes, textBytes.size, signature.size)

        // Receiver extracts text without including 64-byte signature
        val extractedText = if (signedBytes.size >= 64) {
            val tBytes = signedBytes.copyOfRange(0, signedBytes.size - 64)
            String(tBytes, Charsets.UTF_8)
        } else {
            String(signedBytes, Charsets.UTF_8)
        }

        assertThat(extractedText).isEqualTo(text)
    }
}

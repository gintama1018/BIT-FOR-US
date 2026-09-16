package com.meshwhisper.core.harness

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Production-bytes → production-ingest integration test harness.
 * Connects real wire serialization to ingest verification.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2 and 02_VNEXT_IMPLEMENTATION_PLAN.md §10.
 */
object ProductionPacketHarness {

    sealed class IngestResult {
        data class Accepted(val packet: MeshPacket, val decryptedPayload: ByteArray) : IngestResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Accepted
                return packet == other.packet && decryptedPayload.contentEquals(other.decryptedPayload)
            }

            override fun hashCode(): Int {
                var result = packet.hashCode()
                result = 31 * result + decryptedPayload.contentHashCode()
                return result
            }
        }

        data class Rejected(val reason: String, val packet: MeshPacket? = null) : IngestResult()
    }

    /**
     * Builds wire bytes for a v1 DIRECT_MESSAGE using exact production encryption and framing.
     */
    fun buildV1DirectMessage(
        senderPriv: ByteArray,
        senderPub: ByteArray,
        recipientPub: ByteArray,
        text: String,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
        messageId: UUID = UUID.randomUUID(),
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): ByteArray {
        val senderNodeId = PureCryptoEngine.deriveNodeId(senderPub)
        val recipientNodeId = PureCryptoEngine.deriveNodeId(recipientPub)
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(senderPriv, recipientPub, timestampSec)
        val textBytes = text.toByteArray(Charsets.UTF_8)

        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderNodeId,
            recipientId = recipientNodeId,
            timestamp = timestampSec
        )

        val enc = PureCryptoEngine.encrypt(
            plaintext = textBytes,
            messageId = messageId,
            aesKey = sessionKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderNodeId,
            recipientId = recipientNodeId,
            ttl = ttl,
            timestamp = timestampSec,
            payload = enc.ciphertext,
            authTag = enc.authTag
        )

        return MeshPacket.serialize(packet)
    }

    /**
     * Builds wire bytes for a v1 PEER_ANNOUNCE using exact production announcePresence() formatting.
     * Contains the F-0 flaw where the X25519 public key is transmitted in the payload alongside
     * an Ed25519 signature.
     */
    fun buildV1PeerAnnounce(
        senderPriv: ByteArray,
        senderPub: ByteArray,
        alias: String = "AlicePhone",
        neighbors: List<Long> = emptyList(),
        avatarHash: Byte = 0,
        timestampSec: Long = System.currentTimeMillis() / 1000L,
        messageId: UUID = UUID.randomUUID(),
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): ByteArray {
        val senderNodeId = PureCryptoEngine.deriveNodeId(senderPub)
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val payloadLen = 1 + aliasBytes.size + senderPub.size + 1 + (neighbors.size * 8) + 1
        val buf = ByteBuffer.allocate(payloadLen)
        buf.put((aliasBytes.size and 0xFF).toByte())
        buf.put(aliasBytes)
        buf.put(senderPub) // 32-byte X25519 public key
        buf.put(neighbors.size.toByte())
        for (n in neighbors) buf.putLong(n)
        buf.put(avatarHash)

        val unsignedPayload = buf.array()

        // Generate Ed25519 digital signature over the announcement data
        val signature = PureCryptoEngine.sign(senderPriv, unsignedPayload)

        val signedPayload = ByteArray(unsignedPayload.size + signature.size)
        System.arraycopy(unsignedPayload, 0, signedPayload, 0, unsignedPayload.size)
        System.arraycopy(signature, 0, signedPayload, unsignedPayload.size, signature.size)

        val aad = MeshPacket.computeAad(
            type = PacketType.PEER_ANNOUNCE,
            messageId = messageId,
            senderId = senderNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestampSec
        )

        val enc = PureCryptoEngine.encrypt(
            plaintext = signedPayload,
            messageId = messageId,
            aesKey = PureCryptoEngine.derivePublicChannelKey(),
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = messageId,
            senderId = senderNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = ttl,
            timestamp = timestampSec,
            payload = enc.ciphertext,
            authTag = enc.authTag
        )

        return MeshPacket.serialize(packet)
    }

    /**
     * Authenticated ingest processor for raw wire bytes.
     * Exercises the production authentication pipeline:
     * - Decrypts AEAD payload
     * - Verifies header authentication binding
     * - Verifies cryptographic digital signatures against sender public key
     */
    fun ingest(
        wireBytes: ByteArray,
        receiverPriv: ByteArray? = null,
        knownSenderPub: ByteArray? = null
    ): IngestResult {
        val packet = MeshPacket.deserialize(wireBytes)
            ?: return IngestResult.Rejected("Failed to parse packet header")

        when (packet.type) {
            PacketType.DIRECT_MESSAGE -> {
                if (receiverPriv == null || knownSenderPub == null) {
                    return IngestResult.Rejected("Missing receiver keys for DM", packet)
                }
                val sessionKey = PureCryptoEngine.derivePeerSessionKey(receiverPriv, knownSenderPub, packet.timestamp)
                return try {
                    val decrypted = PureCryptoEngine.decrypt(
                        ciphertext = packet.payload,
                        authTag = packet.authTag,
                        messageId = packet.messageId,
                        aesKey = sessionKey,
                        aad = packet.getAuthenticatedHeaderBytes()
                    )
                    IngestResult.Accepted(packet, decrypted)
                } catch (e: Exception) {
                    IngestResult.Rejected("AEAD decryption failed: ${e.message}", packet)
                }
            }

            PacketType.PEER_ANNOUNCE -> {
                val decrypted = try {
                    PureCryptoEngine.decrypt(
                        ciphertext = packet.payload,
                        authTag = packet.authTag,
                        messageId = packet.messageId,
                        aesKey = PureCryptoEngine.derivePublicChannelKey(),
                        aad = packet.getAuthenticatedHeaderBytes()
                    )
                } catch (e: Exception) {
                    return IngestResult.Rejected("Failed to decrypt PEER_ANNOUNCE: ${e.message}", packet)
                }

                if (decrypted.size < 32 + 64) {
                    return IngestResult.Rejected("Payload too short for signature", packet)
                }

                val unsignedPayload = decrypted.copyOfRange(0, decrypted.size - 64)
                val signature = decrypted.copyOfRange(decrypted.size - 64, decrypted.size)

                val buf = ByteBuffer.wrap(unsignedPayload)
                val aliasLen = buf.get().toInt() and 0xFF
                if (buf.remaining() < aliasLen + 32) {
                    return IngestResult.Rejected("Malformed announce body", packet)
                }
                buf.position(1 + aliasLen)
                val announcedPubKey = ByteArray(32)
                buf.get(announcedPubKey)

                // Verify Ed25519 signature against announced public key.
                // In v1, announcedPubKey is an X25519 key (Finding F-0).
                // PureCryptoEngine.verifySignature expects an Ed25519 public key.
                val isSigValid = PureCryptoEngine.verifySignature(announcedPubKey, unsignedPayload, signature)
                return if (isSigValid) {
                    IngestResult.Accepted(packet, unsignedPayload)
                } else {
                    IngestResult.Rejected(
                        "Ed25519 signature verification failed against announced public key (F-0 mismatch)",
                        packet
                    )
                }
            }

            else -> {
                return IngestResult.Accepted(packet, packet.payload)
            }
        }
    }
}

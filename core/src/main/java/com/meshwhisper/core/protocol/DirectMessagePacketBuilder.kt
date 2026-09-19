package com.meshwhisper.core.protocol

import com.meshwhisper.core.crypto.PureCryptoEngine
import java.security.MessageDigest
import java.util.UUID

/**
 * Shared :core authoritative builder for constructing signed and encrypted vNext DIRECT_MESSAGE packets.
 * Used by both Android and Desktop to ensure 100% protocol and cryptographic parity.
 *
 * Implements:
 * 1. Peer session key derivation via HKDF: derivePeerSessionKey(myPriv, peerPub, timestampSec)
 * 2. Standard AAD binding via computeAad
 * 3. AES-256-GCM authenticated payload encryption
 * 4. Content transcript building with PURPOSE_CONTENT
 * 5. Ed25519 hop signature over transcript
 * 6. Canonical MeshPacket assembly
 */
object DirectMessagePacketBuilder {

    fun build(
        senderNodeId64: Long,
        senderIdentityHash: ByteArray,
        senderPrivateKey: ByteArray,
        recipientNodeId64: Long,
        peerPublicKey: ByteArray,
        plaintext: ByteArray,
        timestampSec: Long,
        messageId: UUID = UUID.randomUUID(),
        ttl: Int = MeshPacket.DEFAULT_TTL
    ): MeshPacket {
        require(senderIdentityHash.size == 32) { "senderIdentityHash must be 32 bytes" }
        require(senderPrivateKey.size == 32) { "senderPrivateKey must be 32 bytes" }
        require(peerPublicKey.size == 32) { "peerPublicKey must be 32 bytes" }

        // 1. Derive peer session key
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(
            myPrivateKey = senderPrivateKey,
            peerPublicKeyBytes = peerPublicKey,
            timestampSec = timestampSec
        )

        // 2. Compute canonical AAD
        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderNodeId64,
            recipientId = recipientNodeId64,
            timestamp = timestampSec
        )

        // 3. Encrypt plaintext
        val encResult = PureCryptoEngine.encrypt(
            plaintext = plaintext,
            messageId = messageId,
            aesKey = sessionKey,
            aad = aad
        )

        // 4. SHA-256 digest of ciphertext || authTag
        val md = MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        // 5. Build standard content transcript
        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.DIRECT_MESSAGE.wireByte,
            messageId = messageId,
            senderIdentityHash = senderIdentityHash,
            senderNodeId64 = senderNodeId64,
            recipientNodeId64 = recipientNodeId64,
            timestamp = timestampSec,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )

        // 6. Sign transcript with identity seed
        val hopSig = PureCryptoEngine.sign(
            identitySeed = senderPrivateKey,
            data = transcript
        )
        val fullPayload = encResult.ciphertext + hopSig

        // 7. Assemble final wire packet
        return MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderNodeId64,
            recipientId = recipientNodeId64,
            ttl = ttl,
            timestamp = timestampSec,
            payload = fullPayload,
            authTag = encResult.authTag
        )
    }
}

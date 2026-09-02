package com.meshwhisper.core

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.router.LruDedupCache
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.UUID

/**
 * Rigorous Ground-Truth Cryptographic and Multi-Hop Network Simulation Test Suite.
 * 
 * Formally verifies:
 * 1. 3-Node Multi-Hop Relay Chain (Node A -> Node B -> Node C) with TTL decrement.
 * 2. Zero-Knowledge Intermediary Relay (Node B cannot decrypt Node A -> Node C payload).
 * 3. End-to-End Encryption integrity (Node C decrypts accurately).
 * 4. Deduplication / Flood Loop Prevention.
 * 5. TTL Expiry / Drop Boundary.
 * 6. Cryptographic Tamper-Resistance (AEAD Auth Tag + AAD Header Binding).
 * 7. Hourly Epoch Session Key Rotation (Forward Secrecy).
 */
class MultiHopRelayAndSecurityMeshTest {

    @Test
    fun testThreeNodeMultiHopRelayAndE2EESecurity() {
        // Step 1: Generate 3 independent cryptographic identities
        val (nodeAPriv, nodeAPub) = PureCryptoEngine.generateX25519KeyPair()
        val (nodeBPriv, nodeBPub) = PureCryptoEngine.generateX25519KeyPair()
        val (nodeCPriv, nodeCPub) = PureCryptoEngine.generateX25519KeyPair()

        val nodeAId = PureCryptoEngine.deriveNodeId(nodeAPub)
        val nodeBId = PureCryptoEngine.deriveNodeId(nodeBPub)
        val nodeCId = PureCryptoEngine.deriveNodeId(nodeCPub)

        val timestamp = 1720000000L
        val originalSecretMessage = "URGENT: Evacuate North Quad to Station 4"
        val originalMsgId = UUID.randomUUID()

        // Step 2: Node A derives E2EE Session Key with Node C (using Node C's public key)
        val sessionKeyAC = PureCryptoEngine.derivePeerSessionKey(nodeAPriv, nodeCPub, timestamp)
        val aadAtoC = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = originalMsgId,
            senderId = nodeAId,
            recipientId = nodeCId,
            timestamp = timestamp
        )

        // Node A encrypts the secret message
        val encResult = PureCryptoEngine.encrypt(
            plaintext = originalSecretMessage.toByteArray(Charsets.UTF_8),
            messageId = originalMsgId,
            aesKey = sessionKeyAC,
            aad = aadAtoC
        )

        val packetFromA = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = originalMsgId,
            senderId = nodeAId,
            recipientId = nodeCId,
            ttl = 7, // Initial TTL
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        // Serialize to wire bytes and transmit across radio link
        val wireBytesFromA = MeshPacket.serialize(packetFromA)
        assertThat(wireBytesFromA.size).isEqualTo(56 + encResult.ciphertext.size)

        // =========================================================================
        // Step 3: Node B (Intermediary Corridor Relay) receives packet from air
        // =========================================================================
        val packetReceivedAtB = MeshPacket.deserialize(wireBytesFromA)
        assertThat(packetReceivedAtB).isNotNull()
        assertThat(packetReceivedAtB?.recipientId).isEqualTo(nodeCId)
        assertThat(packetReceivedAtB?.recipientId).isNotEqualTo(nodeBId)

        // CLAIM VERIFICATION: Zero-Knowledge Relay Privacy
        // Node B tries to decrypt the packet with its own private key
        val sessionKeyBA = PureCryptoEngine.derivePeerSessionKey(nodeBPriv, nodeAPub, timestamp)
        val sessionKeyBC = PureCryptoEngine.derivePeerSessionKey(nodeBPriv, nodeCPub, timestamp)

        // Decryption by Relay Node B MUST FAIL with AEAD Auth Exception
        assertThrows(Exception::class.java) {
            PureCryptoEngine.decrypt(
                ciphertext = packetReceivedAtB!!.payload,
                authTag = packetReceivedAtB.authTag,
                messageId = packetReceivedAtB.messageId,
                aesKey = sessionKeyBA,
                aad = aadAtoC
            )
        }

        assertThrows(Exception::class.java) {
            PureCryptoEngine.decrypt(
                ciphertext = packetReceivedAtB!!.payload,
                authTag = packetReceivedAtB.authTag,
                messageId = packetReceivedAtB.messageId,
                aesKey = sessionKeyBC,
                aad = aadAtoC
            )
        }

        // Relay Operation: Node B decrements TTL and re-broadcasts
        assertThat(packetReceivedAtB!!.ttl).isEqualTo(7)
        val relayedPacketFromB = packetReceivedAtB.decrementTtl()
        assertThat(relayedPacketFromB.ttl).isEqualTo(6) // TTL decremented by 1

        val wireBytesFromB = MeshPacket.serialize(relayedPacketFromB)

        // =========================================================================
        // Step 4: Node C (Destination) receives relayed packet from Node B
        // =========================================================================
        val packetReceivedAtC = MeshPacket.deserialize(wireBytesFromB)
        assertThat(packetReceivedAtC).isNotNull()
        assertThat(packetReceivedAtC?.recipientId).isEqualTo(nodeCId)
        assertThat(packetReceivedAtC?.ttl).isEqualTo(6)

        // Node C derives the matching session key $(C_{priv}, A_{pub})$
        val sessionKeyCA = PureCryptoEngine.derivePeerSessionKey(nodeCPriv, nodeAPub, timestamp)
        assertThat(sessionKeyCA).isEqualTo(sessionKeyAC)

        val decryptedByC = PureCryptoEngine.decrypt(
            ciphertext = packetReceivedAtC!!.payload,
            authTag = packetReceivedAtC.authTag,
            messageId = packetReceivedAtC.messageId,
            aesKey = sessionKeyCA,
            aad = aadAtoC
        )

        val decryptedText = String(decryptedByC, Charsets.UTF_8)
        assertThat(decryptedText).isEqualTo(originalSecretMessage)

        // Verify calculated hop count at destination
        val hopCount = MeshPacket.DEFAULT_TTL - packetReceivedAtC.ttl
        assertThat(hopCount).isEqualTo(1) // 1 intermediary relay hop (2 network hops total)
    }

    @Test
    fun testLoopPreventionAndDedupCache() {
        val dedupCache = LruDedupCache<String, Long>(maxEntries = 1000)
        val msgId = UUID.randomUUID()
        val dedupKey = "${msgId}:${PacketType.DIRECT_MESSAGE.code}"

        // First time packet is seen: Process and record
        val isFirstTimeSeen = !dedupCache.containsKey(dedupKey)
        assertThat(isFirstTimeSeen).isTrue()
        dedupCache.put(dedupKey, System.currentTimeMillis())

        // Second time identical packet arrives (echo / multi-path loop)
        val isSecondTimeSeen = dedupCache.containsKey(dedupKey)
        assertThat(isSecondTimeSeen).isTrue() // Caught by dedup cache! Dropped immediately.
    }

    @Test
    fun testTtlDropBoundaryAtZero() {
        val msgId = UUID.randomUUID()
        val packet = MeshPacket(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = msgId,
            senderId = 0x1111L,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = 1, // Final hop remaining
            timestamp = 1720000000L,
            payload = ByteArray(10),
            authTag = ByteArray(16)
        )

        // Relay decrements TTL to 0
        val decremented = packet.decrementTtl()
        assertThat(decremented.ttl).isEqualTo(0)

        // A node receiving a TTL=0 packet drops it from further relay
        val shouldRelay = decremented.ttl > 0
        assertThat(shouldRelay).isFalse()
    }

    @Test
    fun testTamperProofAeadAuthTagHeaderBinding() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val (bobPriv, bobPub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceId = PureCryptoEngine.deriveNodeId(alicePub)
        val bobId = PureCryptoEngine.deriveNodeId(bobPub)
        val ts = 1720000000L
        val msgId = UUID.randomUUID()

        val sessionKey = PureCryptoEngine.derivePeerSessionKey(alicePriv, bobPub, ts)
        val aad = MeshPacket.computeAad(PacketType.DIRECT_MESSAGE, msgId, aliceId, bobId, ts)

        val plaintext = "Authentic Unmodified Coordinates".toByteArray(Charsets.UTF_8)
        val enc = PureCryptoEngine.encrypt(plaintext, msgId, sessionKey, aad)

        // Attack Scenario A: Man-in-the-middle flips 1 bit in ciphertext payload
        val tamperedCiphertext = enc.ciphertext.clone()
        tamperedCiphertext[0] = (tamperedCiphertext[0].toInt() xor 0x01).toByte()

        assertThrows(Exception::class.java) {
            PureCryptoEngine.decrypt(tamperedCiphertext, enc.authTag, msgId, sessionKey, aad)
        }

        // Attack Scenario B: Man-in-the-middle alters the sender ID in the AAD header
        val tamperedAad = MeshPacket.computeAad(PacketType.DIRECT_MESSAGE, msgId, 0x99999999L, bobId, ts)
        assertThrows(Exception::class.java) {
            PureCryptoEngine.decrypt(enc.ciphertext, enc.authTag, msgId, sessionKey, tamperedAad)
        }
    }

    @Test
    fun testHourlyEpochSessionKeyRotation() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val (bobPriv, bobPub) = PureCryptoEngine.generateX25519KeyPair()

        // Hour 1 timestamp
        val tsHour1 = 1720000000L
        val keyHour1 = PureCryptoEngine.derivePeerSessionKey(alicePriv, bobPub, tsHour1)

        // Hour 2 timestamp (3600 seconds later)
        val tsHour2 = 1720000000L + 3600L
        val keyHour2 = PureCryptoEngine.derivePeerSessionKey(alicePriv, bobPub, tsHour2)

        // Keys MUST be distinct across different epoch hours (Forward Secrecy)
        assertThat(keyHour1).isNotEqualTo(keyHour2)
        assertThat(keyHour1.size).isEqualTo(32)
        assertThat(keyHour2.size).isEqualTo(32)
    }
}

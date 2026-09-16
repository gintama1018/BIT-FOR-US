package com.meshwhisper.core.harness

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import org.junit.Test
import java.nio.ByteBuffer

/**
 * T-INT-01: Production bytes through production ingest integration test.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.4.
 *
 * Current v1 behavior:
 * - Production-built v1 DIRECT_MESSAGE → Accepted
 * - Production-built v1 PEER_ANNOUNCE → Rejected because of the existing F-0 X25519/Ed25519 mismatch
 *
 * This test passes by asserting the expected current v1 behavior, proving that the harness
 * works and reproducing Finding F-0 before the Phase P1-P3 protocol overhaul.
 */
class TInt01Test {

    @Test
    fun testV1DirectMessageIsAcceptedThroughIngest() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val (bobPriv, bobPub) = PureCryptoEngine.generateX25519KeyPair()

        val expectedText = "Hello Bob! This is an authenticated message from Alice."
        val wireBytes = ProductionPacketHarness.buildV1DirectMessage(
            senderPriv = alicePriv,
            senderPub = alicePub,
            recipientPub = bobPub,
            text = expectedText
        )

        assertThat(wireBytes).isNotEmpty()

        val result = ProductionPacketHarness.ingest(
            wireBytes = wireBytes,
            receiverPriv = bobPriv,
            knownSenderPub = alicePub
        )

        assertThat(result).isInstanceOf(ProductionPacketHarness.IngestResult.Accepted::class.java)
        val accepted = result as ProductionPacketHarness.IngestResult.Accepted
        assertThat(accepted.packet.type).isEqualTo(PacketType.DIRECT_MESSAGE)
        assertThat(String(accepted.decryptedPayload, Charsets.UTF_8)).isEqualTo(expectedText)
    }

    @Test
    fun testV1PeerAnnounceIsRejectedDueToF0Mismatch() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()

        // 1. Build production v1 PEER_ANNOUNCE packet wire bytes (same as MeshRouter.announcePresence)
        val wireBytes = ProductionPacketHarness.buildV1PeerAnnounce(
            senderPriv = alicePriv,
            senderPub = alicePub,
            alias = "StationAlpha"
        )

        assertThat(wireBytes).isNotEmpty()

        // 2. Feed production wire bytes into production ingest
        val result = ProductionPacketHarness.ingest(wireBytes)

        // 3. Current v1 behavior: PEER_ANNOUNCE is rejected by authenticated ingest because of F-0
        assertThat(result).isInstanceOf(ProductionPacketHarness.IngestResult.Rejected::class.java)
        val rejected = result as ProductionPacketHarness.IngestResult.Rejected
        assertThat(rejected.reason).contains("F-0 mismatch")

        // 4. Directly demonstrate and document the exact F-0 cryptographic mechanism:
        val packet = MeshPacket.deserialize(wireBytes)
        assertThat(packet).isNotNull()
        val decrypted = PureCryptoEngine.decrypt(
            ciphertext = packet!!.payload,
            authTag = packet.authTag,
            messageId = packet.messageId,
            aesKey = PureCryptoEngine.derivePublicChannelKey(),
            aad = packet.getAuthenticatedHeaderBytes()
        )
        val unsignedPayload = decrypted.copyOfRange(0, decrypted.size - 64)
        val signature = decrypted.copyOfRange(decrypted.size - 64, decrypted.size)

        val buf = ByteBuffer.wrap(unsignedPayload)
        val aliasLen = buf.get().toInt() and 0xFF
        buf.position(1 + aliasLen)
        val announcedPubKey = ByteArray(32)
        buf.get(announcedPubKey)

        // The key in the payload is Alice's X25519 public key:
        assertThat(announcedPubKey).isEqualTo(alicePub)

        // Verifying Ed25519 signature against X25519 public key FAILS (F-0):
        val x25519VerifyResult = PureCryptoEngine.verifySignature(announcedPubKey, unsignedPayload, signature)
        assertThat(x25519VerifyResult).isFalse()

        // Whereas verifying against the actual Ed25519 signing key WOULD succeed:
        val aliceEd25519SigningPub = PureCryptoEngine.deriveSigningPublicKey(alicePriv)
        val ed25519VerifyResult = PureCryptoEngine.verifySignature(aliceEd25519SigningPub, unsignedPayload, signature)
        assertThat(ed25519VerifyResult).isTrue()
    }
}

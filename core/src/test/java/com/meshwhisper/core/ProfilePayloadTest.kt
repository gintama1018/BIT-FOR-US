package com.meshwhisper.core

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.ProfilePayload
import org.junit.Test
import java.security.MessageDigest

class ProfilePayloadTest {

    @Test
    fun testCanonicalBytesDeterminism() {
        val nodeId = 0x0102030405060708L
        val version = 42L
        val name = "Alice Tactical"
        val bio = "Mesh node deployed in Sector 4"
        val avatarHash = MessageDigest.getInstance("SHA-256").digest("test_avatar".toByteArray())
        val signingPub = ByteArray(32) { it.toByte() }

        val bytes1 = ProfilePayload.computeCanonicalBytes(nodeId, version, name, bio, avatarHash, signingPub)
        val bytes2 = ProfilePayload.computeCanonicalBytes(nodeId, version, name, bio, avatarHash, signingPub)

        assertThat(bytes1).isEqualTo(bytes2)
        assertThat(bytes1.size).isAtMost(280)
        // Check domain tag "MWP1" at offset 0
        assertThat(bytes1.copyOfRange(0, 4)).isEqualTo(ProfilePayload.DOMAIN_TAG)
    }

    @Test
    fun testIdentityBindingValidation() {
        val (priv, pub) = PureCryptoEngine.generateX25519KeyPair()
        val derivedNodeId = PureCryptoEngine.deriveNodeId(pub)
        val signingPub = PureCryptoEngine.deriveSigningPublicKey(priv)

        val validPayload = ProfilePayload(
            nodeId = derivedNodeId,
            version = 1L,
            displayName = "Valid Node",
            bio = "Bound identity",
            avatarHash = ProfilePayload.EMPTY_AVATAR_HASH,
            signingPublicKey = signingPub,
            signature = ByteArray(64)
        )
        assertThat(validPayload.isIdentityBoundToKey(pub)).isTrue()

        // Test with different public key (spoof attempt)
        val (_, otherPub) = PureCryptoEngine.generateX25519KeyPair()
        assertThat(validPayload.isIdentityBoundToKey(otherPub)).isFalse()

        // Test with invalid nodeId
        val spoofedPayload = validPayload.copy(nodeId = 0x99999999L)
        assertThat(spoofedPayload.isIdentityBoundToKey(pub)).isFalse()
    }

    @Test
    fun testEd25519SigningAndVerification() {
        val (identityPriv, identityPub) = PureCryptoEngine.generateX25519KeyPair()
        val signingPub = PureCryptoEngine.deriveSigningPublicKey(identityPriv)
        val nodeId = PureCryptoEngine.deriveNodeId(identityPub)

        val avatarHash = MessageDigest.getInstance("SHA-256").digest("avatar_bytes".toByteArray())
        val canonical = ProfilePayload.computeCanonicalBytes(
            nodeId = nodeId,
            version = 10L,
            displayName = "Alpha Team Leader",
            bio = "Tactical communications active",
            avatarHash = avatarHash,
            signingPublicKey = signingPub
        )

        val signature = PureCryptoEngine.sign(identityPriv, canonical)
        assertThat(signature.size).isEqualTo(64)

        val payload = ProfilePayload(
            nodeId = nodeId,
            version = 10L,
            displayName = "Alpha Team Leader",
            bio = "Tactical communications active",
            avatarHash = avatarHash,
            signingPublicKey = signingPub,
            signature = signature
        )

        // Valid signature verification
        assertThat(payload.verifySignature()).isTrue()
        assertThat(payload.verifySignature(signingPub)).isTrue()

        // Verification with wrong expected signing key must fail
        val (otherPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val otherSigningPub = PureCryptoEngine.deriveSigningPublicKey(otherPriv)
        assertThat(payload.verifySignature(otherSigningPub)).isFalse()
    }

    @Test
    fun testTamperingInvalidatesSignature() {
        val (identityPriv, identityPub) = PureCryptoEngine.generateX25519KeyPair()
        val signingPub = PureCryptoEngine.deriveSigningPublicKey(identityPriv)
        val nodeId = PureCryptoEngine.deriveNodeId(identityPub)

        val avatarHash = MessageDigest.getInstance("SHA-256").digest("avatar_bytes".toByteArray())
        val canonical = ProfilePayload.computeCanonicalBytes(
            nodeId = nodeId,
            version = 1L,
            displayName = "Legitimate Name",
            bio = "Legitimate Bio",
            avatarHash = avatarHash,
            signingPublicKey = signingPub
        )
        val signature = PureCryptoEngine.sign(identityPriv, canonical)

        val original = ProfilePayload(
            nodeId = nodeId,
            version = 1L,
            displayName = "Legitimate Name",
            bio = "Legitimate Bio",
            avatarHash = avatarHash,
            signingPublicKey = signingPub,
            signature = signature
        )
        assertThat(original.verifySignature()).isTrue()

        // 1. Tamper with version (attempted rollback or artificial increment)
        val tamperedVersion = original.copy(version = 2L)
        assertThat(tamperedVersion.verifySignature()).isFalse()

        // 2. Tamper with display name
        val tamperedName = original.copy(displayName = "Imposter Name")
        assertThat(tamperedName.verifySignature()).isFalse()

        // 3. Tamper with bio
        val tamperedBio = original.copy(bio = "Malicious link injected")
        assertThat(tamperedBio.verifySignature()).isFalse()

        // 4. Tamper with avatar hash
        val tamperedAvatar = original.copy(avatarHash = ByteArray(32) { 0xFF.toByte() })
        assertThat(tamperedAvatar.verifySignature()).isFalse()

        // 5. Tamper with signing public key (Mallory swapping Alice's key for her own)
        val (malloryPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val mallorySigningPub = PureCryptoEngine.deriveSigningPublicKey(malloryPriv)
        val tamperedKey = original.copy(signingPublicKey = mallorySigningPub)
        assertThat(tamperedKey.verifySignature()).isFalse()
    }

    @Test
    fun testWireSerializationRoundTrip() {
        val avatarHash = ByteArray(32) { it.toByte() }
        val signingPub = ByteArray(32) { (it + 5).toByte() }
        val signature = ByteArray(64) { (it * 2).toByte() }

        val original = ProfilePayload(
            nodeId = 0x1122334455667788L,
            version = 15L,
            displayName = "Bravo Scout",
            bio = "Recon unit online. Frequency clear.",
            avatarHash = avatarHash,
            signingPublicKey = signingPub,
            signature = signature
        )

        val wireBytes = original.serialize()
        assertThat(wireBytes.size).isLessThan(310)

        val deserialized = ProfilePayload.deserialize(wireBytes)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized).isEqualTo(original)
    }

    @Test
    fun testMalformedWireDataHandling() {
        // Truncated wire bytes
        val truncated = byteArrayOf(0x4D, 0x57, 0x50, 0x31, 0x01, 0x02)
        assertThat(ProfilePayload.deserialize(truncated)).isNull()

        // Corrupted domain tag
        val validPayload = ProfilePayload(
            nodeId = 1L,
            version = 1L,
            displayName = "Test",
            bio = "Bio",
            avatarHash = ProfilePayload.EMPTY_AVATAR_HASH,
            signingPublicKey = ByteArray(32),
            signature = ByteArray(64)
        )
        val wire = validPayload.serialize()
        wire[0] = 0x00 // corrupt domain tag
        assertThat(ProfilePayload.deserialize(wire)).isNull()
    }
}

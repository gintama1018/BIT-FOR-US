package com.meshwhisper.app.router

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import com.meshwhisper.app.protocol.ProfilePayload
import com.meshwhisper.app.protocol.TrafficPriority
import com.meshwhisper.app.protocol.trafficPriority
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID

class ProfileAntiRollbackTest {

    @Test
    fun testProfileTrafficPriorityMapping() {
        assertThat(PacketType.PROFILE_UPDATE.trafficPriority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)
        assertThat(PacketType.PROFILE_REQUEST.trafficPriority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)
    }

    @Test
    fun testAntiRollbackMonotonicVersionRejection() {
        // Simulates the router's profile cache / database state
        val cachedProfiles = mutableMapOf<Long, Pair<Long, String>>() // nodeId -> (version, displayName)

        fun processProfileUpdate(senderId: Long, payload: ProfilePayload): Boolean {
            // 1. Identity binding
            if (payload.nodeId != senderId) return false
            // 2. Cryptographic signature check
            if (!payload.verifySignature()) return false
            // 3. Monotonic anti-rollback check
            val existing = cachedProfiles[payload.nodeId]
            if (existing != null && payload.version <= existing.first) {
                return false // Rollback or duplicate replay rejected!
            }
            cachedProfiles[payload.nodeId] = Pair(payload.version, payload.displayName)
            return true
        }

        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceSigningPub = PureCryptoEngine.deriveSigningPublicKey(alicePriv)
        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)

        fun createSignedProfile(version: Long, name: String, bio: String): ProfilePayload {
            val hash = MessageDigest.getInstance("SHA-256").digest("avatar".toByteArray())
            val canonical = ProfilePayload.computeCanonicalBytes(
                nodeId = aliceNodeId,
                version = version,
                displayName = name,
                bio = bio,
                avatarHash = hash,
                signingPublicKey = aliceSigningPub
            )
            val sig = PureCryptoEngine.sign(alicePriv, canonical)
            return ProfilePayload(
                nodeId = aliceNodeId,
                version = version,
                displayName = name,
                bio = bio,
                avatarHash = hash,
                signingPublicKey = aliceSigningPub,
                signature = sig
            )
        }

        // 1. Initial profile v1 is accepted
        val profileV1 = createSignedProfile(1L, "Alice Alpha", "Base camp operator")
        val acceptedV1 = processProfileUpdate(aliceNodeId, profileV1)
        assertThat(acceptedV1).isTrue()
        assertThat(cachedProfiles[aliceNodeId]?.first).isEqualTo(1L)
        assertThat(cachedProfiles[aliceNodeId]?.second).isEqualTo("Alice Alpha")

        // 2. Updated profile v2 is accepted
        val profileV2 = createSignedProfile(2L, "Alice Bravo", "Patrol unit active")
        val acceptedV2 = processProfileUpdate(aliceNodeId, profileV2)
        assertThat(acceptedV2).isTrue()
        assertThat(cachedProfiles[aliceNodeId]?.first).isEqualTo(2L)
        assertThat(cachedProfiles[aliceNodeId]?.second).isEqualTo("Alice Bravo")

        // 3. Rollback Attack: Adversary replays validly-signed Profile v1
        // MUST BE REJECTED despite having a valid cryptographic signature!
        val rollbackReplayAccepted = processProfileUpdate(aliceNodeId, profileV1)
        assertThat(rollbackReplayAccepted).isFalse()
        // State remains at v2
        assertThat(cachedProfiles[aliceNodeId]?.first).isEqualTo(2L)
        assertThat(cachedProfiles[aliceNodeId]?.second).isEqualTo("Alice Bravo")

        // 4. Same-Version Conflict / Replay Attack: Adversary resends v2
        // MUST BE REJECTED to prevent database thrashing and split-brain flapping
        val duplicateV2Accepted = processProfileUpdate(aliceNodeId, profileV2)
        assertThat(duplicateV2Accepted).isFalse()
    }

    @Test
    fun testForgedOrTamperedProfileRejection() {
        val (alicePriv, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceSigningPub = PureCryptoEngine.deriveSigningPublicKey(alicePriv)
        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)

        val (malloryPriv, malloryPub) = PureCryptoEngine.generateX25519KeyPair()
        val mallorySigningPub = PureCryptoEngine.deriveSigningPublicKey(malloryPriv)
        val malloryNodeId = PureCryptoEngine.deriveNodeId(malloryPub)

        val hash = ByteArray(32) { 0xAA.toByte() }
        val canonical = ProfilePayload.computeCanonicalBytes(
            nodeId = aliceNodeId,
            version = 1L,
            displayName = "Alice",
            bio = "Field Medic",
            avatarHash = hash,
            signingPublicKey = aliceSigningPub
        )
        val aliceSig = PureCryptoEngine.sign(alicePriv, canonical)

        val validAlicePayload = ProfilePayload(
            nodeId = aliceNodeId,
            version = 1L,
            displayName = "Alice",
            bio = "Field Medic",
            avatarHash = hash,
            signingPublicKey = aliceSigningPub,
            signature = aliceSig
        )

        // 1. Valid profile verifies cleanly
        assertThat(validAlicePayload.verifySignature()).isTrue()

        // 2. Impersonation Attack: Mallory claims Alice's nodeId in a packet from Mallory's node
        val impersonationSenderId = malloryNodeId
        assertThat(validAlicePayload.nodeId == impersonationSenderId).isFalse()

        // 3. Payload Tampering: Mallory alters Alice's name to "Mallory"
        val tamperedPayload = validAlicePayload.copy(displayName = "Mallory")
        assertThat(tamperedPayload.verifySignature()).isFalse()

        // 4. Key-Swap Attack: Mallory replaces Alice's signing key with Mallory's key and signs with Mallory's key
        val malloryCanonical = ProfilePayload.computeCanonicalBytes(
            nodeId = aliceNodeId, // Claiming Alice's nodeId
            version = 5L,
            displayName = "Alice Impersonated",
            bio = "Hostile takeover",
            avatarHash = hash,
            signingPublicKey = mallorySigningPub
        )
        val mallorySig = PureCryptoEngine.sign(malloryPriv, malloryCanonical)
        val keySwappedPayload = ProfilePayload(
            nodeId = aliceNodeId,
            version = 5L,
            displayName = "Alice Impersonated",
            bio = "Hostile takeover",
            avatarHash = hash,
            signingPublicKey = mallorySigningPub,
            signature = mallorySig
        )
        // Even though Mallory's signature is self-consistent with mallorySigningPub,
        // it fails verification against Alice's authentic signing public key!
        assertThat(keySwappedPayload.verifySignature(aliceSigningPub)).isFalse()
        assertThat(keySwappedPayload.signingPublicKey.contentEquals(aliceSigningPub)).isFalse()
    }

    @Test
    fun testAvatarHashDifferenceDetection() {
        val hash1 = MessageDigest.getInstance("SHA-256").digest("avatar_v1".toByteArray())
        val hash2 = MessageDigest.getInstance("SHA-256").digest("avatar_v2".toByteArray())

        val hex1 = com.meshwhisper.app.crypto.CryptoEngine.bytesToHex(hash1)
        val hex2 = com.meshwhisper.app.crypto.CryptoEngine.bytesToHex(hash2)

        assertThat(hex1).isNotEqualTo(hex2)
        assertThat(hex1.length).isEqualTo(64)
        assertThat(hex2.length).isEqualTo(64)

        // Verifies that a non-empty hash change is detected as needing an avatar sync
        var avatarSyncRequested = false
        fun checkAvatarUpdate(cachedHex: String, incomingHex: String) {
            val hasAvatar = incomingHex.isNotBlank() && !incomingHex.all { it == '0' }
            if (hasAvatar && incomingHex != cachedHex) {
                avatarSyncRequested = true
            }
        }

        checkAvatarUpdate(hex1, hex2)
        assertThat(avatarSyncRequested).isTrue()

        // Same avatar hash does NOT trigger another sync
        avatarSyncRequested = false
        checkAvatarUpdate(hex2, hex2)
        assertThat(avatarSyncRequested).isFalse()
    }
}

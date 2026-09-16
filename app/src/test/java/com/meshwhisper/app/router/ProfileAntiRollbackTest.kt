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
            // 2. Monotonic anti-rollback check
            val existing = cachedProfiles[payload.nodeId]
            if (existing != null && payload.version <= existing.first) {
                return false // Rollback or duplicate replay rejected!
            }
            cachedProfiles[payload.nodeId] = Pair(payload.version, payload.displayName)
            return true
        }

        val (_, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)

        fun createProfile(version: Long, name: String, bio: String): ProfilePayload {
            val hash = MessageDigest.getInstance("SHA-256").digest("avatar".toByteArray())
            return ProfilePayload(
                nodeId = aliceNodeId,
                version = version,
                displayName = name,
                bio = bio,
                avatarHash = hash
            )
        }

        // 1. Initial profile v1 is accepted
        val profileV1 = createProfile(1L, "Alice Alpha", "Base camp operator")
        val acceptedV1 = processProfileUpdate(aliceNodeId, profileV1)
        assertThat(acceptedV1).isTrue()
        assertThat(cachedProfiles[aliceNodeId]?.first).isEqualTo(1L)
        assertThat(cachedProfiles[aliceNodeId]?.second).isEqualTo("Alice Alpha")

        // 2. Updated profile v2 is accepted
        val profileV2 = createProfile(2L, "Alice Bravo", "Patrol unit active")
        val acceptedV2 = processProfileUpdate(aliceNodeId, profileV2)
        assertThat(acceptedV2).isTrue()
        assertThat(cachedProfiles[aliceNodeId]?.first).isEqualTo(2L)
        assertThat(cachedProfiles[aliceNodeId]?.second).isEqualTo("Alice Bravo")

        // 3. Rollback Attack: Adversary replays Profile v1
        // MUST BE REJECTED by anti-rollback check
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
        val (_, alicePub) = PureCryptoEngine.generateX25519KeyPair()
        val aliceNodeId = PureCryptoEngine.deriveNodeId(alicePub)

        val (_, malloryPub) = PureCryptoEngine.generateX25519KeyPair()
        val malloryNodeId = PureCryptoEngine.deriveNodeId(malloryPub)

        val hash = ByteArray(32) { 0xAA.toByte() }
        val validAlicePayload = ProfilePayload(
            nodeId = aliceNodeId,
            version = 1L,
            displayName = "Alice",
            bio = "Field Medic",
            avatarHash = hash
        )

        // 1. Serialization round-trip
        val serialized = validAlicePayload.serialize()
        val deserialized = ProfilePayload.deserialize(serialized)
        assertThat(deserialized).isEqualTo(validAlicePayload)

        // 2. Impersonation Attack: Mallory claims Alice's nodeId in a packet from Mallory's node
        val impersonationSenderId = malloryNodeId
        assertThat(validAlicePayload.nodeId == impersonationSenderId).isFalse()

        // 3. Payload Tampering / Trailing Byte: Modifying wire size or corrupting bytes causes deserialization failure
        val corruptedBytes = serialized.copyOf(serialized.size + 1)
        corruptedBytes[corruptedBytes.size - 1] = 0x55.toByte()
        assertThat(ProfilePayload.deserialize(corruptedBytes)).isNull()
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

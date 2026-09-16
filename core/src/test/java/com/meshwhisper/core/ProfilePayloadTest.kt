package com.meshwhisper.core

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.protocol.ProfilePayload
import org.junit.Test
import java.security.MessageDigest
import java.util.Random

/**
 * Validates canonical MWP2 profile presentation payload serialization,
 * strict bounds checking, exact-length enforcement, and fuzzer safety.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.10 and NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md (T-FUZZ-02).
 */
class ProfilePayloadTest {

    @Test
    fun testWireSerializationRoundTrip() {
        val avatarHash = ByteArray(32) { (it * 3).toByte() }
        val original = ProfilePayload(
            nodeId = 0x1122334455667788L,
            version = 42L,
            displayName = "Alice Tactical",
            bio = "Mesh node deployed in Sector 4",
            avatarHash = avatarHash
        )

        val wireBytes = original.serialize()
        assertThat(wireBytes.size).isAtLeast(ProfilePayload.MIN_SIZE) // >= 55
        assertThat(wireBytes.size).isAtMost(ProfilePayload.MAX_SIZE) // <= 207

        // Domain tag is "MWP2"
        assertThat(wireBytes.copyOfRange(0, 4)).isEqualTo(ProfilePayload.DOMAIN_TAG)

        val deserialized = ProfilePayload.deserialize(wireBytes)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized).isEqualTo(original)
        assertThat(deserialized!!.displayName).isEqualTo("Alice Tactical")
        assertThat(deserialized.bio).isEqualTo("Mesh node deployed in Sector 4")
        assertThat(deserialized.avatarHash).isEqualTo(avatarHash)
    }

    @Test
    fun testMinAndMaxPayloadSizes() {
        val avatarHash = ByteArray(32)

        // Minimum payload: empty display name and empty bio
        val minPayload = ProfilePayload(
            nodeId = 1L,
            version = 1L,
            displayName = "",
            bio = "",
            avatarHash = avatarHash
        )
        val minBytes = minPayload.serialize()
        assertThat(minBytes.size).isEqualTo(ProfilePayload.MIN_SIZE) // exactly 55
        assertThat(ProfilePayload.deserialize(minBytes)).isEqualTo(minPayload)

        // Maximum payload: 32 bytes display name and 120 bytes bio
        val maxName = "A".repeat(32)
        val maxBio = "B".repeat(120)
        val maxPayload = ProfilePayload(
            nodeId = Long.MAX_VALUE,
            version = Long.MAX_VALUE,
            displayName = maxName,
            bio = maxBio,
            avatarHash = avatarHash
        )
        val maxBytes = maxPayload.serialize()
        assertThat(maxBytes.size).isEqualTo(ProfilePayload.MAX_SIZE) // exactly 207
        assertThat(ProfilePayload.deserialize(maxBytes)).isEqualTo(maxPayload)
    }

    @Test
    fun testRejectLegacyMwp1DomainTag() {
        val payload = ProfilePayload(
            nodeId = 1L,
            version = 1L,
            displayName = "Test",
            bio = "Bio",
            avatarHash = ByteArray(32)
        )
        val wire = payload.serialize()
        // Corrupt domain tag to MWP1
        wire[3] = '1'.code.toByte()
        assertThat(ProfilePayload.deserialize(wire)).isNull()
    }

    @Test
    fun testExactLengthAndTrailingByteRejection() {
        val payload = ProfilePayload(
            nodeId = 1L,
            version = 1L,
            displayName = "Test",
            bio = "Bio",
            avatarHash = ByteArray(32)
        )
        val wire = payload.serialize()

        // Trailing byte added
        val withTrailing = ByteArray(wire.size + 1)
        System.arraycopy(wire, 0, withTrailing, 0, wire.size)
        withTrailing[withTrailing.size - 1] = 0x99.toByte()
        assertThat(ProfilePayload.deserialize(withTrailing)).isNull()

        // Truncated (missing 1 byte of avatar hash)
        val truncated = wire.copyOf(wire.size - 1)
        assertThat(ProfilePayload.deserialize(truncated)).isNull()
    }

    @Test
    fun testFuzzParserNeverThrowsT_FUZZ_02() {
        val random = Random(12345L)
        // 10,000 cases of fuzz data
        for (i in 0 until 10_000) {
            val length = random.nextInt(300)
            val randomBytes = ByteArray(length)
            random.nextBytes(randomBytes)

            // Must never throw an uncaught exception
            val result = ProfilePayload.deserialize(randomBytes)
            if (result != null) {
                // If it successfully parsed, it must obey all MWP2 bounds
                assertThat(result.displayName.toByteArray(Charsets.UTF_8).size).isAtMost(32)
                assertThat(result.bio.toByteArray(Charsets.UTF_8).size).isAtMost(120)
                assertThat(result.avatarHash.size).isEqualTo(32)
            }
        }
    }
}

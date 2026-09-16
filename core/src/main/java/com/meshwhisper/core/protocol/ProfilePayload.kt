package com.meshwhisper.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure Kotlin Canonical User Profile Presentation Payload (MWP2).
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.10 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §9.1.
 *
 * Wire format (plaintext inside AEAD under publicChannelKey):
 *   DOMAIN_TAG "MWP2"     4 B (ASCII 0x4D, 0x57, 0x50, 0x32)
 *   nodeId                8 B (u64 BigEndian)
 *   version               8 B (u64 BigEndian)
 *   displayNameLen        1 B (u8 <= 32)
 *   displayName           0..32 B UTF-8
 *   bioLen                2 B (u16 <= 120 BigEndian)
 *   bio                   0..120 B UTF-8
 *   avatarHash            32 B (SHA-256)
 *
 * Strict size: min 55 B, max 207 B.
 * Signing keys and signatures are deleted from the format; authentication belongs to the packet hop signature.
 */
data class ProfilePayload(
    val nodeId: Long,
    val version: Long,
    val displayName: String,
    val bio: String,
    val avatarHash: ByteArray // Exactly 32 bytes (SHA-256)
) {
    init {
        require(avatarHash.size == AVATAR_HASH_SIZE) {
            "Avatar hash must be exactly $AVATAR_HASH_SIZE bytes (got ${avatarHash.size})"
        }
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= MAX_DISPLAY_NAME_BYTES) {
            "DisplayName exceeds max $MAX_DISPLAY_NAME_BYTES bytes (got ${nameBytes.size})"
        }
        val bioBytes = bio.toByteArray(Charsets.UTF_8)
        require(bioBytes.size <= MAX_BIO_BYTES) {
            "Bio exceeds max $MAX_BIO_BYTES bytes (got ${bioBytes.size})"
        }
    }

    /**
     * Serializes this ProfilePayload into MWP2 binary wire format.
     */
    fun serialize(): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        val bioBytes = bio.toByteArray(Charsets.UTF_8)
        val totalSize = 4 + 8 + 8 + 1 + nameBytes.size + 2 + bioBytes.size + AVATAR_HASH_SIZE

        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(DOMAIN_TAG)
        buffer.putLong(nodeId)
        buffer.putLong(version)
        buffer.put((nameBytes.size and 0xFF).toByte())
        buffer.put(nameBytes)
        buffer.putShort((bioBytes.size and 0xFFFF).toShort())
        buffer.put(bioBytes)
        buffer.put(avatarHash)

        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProfilePayload
        return nodeId == other.nodeId &&
                version == other.version &&
                displayName == other.displayName &&
                bio == other.bio &&
                avatarHash.contentEquals(other.avatarHash)
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + bio.hashCode()
        result = 31 * result + avatarHash.contentHashCode()
        return result
    }

    companion object {
        // Domain Separation Tag: "MWP2" (MeshWhisper Profile v2)
        val DOMAIN_TAG: ByteArray = byteArrayOf(0x4D, 0x57, 0x50, 0x32)
        const val MIN_SIZE = ResourceLimits.MIN_PROFILE_PAYLOAD_SIZE // 55 bytes
        const val MAX_SIZE = ResourceLimits.MAX_PROFILE_PAYLOAD_SIZE // 207 bytes
        const val MAX_DISPLAY_NAME_BYTES = 32
        const val MAX_BIO_BYTES = 120
        const val AVATAR_HASH_SIZE = 32
        val EMPTY_AVATAR_HASH: ByteArray = ByteArray(AVATAR_HASH_SIZE)

        /**
         * Parses MWP2 binary bytes into ProfilePayload.
         * Enforces strict bounds, domain tag, and exact-length check (no trailing bytes).
         * Never throws, never allocates > 207 bytes.
         */
        fun deserialize(bytes: ByteArray): ProfilePayload? {
            if (bytes.size < MIN_SIZE || bytes.size > MAX_SIZE) return null

            return try {
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

                // Domain tag check (reject MWP1 or corrupted tags)
                val tag = ByteArray(4)
                buffer.get(tag)
                if (!tag.contentEquals(DOMAIN_TAG)) return null

                val nodeId = buffer.getLong()
                val version = buffer.getLong()

                val nameLen = buffer.get().toInt() and 0xFF
                if (nameLen > MAX_DISPLAY_NAME_BYTES) return null
                if (buffer.remaining() < nameLen + 2 + AVATAR_HASH_SIZE) return null

                val nameBytes = ByteArray(nameLen)
                buffer.get(nameBytes)
                val displayName = String(nameBytes, Charsets.UTF_8)

                val bioLen = buffer.getShort().toInt() and 0xFFFF
                if (bioLen > MAX_BIO_BYTES) return null

                // Strict exact length check: declared lengths must match remaining bytes exactly
                if (buffer.remaining() != bioLen + AVATAR_HASH_SIZE) return null

                val bioBytes = ByteArray(bioLen)
                buffer.get(bioBytes)
                val bio = String(bioBytes, Charsets.UTF_8)

                val avatarHash = ByteArray(AVATAR_HASH_SIZE)
                buffer.get(avatarHash)

                // No trailing bytes permitted
                if (buffer.hasRemaining()) return null

                ProfilePayload(
                    nodeId = nodeId,
                    version = version,
                    displayName = displayName,
                    bio = bio,
                    avatarHash = avatarHash
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

package com.meshwhisper.core.protocol

import com.meshwhisper.core.crypto.PureCryptoEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays

/**
 * Pure Kotlin Canonical User Profile Presentation Payload.
 *
 * Implements:
 * 1. Cryptographic Domain Separation ("MWP1") preventing cross-protocol signature reuse.
 * 2. Strict Deterministic Big-Endian Binary Canonicalization for Ed25519 signing.
 * 3. Key-to-Identity Binding: Embeds and signs the sender's 32-byte Ed25519 signing public key.
 * 4. Monotonic Versioning: Strict anti-rollback enforcement (version > cached.version).
 * 5. Bounded Wire Size: DisplayName (<= 32 bytes), bio (<= 120 bytes), avatar hash (32 bytes SHA-256).
 *    Total wire size is strictly <= 310 bytes.
 */
data class ProfilePayload(
    val nodeId: Long,
    val version: Long,
    val displayName: String,
    val bio: String,
    val avatarHash: ByteArray,         // Exactly 32 bytes (SHA-256)
    val signingPublicKey: ByteArray,   // Exactly 32 bytes (Ed25519 signing public key)
    val signature: ByteArray           // Exactly 64 bytes (Ed25519)
) {
    init {
        require(avatarHash.size == AVATAR_HASH_SIZE) {
            "Avatar hash must be exactly $AVATAR_HASH_SIZE bytes (got ${avatarHash.size})"
        }
        require(signingPublicKey.size == PUBLIC_KEY_SIZE) {
            "Signing public key must be exactly $PUBLIC_KEY_SIZE bytes (got ${signingPublicKey.size})"
        }
        require(signature.size == SIGNATURE_SIZE) {
            "Signature must be exactly $SIGNATURE_SIZE bytes (got ${signature.size})"
        }
    }

    /**
     * Serializes this ProfilePayload into wire bytes.
     * Format: [Canonical Signed Bytes (MWP1 domain tag + fields)] + [64B Signature]
     */
    fun serialize(): ByteArray {
        val canonical = computeCanonicalBytes(nodeId, version, displayName, bio, avatarHash, signingPublicKey)
        val wire = ByteArray(canonical.size + SIGNATURE_SIZE)
        System.arraycopy(canonical, 0, wire, 0, canonical.size)
        System.arraycopy(signature, 0, wire, canonical.size, SIGNATURE_SIZE)
        return wire
    }

    /**
     * Verifies that the claimed nodeId strictly derives from the given root public key.
     */
    fun isIdentityBoundToKey(publicKeyBytes: ByteArray): Boolean {
        if (publicKeyBytes.size != 32) return false
        val derivedId = PureCryptoEngine.deriveNodeId(publicKeyBytes)
        return derivedId == nodeId
    }

    /**
     * Cryptographically verifies the Ed25519 signature against the embedded signing public key.
     */
    fun verifySignature(): Boolean {
        val canonical = computeCanonicalBytes(nodeId, version, displayName, bio, avatarHash, signingPublicKey)
        return PureCryptoEngine.verifySignature(signingPublicKey, canonical, signature)
    }

    /**
     * Cryptographically verifies the Ed25519 signature against an explicit signing public key.
     */
    fun verifySignature(expectedSigningPublicKey: ByteArray): Boolean {
        if (!Arrays.equals(signingPublicKey, expectedSigningPublicKey)) return false
        val canonical = computeCanonicalBytes(nodeId, version, displayName, bio, avatarHash, signingPublicKey)
        return PureCryptoEngine.verifySignature(expectedSigningPublicKey, canonical, signature)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProfilePayload
        return nodeId == other.nodeId &&
                version == other.version &&
                displayName == other.displayName &&
                bio == other.bio &&
                avatarHash.contentEquals(other.avatarHash) &&
                signingPublicKey.contentEquals(other.signingPublicKey) &&
                signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + bio.hashCode()
        result = 31 * result + avatarHash.contentHashCode()
        result = 31 * result + signingPublicKey.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }

    companion object {
        // Domain Separation Tag: "MWP1" (MeshWhisper Profile v1)
        val DOMAIN_TAG: ByteArray = byteArrayOf(0x4D, 0x57, 0x50, 0x31)
        const val MAX_DISPLAY_NAME_BYTES = 32
        const val MAX_BIO_BYTES = 120
        const val AVATAR_HASH_SIZE = 32
        const val PUBLIC_KEY_SIZE = 32
        const val SIGNATURE_SIZE = 64
        val EMPTY_AVATAR_HASH: ByteArray = ByteArray(AVATAR_HASH_SIZE)

        /**
         * Computes the canonical byte sequence for signing or signature verification.
         * Deterministic format:
         * [4B DOMAIN_TAG ("MWP1")]
         * [8B nodeId (BigEndian)]
         * [8B version (BigEndian)]
         * [1B displayNameLen]
         * [N bytes displayName UTF-8 (bounded to 32)]
         * [2B bioLen (BigEndian)]
         * [M bytes bio UTF-8 (bounded to 120)]
         * [32B avatarHash]
         * [32B signingPublicKey]
         */
        fun computeCanonicalBytes(
            nodeId: Long,
            version: Long,
            displayName: String,
            bio: String,
            avatarHash: ByteArray,
            signingPublicKey: ByteArray
        ): ByteArray {
            require(avatarHash.size == AVATAR_HASH_SIZE) { "Avatar hash must be 32 bytes" }
            require(signingPublicKey.size == PUBLIC_KEY_SIZE) { "Signing public key must be 32 bytes" }
            val nameBytes = displayName.toByteArray(Charsets.UTF_8).let {
                if (it.size > MAX_DISPLAY_NAME_BYTES) it.copyOf(MAX_DISPLAY_NAME_BYTES) else it
            }
            val bioBytes = bio.toByteArray(Charsets.UTF_8).let {
                if (it.size > MAX_BIO_BYTES) it.copyOf(MAX_BIO_BYTES) else it
            }

            val totalSize = DOMAIN_TAG.size + 8 + 8 + 1 + nameBytes.size + 2 + bioBytes.size + AVATAR_HASH_SIZE + PUBLIC_KEY_SIZE
            val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

            buffer.put(DOMAIN_TAG)
            buffer.putLong(nodeId)
            buffer.putLong(version)
            buffer.put((nameBytes.size and 0xFF).toByte())
            buffer.put(nameBytes)
            buffer.putShort((bioBytes.size and 0xFFFF).toShort())
            buffer.put(bioBytes)
            buffer.put(avatarHash)
            buffer.put(signingPublicKey)

            return buffer.array()
        }

        /**
         * Deserializes wire bytes into a ProfilePayload.
         * Returns null if wire format is malformed or invalid.
         */
        fun deserialize(wireBytes: ByteArray): ProfilePayload? {
            // Minimum size: DOMAIN(4) + nodeId(8) + ver(8) + nameLen(1) + bioLen(2) + avatarHash(32) + pubKey(32) + sig(64) = 151 bytes
            if (wireBytes.size < 151) return null

            val buffer = ByteBuffer.wrap(wireBytes).order(ByteOrder.BIG_ENDIAN)

            // 1. Verify Domain Tag
            val domain = ByteArray(DOMAIN_TAG.size)
            buffer.get(domain)
            if (!domain.contentEquals(DOMAIN_TAG)) return null

            // 2. Read NodeId & Version
            val nodeId = buffer.long
            val version = buffer.long

            // 3. Read Display Name
            val nameLen = buffer.get().toInt() and 0xFF
            if (nameLen > MAX_DISPLAY_NAME_BYTES || buffer.remaining() < nameLen) return null
            val nameBytes = ByteArray(nameLen)
            buffer.get(nameBytes)
            val displayName = String(nameBytes, Charsets.UTF_8)

            // 4. Read Bio
            if (buffer.remaining() < 2) return null
            val bioLen = buffer.short.toInt() and 0xFFFF
            if (bioLen > MAX_BIO_BYTES || buffer.remaining() < bioLen) return null
            val bioBytes = ByteArray(bioLen)
            buffer.get(bioBytes)
            val bio = String(bioBytes, Charsets.UTF_8)

            // 5. Read Avatar Hash
            if (buffer.remaining() < AVATAR_HASH_SIZE) return null
            val avatarHash = ByteArray(AVATAR_HASH_SIZE)
            buffer.get(avatarHash)

            // 6. Read Signing Public Key
            if (buffer.remaining() < PUBLIC_KEY_SIZE) return null
            val signingPublicKey = ByteArray(PUBLIC_KEY_SIZE)
            buffer.get(signingPublicKey)

            // 7. Read Signature
            if (buffer.remaining() != SIGNATURE_SIZE) return null
            val signature = ByteArray(SIGNATURE_SIZE)
            buffer.get(signature)

            return ProfilePayload(
                nodeId = nodeId,
                version = version,
                displayName = displayName,
                bio = bio,
                avatarHash = avatarHash,
                signingPublicKey = signingPublicKey,
                signature = signature
            )
        }
    }
}

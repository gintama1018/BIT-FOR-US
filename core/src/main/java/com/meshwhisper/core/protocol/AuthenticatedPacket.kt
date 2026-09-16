package com.meshwhisper.core.protocol

import com.meshwhisper.core.identity.PeerIdentity

/**
 * An authenticated, validated vNext packet that has passed stages S0 through S7.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §9.1.
 *
 * Guarantees established by S0–S7:
 * - S0: Frame within transport bounds, link rate budget respected, pending link rules enforced
 * - S1: Canonical 56-byte header, known type != 0x22, exact payload length, clamped TTL
 * - S2: Packet timestamp strictly within validity window (-120s..+pastWindow)
 * - S3: Not present in RAM LRU or persistent dedup store
 * - S4: Sender identity non-null, verified in IdentityStore (or self-bootstrapping PEER_ANNOUNCE/LINK_AUTH), not BLOCKED
 * - S5: AEAD authenticated decryption verified under correct key (or C-17 relay-only)
 * - S6: 115-byte SIG_TRANSCRIPT Ed25519 hop signature verified using canonical IK_pk
 * - S7: Full payload parsed, strict length arithmetic, flags and bounds valid
 *
 * Construction is restricted:
 * - Constructor is private.
 * - No public factory methods exist.
 * - Exactly one construction call site in the entire codebase (inside PacketPipeline.kt).
 */
class AuthenticatedPacket private constructor(
    val packet: MeshPacket,
    val senderIdentity: PeerIdentity,
    val decryptedPayload: ByteArray,
    val isRelayOnly: Boolean,
    val isDuplicateDmForUs: Boolean
) {
    companion object {
        /**
         * The SINGLE legitimate construction path for AuthenticatedPacket.
         * Package-private (internal) to com.meshwhisper.core.protocol and invoked exclusively by PacketPipeline.
         */
        internal fun createFromPipeline(
            packet: MeshPacket,
            senderIdentity: PeerIdentity,
            decryptedPayload: ByteArray,
            isRelayOnly: Boolean = false,
            isDuplicateDmForUs: Boolean = false
        ): AuthenticatedPacket {
            return AuthenticatedPacket(packet, senderIdentity, decryptedPayload, isRelayOnly, isDuplicateDmForUs)
        }
    }
}

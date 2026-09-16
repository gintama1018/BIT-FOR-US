package com.meshwhisper.core.protocol

import com.meshwhisper.core.identity.PeerIdentity
import java.util.UUID

/**
 * An admitted MEDIA_CHUNK (0x26) packet that has passed stages S0 through S7.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.12, §4, and C-14.
 *
 * C-14 Admission Semantics:
 * - No hop signature (C-14 exception to avoid 2x BLE frame explosion on bulk media).
 * - Directed transfers: Protected by pairwise session-key AEAD.
 * - Broadcast transfers: Decrypted under publicChannelKey; write-once per chunk index;
 *   first value received for (mediaId, chunkIndex) is final.
 * - Broadcast SHA-256 mismatch at reassembly: discard silently (no NACK).
 * - SHA-256 over reassembly (committed in signed MEDIA_INIT) is the final acceptance backstop.
 *
 * Construction is restricted:
 * - Constructor is private.
 * - No public factory methods exist.
 * - Exactly one construction call site in the entire codebase (inside PacketPipeline.kt).
 */
class AdmittedChunk private constructor(
    val packet: MeshPacket,
    val senderIdentity: PeerIdentity,
    val mediaId: UUID,
    val chunkIndex: Int,
    val chunkData: ByteArray,
    val isBroadcast: Boolean,
    val isRelayOnly: Boolean = false
) {
    companion object {
        /**
         * The SINGLE legitimate construction path for AdmittedChunk.
         * Package-private (internal) to com.meshwhisper.core.protocol and invoked exclusively by PacketPipeline.
         */
        internal fun createFromPipeline(
            packet: MeshPacket,
            senderIdentity: PeerIdentity,
            mediaId: UUID,
            chunkIndex: Int,
            chunkData: ByteArray,
            isBroadcast: Boolean,
            isRelayOnly: Boolean = false
        ): AdmittedChunk {
            return AdmittedChunk(packet, senderIdentity, mediaId, chunkIndex, chunkData, isBroadcast, isRelayOnly)
        }
    }
}

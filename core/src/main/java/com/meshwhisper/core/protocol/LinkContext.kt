package com.meshwhisper.core.protocol

/**
 * Transport types supported by MeshWhisper vNext.
 */
enum class TransportType(val maxFrameSize: Int) {
    BLE(ResourceLimits.MAX_PACKET_SIZE),
    WIFI_TCP(ResourceLimits.MAX_WIFI_TCP_FRAME_SIZE),
    WIFI_DIRECT(ResourceLimits.MAX_WIFI_TCP_FRAME_SIZE),
    UDP(ResourceLimits.MAX_UDP_BEACON_SIZE), // Forbidden for mesh packets (S0 drop)
    LOOPBACK(ResourceLimits.MAX_PACKET_SIZE),
    TEST(ResourceLimits.MAX_PACKET_SIZE)
}

/**
 * Link authentication states.
 */
enum class LinkState {
    PENDING,
    AUTHENTICATED
}

/**
 * Context of the incoming radio/network link on which raw bytes arrived.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4 (S0 Admission).
 *
 * Security Amendment 1:
 * State and boundIdentity must be explicit — no default to AUTHENTICATED.
 */
data class LinkContext(
    val linkHandle: String,
    val transport: TransportType,
    val boundIdentity: ByteArray?, // 32-byte identityHash if authenticated link, null otherwise
    val state: LinkState
) {
    init {
        if (boundIdentity != null) {
            require(boundIdentity.size == 32) { "boundIdentity must be exactly 32 bytes, got ${boundIdentity.size}" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as LinkContext

        if (linkHandle != other.linkHandle) return false
        if (transport != other.transport) return false
        if (state != other.state) return false
        if (boundIdentity != null) {
            if (other.boundIdentity == null) return false
            if (!boundIdentity.contentEquals(other.boundIdentity)) return false
        } else if (other.boundIdentity != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = linkHandle.hashCode()
        result = 31 * result + transport.hashCode()
        result = 31 * result + (boundIdentity?.contentHashCode() ?: 0)
        result = 31 * result + state.hashCode()
        return result
    }
}

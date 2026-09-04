package com.meshwhisper.core.protocol

/**
 * Traffic Quality-of-Service (QoS) Priority Classes for MeshWhisper.
 * Lower ordinal / level value indicates higher transmission priority.
 */
enum class TrafficPriority(val level: Int) {
    /**
     * Tier 0: Life safety, distress beacons, and real-time call control.
     * Always preempts normal traffic; zero queue delay.
     */
    CRITICAL_EMERGENCY(0),

    /**
     * Tier 1: Interactive confirmations, key exchanges, typing status, and real-time audio.
     * High priority to maintain responsive UX and delivery ACKs.
     */
    HIGH_INTERACTIVE(1),

    /**
     * Tier 2: Normal conversational chat and peer discovery heartbeats.
     */
    STANDARD_MESSAGING(2),

    /**
     * Tier 3: High-volume binary chunks (images, audio files, avatars).
     * Subject to aggressive backpressure and pacing to prevent channel starvation.
     */
    BULK_TRANSFER(3);

    companion object {
        fun fromPacketType(type: PacketType): TrafficPriority = when (type) {
            PacketType.SOS_MESSAGE -> CRITICAL_EMERGENCY
            PacketType.ACK,
            PacketType.KEY_EXCHANGE,
            PacketType.TYPING_INDICATOR -> HIGH_INTERACTIVE
            PacketType.DIRECT_MESSAGE,
            PacketType.BROADCAST_MESSAGE,
            PacketType.PEER_ANNOUNCE -> STANDARD_MESSAGING
            PacketType.MEDIA_INIT,
            PacketType.MEDIA_CHUNK,
            PacketType.AVATAR_REQUEST,
            PacketType.MEDIA_NACK,
            PacketType.MEDIA_ACK,
            PacketType.MEDIA_ABORT -> BULK_TRANSFER
        }
    }
}

/**
 * Extension property to retrieve the TrafficPriority for any PacketType.
 */
val PacketType.trafficPriority: TrafficPriority
    get() = TrafficPriority.fromPacketType(this)

/**
 * Prioritized outbound packet envelope for non-blocking QoS scheduling.
 * Ordered by Priority (ascending level) then FIFO by enqueue timestamp.
 */
data class PrioritizedPacket(
    val rawBytes: ByteArray,
    val priority: TrafficPriority,
    val targetNodeId: Long? = null,
    val excludeAddress: String? = null,
    val enqueuedAtMs: Long = System.currentTimeMillis()
) : Comparable<PrioritizedPacket> {

    override fun compareTo(other: PrioritizedPacket): Int {
        val priorityComparison = this.priority.level.compareTo(other.priority.level)
        if (priorityComparison != 0) {
            return priorityComparison
        }
        return this.enqueuedAtMs.compareTo(other.enqueuedAtMs)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as PrioritizedPacket
        return rawBytes.contentEquals(other.rawBytes) &&
                priority == other.priority &&
                targetNodeId == other.targetNodeId &&
                enqueuedAtMs == other.enqueuedAtMs
    }

    override fun hashCode(): Int {
        var result = rawBytes.contentHashCode()
        result = 31 * result + priority.hashCode()
        result = 31 * result + (targetNodeId?.hashCode() ?: 0)
        result = 31 * result + enqueuedAtMs.hashCode()
        return result
    }
}

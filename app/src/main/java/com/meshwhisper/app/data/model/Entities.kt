package com.meshwhisper.app.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val nodeId: Long,
    val alias: String,
    val publicKeyHex: String,
    val fingerprint: String,
    val lastSeen: Long,
    val isDirect: Boolean,
    val rssi: Int = 0,
    val hopCount: Int = 1,
    val isBlocked: Boolean = false,
    val hasKeyChanged: Boolean = false,
    val previousFingerprint: String? = null,
    val avatarUri: String? = null,
    val avatarHash: Byte = 0,
    val isMuted: Boolean = false,
    val isVerified: Boolean = false,
    val identityHashHex: String? = null,
    val trustState: String = "LEGACY_UNVERIFIED",
    val shareLocationWithContact: Boolean = false
) {
    val nodeIdHex: String
        get() = String.format("%016X", nodeId)
}

enum class MessageStatus {
    PENDING,
    SENT,
    RELAYED,
    DELIVERED,
    FAILED,
    CANCELLED,
    EXPIRED,
    CUSTODY_HELD
}

enum class MediaType {
    NONE,
    IMAGE,
    VOICE,
    AVATAR,
    FILE
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val senderId: Long,
    val recipientId: Long,
    val senderAlias: String,
    val text: String,
    val timestamp: Long,
    val isOutgoing: Boolean,
    val isBroadcast: Boolean,
    val status: MessageStatus = MessageStatus.SENT,
    val hopCount: Int = 0,
    val mediaType: MediaType = MediaType.NONE,
    val mediaUri: String? = null,
    val mediaSizeBytes: Long = 0L,
    val mediaProgress: Float = 1.0f,
    val mediaDurationMs: Long = 0L,
    val originalFileName: String? = null,
    val mediaPreviewBase64: String? = null,
    val isSos: Boolean = false
)

@Entity(tableName = "store_forward_queue")
data class StoreForwardEntity(
    @PrimaryKey val messageId: String,
    val recipientId: Long,
    val packetData: ByteArray,
    val createdAt: Long,
    val expiresAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as StoreForwardEntity
        return messageId == other.messageId && recipientId == other.recipientId && packetData.contentEquals(other.packetData)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + recipientId.hashCode()
        result = 31 * result + packetData.contentHashCode()
        return result
    }
}

@Entity(tableName = "packet_logs")
data class PacketLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val direction: String, // RX, TX, RELAY, DROP
    val packetType: String,
    val messageId: String,
    val senderId: Long,
    val recipientId: Long,
    val ttl: Int,
    val byteSize: Int,
    val details: String
)

@Entity(tableName = "processed_packets")
data class ProcessedPacketEntity(
    @PrimaryKey val messageId: String,
    val timestamp: Long
)

@Entity(tableName = "topology_edges", primaryKeys = ["fromNode", "toNode"])
data class TopologyEdgeEntity(
    val fromNode: Long,
    val toNode: Long,
    val rssi: Int = 0,
    val lastSeen: Long = System.currentTimeMillis(),
    val state: String = "STAGED",
    val fromIdentityHashHex: String? = null,
    val toIdentityHashHex: String? = null
)

@Entity(tableName = "last_known_locations")
data class LastKnownLocationEntity(
    @PrimaryKey val nodeId: Long,
    val alias: String,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float = 0f,
    val timestamp: Long = System.currentTimeMillis(), // Hardware GPS fix timestamp (fixTimestampMillis)
    val sequenceNumber: Long = 1L,
    val receivedTimestamp: Long = System.currentTimeMillis(),
    val altitude: Double = 0.0,
    val batteryPercent: Int = -1,
    val triggerType: Int = 1,
    val note: String? = null
) {
    val nodeIdHex: String
        get() = String.format("%016X", nodeId)
}

@Entity(
    tableName = "breadcrumb_history",
    indices = [Index(value = ["nodeId", "sequenceNumber"], unique = true)]
)
data class BreadcrumbHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nodeId: Long,
    val sequenceNumber: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracyMeters: Float = 0.0f,
    val batteryPercent: Int = -1,
    val triggerType: Int = 1,
    val sentTimestamp: Long = System.currentTimeMillis(),
    val receivedTimestamp: Long = System.currentTimeMillis(),
    val note: String? = null
)

/**
 * vNext Identity entity representing persistent cryptographic identities in Room schema 12.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.1–§2.7 and NEXTGEN/02_VNEXT_IMPLEMENTATION_PLAN.md §11.3.
 */
@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val identityHashHex: String,
    val ikPubHex: String,
    val ekPubHex: String,
    val keyVersion: Long = 1L,
    val lastAnnounceCounter: Long = 0L,
    val trustState: String = "SEEN",
    val nodeId64: Long = 0L,
    val alias: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
)



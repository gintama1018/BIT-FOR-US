package com.meshwhisper.app.data.model

import androidx.room.Entity
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
    val isMuted: Boolean = false
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
    CANCELLED
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
    val mediaPreviewBase64: String? = null
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
    val lastSeen: Long = System.currentTimeMillis()
)



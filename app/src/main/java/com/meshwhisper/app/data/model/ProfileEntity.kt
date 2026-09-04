package com.meshwhisper.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val nodeId: Long,
    val displayName: String,
    val bio: String = "",
    val avatarHashHex: String = "",
    val avatarUri: String? = null,
    val version: Long = 1L,
    val signature: ByteArray? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val nodeIdHex: String
        get() = String.format("%016X", nodeId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ProfileEntity
        return nodeId == other.nodeId &&
                displayName == other.displayName &&
                bio == other.bio &&
                avatarHashHex == other.avatarHashHex &&
                avatarUri == other.avatarUri &&
                version == other.version &&
                (signature?.contentEquals(other.signature ?: ByteArray(0)) ?: (other.signature == null)) &&
                updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + displayName.hashCode()
        result = 31 * result + bio.hashCode()
        result = 31 * result + avatarHashHex.hashCode()
        result = 31 * result + (avatarUri?.hashCode() ?: 0)
        result = 31 * result + version.hashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

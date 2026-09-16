package com.meshwhisper.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Neighbor entry inside PEER_ANNOUNCE v2.
 */
data class NeighborEntry(
    val nodeId64: Long,
    val linkQuality: Byte
) : Comparable<NeighborEntry> {
    override fun compareTo(other: NeighborEntry): Int = nodeId64.compareTo(other.nodeId64)
}

/**
 * GPS location entry inside PEER_ANNOUNCE v2 (TTL=1 only).
 */
data class AnnounceLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val fixTime: Long
)

/**
 * vNext PEER_ANNOUNCE (0x24) Payload format.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.3.
 */
data class PeerAnnouncePayload(
    val announceVersion: Byte = 0x02,
    val flags: Byte,
    val ikPub: ByteArray,
    val ekPub: ByteArray,
    val keyVersion: Long,
    val notBefore: Long,
    val ibcSignature: ByteArray,
    val announceCounter: Long,
    val alias: String,
    val avatarHashPrefix: ByteArray = ByteArray(4), // 4 bytes
    val neighbors: List<NeighborEntry> = emptyList(),
    val location: AnnounceLocation? = null
) {
    init {
        require(announceVersion == 0x02.toByte()) { "announceVersion must be 0x02, got $announceVersion" }
        require(ikPub.size == 32) { "ikPub must be 32 bytes" }
        require(ekPub.size == 32) { "ekPub must be 32 bytes" }
        require(keyVersion in 1L..0xFFFFFFFFL) { "keyVersion must be in 1..0xFFFFFFFF" }
        require(notBefore in 0L..0xFFFFFFFFL) { "notBefore must be in 0..0xFFFFFFFF" }
        require(ibcSignature.size == 64) { "ibcSignature must be 64 bytes" }
        require(announceCounter >= 1L) { "announceCounter must be >= 1" }
        require(alias.toByteArray(Charsets.UTF_8).size <= 64) { "alias must be <= 64 bytes" }
        require(avatarHashPrefix.size == 4) { "avatarHashPrefix must be 4 bytes" }
        require(neighbors.size <= 16) { "neighbors must be <= 16" }
    }

    val hasLocation: Boolean get() = (flags.toInt() and 0x01) != 0
    val hasNeighbors: Boolean get() = (flags.toInt() and 0x02) != 0
    val hasAvatarHash: Boolean get() = (flags.toInt() and 0x04) != 0

    fun serialize(): ByteArray {
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val locationSize = if (hasLocation && location != null) 28 else 0
        val totalSize = 1 + 1 + 32 + 32 + 4 + 4 + 64 + 8 + 1 + aliasBytes.size + 4 + 1 + (neighbors.size * 9) + locationSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)
        buf.put(announceVersion)
        buf.put(flags)
        buf.put(ikPub)
        buf.put(ekPub)
        buf.putInt((keyVersion and 0xFFFFFFFFL).toInt())
        buf.putInt((notBefore and 0xFFFFFFFFL).toInt())
        buf.put(ibcSignature)
        buf.putLong(announceCounter)
        buf.put((aliasBytes.size and 0xFF).toByte())
        buf.put(aliasBytes)
        buf.put(avatarHashPrefix)
        buf.put((neighbors.size and 0xFF).toByte())
        for (n in neighbors) {
            buf.putLong(n.nodeId64)
            buf.put(n.linkQuality)
        }
        if (hasLocation && location != null) {
            buf.putDouble(location.latitude)
            buf.putDouble(location.longitude)
            buf.putFloat(location.accuracy)
            buf.putLong(location.fixTime)
        }
        return buf.array()
    }

    companion object {
        fun deserialize(bytes: ByteArray, senderNodeId64: Long, packetTtl: Int): PeerAnnouncePayload? {
            if (bytes.size < 143 || bytes.size > 388) return null

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

            val version = buf.get()
            if (version != 0x02.toByte()) return null

            val flags = buf.get()
            val flagsInt = flags.toInt() and 0xFF
            // Reserved bits 3..7 MUST be 0
            if ((flagsInt and 0xF8) != 0) return null

            val hasLocation = (flagsInt and 0x01) != 0
            val hasNeighbors = (flagsInt and 0x02) != 0
            val hasAvatarHash = (flagsInt and 0x04) != 0

            // C-03: location forbidden if ttl != 1
            if (hasLocation && packetTtl != 1) return null

            val ikPub = ByteArray(32)
            buf.get(ikPub)

            val ekPub = ByteArray(32)
            buf.get(ekPub)

            val keyVersion = buf.getInt().toLong() and 0xFFFFFFFFL
            if (keyVersion < 1L) return null

            val notBefore = buf.getInt().toLong() and 0xFFFFFFFFL

            val ibcSig = ByteArray(64)
            buf.get(ibcSig)

            val announceCounter = buf.getLong()
            if (announceCounter < 1L) return null

            val aliasLen = buf.get().toInt() and 0xFF
            if (aliasLen > 64 || buf.remaining() < aliasLen) return null

            val aliasBytes = ByteArray(aliasLen)
            buf.get(aliasBytes)

            // UTF-8 check and no C0/C1 control chars
            val alias = try {
                val s = String(aliasBytes, Charsets.UTF_8)
                if (s.any { it.code in 0x00..0x1F || it.code in 0x7F..0x9F }) return null
                s
            } catch (_: Exception) {
                return null
            }

            if (buf.remaining() < 4) return null
            val avatarHashPrefix = ByteArray(4)
            buf.get(avatarHashPrefix)

            if (buf.remaining() < 1) return null
            val neighborCount = buf.get().toInt() and 0xFF
            if (neighborCount > 16) return null
            if (!hasNeighbors && neighborCount != 0) return null
            if (hasNeighbors && neighborCount == 0) return null

            if (buf.remaining() < neighborCount * 9) return null
            val neighbors = mutableListOf<NeighborEntry>()
            var prevNodeId: Long? = null
            for (i in 0 until neighborCount) {
                val nId = buf.getLong()
                val lq = buf.get()
                // Cannot contain 0L or sender's own nodeId
                if (nId == 0L || nId == senderNodeId64) return null
                // Must be strictly ascending and unique
                if (prevNodeId != null && nId <= prevNodeId) return null
                prevNodeId = nId
                neighbors.add(NeighborEntry(nId, lq))
            }

            val location = if (hasLocation) {
                if (buf.remaining() < 28) return null
                val lat = buf.getDouble()
                val lon = buf.getDouble()
                val acc = buf.getFloat()
                val fixTime = buf.getLong()
                AnnounceLocation(lat, lon, acc, fixTime)
            } else {
                null
            }

            // Reject if trailing bytes exist (declared sizes must sum exactly to plaintext length)
            if (buf.hasRemaining()) return null

            return PeerAnnouncePayload(
                announceVersion = version,
                flags = flags,
                ikPub = ikPub,
                ekPub = ekPub,
                keyVersion = keyVersion,
                notBefore = notBefore,
                ibcSignature = ibcSig,
                announceCounter = announceCounter,
                alias = alias,
                avatarHashPrefix = avatarHashPrefix,
                neighbors = neighbors,
                location = location
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PeerAnnouncePayload

        if (announceVersion != other.announceVersion) return false
        if (flags != other.flags) return false
        if (!ikPub.contentEquals(other.ikPub)) return false
        if (!ekPub.contentEquals(other.ekPub)) return false
        if (keyVersion != other.keyVersion) return false
        if (notBefore != other.notBefore) return false
        if (!ibcSignature.contentEquals(other.ibcSignature)) return false
        if (announceCounter != other.announceCounter) return false
        if (alias != other.alias) return false
        if (!avatarHashPrefix.contentEquals(other.avatarHashPrefix)) return false
        if (neighbors != other.neighbors) return false
        if (location != other.location) return false

        return true
    }

    override fun hashCode(): Int {
        var result = announceVersion.toInt()
        result = 31 * result + flags.toInt()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + ekPub.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + notBefore.hashCode()
        result = 31 * result + ibcSignature.contentHashCode()
        result = 31 * result + announceCounter.hashCode()
        result = 31 * result + alias.hashCode()
        result = 31 * result + avatarHashPrefix.contentHashCode()
        result = 31 * result + neighbors.hashCode()
        result = 31 * result + (location?.hashCode() ?: 0)
        return result
    }
}

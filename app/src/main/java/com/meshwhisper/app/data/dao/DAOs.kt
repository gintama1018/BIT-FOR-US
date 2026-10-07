package com.meshwhisper.app.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.ProcessedPacketEntity
import com.meshwhisper.app.data.model.StoreForwardEntity
import kotlinx.coroutines.flow.Flow
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers")
    suspend fun getAllPeersList(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getPeerById(nodeId: Long): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(peer: PeerEntity)

    @Update
    suspend fun update(peer: PeerEntity)

    @Query("UPDATE peers SET isBlocked = :blocked WHERE nodeId = :nodeId")
    suspend fun setPeerBlocked(nodeId: Long, blocked: Boolean)

    @Query("UPDATE peers SET rssi = :rssi WHERE nodeId = :nodeId")
    suspend fun updateRssi(nodeId: Long, rssi: Int)

    @Query("UPDATE peers SET hasKeyChanged = :hasChanged, previousFingerprint = :prevFp WHERE nodeId = :nodeId")
    suspend fun markKeyChanged(nodeId: Long, hasChanged: Boolean, prevFp: String?)

    @Query("UPDATE peers SET avatarUri = :avatarUri, avatarHash = :avatarHash WHERE nodeId = :nodeId")
    suspend fun updateAvatar(nodeId: Long, avatarUri: String?, avatarHash: Byte)

    @Query("UPDATE peers SET isMuted = :isMuted WHERE nodeId = :nodeId")
    suspend fun setPeerMuted(nodeId: Long, isMuted: Boolean)

    @Query("SELECT * FROM peers WHERE identityHashHex = :identityHashHex LIMIT 1")
    suspend fun getPeerByIdentityHash(identityHashHex: String): PeerEntity?

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun getPeersByNodeId(nodeId: Long): List<PeerEntity>

    @Query("""
        UPDATE peers 
        SET trustState = :trustState, 
            isVerified = CASE WHEN :trustState = 'VERIFIED' THEN 1 ELSE 0 END 
        WHERE identityHashHex = :identityHashHex
    """)
    suspend fun updateTrustStateByIdentityHash(identityHashHex: String, trustState: String)

    @Query("""
        UPDATE peers 
        SET trustState = :trustState, 
            isVerified = CASE WHEN :trustState = 'VERIFIED' THEN 1 ELSE 0 END 
        WHERE nodeId = :nodeId
    """)
    suspend fun updateTrustStateByNodeId(nodeId: Long, trustState: String)

    @Query("UPDATE peers SET hasKeyChanged = :hasChanged WHERE identityHashHex = :identityHashHex")
    suspend fun updateHasKeyChangedByIdentityHash(identityHashHex: String, hasChanged: Boolean)

    @Query("UPDATE peers SET hasKeyChanged = :hasChanged WHERE nodeId = :nodeId")
    suspend fun updateHasKeyChangedByNodeId(nodeId: Long, hasChanged: Boolean)

    @Query("SELECT * FROM peers WHERE trustState = 'VERIFIED' AND shareLocationWithContact = 1")
    suspend fun getOptedInPeers(): List<PeerEntity>

    @Query("SELECT * FROM peers WHERE trustState = 'VERIFIED' AND shareLocationWithContact = 1")
    fun getOptedInPeersFlow(): Flow<List<PeerEntity>>

    @Query("UPDATE peers SET shareLocationWithContact = :enabled WHERE nodeId = :nodeId")
    suspend fun setShareLocationWithContact(nodeId: Long, enabled: Boolean)

    @Query("DELETE FROM peers WHERE nodeId = :nodeId")
    suspend fun deletePeer(nodeId: Long)

    @Query("DELETE FROM peers")
    suspend fun deleteAll()
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE isBroadcast = 1 ORDER BY timestamp ASC")
    fun getBroadcastMessages(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE isBroadcast = 0 AND (senderId = :peerNodeId OR recipientId = :peerNodeId) ORDER BY timestamp ASC")
    fun getDirectMessagesForPeer(peerNodeId: Long): Flow<List<MessageEntity>>

    @Query("""
        SELECT m.* FROM messages m
        INNER JOIN (
            SELECT 
                CASE WHEN isOutgoing = 1 THEN recipientId ELSE senderId END AS peerId,
                MAX(timestamp) AS maxTs
            FROM messages
            WHERE isBroadcast = 0
            GROUP BY peerId
        ) latest ON (CASE WHEN m.isOutgoing = 1 THEN m.recipientId ELSE m.senderId END) = latest.peerId
        AND m.timestamp = latest.maxTs
        WHERE m.isBroadcast = 0
        GROUP BY (CASE WHEN m.isOutgoing = 1 THEN m.recipientId ELSE m.senderId END)
        ORDER BY m.timestamp DESC
    """)
    fun getRecentDirectConversations(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: MessageStatus)

    @Query("UPDATE messages SET mediaProgress = :progress, mediaUri = :mediaUri, status = :status WHERE messageId = :messageId")
    suspend fun updateMediaTransfer(messageId: String, progress: Float, mediaUri: String?, status: MessageStatus)

    @Query("SELECT * FROM messages WHERE isSos = 1 ORDER BY timestamp DESC LIMIT :limit")
    fun getSosMessages(limit: Int = 50): Flow<List<MessageEntity>>

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface StoreForwardDao {
    @Query("SELECT * FROM store_forward_queue WHERE recipientId = :recipientId AND expiresAt > :currentTime ORDER BY createdAt ASC")
    suspend fun getPendingForRecipient(recipientId: Long, currentTime: Long): List<StoreForwardEntity>

    @Query("SELECT DISTINCT recipientId FROM store_forward_queue WHERE expiresAt > :currentTime")
    suspend fun getPendingRecipients(currentTime: Long = System.currentTimeMillis()): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: StoreForwardEntity)

    @Query("SELECT * FROM store_forward_queue")
    suspend fun getAll(): List<StoreForwardEntity>

    @Query("SELECT COUNT(*) FROM store_forward_queue")
    suspend fun getCount(): Int

    @Query("SELECT * FROM store_forward_queue WHERE messageId = :messageId")
    suspend fun getById(messageId: String): StoreForwardEntity?

    @Query("DELETE FROM store_forward_queue WHERE recipientId = :recipientId AND messageId NOT IN (SELECT messageId FROM store_forward_queue WHERE recipientId = :recipientId ORDER BY createdAt DESC LIMIT :keepLimit)")
    suspend fun trimRecipientQueue(recipientId: Long, keepLimit: Int = 50)

    @Query("DELETE FROM store_forward_queue WHERE messageId NOT IN (SELECT messageId FROM store_forward_queue ORDER BY createdAt DESC LIMIT :keepLimit)")
    suspend fun trimTotalQueue(keepLimit: Int = 500)

    @Query("DELETE FROM store_forward_queue WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM store_forward_queue WHERE expiresAt <= :currentTime")
    suspend fun purgeExpired(currentTime: Long): Int

    /**
     * Inserts an entity respecting the 300 own / 200 relayed partition limits (S-12, §8).
     * Hostile relayed flood can never evict or touch the 300 own-message partition.
     */
    suspend fun insertPartitioned(item: StoreForwardEntity, localNodeId: Long) {
        val isOwn = if (item.packetData.size >= 25) {
            val buf = ByteBuffer.wrap(item.packetData).order(ByteOrder.BIG_ENDIAN)
            buf.getLong(17) == localNodeId
        } else {
            false
        }

        val all = getAll()
        if (isOwn) {
            val ownItems = all.filter {
                it.packetData.size >= 25 && ByteBuffer.wrap(it.packetData).order(ByteOrder.BIG_ENDIAN).getLong(17) == localNodeId
            }
            if (ownItems.size >= 300) {
                val toTrim = ownItems.sortedBy { it.createdAt }.take(ownItems.size - 299)
                for (old in toTrim) {
                    delete(old.messageId)
                }
            }
            insert(item)
        } else {
            val relayedItems = all.filter {
                it.packetData.size < 25 || ByteBuffer.wrap(it.packetData).order(ByteOrder.BIG_ENDIAN).getLong(17) != localNodeId
            }
            if (relayedItems.size >= 200) {
                // Relayed partition full: trim oldest relayed entry, preserving own partition untouched
                val toTrim = relayedItems.sortedBy { it.createdAt }.take(relayedItems.size - 199)
                for (old in toTrim) {
                    delete(old.messageId)
                }
            }
            insert(item)
        }
    }
}

@Dao
interface PacketLogDao {
    @Query("SELECT * FROM packet_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 100): Flow<List<PacketLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: PacketLogEntity)

    @Query("DELETE FROM packet_logs WHERE id NOT IN (SELECT id FROM packet_logs ORDER BY timestamp DESC LIMIT :keepLimit)")
    suspend fun trimOldLogs(keepLimit: Int = 500)

    @Query("DELETE FROM packet_logs")
    suspend fun deleteAll()
}

@Dao
interface ProcessedPacketDao {
    @Query("SELECT COUNT(*) FROM processed_packets WHERE messageId = :messageId LIMIT 1")
    suspend fun hasSeen(messageId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markSeen(packet: ProcessedPacketEntity): Long
    // Returns the new row ID on first insertion, or -1 if the packet was already seen (atomic dedup)

    @Query("DELETE FROM processed_packets WHERE timestamp < :cutoffTime")
    suspend fun purgeOld(cutoffTime: Long): Int

    @Query("SELECT messageId FROM processed_packets ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessageIds(limit: Int): List<String>

    @Query("DELETE FROM processed_packets WHERE messageId NOT IN (SELECT messageId FROM processed_packets ORDER BY timestamp DESC LIMIT :maxRows)")
    suspend fun pruneExcessRows(maxRows: Int): Int

    @Query("SELECT COUNT(*) FROM processed_packets")
    suspend fun count(): Int

    @Query("DELETE FROM processed_packets")
    suspend fun deleteAll()
}

@Dao
interface TopologyEdgeDao {
    @Query("SELECT * FROM topology_edges ORDER BY lastSeen DESC")
    fun getAllEdges(): Flow<List<com.meshwhisper.app.data.model.TopologyEdgeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(edge: com.meshwhisper.app.data.model.TopologyEdgeEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAll(edges: List<com.meshwhisper.app.data.model.TopologyEdgeEntity>)

    @Query("DELETE FROM topology_edges WHERE lastSeen < :cutoffTimestamp")
    suspend fun pruneStaleEdges(cutoffTimestamp: Long): Int

    @Query("DELETE FROM topology_edges")
    suspend fun deleteAll()
}

@Dao
interface LocationDao {
    @Query("SELECT * FROM last_known_locations ORDER BY timestamp DESC")
    fun getAllLocations(): Flow<List<com.meshwhisper.app.data.model.LastKnownLocationEntity>>

    @Query("SELECT * FROM last_known_locations WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getLocationForNode(nodeId: Long): com.meshwhisper.app.data.model.LastKnownLocationEntity?

    @Query("SELECT * FROM last_known_locations WHERE nodeId = :nodeId LIMIT 1")
    fun getLocationFlowForNode(nodeId: Long): Flow<com.meshwhisper.app.data.model.LastKnownLocationEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(location: com.meshwhisper.app.data.model.LastKnownLocationEntity)

    @Query("""
        UPDATE last_known_locations 
        SET alias = :alias,
            latitude = :latitude,
            longitude = :longitude,
            accuracyMeters = :accuracyMeters,
            timestamp = :fixTimestamp,
            sequenceNumber = :sequenceNumber,
            receivedTimestamp = :receivedTimestamp,
            altitude = :altitude,
            batteryPercent = :batteryPercent,
            triggerType = :triggerType,
            note = :note
        WHERE nodeId = :nodeId 
          AND (:fixTimestamp > timestamp OR (:fixTimestamp = timestamp AND :sequenceNumber > sequenceNumber))
    """)
    suspend fun updateIfNewer(
        nodeId: Long,
        alias: String,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
        fixTimestamp: Long,
        sequenceNumber: Long,
        receivedTimestamp: Long,
        altitude: Double,
        batteryPercent: Int,
        triggerType: Int,
        note: String?
    ): Int

    @Query("DELETE FROM last_known_locations WHERE nodeId = :nodeId")
    suspend fun deleteLocationForNode(nodeId: Long)

    @Query("SELECT * FROM breadcrumb_history WHERE nodeId = :nodeId ORDER BY sequenceNumber DESC LIMIT :limit")
    fun getBreadcrumbHistory(nodeId: Long, limit: Int = 5): Flow<List<com.meshwhisper.app.data.model.BreadcrumbHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertHistory(history: com.meshwhisper.app.data.model.BreadcrumbHistoryEntity)

    @Query("DELETE FROM breadcrumb_history WHERE nodeId = :nodeId")
    suspend fun deleteHistoryForNode(nodeId: Long)

    @Query("DELETE FROM breadcrumb_history WHERE nodeId = :nodeId AND id NOT IN (SELECT id FROM breadcrumb_history WHERE nodeId = :nodeId ORDER BY sequenceNumber DESC LIMIT :keepLimit)")
    suspend fun pruneHistory(nodeId: Long, keepLimit: Int = 5)

    @Query("DELETE FROM last_known_locations")
    suspend fun deleteAll()
}

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE nodeId = :nodeId LIMIT 1")
    fun getProfileFlow(nodeId: Long): Flow<com.meshwhisper.app.data.model.ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE nodeId = :nodeId LIMIT 1")
    suspend fun getProfile(nodeId: Long): com.meshwhisper.app.data.model.ProfileEntity?

    @Query("SELECT * FROM profiles ORDER BY updatedAt DESC")
    fun getAllProfiles(): Flow<List<com.meshwhisper.app.data.model.ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: com.meshwhisper.app.data.model.ProfileEntity)

    @Query("UPDATE profiles SET avatarUri = :uri, avatarHashHex = :hashHex, updatedAt = :timestamp WHERE nodeId = :nodeId")
    suspend fun updateAvatar(nodeId: Long, uri: String, hashHex: String, timestamp: Long = System.currentTimeMillis())

    @Query("DELETE FROM profiles WHERE nodeId = :nodeId")
    suspend fun deleteProfile(nodeId: Long)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities WHERE identityHashHex = :hashHex LIMIT 1")
    suspend fun getByIdentityHash(hashHex: String): com.meshwhisper.app.data.model.IdentityEntity?

    @Query("SELECT * FROM identities WHERE nodeId64 = :nodeId64 LIMIT 1")
    suspend fun getByNodeId64(nodeId64: Long): com.meshwhisper.app.data.model.IdentityEntity?

    @Query("SELECT * FROM identities WHERE nodeId64 = :nodeId64")
    suspend fun getAllByNodeId64(nodeId64: Long): List<com.meshwhisper.app.data.model.IdentityEntity>

    @Query("SELECT * FROM identities")
    suspend fun getAll(): List<com.meshwhisper.app.data.model.IdentityEntity>

    @Query("""
        SELECT * FROM identities 
        ORDER BY 
            CASE 
                WHEN trustState = 'VERIFIED' THEN 1 
                WHEN trustState = 'CONFLICTED' THEN 2 
                WHEN trustState = 'BLOCKED' THEN 3 
                ELSE 4 
            END ASC,
            lastSeenAt DESC
        LIMIT :limit
    """)
    suspend fun getPrioritizedIdentities(limit: Int = 1024): List<com.meshwhisper.app.data.model.IdentityEntity>

    @Query("""
        DELETE FROM identities 
        WHERE trustState NOT IN ('VERIFIED', 'CONFLICTED', 'BLOCKED')
          AND identityHashHex NOT IN (
              SELECT identityHashHex FROM identities 
              WHERE trustState NOT IN ('VERIFIED', 'CONFLICTED', 'BLOCKED') 
              ORDER BY lastSeenAt DESC 
              LIMIT :maxUnverified
          )
    """)
    suspend fun pruneExcessUnverified(maxUnverified: Int = 1024): Int

    @Query("SELECT COUNT(*) FROM identities")
    suspend fun getIdentityCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(identity: com.meshwhisper.app.data.model.IdentityEntity)

    @Query("DELETE FROM identities WHERE identityHashHex = :hashHex")
    suspend fun delete(hashHex: String)

    @Query("DELETE FROM identities")
    suspend fun deleteAll()
}



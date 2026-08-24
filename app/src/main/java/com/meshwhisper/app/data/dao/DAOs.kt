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

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeen DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

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

    @Query("DELETE FROM messages")
    suspend fun deleteAll()
}

@Dao
interface StoreForwardDao {
    @Query("SELECT * FROM store_forward_queue WHERE recipientId = :recipientId AND expiresAt > :currentTime ORDER BY createdAt ASC")
    suspend fun getPendingForRecipient(recipientId: Long, currentTime: Long): List<StoreForwardEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: StoreForwardEntity)

    @Query("DELETE FROM store_forward_queue WHERE messageId = :messageId")
    suspend fun delete(messageId: String)

    @Query("DELETE FROM store_forward_queue WHERE expiresAt <= :currentTime")
    suspend fun purgeExpired(currentTime: Long): Int
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


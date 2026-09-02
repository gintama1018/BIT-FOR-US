package com.meshwhisper.desktop.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

data class DesktopPeer(
    val nodeId: Long,
    val publicKeyHex: String,
    val alias: String,
    val rssi: Int = -60,
    val hops: Int = 1,
    val lastSeen: Long = System.currentTimeMillis(),
    val isPinned: Boolean = false,
    val isBlocked: Boolean = false,
    val publicFingerprint: String = ""
)

data class DesktopMessage(
    val messageId: String,
    val senderNodeId: Long,
    val recipientNodeId: Long,
    val text: String,
    val timestamp: Long,
    val isIncoming: Boolean,
    val isAcked: Boolean = false,
    val isDelivered: Boolean = true,
    val ttlRemaining: Int = 7,
    val isChannelBroadcast: Boolean = false,
    val channelName: String? = null,
    val isEmergencySos: Boolean = false
)

data class DesktopStoreForward(
    val messageId: String,
    val recipientId: Long,
    val packetData: ByteArray,
    val createdAt: Long,
    val expiresAt: Long
)

data class DesktopPacketLog(
    val id: Long = 0L,
    val timestamp: Long,
    val direction: String,
    val type: String,
    val messageIdHex: String,
    val sizeBytes: Int,
    val info: String
)

data class DesktopTopologyEdge(
    val sourceNodeId: Long,
    val targetNodeId: Long,
    val rssi: Int,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Pure SQLite Database layer for MeshWhisper Desktop via sqlite-jdbc.
 * Manages all 6 core tables matching the Android schema parity.
 */
class DesktopDatabase(
    private val dbFile: File = File(File(System.getProperty("user.home"), ".meshwhisper"), "meshwhisper.db")
) {

    private val jdbcUrl: String

    init {
        val parent = dbFile.parentFile
        if (parent != null && !parent.exists()) {
            parent.mkdirs()
        }
        jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        initSchema()
    }

    private fun getConnection(): Connection {
        return DriverManager.getConnection(jdbcUrl)
    }

    private fun initSchema() {
        getConnection().use { conn ->
            conn.createStatement().use { stmt ->
                // 1. Peers
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS peers (
                        nodeId INTEGER PRIMARY KEY,
                        publicKeyHex TEXT NOT NULL,
                        alias TEXT NOT NULL,
                        rssi INTEGER NOT NULL,
                        hops INTEGER NOT NULL,
                        lastSeen INTEGER NOT NULL,
                        isPinned INTEGER NOT NULL DEFAULT 0,
                        isBlocked INTEGER NOT NULL DEFAULT 0,
                        publicFingerprint TEXT NOT NULL DEFAULT ''
                    );
                """.trimIndent())

                // 2. Messages
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS messages (
                        messageId TEXT PRIMARY KEY,
                        senderNodeId INTEGER NOT NULL,
                        recipientNodeId INTEGER NOT NULL,
                        text TEXT NOT NULL,
                        timestamp INTEGER NOT NULL,
                        isIncoming INTEGER NOT NULL,
                        isAcked INTEGER NOT NULL DEFAULT 0,
                        isDelivered INTEGER NOT NULL DEFAULT 1,
                        ttlRemaining INTEGER NOT NULL DEFAULT 7,
                        isChannelBroadcast INTEGER NOT NULL DEFAULT 0,
                        channelName TEXT,
                        isEmergencySos INTEGER NOT NULL DEFAULT 0
                    );
                """.trimIndent())

                // 3. Store and Forward
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS store_forward (
                        messageId TEXT PRIMARY KEY,
                        recipientId INTEGER NOT NULL,
                        packetData BLOB NOT NULL,
                        createdAt INTEGER NOT NULL,
                        expiresAt INTEGER NOT NULL
                    );
                """.trimIndent())

                // 4. Packet Logs
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS packet_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        timestamp INTEGER NOT NULL,
                        direction TEXT NOT NULL,
                        type TEXT NOT NULL,
                        messageIdHex TEXT NOT NULL,
                        sizeBytes INTEGER NOT NULL,
                        info TEXT NOT NULL
                    );
                """.trimIndent())

                // 5. Processed Packets (Dedup)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS processed_packets (
                        dedupKey TEXT PRIMARY KEY,
                        processedAt INTEGER NOT NULL
                    );
                """.trimIndent())

                // 6. Topology Edges (Mesh Radar Graph)
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS topology_edges (
                        sourceNodeId INTEGER NOT NULL,
                        targetNodeId INTEGER NOT NULL,
                        rssi INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        PRIMARY KEY (sourceNodeId, targetNodeId)
                    );
                """.trimIndent())
            }
        }
    }

    // --- Peer DAO Operations ---
    @Synchronized
    fun upsertPeer(peer: DesktopPeer) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO peers (nodeId, publicKeyHex, alias, rssi, hops, lastSeen, isPinned, isBlocked, publicFingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(nodeId) DO UPDATE SET
                    publicKeyHex=excluded.publicKeyHex,
                    alias=excluded.alias,
                    rssi=excluded.rssi,
                    hops=excluded.hops,
                    lastSeen=excluded.lastSeen,
                    publicFingerprint=excluded.publicFingerprint;
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, peer.nodeId)
                stmt.setString(2, peer.publicKeyHex)
                stmt.setString(3, peer.alias)
                stmt.setInt(4, peer.rssi)
                stmt.setInt(5, peer.hops)
                stmt.setLong(6, peer.lastSeen)
                stmt.setInt(7, if (peer.isPinned) 1 else 0)
                stmt.setInt(8, if (peer.isBlocked) 1 else 0)
                stmt.setString(9, peer.publicFingerprint)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getPeer(nodeId: Long): DesktopPeer? {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM peers WHERE nodeId = ?").use { stmt ->
                stmt.setLong(1, nodeId)
                val rs = stmt.executeQuery()
                return if (rs.next()) mapPeer(rs) else null
            }
        }
    }

    @Synchronized
    fun getAllPeers(): List<DesktopPeer> {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM peers ORDER BY lastSeen DESC").use { stmt ->
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopPeer>()
                while (rs.next()) {
                    list.add(mapPeer(rs))
                }
                return list
            }
        }
    }

    private fun mapPeer(rs: ResultSet): DesktopPeer {
        return DesktopPeer(
            nodeId = rs.getLong("nodeId"),
            publicKeyHex = rs.getString("publicKeyHex"),
            alias = rs.getString("alias"),
            rssi = rs.getInt("rssi"),
            hops = rs.getInt("hops"),
            lastSeen = rs.getLong("lastSeen"),
            isPinned = rs.getInt("isPinned") == 1,
            isBlocked = rs.getInt("isBlocked") == 1,
            publicFingerprint = rs.getString("publicFingerprint") ?: ""
        )
    }

    // --- Message DAO Operations ---
    @Synchronized
    fun insertMessage(msg: DesktopMessage) {
        getConnection().use { conn ->
            val sql = """
                INSERT OR REPLACE INTO messages (
                    messageId, senderNodeId, recipientNodeId, text, timestamp,
                    isIncoming, isAcked, isDelivered, ttlRemaining, isChannelBroadcast,
                    channelName, isEmergencySos
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, msg.messageId)
                stmt.setLong(2, msg.senderNodeId)
                stmt.setLong(3, msg.recipientNodeId)
                stmt.setString(4, msg.text)
                stmt.setLong(5, msg.timestamp)
                stmt.setInt(6, if (msg.isIncoming) 1 else 0)
                stmt.setInt(7, if (msg.isAcked) 1 else 0)
                stmt.setInt(8, if (msg.isDelivered) 1 else 0)
                stmt.setInt(9, msg.ttlRemaining)
                stmt.setInt(10, if (msg.isChannelBroadcast) 1 else 0)
                stmt.setString(11, msg.channelName)
                stmt.setInt(12, if (msg.isEmergencySos) 1 else 0)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getPublicAndSosMessages(): List<DesktopMessage> {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM messages WHERE recipientNodeId = -1 OR isEmergencySos = 1 ORDER BY timestamp ASC").use { stmt ->
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopMessage>()
                while (rs.next()) {
                    list.add(mapMessage(rs))
                }
                return list
            }
        }
    }

    @Synchronized
    fun getDirectConversation(peerNodeId: Long, myNodeId: Long): List<DesktopMessage> {
        getConnection().use { conn ->
            val sql = "SELECT * FROM messages WHERE (senderNodeId = ? AND recipientNodeId = ?) OR (senderNodeId = ? AND recipientNodeId = ?) ORDER BY timestamp ASC"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, peerNodeId)
                stmt.setLong(2, myNodeId)
                stmt.setLong(3, myNodeId)
                stmt.setLong(4, peerNodeId)
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopMessage>()
                while (rs.next()) {
                    list.add(mapMessage(rs))
                }
                return list
            }
        }
    }

    private fun mapMessage(rs: ResultSet): DesktopMessage {
        return DesktopMessage(
            messageId = rs.getString("messageId"),
            senderNodeId = rs.getLong("senderNodeId"),
            recipientNodeId = rs.getLong("recipientNodeId"),
            text = rs.getString("text"),
            timestamp = rs.getLong("timestamp"),
            isIncoming = rs.getInt("isIncoming") == 1,
            isAcked = rs.getInt("isAcked") == 1,
            isDelivered = rs.getInt("isDelivered") == 1,
            ttlRemaining = rs.getInt("ttlRemaining"),
            isChannelBroadcast = rs.getInt("isChannelBroadcast") == 1,
            channelName = rs.getString("channelName"),
            isEmergencySos = rs.getInt("isEmergencySos") == 1
        )
    }

    // --- Topology Edge DAO Operations (Mesh Radar) ---
    @Synchronized
    fun upsertTopologyEdge(edge: DesktopTopologyEdge) {
        getConnection().use { conn ->
            val sql = """
                INSERT INTO topology_edges (sourceNodeId, targetNodeId, rssi, updatedAt)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(sourceNodeId, targetNodeId) DO UPDATE SET
                    rssi=excluded.rssi,
                    updatedAt=excluded.updatedAt;
            """.trimIndent()
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, edge.sourceNodeId)
                stmt.setLong(2, edge.targetNodeId)
                stmt.setInt(3, edge.rssi)
                stmt.setLong(4, edge.updatedAt)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getAllTopologyEdges(): List<DesktopTopologyEdge> {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM topology_edges ORDER BY updatedAt DESC").use { stmt ->
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopTopologyEdge>()
                while (rs.next()) {
                    list.add(
                        DesktopTopologyEdge(
                            sourceNodeId = rs.getLong("sourceNodeId"),
                            targetNodeId = rs.getLong("targetNodeId"),
                            rssi = rs.getInt("rssi"),
                            updatedAt = rs.getLong("updatedAt")
                        )
                    )
                }
                return list
            }
        }
    }

    // --- Packet Logs DAO Operations ---
    @Synchronized
    fun insertPacketLog(log: DesktopPacketLog) {
        getConnection().use { conn ->
            val sql = "INSERT INTO packet_logs (timestamp, direction, type, messageIdHex, sizeBytes, info) VALUES (?, ?, ?, ?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setLong(1, log.timestamp)
                stmt.setString(2, log.direction)
                stmt.setString(3, log.type)
                stmt.setString(4, log.messageIdHex)
                stmt.setInt(5, log.sizeBytes)
                stmt.setString(6, log.info)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getRecentPacketLogs(limit: Int = 100): List<DesktopPacketLog> {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT * FROM packet_logs ORDER BY id DESC LIMIT ?").use { stmt ->
                stmt.setInt(1, limit)
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopPacketLog>()
                while (rs.next()) {
                    list.add(
                        DesktopPacketLog(
                            id = rs.getLong("id"),
                            timestamp = rs.getLong("timestamp"),
                            direction = rs.getString("direction"),
                            type = rs.getString("type"),
                            messageIdHex = rs.getString("messageIdHex"),
                            sizeBytes = rs.getInt("sizeBytes"),
                            info = rs.getString("info")
                        )
                    )
                }
                return list
            }
        }
    }

    // --- Dedup Processed Packets ---
    @Synchronized
    fun markPacketSeen(dedupKey: String, processedAt: Long = System.currentTimeMillis() / 1000L) {
        getConnection().use { conn ->
            conn.prepareStatement("INSERT OR REPLACE INTO processed_packets (dedupKey, processedAt) VALUES (?, ?)").use { stmt ->
                stmt.setString(1, dedupKey)
                stmt.setLong(2, processedAt)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun isPacketSeen(dedupKey: String): Boolean {
        getConnection().use { conn ->
            conn.prepareStatement("SELECT 1 FROM processed_packets WHERE dedupKey = ?").use { stmt ->
                stmt.setString(1, dedupKey)
                val rs = stmt.executeQuery()
                return rs.next()
            }
        }
    }

    // --- Store and Forward Queue ---
    @Synchronized
    fun insertStoreAndForward(sf: DesktopStoreForward) {
        getConnection().use { conn ->
            val sql = "INSERT OR REPLACE INTO store_forward (messageId, recipientId, packetData, createdAt, expiresAt) VALUES (?, ?, ?, ?, ?)"
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, sf.messageId)
                stmt.setLong(2, sf.recipientId)
                stmt.setBytes(3, sf.packetData)
                stmt.setLong(4, sf.createdAt)
                stmt.setLong(5, sf.expiresAt)
                stmt.executeUpdate()
            }
        }
    }

    @Synchronized
    fun getPendingPacketsForPeer(recipientId: Long): List<DesktopStoreForward> {
        getConnection().use { conn ->
            val now = System.currentTimeMillis()
            conn.prepareStatement("SELECT * FROM store_forward WHERE recipientId = ? AND expiresAt > ?").use { stmt ->
                stmt.setLong(1, recipientId)
                stmt.setLong(2, now)
                val rs = stmt.executeQuery()
                val list = mutableListOf<DesktopStoreForward>()
                while (rs.next()) {
                    list.add(
                        DesktopStoreForward(
                            messageId = rs.getString("messageId"),
                            recipientId = rs.getLong("recipientId"),
                            packetData = rs.getBytes("packetData"),
                            createdAt = rs.getLong("createdAt"),
                            expiresAt = rs.getLong("expiresAt")
                        )
                    )
                }
                return list
            }
        }
    }

    @Synchronized
    fun deleteStoreAndForward(messageId: String) {
        getConnection().use { conn ->
            conn.prepareStatement("DELETE FROM store_forward WHERE messageId = ?").use { stmt ->
                stmt.setString(1, messageId)
                stmt.executeUpdate()
            }
        }
    }
}

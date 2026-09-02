package com.meshwhisper.desktop

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.logging.NoOpLogger
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.*
import com.meshwhisper.desktop.router.DesktopMeshRouter
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

class DesktopEngineAndDatabaseTest {

    private lateinit var tempDir: File
    private lateinit var database: DesktopDatabase
    private lateinit var keyStorage: DesktopPassphraseKeyStorage

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "meshwhisper_test_${UUID.randomUUID()}")
        tempDir.mkdirs()
        val dbFile = File(tempDir, "test_mesh.db")
        database = DesktopDatabase(dbFile)
        keyStorage = DesktopPassphraseKeyStorage(tempDir, "SecretTestPassphrase123!".toCharArray())
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun testKeyStorageEncryptionAndPersistence() {
        val (priv, _) = PureCryptoEngine.generateX25519KeyPair()
        keyStorage.storePrivateKey(priv)
        keyStorage.writeAlias("AlphaStation")

        // Reload fresh from disk using same passphrase
        val reloadedStorage = DesktopPassphraseKeyStorage(tempDir, "SecretTestPassphrase123!".toCharArray())
        assertThat(reloadedStorage.getPrivateKey()).isEqualTo(priv)
        assertThat(reloadedStorage.readAlias()).isEqualTo("AlphaStation")
    }

    @Test
    fun testDatabaseAllTablesOperations() {
        // 1. Peers Table
        val peer = DesktopPeer(
            nodeId = 0x1122334455667788L,
            publicKeyHex = "AABBCCDDEEFF00112233445566778899AABBCCDDEEFF00112233445566778899",
            alias = "FieldMedic-1",
            rssi = -62,
            hops = 2
        )
        database.upsertPeer(peer)
        val loadedPeer = database.getPeer(peer.nodeId)
        assertThat(loadedPeer).isNotNull()
        assertThat(loadedPeer?.alias).isEqualTo("FieldMedic-1")

        // 2. Messages Table (Broadcast & SOS)
        val msg = DesktopMessage(
            messageId = UUID.randomUUID().toString(),
            senderNodeId = peer.nodeId,
            recipientNodeId = MeshPacket.BROADCAST_RECIPIENT_ID,
            text = "Emergency Medical Evacuation Required",
            timestamp = 1720000000L,
            isIncoming = true,
            isEmergencySos = true
        )
        database.insertMessage(msg)
        val messages = database.getPublicAndSosMessages()
        assertThat(messages.size).isEqualTo(1)
        assertThat(messages[0].isEmergencySos).isTrue()
        assertThat(messages[0].text).isEqualTo("Emergency Medical Evacuation Required")

        // 3. Topology Edges Table (Mesh Radar Graph)
        val edge = DesktopTopologyEdge(
            sourceNodeId = 0x1111L,
            targetNodeId = 0x2222L,
            rssi = -55,
            updatedAt = 1720000000L
        )
        database.upsertTopologyEdge(edge)
        val edges = database.getAllTopologyEdges()
        assertThat(edges.size).isEqualTo(1)
        assertThat(edges[0].sourceNodeId).isEqualTo(0x1111L)
        assertThat(edges[0].targetNodeId).isEqualTo(0x2222L)

        // 4. Packet Logs Table
        val log = DesktopPacketLog(
            timestamp = 1720000000L,
            direction = "RX",
            type = "SOS_MESSAGE",
            messageIdHex = msg.messageId,
            sizeBytes = 85,
            info = "From WiFi_TCP"
        )
        database.insertPacketLog(log)
        val logs = database.getRecentPacketLogs()
        assertThat(logs.size).isEqualTo(1)
        assertThat(logs[0].type).isEqualTo("SOS_MESSAGE")

        // 5. Store & Forward Table
        val sf = DesktopStoreForward(
            messageId = UUID.randomUUID().toString(),
            recipientId = peer.nodeId,
            packetData = byteArrayOf(1, 2, 3, 4),
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60000L
        )
        database.insertStoreAndForward(sf)
        val pending = database.getPendingPacketsForPeer(peer.nodeId)
        assertThat(pending.size).isEqualTo(1)
        database.deleteStoreAndForward(sf.messageId)
        assertThat(database.getPendingPacketsForPeer(peer.nodeId)).isEmpty()

        // 6. Processed Packets Table (Dedup)
        val dedupKey = "msg-123:0"
        assertThat(database.isPacketSeen(dedupKey)).isFalse()
        database.markPacketSeen(dedupKey)
        assertThat(database.isPacketSeen(dedupKey)).isTrue()
    }

    @Test
    fun testDesktopRouterPublicAndSosTransmission() {
        val wifiEngine = DesktopWifiEngine(NoOpLogger)
        val router = DesktopMeshRouter(keyStorage, database, wifiEngine, NoOpLogger)

        val publicMsgId = router.sendPublicMessage("Hello Mesh from Windows Station!", isSos = false)
        assertThat(publicMsgId).isNotEmpty()

        val sosMsgId = router.sendPublicMessage("CRITICAL STRUCTURAL COLLAPSE", isSos = true)
        assertThat(sosMsgId).isNotEmpty()

        val publicAndSos = database.getPublicAndSosMessages()
        assertThat(publicAndSos.size).isAtLeast(2)
        val sosMsg = publicAndSos.firstOrNull { it.isEmergencySos }
        assertThat(sosMsg).isNotNull()
        assertThat(sosMsg?.text).isEqualTo("CRITICAL STRUCTURAL COLLAPSE")
    }
}

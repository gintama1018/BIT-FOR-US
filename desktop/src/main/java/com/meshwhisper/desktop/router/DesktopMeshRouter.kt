package com.meshwhisper.desktop.router

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.logging.StdoutLogger
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.*
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Desktop Mesh Router for Windows and macOS nodes.
 * Coordinates pure crypto, LRU deduplication, flood routing over Wi-Fi, SQLite persistence, and topology tracking.
 */
class DesktopMeshRouter(
    val keyStorage: DesktopPassphraseKeyStorage,
    val database: DesktopDatabase,
    val wifiEngine: DesktopWifiEngine,
    val logger: MeshLogger = StdoutLogger
) {
    companion object {
        private const val TAG = "DesktopMeshRouter"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val dedupCache = LruDedupCache<String, Long>(4000)

    var myPrivateKey: ByteArray
        private set
    var myPublicKey: ByteArray
        private set
    var myNodeId: Long
        private set
    var myNodeIdHex: String
        private set
    var myAlias: String
        private set

    private val _incomingMessages = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<DesktopMessage> = _incomingMessages.asSharedFlow()

    private val _sosAlerts = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 32)
    val sosAlerts: SharedFlow<DesktopMessage> = _sosAlerts.asSharedFlow()

    init {
        val existingPriv = keyStorage.getPrivateKey()
        if (existingPriv != null && existingPriv.isNotEmpty()) {
            myPrivateKey = existingPriv
            myPublicKey = PureCryptoEngine.derivePublicKey(existingPriv)
        } else {
            val (priv, pub) = PureCryptoEngine.generateX25519KeyPair()
            keyStorage.storePrivateKey(priv)
            myPrivateKey = priv
            myPublicKey = pub
        }

        myNodeId = PureCryptoEngine.deriveNodeId(myPublicKey)
        myNodeIdHex = java.lang.Long.toUnsignedString(myNodeId, 16).padStart(16, '0').uppercase()
        myAlias = keyStorage.readAlias() ?: "Desktop-${myNodeIdHex.takeLast(4)}"

        wifiEngine.onPacketReceivedListener = { rawBytes, ingressSource ->
            handleIncomingRawPacket(rawBytes, ingressSource)
        }

        wifiEngine.onPeerConnectedListener = { peerId, ip ->
            logger.i(TAG, "Peer connected: 0x${String.format("%016X", peerId)} at $ip")
            drainStoreAndForward(peerId)
            announcePresence()
        }
    }

    private var heartbeatJob: Job? = null

    fun start() {
        wifiEngine.start(myNodeId, myAlias)
        announcePresence()

        heartbeatJob = scope.launch {
            while (isActive) {
                delay(6000L)
                announcePresence()
            }
        }
    }

    fun stop() {
        heartbeatJob?.cancel()
        wifiEngine.stop()
    }

    fun updateAlias(newAlias: String) {
        myAlias = newAlias
        keyStorage.writeAlias(newAlias)
        wifiEngine.updateAlias(newAlias)
        announcePresence()
    }

    private fun handleIncomingRawPacket(rawBytes: ByteArray, ingressSource: String) {
        val packet = MeshPacket.deserialize(rawBytes) ?: return
        val dedupKey = "${packet.messageId}:${packet.type.code}"

        // Deduplication Check
        if (dedupCache.containsKey(dedupKey) || database.isPacketSeen(dedupKey)) {
            return
        }
        dedupCache.put(dedupKey, System.currentTimeMillis())
        database.markPacketSeen(dedupKey, packet.timestamp)

        logPacket("RX", packet, rawBytes.size, "From $ingressSource (TTL=${packet.ttl})")

        // Record Topology Edge (Radar Graph)
        database.upsertTopologyEdge(
            DesktopTopologyEdge(
                sourceNodeId = packet.senderId,
                targetNodeId = myNodeId,
                rssi = -55,
                updatedAt = System.currentTimeMillis()
            )
        )

        // Process based on packet opcode
        when (packet.type) {
            PacketType.BROADCAST_MESSAGE -> handleBroadcastMessage(packet, isSos = false)
            PacketType.SOS_MESSAGE -> handleBroadcastMessage(packet, isSos = true)
            PacketType.DIRECT_MESSAGE -> handleDirectMessage(packet)
            PacketType.ACK -> handleAck(packet)
            PacketType.PEER_ANNOUNCE -> handlePeerAnnounce(packet)
            else -> {}
        }

        // Flood Relay (if TTL > 0 and not addressed exclusively to me)
        if (packet.ttl > 0 && packet.recipientId != myNodeId) {
            val relayPacket = packet.decrementTtl()
            val relayBytes = MeshPacket.serialize(relayPacket)
            wifiEngine.broadcastPacket(relayBytes)
            logPacket("RELAY", relayPacket, relayBytes.size, "Relayed flood (TTL=${relayPacket.ttl})")
        }
    }

    private fun handleBroadcastMessage(packet: MeshPacket, isSos: Boolean) {
        val publicChannelKey = PureCryptoEngine.derivePublicChannelKey()
        val aad = packet.getAuthenticatedHeaderBytes()

        try {
            val decryptedBytes = PureCryptoEngine.decrypt(
                ciphertext = packet.payload,
                authTag = packet.authTag,
                messageId = packet.messageId,
                aesKey = publicChannelKey,
                aad = aad
            )
            val text = String(decryptedBytes, Charsets.UTF_8)
            val msg = DesktopMessage(
                messageId = packet.messageId.toString(),
                senderNodeId = packet.senderId,
                recipientNodeId = MeshPacket.BROADCAST_RECIPIENT_ID,
                text = text,
                timestamp = packet.timestamp,
                isIncoming = true,
                ttlRemaining = packet.ttl,
                isChannelBroadcast = true,
                channelName = if (isSos) "SOS_EMERGENCY" else "public",
                isEmergencySos = isSos
            )
            database.insertMessage(msg)
            _incomingMessages.tryEmit(msg)
            if (isSos) {
                _sosAlerts.tryEmit(msg)
            }
            logger.i(TAG, "${if (isSos) "🚨 [SOS ALERT]" else "💬 [PUBLIC]"} from 0x${String.format("%016X", packet.senderId)}: $text")
        } catch (e: Exception) {
            logger.w(TAG, "AEAD auth failure on broadcast message from 0x${String.format("%016X", packet.senderId)}")
        }
    }

    private fun handleDirectMessage(packet: MeshPacket) {
        if (packet.recipientId != myNodeId) return

        val peer = database.getPeer(packet.senderId)
        val sessionKey = if (peer != null) {
            val peerPubKey = PureCryptoEngine.hexToBytes(peer.publicKeyHex)
            PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, packet.timestamp)
        } else {
            null
        }

        if (sessionKey != null) {
            val aad = packet.getAuthenticatedHeaderBytes()
            try {
                val decrypted = PureCryptoEngine.decrypt(
                    ciphertext = packet.payload,
                    authTag = packet.authTag,
                    messageId = packet.messageId,
                    aesKey = sessionKey,
                    aad = aad
                )
                val text = String(decrypted, Charsets.UTF_8)
                val msg = DesktopMessage(
                    messageId = packet.messageId.toString(),
                    senderNodeId = packet.senderId,
                    recipientNodeId = myNodeId,
                    text = text,
                    timestamp = packet.timestamp,
                    isIncoming = true,
                    isDelivered = true,
                    ttlRemaining = packet.ttl
                )
                database.insertMessage(msg)
                _incomingMessages.tryEmit(msg)
                logger.i(TAG, "🔒 [DM] from 0x${String.format("%016X", packet.senderId)}: $text")

                // Send authenticated ACK back
                sendAck(packet.senderId, packet.messageId)
            } catch (e: Exception) {
                logger.w(TAG, "AEAD decryption failed for DM from 0x${String.format("%016X", packet.senderId)}")
            }
        }
    }

    private fun handleAck(packet: MeshPacket) {
        if (packet.recipientId != myNodeId) return
        val peer = database.getPeer(packet.senderId) ?: return
        val peerPubKey = PureCryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, packet.timestamp)
        val aad = packet.getAuthenticatedHeaderBytes()

        try {
            val plain = PureCryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
            if (plain.size >= 16) {
                val buf = ByteBuffer.wrap(plain)
                val most = buf.getLong()
                val least = buf.getLong()
                val originalMsgId = UUID(most, least).toString()
                logger.i(TAG, "✅ [ACK RECEIVED] for message $originalMsgId from 0x${String.format("%016X", packet.senderId)}")
            }
        } catch (_: Exception) {}
    }

    private fun handlePeerAnnounce(packet: MeshPacket) {
        val payload = packet.payload
        if (payload.size < 33) return

        val buffer = ByteBuffer.wrap(payload)
        val aliasLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < aliasLen + 32) return

        val aliasBytes = ByteArray(aliasLen)
        buffer.get(aliasBytes)
        val alias = String(aliasBytes, Charsets.UTF_8)

        val pubKey = ByteArray(32)
        buffer.get(pubKey)

        val peerNodeId = PureCryptoEngine.deriveNodeId(pubKey)
        if (peerNodeId != packet.senderId) return

        val peer = DesktopPeer(
            nodeId = peerNodeId,
            publicKeyHex = PureCryptoEngine.bytesToHex(pubKey),
            alias = alias,
            rssi = -50,
            hops = maxOf(1, MeshPacket.DEFAULT_TTL - packet.ttl),
            lastSeen = System.currentTimeMillis(),
            publicFingerprint = PureCryptoEngine.generateFingerprint(pubKey)
        )
        database.upsertPeer(peer)
        logger.i(TAG, "Discovered mesh peer: $alias (0x${String.format("%016X", peerNodeId)})")
    }

    fun sendPublicMessage(text: String, isSos: Boolean = false): String {
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val plainBytes = text.toByteArray(Charsets.UTF_8)
        val publicChannelKey = PureCryptoEngine.derivePublicChannelKey()

        val type = if (isSos) PacketType.SOS_MESSAGE else PacketType.BROADCAST_MESSAGE
        val aad = MeshPacket.computeAad(
            type = type,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = PureCryptoEngine.encrypt(
            plaintext = plainBytes,
            messageId = msgId,
            aesKey = publicChannelKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = type,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${msgId}:${type.code}"
        dedupCache.put(dedupKey, System.currentTimeMillis())
        database.markPacketSeen(dedupKey, timestamp)

        database.insertMessage(
            DesktopMessage(
                messageId = msgId.toString(),
                senderNodeId = myNodeId,
                recipientNodeId = MeshPacket.BROADCAST_RECIPIENT_ID,
                text = text,
                timestamp = timestamp,
                isIncoming = false,
                isChannelBroadcast = true,
                channelName = if (isSos) "SOS_EMERGENCY" else "public",
                isEmergencySos = isSos
            )
        )

        wifiEngine.broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Broadcast ${if (isSos) "SOS" else "chat"} ($text)")
        return msgId.toString()
    }

    fun sendDirectMessage(recipientNodeId: Long, text: String): String? {
        val peer = database.getPeer(recipientNodeId) ?: return null
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val plainBytes = text.toByteArray(Charsets.UTF_8)

        val peerPubKey = PureCryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestamp)
        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val encResult = PureCryptoEngine.encrypt(plainBytes, msgId, sessionKey, aad)
        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${msgId}:${PacketType.DIRECT_MESSAGE.code}"
        dedupCache.put(dedupKey, System.currentTimeMillis())
        database.markPacketSeen(dedupKey, timestamp)

        database.insertMessage(
            DesktopMessage(
                messageId = msgId.toString(),
                senderNodeId = myNodeId,
                recipientNodeId = recipientNodeId,
                text = text,
                timestamp = timestamp,
                isIncoming = false,
                isDelivered = false
            )
        )

        // Queue store & forward
        database.insertStoreAndForward(
            DesktopStoreForward(
                messageId = msgId.toString(),
                recipientId = recipientNodeId,
                packetData = raw,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 3600 * 1000L)
            )
        )

        if (wifiEngine.isPeerConnected(recipientNodeId)) {
            wifiEngine.sendDirectPacket(recipientNodeId, raw)
        } else {
            wifiEngine.broadcastPacket(raw)
        }
        logPacket("TX", packet, raw.size, "DM to 0x${String.format("%016X", recipientNodeId)}")
        return msgId.toString()
    }

    private fun sendAck(recipientNodeId: Long, originalMsgId: UUID) {
        val peer = database.getPeer(recipientNodeId) ?: return
        val timestamp = System.currentTimeMillis() / 1000L
        val ackPacketId = UUID.randomUUID()

        val plainPayload = ByteBuffer.allocate(16).apply {
            putLong(originalMsgId.mostSignificantBits)
            putLong(originalMsgId.leastSignificantBits)
        }.array()

        val aad = MeshPacket.computeAad(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val peerPubKey = PureCryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestamp)
        val encResult = PureCryptoEngine.encrypt(plainPayload, ackPacketId, sessionKey, aad)

        val packet = MeshPacket(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${ackPacketId}:${PacketType.ACK.code}"
        dedupCache.put(dedupKey, System.currentTimeMillis())
        database.markPacketSeen(dedupKey, timestamp)

        if (wifiEngine.isPeerConnected(recipientNodeId)) {
            wifiEngine.sendDirectPacket(recipientNodeId, raw)
        } else {
            wifiEngine.broadcastPacket(raw)
        }
    }

    fun announcePresence() {
        val rawAliasBytes = myAlias.toByteArray(Charsets.UTF_8)
        val aliasBytes = if (rawAliasBytes.size > 255) rawAliasBytes.copyOf(255) else rawAliasBytes
        val payload = ByteBuffer.allocate(1 + aliasBytes.size + 32 + 1 + 1).apply {
            put((aliasBytes.size and 0xFF).toByte())
            put(aliasBytes)
            put(myPublicKey)
            put(0.toByte()) // directNeighbors count = 0
            put(0.toByte()) // avatarHash = 0
        }.array()

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = UUID.randomUUID(),
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = payload
        )

        val raw = MeshPacket.serialize(packet)
        wifiEngine.broadcastPacket(raw)
    }

    private fun drainStoreAndForward(peerNodeId: Long) {
        scope.launch {
            val pending = database.getPendingPacketsForPeer(peerNodeId)
            for (item in pending) {
                if (wifiEngine.sendDirectPacket(peerNodeId, item.packetData)) {
                    database.deleteStoreAndForward(item.messageId)
                    logger.i(TAG, "Drained Store&Forward message ${item.messageId} to peer 0x${String.format("%016X", peerNodeId)}")
                }
            }
        }
    }

    private fun logPacket(direction: String, packet: MeshPacket, size: Int, info: String) {
        database.insertPacketLog(
            DesktopPacketLog(
                timestamp = System.currentTimeMillis(),
                direction = direction,
                type = packet.type.name,
                messageIdHex = packet.messageId.toString(),
                sizeBytes = size,
                info = info
            )
        )
    }
}

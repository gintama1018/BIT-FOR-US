package com.meshwhisper.app.router

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.meshwhisper.app.ble.MeshBleEngine
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.StoreForwardEntity
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.UUID

class MeshRouter(
    private val context: Context,
    private val bleEngine: MeshBleEngine,
    private val cryptoEngine: CryptoEngine,
    private val database: MeshDatabase
) {
    private val tag = "MeshRouter"
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshRouter: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Deduplication Cache (Capacity: 4000 keys) - Keyed by messageId:packetType to prevent ACK/DM collision
    private val dedupCache = LruCache<String, Long>(4000)

    // Statistics
    private val _relayedPacketsCount = MutableStateFlow(0)
    val relayedPacketsCount: StateFlow<Int> = _relayedPacketsCount.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0)
    val totalPacketsReceived: StateFlow<Int> = _totalPacketsReceived.asStateFlow()

    val mediaTransferManager = com.meshwhisper.app.media.MediaTransferManager(
        context = context,
        database = database,
        cryptoEngine = cryptoEngine,
        packetBroadcaster = { bleEngine.broadcastPacket(it) },
        ackSender = { recipientId, msgId ->
            scope.launch {
                sendAck(recipientId, msgId)
            }
        }
    )

    init {
        bleEngine.onPacketReceivedListener = { packetBytes, ingressAddress ->
            handleIncomingPacket(packetBytes, ingressAddress)
        }

        bleEngine.onPeerReadyListener = { _ ->
            // Exchange identity upon established BLE link
            scope.launch {
                announcePresence()
            }
        }
    }

    /**
     * Entry point for incoming raw packets from BLE.
     */
    fun handleIncomingPacket(rawBytes: ByteArray, ingressAddress: String? = null) {
        val packet = MeshPacket.deserialize(rawBytes) ?: run {
            Log.w(tag, "Failed to deserialize packet (${rawBytes.size} bytes)")
            return
        }

        _totalPacketsReceived.value += 1

        // Ignore own echoes
        if (packet.senderId == cryptoEngine.nodeId) {
            return
        }

        // Anti-Replay: Timestamp freshness window check (5 minutes in past, 10 minutes in future)
        val nowSec = System.currentTimeMillis() / 1000L
        val packetAge = nowSec - packet.timestamp
        if (packetAge > 600 || packetAge < -300) {
            logPacket("DROP", packet, rawBytes.size, "Packet dropped: timestamp outside validity window (age: ${packetAge}s)")
            return
        }

        // Register direct node link strictly if packet originated directly (TTL not decremented by relays)
        if (ingressAddress != null && packet.ttl >= MeshPacket.DEFAULT_TTL - 1) {
            bleEngine.registerDirectNode(ingressAddress, packet.senderId)
        }

        // Fast Layer 1 Deduplication Check: In-memory LRU Cache (keyed by msgId:type)
        val dedupKey = "${packet.messageId}:${packet.type.code}"
        synchronized(dedupCache) {
            if (dedupCache.get(dedupKey) != null) {
                logPacket("DROP", packet, rawBytes.size, "Duplicate packet dropped (fast RAM cache)")
                return
            }
        }

        scope.launch {
            // Layer 2 Deduplication Check: Persistent Room Replay Table
            if (database.processedPacketDao().hasSeen(dedupKey) > 0) {
                synchronized(dedupCache) {
                    dedupCache.put(dedupKey, System.currentTimeMillis())
                }
                logPacket("DROP", packet, rawBytes.size, "Duplicate packet dropped (persistent replay DB)")
                return@launch
            }

            // Register in both fast RAM cache and persistent replay table
            synchronized(dedupCache) {
                dedupCache.put(dedupKey, System.currentTimeMillis())
            }
            database.processedPacketDao().markSeen(
                com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, packet.timestamp)
            )

            when (packet.type) {
                PacketType.PEER_ANNOUNCE, PacketType.KEY_EXCHANGE -> {
                    handlePeerAnnounce(packet, ingressAddress)
                }
                PacketType.BROADCAST_MESSAGE -> {
                    handleBroadcastMessage(packet, rawBytes, ingressAddress)
                }
                PacketType.DIRECT_MESSAGE -> {
                    handleDirectMessage(packet, rawBytes, ingressAddress)
                }
                PacketType.ACK -> {
                    handleAck(packet, rawBytes, ingressAddress)
                }
                PacketType.MEDIA_INIT -> {
                    handleMediaInit(packet, rawBytes, ingressAddress)
                }
                PacketType.MEDIA_CHUNK -> {
                    handleMediaChunk(packet, rawBytes, ingressAddress)
                }
            }
        }
    }

    // =========================================================================
    // PACKET HANDLERS
    // =========================================================================

    private suspend fun handlePeerAnnounce(packet: MeshPacket, ingressAddress: String?) {
        logPacket("RX", packet, packet.payload.size + MeshPacket.OVERHEAD_SIZE, "Peer announce from ${packet.senderId}")

        if (packet.payload.size < 32) return
        val buffer = ByteBuffer.wrap(packet.payload)
        val aliasLen = buffer.get().toInt() and 0xFF
        if (buffer.remaining() < aliasLen + 32) return

        val aliasBytes = ByteArray(aliasLen)
        buffer.get(aliasBytes)
        val alias = String(aliasBytes, Charsets.UTF_8)

        val pubKeyBytes = ByteArray(32)
        buffer.get(pubKeyBytes)

        val derivedId = CryptoEngine.deriveNodeId(pubKeyBytes)
        if (derivedId != packet.senderId) {
            Log.w(tag, "Sender ID mismatch with public key")
            return
        }

        val fingerprint = CryptoEngine.generateFingerprint(pubKeyBytes)
        val pubHex = CryptoEngine.bytesToHex(pubKeyBytes)

        // Parse direct neighbor list from gossip extension
        val neighborNodeIds = mutableListOf<Long>()
        if (buffer.hasRemaining()) {
            val neighborCount = buffer.get().toInt() and 0xFF
            for (i in 0 until neighborCount) {
                if (buffer.remaining() >= 8) {
                    neighborNodeIds.add(buffer.long)
                }
            }
        }

        // Upsert reported topology edges into database
        val now = System.currentTimeMillis()
        for (neighborId in neighborNodeIds) {
            database.topologyEdgeDao().insertOrUpdate(
                com.meshwhisper.app.data.model.TopologyEdgeEntity(
                    fromNode = packet.senderId,
                    toNode = neighborId,
                    rssi = 0,
                    lastSeen = now
                )
            )
        }

        val isDirectLink = (packet.ttl >= MeshPacket.DEFAULT_TTL - 1)
        if (isDirectLink) {
            database.topologyEdgeDao().insertOrUpdate(
                com.meshwhisper.app.data.model.TopologyEdgeEntity(
                    fromNode = cryptoEngine.nodeId,
                    toNode = packet.senderId,
                    rssi = 0,
                    lastSeen = now
                )
            )
        }

        // Check if existing peer has rotated or changed public key (TOFU safety check)
        val existingPeer = database.peerDao().getPeerById(packet.senderId)
        val hasKeyChanged = (existingPeer != null && existingPeer.publicKeyHex != pubHex)
        val prevFp = if (hasKeyChanged) existingPeer?.fingerprint else existingPeer?.previousFingerprint
        val isBlocked = existingPeer?.isBlocked ?: false

        if (hasKeyChanged) {
            Log.w(tag, "SECURITY ALERT: Safety number / Public key changed for peer ${packet.senderId}! (Old: ${existingPeer?.fingerprint}, New: $fingerprint)")
            cryptoEngine.invalidateSessionKey(packet.senderId)
        }

        val peer = PeerEntity(
            nodeId = packet.senderId,
            alias = alias,
            publicKeyHex = pubHex,
            fingerprint = fingerprint,
            lastSeen = System.currentTimeMillis(),
            isDirect = isDirectLink,
            hopCount = maxOf(1, MeshPacket.DEFAULT_TTL - packet.ttl),
            isBlocked = isBlocked,
            hasKeyChanged = hasKeyChanged || (existingPeer?.hasKeyChanged ?: false),
            previousFingerprint = prevFp
        )
        database.peerDao().insertOrUpdate(peer)

        // Drain store-and-forward queue for this newly available peer
        drainStoreAndForwardQueueForPeer(packet.senderId)

        // Multi-hop flood relay for peer discovery
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            bleEngine.broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying peer announce for ${packet.senderId}")
        }
    }

    private suspend fun handleBroadcastMessage(
        packet: MeshPacket,
        rawBytes: ByteArray,
        ingressAddress: String?
    ) {
        logPacket("RX", packet, rawBytes.size, "Broadcast msg from ${packet.senderId}")

        // Decrypt public payload with AAD header verification
        try {
            val decryptedBytes = cryptoEngine.decrypt(
                ciphertext = packet.payload,
                authTag = packet.authTag,
                messageId = packet.messageId,
                aesKey = cryptoEngine.publicChannelKey,
                aad = packet.getAuthenticatedHeaderBytes()
            )
            val text = String(decryptedBytes, Charsets.UTF_8)
            val sender = database.peerDao().getPeerById(packet.senderId)
            val senderAlias = sender?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"

            val messageEntity = MessageEntity(
                messageId = packet.messageId.toString(),
                senderId = packet.senderId,
                recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
                senderAlias = senderAlias,
                text = text,
                timestamp = packet.timestamp * 1000L,
                isOutgoing = (packet.senderId == cryptoEngine.nodeId),
                isBroadcast = true,
                status = MessageStatus.DELIVERED,
                hopCount = MeshPacket.DEFAULT_TTL - packet.ttl
            )
            database.messageDao().insert(messageEntity)
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt or authenticate broadcast packet (AEAD header mismatch / corrupt): ${e.message}")
        }

        // Flood relay if hops remain
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            bleEngine.broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying broadcast msg")
        }
    }

    private suspend fun handleDirectMessage(
        packet: MeshPacket,
        rawBytes: ByteArray,
        ingressAddress: String?
    ) {
        if (packet.recipientId == cryptoEngine.nodeId) {
            // DIRECT MESSAGE IS FOR US!
            logPacket("RX", packet, rawBytes.size, "Private DM for THIS device from ${packet.senderId}")

            val senderPeer = database.peerDao().getPeerById(packet.senderId)
            if (senderPeer != null) {
                // Ingress check: Drop if peer is blocked
                if (senderPeer.isBlocked) {
                    logPacket("DROP", packet, rawBytes.size, "Dropped direct message from BLOCKED peer ${packet.senderId}")
                    return
                }

                try {
                    val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
                    val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
                    val decryptedBytes = cryptoEngine.decrypt(
                        ciphertext = packet.payload,
                        authTag = packet.authTag,
                        messageId = packet.messageId,
                        aesKey = sessionKey,
                        aad = packet.getAuthenticatedHeaderBytes()
                    )
                    val text = String(decryptedBytes, Charsets.UTF_8)

                    val messageEntity = MessageEntity(
                        messageId = packet.messageId.toString(),
                        senderId = packet.senderId,
                        recipientId = cryptoEngine.nodeId,
                        senderAlias = senderPeer.alias,
                        text = text,
                        timestamp = packet.timestamp * 1000L,
                        isOutgoing = false,
                        isBroadcast = false,
                        status = MessageStatus.DELIVERED,
                        hopCount = MeshPacket.DEFAULT_TTL - packet.ttl
                    )
                    database.messageDao().insert(messageEntity)

                    // Send Delivery ACK back to sender
                    sendAck(packet.senderId, packet.messageId)
                } catch (e: Exception) {
                    Log.e(tag, "Failed to decrypt private DM (AEAD auth tag failure or header tampered): ${e.message}")
                }
            } else {
                Log.w(tag, "Received DM from unknown peer ${packet.senderId}, requesting announce...")
                announcePresence()
            }
        } else {
            // MULTI-HOP RELAY FOR ANOTHER NODE
            logPacket("RELAY", packet, rawBytes.size, "Relaying private DM for ${packet.recipientId}")

            // Store-and-forward offline buffer (expires in 24 hours)
            val sfEntity = StoreForwardEntity(
                messageId = packet.messageId.toString(),
                recipientId = packet.recipientId,
                packetData = rawBytes,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
            )
            database.storeForwardDao().insert(sfEntity)

            // Flood relay forward
            if (packet.ttl > 1) {
                val relayedPacket = packet.decrementTtl()
                val relayedBytes = MeshPacket.serialize(relayedPacket)
                bleEngine.broadcastPacket(relayedBytes, ingressAddress)
                _relayedPacketsCount.value += 1
            }
        }
    }

    private suspend fun handleAck(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        if (packet.recipientId == cryptoEngine.nodeId) {
            // ACK reached the original sender!
            val originalMsgId = packet.messageId.toString()
            val senderPeer = database.peerDao().getPeerById(packet.senderId)

            if (senderPeer == null) {
                logPacket("DROP", packet, rawBytes.size, "Dropped ACK from unknown peer ${packet.senderId}")
                return
            }

            // Cryptographically verify the ACK proof-of-origin via AEAD auth tag
            try {
                val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
                cryptoEngine.decrypt(
                    ciphertext = packet.payload,
                    authTag = packet.authTag,
                    messageId = packet.messageId,
                    aesKey = sessionKey,
                    aad = packet.getAuthenticatedHeaderBytes()
                )

                logPacket("ACK_RX", packet, rawBytes.size, "Authenticated delivery ACK received for msg $originalMsgId")
                database.messageDao().updateStatus(originalMsgId, MessageStatus.DELIVERED)
                database.storeForwardDao().delete(originalMsgId)
            } catch (e: Exception) {
                logPacket("DROP", packet, rawBytes.size, "Rejected unauthenticated/forged ACK for msg $originalMsgId")
                Log.w(tag, "Rejected forged ACK: auth tag mismatch from ${packet.senderId}")
            }
        } else if (packet.ttl > 1) {
            // Relay the ACK back towards sender
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            bleEngine.broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying ACK for msg ${packet.messageId}")
        }
    }

    private suspend fun handleMediaInit(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        val isBroadcast = (packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)

        if (isForMe) {
            val peer = database.peerDao().getPeerById(packet.senderId)
            val senderAlias = peer?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
            mediaTransferManager.handleMediaInit(packet, senderAlias, isBroadcast)
            logPacket("RX", packet, rawBytes.size, "Received MEDIA_INIT from $senderAlias")
        }

        // Live flood relay (EXCLUDED from StoreForwardDao to protect DB footprint)
        if (packet.ttl > 1 && (!isForMe || isBroadcast)) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            bleEngine.broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_INIT from ${packet.senderId}")
        }
    }

    private suspend fun handleMediaChunk(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        val isBroadcast = (packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)

        if (isForMe) {
            mediaTransferManager.handleMediaChunk(packet, isBroadcast)
            logPacket("RX", packet, rawBytes.size, "Received MEDIA_CHUNK from ${packet.senderId}")
        }

        // Live flood relay (EXCLUDED from StoreForwardDao to protect DB footprint)
        if (packet.ttl > 1 && (!isForMe || isBroadcast)) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            bleEngine.broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_CHUNK from ${packet.senderId}")
        }
    }

    // =========================================================================
    // OUTBOUND MESSAGING APIS
    // =========================================================================

    suspend fun sendMediaDirect(
        recipientNodeId: Long,
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L
    ): String {
        return mediaTransferManager.sendMedia(
            recipientNodeId = recipientNodeId,
            mediaType = mediaType,
            mediaBytes = mediaBytes,
            caption = caption,
            durationMs = durationMs
        )
    }

    suspend fun sendMediaBroadcast(
        mediaType: com.meshwhisper.app.data.model.MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L
    ): String {
        return mediaTransferManager.sendMedia(
            recipientNodeId = MeshPacket.BROADCAST_RECIPIENT_ID,
            mediaType = mediaType,
            mediaBytes = mediaBytes,
            caption = caption,
            durationMs = durationMs
        )
    }

    suspend fun announcePresence() {
        val rawAliasBytes = cryptoEngine.alias.toByteArray(Charsets.UTF_8)
        val aliasBytes = if (rawAliasBytes.size > 255) rawAliasBytes.copyOf(255) else rawAliasBytes
        val pubKeyBytes = cryptoEngine.publicKeyBytes

        // Collect live direct neighbor node IDs (strictly from active GATT links)
        val directNeighbors = bleEngine.connectedNodeIds.value
            .filter { it != cryptoEngine.nodeId && it != 0L }
            .take(10)
            .toList()

        val payload = ByteArray(1 + aliasBytes.size + pubKeyBytes.size + 1 + (directNeighbors.size * 8))
        val buffer = ByteBuffer.wrap(payload)
        buffer.put((aliasBytes.size and 0xFF).toByte())
        buffer.put(aliasBytes)
        buffer.put(pubKeyBytes)
        buffer.put(directNeighbors.size.toByte())
        for (nId in directNeighbors) {
            buffer.putLong(nId)
        }

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = UUID.randomUUID(),
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = payload
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${packet.messageId}:${PacketType.PEER_ANNOUNCE.code}"
        val nowSec = System.currentTimeMillis() / 1000L
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        try {
            database.processedPacketDao().purgeOld(nowSec - 86400L)
            database.storeForwardDao().purgeExpired(System.currentTimeMillis())
            // Prune topology edges older than 2 minutes (live mesh dynamic graph)
            database.topologyEdgeDao().pruneStaleEdges(System.currentTimeMillis() - 120_000L)
        } catch (e: Exception) {
            Log.e(tag, "Failed to purge old records: ${e.message}")
        }
        bleEngine.broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Broadcasted local peer announce (${directNeighbors.size} neighbors)")
    }

    suspend fun sendBroadcastMessage(text: String): String {
        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000L

        val aad = MeshPacket.computeAad(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = textBytes,
            messageId = msgId,
            aesKey = cryptoEngine.publicChannelKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val entity = MessageEntity(
            messageId = msgId.toString(),
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            senderAlias = cryptoEngine.alias,
            text = text,
            timestamp = timestamp * 1000L,
            isOutgoing = true,
            isBroadcast = true,
            status = MessageStatus.SENT,
            hopCount = 0
        )
        database.messageDao().insert(entity)

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "$msgId:${PacketType.BROADCAST_MESSAGE.code}"
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, timestamp)
        )
        bleEngine.broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Sent broadcast msg (${text.length} chars)")

        return msgId.toString()
    }

    suspend fun sendDirectMessage(recipientNodeId: Long, text: String): String? {
        val peer = database.peerDao().getPeerById(recipientNodeId) ?: return null
        if (peer.isBlocked) {
            Log.w(tag, "Cannot send message to blocked peer $recipientNodeId")
            return null
        }

        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000L

        val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)

        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = textBytes,
            messageId = msgId,
            aesKey = sessionKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val entity = MessageEntity(
            messageId = msgId.toString(),
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            senderAlias = cryptoEngine.alias,
            text = text,
            timestamp = timestamp * 1000L,
            isOutgoing = true,
            isBroadcast = false,
            status = MessageStatus.SENT,
            hopCount = 0
        )
        database.messageDao().insert(entity)

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "$msgId:${PacketType.DIRECT_MESSAGE.code}"
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, timestamp)
        )

        // Store-and-forward queue entry
        val sf = StoreForwardEntity(
            messageId = msgId.toString(),
            recipientId = recipientNodeId,
            packetData = raw,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
        )
        database.storeForwardDao().insert(sf)

        bleEngine.broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Sent direct DM to $recipientNodeId (${text.length} chars)")

        return msgId.toString()
    }

    private suspend fun sendAck(recipientNodeId: Long, originalMsgId: UUID) {
        val peer = database.peerDao().getPeerById(recipientNodeId)
        val timestamp = System.currentTimeMillis() / 1000L

        val aad = MeshPacket.computeAad(
            type = PacketType.ACK,
            messageId = originalMsgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val authTag: ByteArray
        if (peer != null) {
            val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
            val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)
            val encResult = cryptoEngine.encrypt(
                plaintext = ByteArray(0),
                messageId = originalMsgId,
                aesKey = sessionKey,
                aad = aad
            )
            authTag = encResult.authTag
        } else {
            authTag = ByteArray(16)
        }

        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            messageId = originalMsgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = ByteArray(0),
            authTag = authTag
        )

        val raw = MeshPacket.serialize(ackPacket)
        val dedupKey = "$originalMsgId:${PacketType.ACK.code}"
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, timestamp)
        )
        bleEngine.broadcastPacket(raw)
        logPacket("ACK_TX", ackPacket, raw.size, "Sent authenticated ACK for msg $originalMsgId to $recipientNodeId")
    }

    private suspend fun drainStoreAndForwardQueueForPeer(recipientNodeId: Long) {
        val now = System.currentTimeMillis()
        val pending = database.storeForwardDao().getPendingForRecipient(recipientNodeId, now)
        for (item in pending) {
            bleEngine.broadcastPacket(item.packetData)
            logPacket("SF_DRAIN", null, item.packetData.size, "Draining store-and-forward msg ${item.messageId} to $recipientNodeId")
        }
    }

    private fun logPacket(direction: String, packet: MeshPacket?, size: Int, details: String) {
        scope.launch {
            val entity = PacketLogEntity(
                timestamp = System.currentTimeMillis(),
                direction = direction,
                packetType = packet?.type?.name ?: "UNKNOWN",
                messageId = packet?.messageId?.toString() ?: "",
                senderId = packet?.senderId ?: 0L,
                recipientId = packet?.recipientId ?: 0L,
                ttl = packet?.ttl ?: 0,
                byteSize = size,
                details = details
            )
            database.packetLogDao().insert(entity)
            database.packetLogDao().trimOldLogs(500)
        }
    }
}

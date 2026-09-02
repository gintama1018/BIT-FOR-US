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
import com.meshwhisper.app.data.model.MediaType
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
    val wifiEngine: com.meshwhisper.app.wifi.MeshWifiEngine,
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

    suspend fun broadcastPacket(rawBytes: ByteArray, ingressAddress: String? = null) {
        bleEngine.broadcastPacket(rawBytes, ingressAddress)
        wifiEngine.broadcastPacket(rawBytes, ingressAddress)
    }

    val mediaTransferManager = com.meshwhisper.app.media.MediaTransferManager(
        context = context,
        database = database,
        cryptoEngine = cryptoEngine,
        packetBroadcaster = { broadcastPacket(it) },
        ackSender = { recipientId, msgId ->
            scope.launch {
                sendAck(recipientId, msgId)
            }
        },
        isDirectPeer = { bleEngine.isDirectlyConnected(it) || wifiEngine.isPeerConnected(it) }
    )

    private val lastDrainTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val logCounter = java.util.concurrent.atomic.AtomicInteger(0)

    var onTypingIndicatorListener: ((senderId: Long, isTyping: Boolean) -> Unit)? = null
    var onIncomingMessageListener: ((senderId: Long, senderAlias: String, text: String, isBroadcast: Boolean) -> Unit)? = null
    var onSosAlertReceivedListener: ((senderId: Long, senderAlias: String, text: String, lat: Double?, lon: Double?) -> Unit)? = null

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

        bleEngine.onPeerDiscoveredListener = { address, rssi ->
            scope.launch {
                val directNodeId = bleEngine.getDirectNodeId(address)
                if (directNodeId != null && directNodeId != 0L) {
                    database.peerDao().updateRssi(directNodeId, rssi)
                }
            }
        }

        wifiEngine.onPacketReceivedListener = { packetBytes, ingressAddress ->
            handleIncomingPacket(packetBytes, ingressAddress)
        }

        wifiEngine.onPeerConnectedListener = { peerId, ip ->
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

        // Anti-Replay: Timestamp freshness window check
        // Direct messages can be stored-and-forwarded for up to 24 hours (86,400s).
        // Live broadcast, announce, and media packets enforce a strict 10-minute window.
        val nowSec = System.currentTimeMillis() / 1000L
        val packetAge = nowSec - packet.timestamp
        val maxPastAgeSec = if (packet.type == PacketType.DIRECT_MESSAGE) 86400L else 600L
        if (packetAge > maxPastAgeSec || packetAge < -300) {
            logPacket("DROP", packet, rawBytes.size, "Packet dropped: timestamp outside validity window (age: ${packetAge}s)")
            return
        }

        // Register direct node link strictly if packet originated directly (0 relay hops)
        if (ingressAddress != null && packet.ttl == MeshPacket.DEFAULT_TTL) {
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
            // Atomic dedup: INSERT OR IGNORE returns -1 if the row already existed.
            // This eliminates the hasSeen()/markSeen() race where two concurrent arrivals
            // both see hasSeen==false before either writes the row.
            val inserted = database.processedPacketDao().markSeen(
                com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, packet.timestamp)
            )
            if (inserted == -1L) {
                logPacket("DROP", packet, rawBytes.size, "Duplicate packet dropped (persistent replay DB)")
                return@launch
            }
            synchronized(dedupCache) {
                dedupCache.put(dedupKey, System.currentTimeMillis())
            }

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
                PacketType.MEDIA_NACK -> {
                    handleMediaNack(packet, rawBytes, ingressAddress)
                }
                PacketType.MEDIA_ACK -> {
                    handleMediaAck(packet, rawBytes, ingressAddress)
                }
                PacketType.MEDIA_ABORT -> {
                    handleMediaAbort(packet, rawBytes, ingressAddress)
                }
                PacketType.AVATAR_REQUEST -> {
                    handleAvatarRequest(packet, rawBytes, ingressAddress)
                }
                PacketType.TYPING_INDICATOR -> {
                    handleTypingIndicator(packet, rawBytes, ingressAddress)
                }
                PacketType.SOS_MESSAGE -> {
                    handleSosMessage(packet, rawBytes, ingressAddress)
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

        val isDirectLink = (packet.ttl == MeshPacket.DEFAULT_TTL)
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

        var peerAvatarHash: Byte = 0
        if (buffer.hasRemaining()) {
            peerAvatarHash = buffer.get()
        }

        // Parse optional location extension (0x4C + 8B lat + 8B lon + 4B accuracy + 8B timestamp = 29 bytes)
        if (buffer.remaining() >= 29) {
            val marker = buffer.get()
            if (marker == 0x4C.toByte()) {
                val lat = buffer.double
                val lon = buffer.double
                val accuracy = buffer.float
                val locTimestamp = buffer.long
                database.locationDao().insertOrUpdate(
                    com.meshwhisper.app.data.model.LastKnownLocationEntity(
                        nodeId = packet.senderId,
                        alias = alias,
                        latitude = lat,
                        longitude = lon,
                        accuracyMeters = accuracy,
                        timestamp = locTimestamp
                    )
                )
            }
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
            previousFingerprint = prevFp,
            avatarUri = existingPeer?.avatarUri,
            avatarHash = if (peerAvatarHash != 0.toByte()) peerAvatarHash else (existingPeer?.avatarHash ?: 0),
            isMuted = existingPeer?.isMuted ?: false
        )
        database.peerDao().insertOrUpdate(peer)

        // If peer announced a new/updated avatar hash, trigger unicast avatar request
        if (peerAvatarHash != 0.toByte() && peerAvatarHash != existingPeer?.avatarHash) {
            scope.launch {
                requestAvatar(packet.senderId)
            }
        }

        // Drain store-and-forward queue for this newly available peer
        drainStoreAndForwardQueueForPeer(packet.senderId)

        // Multi-hop flood relay for peer discovery
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
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
                isOutgoing = false,
                isBroadcast = true,
                status = MessageStatus.DELIVERED,
                hopCount = MeshPacket.DEFAULT_TTL - packet.ttl
            )
            database.messageDao().insert(messageEntity)

            if (packet.senderId != cryptoEngine.nodeId) {
                onIncomingMessageListener?.invoke(packet.senderId, senderAlias, text, true)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt or authenticate broadcast packet (AEAD header mismatch / corrupt): ${e.message}")
        }

        // Flood relay if hops remain
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
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

                    onIncomingMessageListener?.invoke(packet.senderId, senderPeer.alias, text, false)

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
                broadcastPacket(relayedBytes, ingressAddress)
                _relayedPacketsCount.value += 1
            }
        }
    }

    private suspend fun handleAck(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        if (packet.recipientId == cryptoEngine.nodeId) {
            // ACK reached the original sender!
            val senderPeer = database.peerDao().getPeerById(packet.senderId)

            if (senderPeer == null) {
                logPacket("DROP", packet, rawBytes.size, "Dropped ACK from unknown peer ${packet.senderId}")
                return
            }

            // Cryptographically verify the ACK proof-of-origin via AEAD auth tag.
            // NOTE: packet.messageId is the unique ackPacketId (not the original message ID).
            // The original messageId is decoded from the decrypted 16-byte payload.
            try {
                val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
                val decryptedPayload = cryptoEngine.decrypt(
                    ciphertext = packet.payload,
                    authTag = packet.authTag,
                    messageId = packet.messageId,  // ackPacketId used as nonce
                    aesKey = sessionKey,
                    aad = packet.getAuthenticatedHeaderBytes()
                )

                // Recover the original message ID from the 16-byte decrypted payload
                val originalMsgId: String = if (decryptedPayload.size >= 16) {
                    val buf = java.nio.ByteBuffer.wrap(decryptedPayload)
                    UUID(buf.long, buf.long).toString()
                } else {
                    // Fallback for ACKs from older protocol versions (empty payload)
                    packet.messageId.toString()
                }

                logPacket("ACK_RX", packet, rawBytes.size, "Authenticated delivery ACK received for msg $originalMsgId (ackId=${packet.messageId})")
                database.messageDao().updateStatus(originalMsgId, MessageStatus.DELIVERED)
                database.storeForwardDao().delete(originalMsgId)
            } catch (e: Exception) {
                logPacket("DROP", packet, rawBytes.size, "Rejected unauthenticated/forged ACK (ackId=${packet.messageId})")
                Log.w(tag, "Rejected forged ACK: auth tag mismatch from ${packet.senderId}")
            }
        } else if (packet.ttl > 1) {
            // Relay the ACK back towards sender
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying ACK (ackId=${packet.messageId})")
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
            broadcastPacket(relayedBytes, ingressAddress)
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
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_CHUNK from ${packet.senderId}")
        }
    }

    private suspend fun handleMediaNack(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        if (isForMe) {
            mediaTransferManager.handleMediaNack(packet)
            logPacket("NACK_RX", packet, rawBytes.size, "Received MEDIA_NACK from ${packet.senderId}")
        }

        // Relay NACK along mesh path towards sender
        if (packet.ttl > 1 && !isForMe) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_NACK for ${packet.recipientId}")
        }
    }

    private suspend fun handleMediaAck(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val isForMe = (packet.recipientId == cryptoEngine.nodeId)
        if (isForMe) {
            mediaTransferManager.handleMediaAck(packet)
            logPacket("MEDIA_ACK_RX", packet, rawBytes.size, "Received MEDIA_ACK from ${packet.senderId}")
        }

        // Relay ACK back towards sender
        if (packet.ttl > 1 && !isForMe) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_ACK for ${packet.recipientId}")
        }
    }

    private suspend fun handleMediaAbort(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        if (isForMe) {
            mediaTransferManager.handleMediaAbort(packet)
            logPacket("ABORT_RX", packet, rawBytes.size, "Received MEDIA_ABORT from ${packet.senderId}")
        }

        // Relay ABORT along mesh path
        if (packet.ttl > 1 && !isForMe) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying MEDIA_ABORT for ${packet.recipientId}")
        }
    }

    suspend fun announcePresence(latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float = 0f) {
        val rawAliasBytes = cryptoEngine.alias.toByteArray(Charsets.UTF_8)
        val aliasBytes = if (rawAliasBytes.size > 255) rawAliasBytes.copyOf(255) else rawAliasBytes
        val pubKeyBytes = cryptoEngine.publicKeyBytes

        // Collect live direct neighbor node IDs (strictly from active GATT links)
        val directNeighbors = bleEngine.connectedNodeIds.value
            .filter { it != cryptoEngine.nodeId && it != 0L }
            .take(10)
            .toList()

        // Read local avatar hash
        val avatarFile = java.io.File(context.filesDir, "avatars/my_avatar.jpg")
        val avatarHash = if (avatarFile.exists()) {
            val bytes = avatarFile.readBytes()
            (bytes.fold(0) { acc, b -> (acc * 31 + b.toInt()) } and 0xFF).toByte()
        } else {
            0.toByte()
        }

        val hasLocation = (latitude != null && longitude != null)
        val locationBytes = if (hasLocation) 1 + 8 + 8 + 4 + 8 else 0 // 29 bytes
        val payload = ByteArray(1 + aliasBytes.size + pubKeyBytes.size + 1 + (directNeighbors.size * 8) + 1 + locationBytes)
        val buffer = ByteBuffer.wrap(payload)
        buffer.put((aliasBytes.size and 0xFF).toByte())
        buffer.put(aliasBytes)
        buffer.put(pubKeyBytes)
        buffer.put(directNeighbors.size.toByte())
        for (nId in directNeighbors) {
            buffer.putLong(nId)
        }
        buffer.put(avatarHash)
        if (hasLocation) {
            buffer.put(0x4C.toByte()) // 'L'
            buffer.putDouble(latitude!!)
            buffer.putDouble(longitude!!)
            buffer.putFloat(accuracyMeters)
            buffer.putLong(System.currentTimeMillis())
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
        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Broadcasted local peer announce (${directNeighbors.size} neighbors)")

        if (hasLocation) {
            database.locationDao().insertOrUpdate(
                com.meshwhisper.app.data.model.LastKnownLocationEntity(
                    nodeId = cryptoEngine.nodeId,
                    alias = cryptoEngine.alias,
                    latitude = latitude!!,
                    longitude = longitude!!,
                    accuracyMeters = accuracyMeters,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    suspend fun sendSosBroadcast(text: String, latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float = 0f): String {
        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000L

        val hasLocation = (latitude != null && longitude != null)
        val locationSize = if (hasLocation) 8 + 8 + 4 else 0 // 20 bytes
        val payloadBuf = ByteBuffer.allocate(1 + 2 + textBytes.size + locationSize)
        payloadBuf.put(if (hasLocation) 0x01.toByte() else 0x00.toByte()) // flags (0x01 = hasLocation, 0x00 = no location)
        payloadBuf.putShort((textBytes.size and 0xFFFF).toShort())
        payloadBuf.put(textBytes)
        if (hasLocation) {
            payloadBuf.putDouble(latitude!!)
            payloadBuf.putDouble(longitude!!)
            payloadBuf.putFloat(accuracyMeters)
        }
        val payloadBytes = payloadBuf.array()

        val aad = MeshPacket.computeAad(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val (ciphertext, authTag) = cryptoEngine.encrypt(
            plaintext = payloadBytes,
            messageId = msgId,
            aesKey = cryptoEngine.publicChannelKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = ciphertext,
            authTag = authTag
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${packet.messageId}:${PacketType.SOS_MESSAGE.code}"
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(
                messageId = packet.messageId.toString(),
                timestamp = System.currentTimeMillis()
            )
        )

        val messageEntity = MessageEntity(
            messageId = msgId.toString(),
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            senderAlias = cryptoEngine.alias,
            text = text,
            timestamp = System.currentTimeMillis(),
            isOutgoing = true,
            isBroadcast = true,
            status = MessageStatus.DELIVERED,
            isSos = true
        )
        database.messageDao().insert(messageEntity)

        // Out-of-band immediate priority broadcast
        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "PRIORITY_SOS_BROADCAST: Emergency SOS transmitted")

        if (hasLocation) {
            database.locationDao().insertOrUpdate(
                com.meshwhisper.app.data.model.LastKnownLocationEntity(
                    nodeId = cryptoEngine.nodeId,
                    alias = cryptoEngine.alias,
                    latitude = latitude!!,
                    longitude = longitude!!,
                    accuracyMeters = accuracyMeters,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        return msgId.toString()
    }

    private suspend fun handleSosMessage(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        logPacket("RX", packet, rawBytes.size, "EMERGENCY SOS broadcast from ${packet.senderId}")

        try {
            val decryptedBytes = cryptoEngine.decrypt(
                ciphertext = packet.payload,
                authTag = packet.authTag,
                messageId = packet.messageId,
                aesKey = cryptoEngine.publicChannelKey,
                aad = packet.getAuthenticatedHeaderBytes()
            )

            var sosText = ""
            var lat: Double? = null
            var lon: Double? = null

            val buf = ByteBuffer.wrap(decryptedBytes)
            if (buf.remaining() >= 3) {
                val flags = buf.get().toInt() and 0xFF
                val textLen = buf.short.toInt() and 0xFFFF
                if (buf.remaining() >= textLen) {
                    val tBytes = ByteArray(textLen)
                    buf.get(tBytes)
                    sosText = String(tBytes, Charsets.UTF_8)

                    if (flags == 0x01 && buf.remaining() >= 20) {
                        lat = buf.double
                        lon = buf.double
                        val accuracy = buf.float

                        val sender = database.peerDao().getPeerById(packet.senderId)
                        val senderAlias = sender?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
                        database.locationDao().insertOrUpdate(
                            com.meshwhisper.app.data.model.LastKnownLocationEntity(
                                nodeId = packet.senderId,
                                alias = senderAlias,
                                latitude = lat,
                                longitude = lon,
                                accuracyMeters = accuracy,
                                timestamp = packet.timestamp * 1000L
                            )
                        )
                    }
                }
            } else {
                // Fallback for legacy plain text
                sosText = String(decryptedBytes, Charsets.UTF_8)
            }

            val sender = database.peerDao().getPeerById(packet.senderId)
            val senderAlias = sender?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"

            val messageEntity = MessageEntity(
                messageId = packet.messageId.toString(),
                senderId = packet.senderId,
                recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
                senderAlias = senderAlias,
                text = sosText,
                timestamp = packet.timestamp * 1000L,
                isOutgoing = false,
                isBroadcast = true,
                status = MessageStatus.DELIVERED,
                hopCount = MeshPacket.DEFAULT_TTL - packet.ttl,
                isSos = true
            )
            database.messageDao().insert(messageEntity)

            onIncomingMessageListener?.invoke(packet.senderId, senderAlias, sosText, true)
            onSosAlertReceivedListener?.invoke(packet.senderId, senderAlias, sosText, lat, lon)
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt SOS packet: ${e.message}")
        }

        // Out-of-band Priority flood relay: bypass normal queues & rebroadcast immediately
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "PRIORITY_SOS_RELAY: Forwarded emergency SOS across mesh")
        }
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
        broadcastPacket(raw)
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

        // Dual-Radio Dispatch: Send directly over Wi-Fi TCP if available, otherwise BLE broadcast
        if (wifiEngine.isPeerConnected(recipientNodeId)) {
            wifiEngine.sendDirectPacket(recipientNodeId, raw)
        }
        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Sent direct DM to $recipientNodeId (${text.length} chars)")

        return msgId.toString()
    }

    private suspend fun sendAck(recipientNodeId: Long, originalMsgId: UUID) {
        val peer = database.peerDao().getPeerById(recipientNodeId) ?: return
        val timestamp = System.currentTimeMillis() / 1000L

        // SECURITY: Use a fresh unique ID as the packet nonce — NEVER reuse the originalMsgId as GCM IV.
        // The original messageId is embedded as plaintext inside the encrypted payload so the receiver
        // can recover which message is being ACKed without any nonce reuse.
        val ackPacketId = UUID.randomUUID()

        // Encode originalMsgId (16 bytes) as the ACK payload so the receiver knows what was delivered
        val plainPayload = java.nio.ByteBuffer.allocate(16).apply {
            putLong(originalMsgId.mostSignificantBits)
            putLong(originalMsgId.leastSignificantBits)
        }.array()

        val aad = MeshPacket.computeAad(
            type = PacketType.ACK,
            messageId = ackPacketId,  // AAD bound to unique ACK packet identity
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)
        val encResult = cryptoEngine.encrypt(
            plaintext = plainPayload,
            messageId = ackPacketId,  // Fresh nonce — unique per ACK, no reuse
            aesKey = sessionKey,
            aad = aad
        )

        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(ackPacket)
        val dedupKey = "${ackPacketId}:${PacketType.ACK.code}"
        synchronized(dedupCache) {
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, timestamp)
        )
        if (wifiEngine.isPeerConnected(recipientNodeId)) {
            wifiEngine.sendDirectPacket(recipientNodeId, raw)
        }
        broadcastPacket(raw)
        logPacket("ACK_TX", ackPacket, raw.size, "Sent authenticated ACK for msg $originalMsgId to $recipientNodeId (ackId=$ackPacketId)")
    }

    private suspend fun drainStoreAndForwardQueueForPeer(recipientNodeId: Long) {
        val now = System.currentTimeMillis()
        val lastDrain = lastDrainTimes[recipientNodeId] ?: 0L
        if (now - lastDrain < 30_000L) {
            // Throttle: don't flood re-broadcasts if peer announced recently
            return
        }
        lastDrainTimes[recipientNodeId] = now

        val pending = database.storeForwardDao().getPendingForRecipient(recipientNodeId, now)
        for (item in pending) {
            broadcastPacket(item.packetData)
            logPacket("SF_DRAIN", null, item.packetData.size, "Draining store-and-forward msg ${item.messageId} to $recipientNodeId")
        }
    }

    suspend fun requestAvatar(peerNodeId: Long) {
        val peer = database.peerDao().getPeerById(peerNodeId) ?: return
        val timestamp = System.currentTimeMillis() / 1000L
        val msgId = UUID.randomUUID()

        val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)

        val aad = MeshPacket.computeAad(
            type = PacketType.AVATAR_REQUEST,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = peerNodeId,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = ByteArray(0),
            messageId = msgId,
            aesKey = sessionKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.AVATAR_REQUEST,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = peerNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Requested avatar from $peerNodeId")
    }

    private suspend fun handleAvatarRequest(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        if (packet.recipientId == cryptoEngine.nodeId) {
            val senderPeer = database.peerDao().getPeerById(packet.senderId)
            if (senderPeer == null) {
                logPacket("DROP", packet, rawBytes.size, "Dropped avatar request from unknown peer ${packet.senderId}")
                return
            }

            // Cryptographically verify origin proof using AEAD auth tag
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

                logPacket("RX", packet, rawBytes.size, "Authenticated avatar request from ${packet.senderId}")
                val avatarFile = java.io.File(context.filesDir, "avatars/my_avatar.jpg")
                if (avatarFile.exists()) {
                    val avatarBytes = avatarFile.readBytes()
                    mediaTransferManager.sendMedia(
                        recipientNodeId = packet.senderId,
                        mediaType = com.meshwhisper.app.data.model.MediaType.AVATAR,
                        mediaBytes = avatarBytes
                    )
                }
            } catch (e: Exception) {
                logPacket("DROP", packet, rawBytes.size, "Rejected forged/unauthenticated avatar request from ${packet.senderId}")
                Log.w(tag, "Rejected forged avatar request: auth tag mismatch from ${packet.senderId}")
            }
        } else if (packet.ttl > 1) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying avatar request for ${packet.recipientId}")
        }
    }

    suspend fun sendTypingIndicator(recipientNodeId: Long, isTyping: Boolean) {
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val payload = byteArrayOf(if (isTyping) 1 else 0)

        val packet = MeshPacket(
            type = PacketType.TYPING_INDICATOR,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = 1, // Single-hop ephemeral
            timestamp = timestamp,
            payload = payload
        )

        val raw = MeshPacket.serialize(packet)
        bleEngine.broadcastPacket(raw)
    }

    private fun handleTypingIndicator(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        if (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID) {
            val isTyping = packet.payload.isNotEmpty() && packet.payload[0] == 1.toByte()
            onTypingIndicatorListener?.invoke(packet.senderId, isTyping)
        }
    }

    suspend fun sendMediaDirect(
        recipientNodeId: Long,
        mediaType: MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L,
        originalFileName: String = "",
        previewBytes: ByteArray = ByteArray(0),
        gridCols: Int = 1,
        gridRows: Int = 1,
        imageWidthPx: Int = 0,
        imageHeightPx: Int = 0,
        paddedTileByteLengths: List<Int> = emptyList()
    ): String {
        return mediaTransferManager.sendMedia(
            recipientNodeId = recipientNodeId,
            mediaType = mediaType,
            mediaBytes = mediaBytes,
            caption = caption,
            durationMs = durationMs,
            originalFileName = originalFileName,
            previewBytes = previewBytes,
            gridCols = gridCols,
            gridRows = gridRows,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            paddedTileByteLengths = paddedTileByteLengths
        )
    }

    suspend fun sendMediaBroadcast(
        mediaType: MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L,
        originalFileName: String = "",
        previewBytes: ByteArray = ByteArray(0),
        gridCols: Int = 1,
        gridRows: Int = 1,
        imageWidthPx: Int = 0,
        imageHeightPx: Int = 0,
        paddedTileByteLengths: List<Int> = emptyList()
    ): String {
        return mediaTransferManager.sendMedia(
            recipientNodeId = MeshPacket.BROADCAST_RECIPIENT_ID,
            mediaType = mediaType,
            mediaBytes = mediaBytes,
            caption = caption,
            durationMs = durationMs,
            originalFileName = originalFileName,
            previewBytes = previewBytes,
            gridCols = gridCols,
            gridRows = gridRows,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            paddedTileByteLengths = paddedTileByteLengths
        )
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
            if (logCounter.incrementAndGet() % 50 == 0) {
                database.packetLogDao().trimOldLogs(500)
            }
        }
    }
}

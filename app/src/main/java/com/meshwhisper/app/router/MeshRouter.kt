package com.meshwhisper.app.router

import android.content.Context
import android.util.Log
import com.meshwhisper.core.router.LruDedupCache
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
import com.meshwhisper.app.protocol.TrafficPriority
import com.meshwhisper.app.protocol.MeshTrafficController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import com.meshwhisper.core.protocol.ProfilePayload
import com.meshwhisper.core.router.MeshRouteEngine
import com.meshwhisper.core.router.RouteLookupResult
import com.meshwhisper.core.router.RouteEdge

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
    private val dedupCache = LruDedupCache<String, Long>(4000)

    // QoS Traffic Controller (4-tier bounded priority queues, anti-starvation scheduling)
    val trafficController = MeshTrafficController(maxQueuePerTier = 100, maxPacketLifetimeMs = 30_000L)

    // Statistics
    private val _relayedPacketsCount = MutableStateFlow(0)
    val relayedPacketsCount: StateFlow<Int> = _relayedPacketsCount.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0)
    val totalPacketsReceived: StateFlow<Int> = _totalPacketsReceived.asStateFlow()

    /**
     * Checks if the target peer is directly connected over local Wi-Fi TCP or BLE GATT.
     */
    fun isPeerDirectlyConnected(nodeId: Long): Boolean {
        return bleEngine.isDirectlyConnected(nodeId) || wifiEngine.isPeerConnected(nodeId)
    }

    /**
     * Sends a raw packet directly to a specific connected peer node.
     * Tries high-throughput local Wi-Fi TCP first, then direct BLE GATT.
     * Returns true if delivered directly, false if not directly connected.
     */
    suspend fun sendDirectToNode(nodeId: Long, rawBytes: ByteArray): Boolean {
        if (wifiEngine.isPeerConnected(nodeId)) {
            if (wifiEngine.sendDirectPacket(nodeId, rawBytes)) {
                return true
            }
        }
        if (bleEngine.isDirectlyConnected(nodeId)) {
            if (bleEngine.sendDirectPacket(nodeId, rawBytes)) {
                return true
            }
        }
        return false
    }

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

    val routeEngine = MeshRouteEngine(cryptoEngine.nodeId)

    fun syncDirectNeighbors() {
        val blePeers = bleEngine.connectedNodeIds.value
        val wifiPeers = wifiEngine.connectedWifiPeers.value.keys
        val allDirect = (blePeers + wifiPeers).filter { it != cryptoEngine.nodeId && it != 0L }.toSet()
        routeEngine.updateDirectNeighbors(allDirect)
    }

    private val lastDrainTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val logCounter = java.util.concurrent.atomic.AtomicInteger(0)

    var onTypingIndicatorListener: ((senderId: Long, isTyping: Boolean) -> Unit)? = null
    var onIncomingMessageListener: ((senderId: Long, senderAlias: String, text: String, isBroadcast: Boolean) -> Unit)? = null
    var onSosAlertReceivedListener: ((senderId: Long, senderAlias: String, text: String, lat: Double?, lon: Double?, fixTimestamp: Long?) -> Unit)? = null

    init {
        bleEngine.onPacketReceivedListener = { packetBytes, ingressAddress ->
            handleIncomingPacket(packetBytes, ingressAddress)
        }

        bleEngine.onPeerReadyListener = { address ->
            // Exchange identity upon established BLE link and immediately drain S&F
            scope.launch {
                syncDirectNeighbors()
                announcePresence()
                val directNodeId = bleEngine.getDirectNodeId(address)
                if (directNodeId != null && directNodeId != 0L) {
                    drainStoreAndForwardQueueForPeer(directNodeId, forceImmediate = true)
                }
            }
        }

        bleEngine.onPeerDisconnectedListener = { address ->
            scope.launch {
                val directNodeId = bleEngine.getDirectNodeId(address)
                if (directNodeId != null && directNodeId != 0L) {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, directNodeId)
                }
                syncDirectNeighbors()
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
                syncDirectNeighbors()
                announcePresence()
                drainStoreAndForwardQueueForPeer(peerId, forceImmediate = true)
            }
        }

        wifiEngine.onPeerDisconnectedListener = { peerId ->
            scope.launch {
                routeEngine.markLinkFailed(cryptoEngine.nodeId, peerId)
                syncDirectNeighbors()
            }
        }

        // Periodic Store-and-Forward Drain Sweep (Fix #10)
        scope.launch {
            while (isActive) {
                delay(30_000L)
                try {
                    val pendingRecipients = database.storeForwardDao().getPendingRecipients()
                    for (recipientId in pendingRecipients) {
                        drainStoreAndForwardQueueForPeer(recipientId)
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Store-and-Forward periodic sweep error: ${e.message}")
                }
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
        if (dedupCache.containsKey(dedupKey)) {
            // Lost-ACK Recovery: If sender retransmitted this DM because our delivery ACK was dropped,
            // re-emit the delivery ACK so the sender can mark the message DELIVERED.
            if (packet.type == PacketType.DIRECT_MESSAGE && packet.recipientId == cryptoEngine.nodeId) {
                scope.launch {
                    logPacket("ACK_RETRY", packet, rawBytes.size, "Re-emitting delivery ACK for duplicate DM ${packet.messageId}")
                    sendAck(packet.senderId, packet.messageId)
                }
            }
            logPacket("DROP", packet, rawBytes.size, "Duplicate packet dropped (fast RAM cache)")
            return
        }

        scope.launch {
            // Atomic dedup: INSERT OR IGNORE returns -1 if the row already existed.
            // This eliminates the hasSeen()/markSeen() race where two concurrent arrivals
            // both see hasSeen==false before either writes the row.
            val inserted = database.processedPacketDao().markSeen(
                com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, packet.timestamp)
            )
            if (inserted == -1L) {
                if (packet.type == PacketType.DIRECT_MESSAGE && packet.recipientId == cryptoEngine.nodeId) {
                    logPacket("ACK_RETRY", packet, rawBytes.size, "Re-emitting delivery ACK for duplicate DM ${packet.messageId}")
                    sendAck(packet.senderId, packet.messageId)
                }
                logPacket("DROP", packet, rawBytes.size, "Duplicate packet dropped (persistent replay DB)")
                return@launch
            }
            dedupCache.put(dedupKey, System.currentTimeMillis())

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
                PacketType.PROFILE_UPDATE -> {
                    handleProfileUpdate(packet, rawBytes, ingressAddress)
                }
                PacketType.PROFILE_REQUEST -> {
                    handleProfileRequest(packet, rawBytes, ingressAddress)
                }
            }
        }
    }

    // =========================================================================
    // PACKET HANDLERS
    // =========================================================================

    private suspend fun handlePeerAnnounce(packet: MeshPacket, ingressAddress: String?) {
        val decryptedPayload = try {
            cryptoEngine.decrypt(
                ciphertext = packet.payload,
                authTag = packet.authTag,
                messageId = packet.messageId,
                aesKey = cryptoEngine.publicChannelKey,
                aad = packet.getAuthenticatedHeaderBytes()
            )
        } catch (_: Exception) {
            packet.payload
        }

        if (decryptedPayload.size < 32) return

        // Cryptographic Signature Verification for PEER_ANNOUNCE (Anti-Spoofing P0-1 Fix)
        if (decryptedPayload.size >= 32 + 64) {
            val unsignedData = decryptedPayload.copyOfRange(0, decryptedPayload.size - 64)
            val signature = decryptedPayload.copyOfRange(decryptedPayload.size - 64, decryptedPayload.size)

            val tempBuffer = ByteBuffer.wrap(unsignedData)
            val tempAliasLen = tempBuffer.get().toInt() and 0xFF
            if (tempBuffer.remaining() >= tempAliasLen + 32) {
                tempBuffer.position(1 + tempAliasLen)
                val tempPubKey = ByteArray(32)
                tempBuffer.get(tempPubKey)

                val isSigValid = cryptoEngine.verifySignature(tempPubKey, unsignedData, signature)
                if (!isSigValid) {
                    logPacket("DROP", packet, packet.payload.size + MeshPacket.OVERHEAD_SIZE, "REJECTED: Forged / Invalid Ed25519 signature in PEER_ANNOUNCE from ${packet.senderId}")
                    Log.w(tag, "SECURITY ALERT: Dropped forged PEER_ANNOUNCE from ${packet.senderId} (signature verification failed)")
                    return
                }
            }
        }

        val buffer = ByteBuffer.wrap(decryptedPayload)
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

        // Upsert reported topology edges into database and routing engine
        val now = System.currentTimeMillis()
        val freshEdges = mutableListOf<com.meshwhisper.core.router.RouteEdge>()
        for (neighborId in neighborNodeIds) {
            database.topologyEdgeDao().insertOrUpdate(
                com.meshwhisper.app.data.model.TopologyEdgeEntity(
                    fromNode = packet.senderId,
                    toNode = neighborId,
                    rssi = 0,
                    lastSeen = now
                )
            )
            freshEdges.add(com.meshwhisper.core.router.RouteEdge(packet.senderId, neighborId, 1, now))
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
            freshEdges.add(com.meshwhisper.core.router.RouteEdge(cryptoEngine.nodeId, packet.senderId, 1, now))
        }
        routeEngine.updateEdges(freshEdges)

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
            isMuted = existingPeer?.isMuted ?: false,
            isVerified = if (hasKeyChanged) false else (existingPeer?.isVerified ?: false)
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

        // Request profile if not yet known locally
        if (database.profileDao().getProfile(packet.senderId) == null) {
            scope.launch {
                requestProfile(packet.senderId)
            }
        }

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

        // Decrypt broadcast payload with AAD header verification and Ed25519 sender authentication
        try {
            val decryptedBytes = try {
                cryptoEngine.decrypt(
                    ciphertext = packet.payload,
                    authTag = packet.authTag,
                    messageId = packet.messageId,
                    aesKey = cryptoEngine.getActiveBroadcastKey(),
                    aad = packet.getAuthenticatedHeaderBytes()
                )
            } catch (teamEx: Exception) {
                if (cryptoEngine.isCurrentChannelConfidential()) {
                    cryptoEngine.decrypt(
                        ciphertext = packet.payload,
                        authTag = packet.authTag,
                        messageId = packet.messageId,
                        aesKey = cryptoEngine.publicChannelKey,
                        aad = packet.getAuthenticatedHeaderBytes()
                    )
                } else {
                    throw teamEx
                }
            }

            val sender = database.peerDao().getPeerById(packet.senderId)
            val (text, isValidSender) = if (decryptedBytes.size >= 64) {
                val tBytes = decryptedBytes.copyOfRange(0, decryptedBytes.size - 64)
                val signature = decryptedBytes.copyOfRange(decryptedBytes.size - 64, decryptedBytes.size)
                val valid = if (sender != null) {
                    val pubKey = CryptoEngine.hexToBytes(sender.publicKeyHex)
                    cryptoEngine.verifySignature(pubKey, tBytes, signature)
                } else true
                Pair(String(tBytes, Charsets.UTF_8), valid)
            } else {
                Pair(String(decryptedBytes, Charsets.UTF_8), true)
            }

            if (!isValidSender) {
                logPacket("DROP", packet, rawBytes.size, "REJECTED: Forged broadcast signature from ${packet.senderId}")
                Log.w(tag, "SECURITY ALERT: Dropped forged broadcast from ${packet.senderId} (Ed25519 signature verification failed)")
                return
            }

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

        // Flood relay if hops remain (with Software CSMA Jitter)
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying broadcast msg")
        }
    }

    private suspend fun relayPacketWithJitter(
        relayedPacket: MeshPacket,
        ingressAddress: String?,
        logDescription: String,
        isPrioritySos: Boolean = false
    ) {
        // Software CSMA / Collision Avoidance Jitter (15ms - 75ms for normal, 5ms - 20ms for SOS)
        val jitterMs = if (isPrioritySos) {
            java.util.concurrent.ThreadLocalRandom.current().nextLong(5L, 20L)
        } else {
            java.util.concurrent.ThreadLocalRandom.current().nextLong(15L, 75L)
        }
        delay(jitterMs)

        val relayedBytes = MeshPacket.serialize(relayedPacket)
        broadcastPacket(relayedBytes, ingressAddress)
        _relayedPacketsCount.value += 1
        logPacket("RELAY", relayedPacket, relayedBytes.size, logDescription)
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

            syncDirectNeighbors()
            val routeResult = routeEngine.resolveRoute(packet.recipientId)
            var directDelivered = false

            if (packet.ttl > 1) {
                val relayedPacket = packet.decrementTtl()
                val relayedBytes = MeshPacket.serialize(relayedPacket)

                when (routeResult) {
                    is RouteLookupResult.Direct -> {
                        directDelivered = sendDirectToNode(packet.recipientId, relayedBytes)
                        if (directDelivered) {
                            _relayedPacketsCount.value += 1
                            logPacket("RELAY_DIRECT", relayedPacket, relayedBytes.size, "Delivered DM directly to destination ${packet.recipientId}")
                        }
                    }
                    is RouteLookupResult.NextHop -> {
                        val nextHop = routeResult.nextHopNodeId
                        val forwarded = sendDirectToNode(nextHop, relayedBytes)
                        if (forwarded) {
                            directDelivered = true // successfully handed off to next relay
                            _relayedPacketsCount.value += 1
                            logPacket("RELAY_NEXTHOP", relayedPacket, relayedBytes.size, "Forwarded DM for ${packet.recipientId} to next-hop $nextHop (hops=${routeResult.hopCount})")
                        } else {
                            routeEngine.markLinkFailed(cryptoEngine.nodeId, nextHop)
                        }
                    }
                    RouteLookupResult.Unreachable -> {
                        // No route known, will buffer and broadcast below
                    }
                }

                if (!directDelivered) {
                    // Buffer in store-and-forward queue for offline peer
                    val sfEntity = StoreForwardEntity(
                        messageId = packet.messageId.toString(),
                        recipientId = packet.recipientId,
                        packetData = rawBytes,
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                    )
                    database.storeForwardDao().insert(sfEntity)
                    database.storeForwardDao().trimRecipientQueue(packet.recipientId, MAX_STORE_FORWARD_PER_RECIPIENT)

                    // Flood relay forward with jitter as fallback
                    relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying private DM for ${packet.recipientId}")
                }
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
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            syncDirectNeighbors()
            val routeResult = routeEngine.resolveRoute(packet.recipientId)
            var ackRelayedDirect = false
            when (routeResult) {
                is RouteLookupResult.Direct -> {
                    ackRelayedDirect = sendDirectToNode(packet.recipientId, relayedBytes)
                }
                is RouteLookupResult.NextHop -> {
                    ackRelayedDirect = sendDirectToNode(routeResult.nextHopNodeId, relayedBytes)
                }
                RouteLookupResult.Unreachable -> {}
            }
            if (ackRelayedDirect) {
                _relayedPacketsCount.value += 1
                logPacket("RELAY_ACK_DIRECT", relayedPacket, relayedBytes.size, "Forwarded ACK for ${packet.recipientId} via unicast next-hop")
            } else {
                relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying ACK (ackId=${packet.messageId})")
            }
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
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying MEDIA_INIT from ${packet.senderId}")
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
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying MEDIA_CHUNK from ${packet.senderId}")
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
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying MEDIA_NACK for ${packet.recipientId}")
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
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying MEDIA_ACK for ${packet.recipientId}")
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
            relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying MEDIA_ABORT for ${packet.recipientId}")
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

        // Generate Ed25519 digital signature over the announcement data (Anti-Spoofing P0-1 Fix)
        val unsignedPayload = buffer.array()
        val signature = cryptoEngine.sign(unsignedPayload)
        val signedPayload = ByteArray(unsignedPayload.size + signature.size)
        System.arraycopy(unsignedPayload, 0, signedPayload, 0, unsignedPayload.size)
        System.arraycopy(signature, 0, signedPayload, unsignedPayload.size, signature.size)

        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L

        val aad = MeshPacket.computeAad(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = signedPayload,
            messageId = msgId,
            aesKey = cryptoEngine.publicChannelKey,
            aad = aad
        )

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        val dedupKey = "${packet.messageId}:${PacketType.PEER_ANNOUNCE.code}"
        val nowSec = System.currentTimeMillis() / 1000L
        dedupCache.put(dedupKey, System.currentTimeMillis())
        try {
            database.processedPacketDao().purgeOld(nowSec - 86400L)
            database.storeForwardDao().purgeExpired(System.currentTimeMillis())
            database.storeForwardDao().trimTotalQueue(MAX_TOTAL_STORE_FORWARD)
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

    suspend fun sendSosBroadcast(
        text: String,
        latitude: Double? = null,
        longitude: Double? = null,
        accuracyMeters: Float = 0f,
        locationFixTimestamp: Long = System.currentTimeMillis()
    ): String {
        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000L

        val hasLocation = (latitude != null && longitude != null)
        val locationSize = if (hasLocation) 8 + 8 + 4 + 8 else 0 // 28 bytes
        val payloadBuf = ByteBuffer.allocate(1 + 2 + textBytes.size + locationSize)
        payloadBuf.put(if (hasLocation) 0x01.toByte() else 0x00.toByte()) // flags (0x01 = hasLocation, 0x00 = no location)
        payloadBuf.putShort((textBytes.size and 0xFFFF).toShort())
        payloadBuf.put(textBytes)
        if (hasLocation) {
            payloadBuf.putDouble(latitude!!)
            payloadBuf.putDouble(longitude!!)
            payloadBuf.putFloat(accuracyMeters)
            payloadBuf.putLong(locationFixTimestamp)
        }
        val payloadBytes = payloadBuf.array()
        val signature = cryptoEngine.sign(payloadBytes)
        val signedPlaintext = ByteArray(payloadBytes.size + signature.size)
        System.arraycopy(payloadBytes, 0, signedPlaintext, 0, payloadBytes.size)
        System.arraycopy(signature, 0, signedPlaintext, payloadBytes.size, signature.size)

        val aad = MeshPacket.computeAad(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val (ciphertext, authTag) = cryptoEngine.encrypt(
            plaintext = signedPlaintext,
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
        dedupCache.put(dedupKey, System.currentTimeMillis())
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

            val sender = database.peerDao().getPeerById(packet.senderId)

            val (sosPayload, isSenderVerified) = if (decryptedBytes.size >= 64 + 3) {
                val unsigned = decryptedBytes.copyOfRange(0, decryptedBytes.size - 64)
                val sig = decryptedBytes.copyOfRange(decryptedBytes.size - 64, decryptedBytes.size)
                val valid = if (sender != null) {
                    val pubKey = CryptoEngine.hexToBytes(sender.publicKeyHex)
                    cryptoEngine.verifySignature(pubKey, unsigned, sig)
                } else true
                Pair(unsigned, valid)
            } else {
                Pair(decryptedBytes, true)
            }

            if (!isSenderVerified) {
                logPacket("DROP", packet, rawBytes.size, "REJECTED: Forged SOS alert signature from ${packet.senderId}")
                Log.w(tag, "SECURITY ALERT: Dropped forged SOS alert from ${packet.senderId} (Ed25519 signature verification failed)")
                return
            }

            var sosText = ""
            var lat: Double? = null
            var lon: Double? = null
            var fixTimestamp: Long? = null

            val buf = ByteBuffer.wrap(sosPayload)
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
                        fixTimestamp = if (buf.remaining() >= 8) buf.long else (packet.timestamp * 1000L)

                        val senderAlias = sender?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
                        database.locationDao().insertOrUpdate(
                            com.meshwhisper.app.data.model.LastKnownLocationEntity(
                                nodeId = packet.senderId,
                                alias = senderAlias,
                                latitude = lat,
                                longitude = lon,
                                accuracyMeters = accuracy,
                                timestamp = fixTimestamp
                            )
                        )
                    }
                }
            } else {
                // Fallback for legacy plain text
                sosText = String(sosPayload, Charsets.UTF_8)
            }

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
            onSosAlertReceivedListener?.invoke(packet.senderId, senderAlias, sosText, lat, lon, fixTimestamp)
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt SOS packet: ${e.message}")
        }

        // Out-of-band Priority flood relay: bypass normal queues & rebroadcast immediately (with minimal SOS jitter)
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId) {
            val relayedPacket = packet.decrementTtl()
            relayPacketWithJitter(relayedPacket, ingressAddress, "PRIORITY_SOS_RELAY: Forwarded emergency SOS across mesh", isPrioritySos = true)
        }
    }

    suspend fun sendBroadcastMessage(text: String): String {
        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val signature = cryptoEngine.sign(textBytes)
        val signedPlaintext = ByteArray(textBytes.size + signature.size)
        System.arraycopy(textBytes, 0, signedPlaintext, 0, textBytes.size)
        System.arraycopy(signature, 0, signedPlaintext, textBytes.size, signature.size)

        val timestamp = System.currentTimeMillis() / 1000L

        val aad = MeshPacket.computeAad(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = signedPlaintext,
            messageId = msgId,
            aesKey = cryptoEngine.getActiveBroadcastKey(),
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
        dedupCache.put(dedupKey, System.currentTimeMillis())
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
        dedupCache.put(dedupKey, System.currentTimeMillis())
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
        database.storeForwardDao().trimRecipientQueue(recipientNodeId, MAX_STORE_FORWARD_PER_RECIPIENT)

        // Directed Unicast Next-Hop Dispatch with Automatic Failover
        syncDirectNeighbors()
        val routeResult = routeEngine.resolveRoute(recipientNodeId)
        var dispatched = false

        when (routeResult) {
            is RouteLookupResult.Direct -> {
                dispatched = sendDirectToNode(recipientNodeId, raw)
                if (!dispatched) {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, recipientNodeId)
                }
            }
            is RouteLookupResult.NextHop -> {
                val nextHop = routeResult.nextHopNodeId
                dispatched = sendDirectToNode(nextHop, raw)
                if (dispatched) {
                    database.messageDao().updateStatus(msgId.toString(), MessageStatus.RELAYED)
                    logPacket("TX_RELAY_HOP", packet, raw.size, "Forwarded DM for $recipientNodeId via next-hop $nextHop (hops=${routeResult.hopCount})")
                } else {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, nextHop)
                    // Immediate failover to alternate route if available
                    val altRoute = routeEngine.resolveRoute(recipientNodeId)
                    if (altRoute is RouteLookupResult.NextHop) {
                        dispatched = sendDirectToNode(altRoute.nextHopNodeId, raw)
                        if (dispatched) {
                            database.messageDao().updateStatus(msgId.toString(), MessageStatus.RELAYED)
                            logPacket("TX_FAILOVER", packet, raw.size, "Failover DM for $recipientNodeId via alt-hop ${altRoute.nextHopNodeId}")
                        }
                    }
                }
            }
            RouteLookupResult.Unreachable -> {
                // Route not yet discovered, fall through to broadcast below
            }
        }

        if (!dispatched) {
            if (wifiEngine.isPeerConnected(recipientNodeId)) {
                wifiEngine.sendDirectPacket(recipientNodeId, raw)
                bleEngine.broadcastPacket(raw)
            } else {
                broadcastPacket(raw)
            }
        }
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
        dedupCache.put(dedupKey, System.currentTimeMillis())
        database.processedPacketDao().markSeen(
            com.meshwhisper.app.data.model.ProcessedPacketEntity(dedupKey, timestamp)
        )
        syncDirectNeighbors()
        val routeResult = routeEngine.resolveRoute(recipientNodeId)
        var ackDelivered = false
        when (routeResult) {
            is RouteLookupResult.Direct -> {
                ackDelivered = sendDirectToNode(recipientNodeId, raw)
            }
            is RouteLookupResult.NextHop -> {
                ackDelivered = sendDirectToNode(routeResult.nextHopNodeId, raw)
                if (!ackDelivered) {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, routeResult.nextHopNodeId)
                }
            }
            RouteLookupResult.Unreachable -> {}
        }
        if (!ackDelivered) {
            if (wifiEngine.isPeerConnected(recipientNodeId)) {
                wifiEngine.sendDirectPacket(recipientNodeId, raw)
                bleEngine.broadcastPacket(raw)
            } else {
                broadcastPacket(raw)
            }
        }
        logPacket("ACK_TX", ackPacket, raw.size, "Sent authenticated ACK for msg $originalMsgId to $recipientNodeId (ackId=$ackPacketId)")
    }

    internal suspend fun drainStoreAndForwardQueueForPeer(recipientNodeId: Long, forceImmediate: Boolean = false) {
        val now = System.currentTimeMillis()
        val lastDrain = lastDrainTimes[recipientNodeId] ?: 0L
        if (!forceImmediate && now - lastDrain < 30_000L) {
            // Throttle: don't flood re-broadcasts if peer announced recently
            return
        }
        lastDrainTimes[recipientNodeId] = now

        val pending = database.storeForwardDao().getPendingForRecipient(recipientNodeId, now)
        if (pending.isEmpty()) return

        val isDirect = isPeerDirectlyConnected(recipientNodeId)
        syncDirectNeighbors()
        val route = routeEngine.resolveRoute(recipientNodeId)

        for (item in pending) {
            if (isDirect) {
                // Architectural Guarantee: Direct peers get targeted unicast transmission.
                // NEVER falls back to global broadcast to eliminate broadcast amplification.
                val delivered = sendDirectToNode(recipientNodeId, item.packetData)
                if (delivered) {
                    database.storeForwardDao().delete(item.messageId)
                    logPacket("SF_DRAIN_DIRECT", null, item.packetData.size, "Directed unicast drain msg ${item.messageId} to direct peer $recipientNodeId")
                } else {
                    logPacket("SF_DRAIN_FAIL", null, item.packetData.size, "Direct link failed during drain of msg ${item.messageId} to $recipientNodeId")
                }
            } else if (route is RouteLookupResult.NextHop) {
                // Multi-hop peer with known route: Unicast handoff to next relay
                val packet = MeshPacket.deserialize(item.packetData)
                if (packet != null && packet.ttl > 1) {
                    val relayedPacket = packet.decrementTtl()
                    val relayedBytes = MeshPacket.serialize(relayedPacket)
                    val forwarded = sendDirectToNode(route.nextHopNodeId, relayedBytes)
                    if (forwarded) {
                        database.storeForwardDao().delete(item.messageId) // Handed off to next hop!
                        logPacket("SF_DRAIN_NEXTHOP", relayedPacket, relayedBytes.size, "Directed S&F handoff of msg ${item.messageId} via next-hop ${route.nextHopNodeId}")
                    } else {
                        routeEngine.markLinkFailed(cryptoEngine.nodeId, route.nextHopNodeId)
                    }
                }
            } else {
                // Multi-hop peer without known route: Only relay if TTL > 1 with paced jitter
                val packet = MeshPacket.deserialize(item.packetData)
                if (packet != null && packet.ttl > 1) {
                    val relayedPacket = packet.decrementTtl()
                    relayPacketWithJitter(relayedPacket, null, "Relaying store-and-forward msg ${item.messageId} for remote peer $recipientNodeId")
                }
            }
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
        if (isPeerDirectlyConnected(peerNodeId)) {
            sendDirectToNode(peerNodeId, raw)
        } else {
            broadcastPacket(raw)
        }
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

    suspend fun requestProfile(peerNodeId: Long) {
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val buffer = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(peerNodeId)

        val packet = MeshPacket(
            type = PacketType.PROFILE_REQUEST,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = peerNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = buffer.array()
        )
        val raw = MeshPacket.serialize(packet)
        if (isPeerDirectlyConnected(peerNodeId)) {
            sendDirectToNode(peerNodeId, raw)
        } else {
            broadcastPacket(raw)
        }
        logPacket("TX", packet, raw.size, "Requested profile from $peerNodeId")
    }

    private suspend fun handleProfileUpdate(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        val payload = ProfilePayload.deserialize(packet.payload)
        if (payload == null) {
            logPacket("DROP", packet, rawBytes.size, "REJECTED: Malformed ProfilePayload from ${packet.senderId}")
            return
        }

        // Strict Key-to-Identity Binding
        if (payload.nodeId != packet.senderId) {
            logPacket("DROP", packet, rawBytes.size, "SECURITY ALERT: Dropped forged profile - sender ${packet.senderId} claimed node ${payload.nodeId}")
            Log.w(tag, "SECURITY ALERT: Profile senderId ${packet.senderId} does not match payload nodeId ${payload.nodeId}")
            return
        }

        // Cryptographic Ed25519 Signature Verification
        if (!payload.verifySignature()) {
            logPacket("DROP", packet, rawBytes.size, "SECURITY ALERT: Invalid Ed25519 signature in profile from ${packet.senderId}")
            Log.w(tag, "SECURITY ALERT: Dropped profile from ${packet.senderId} (Ed25519 signature invalid)")
            return
        }

        // Anti-Rollback & Conflict Resolution (Strict Monotonicity: version > cached.version)
        val existing = database.profileDao().getProfile(payload.nodeId)
        if (existing != null && payload.version <= existing.version) {
            logPacket("DROP", packet, rawBytes.size, "REJECTED: Stale/duplicate profile version ${payload.version} <= ${existing.version} from ${payload.nodeId}")
            Log.d(tag, "Dropped stale/duplicate profile v${payload.version} from ${payload.nodeId} (cached v${existing.version})")
            return
        }

        val avatarHashHex = CryptoEngine.bytesToHex(payload.avatarHash)
        val hasAvatar = payload.avatarHash.any { it != 0.toByte() }
        val isNewAvatar = hasAvatar && avatarHashHex != existing?.avatarHashHex

        val newProfile = com.meshwhisper.app.data.model.ProfileEntity(
            nodeId = payload.nodeId,
            displayName = payload.displayName,
            bio = payload.bio,
            avatarHashHex = avatarHashHex,
            avatarUri = if (isNewAvatar) null else existing?.avatarUri,
            version = payload.version,
            signature = payload.signature,
            updatedAt = System.currentTimeMillis()
        )
        database.profileDao().upsertProfile(newProfile)
        logPacket("RX", packet, rawBytes.size, "Applied verified profile v${payload.version} for '${payload.displayName}' (${payload.nodeId})")

        // Keep legacy PeerEntity alias in sync for backward compatibility
        val existingPeer = database.peerDao().getPeerById(payload.nodeId)
        if (existingPeer != null && payload.displayName.isNotBlank() && existingPeer.alias != payload.displayName) {
            database.peerDao().insertOrUpdate(existingPeer.copy(alias = payload.displayName))
        }

        // If new avatar hash announced, request targeted unicast avatar sync
        if (isNewAvatar) {
            scope.launch {
                requestAvatar(payload.nodeId)
            }
        }

        // Multi-hop flood relay for profile discovery
        if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId && packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying profile update for ${packet.senderId} (v${payload.version})")
        }
    }

    private suspend fun handleProfileRequest(packet: MeshPacket, rawBytes: ByteArray, ingressAddress: String?) {
        if (packet.recipientId == cryptoEngine.nodeId) {
            val myProfile = database.profileDao().getProfile(cryptoEngine.nodeId)
            if (myProfile != null) {
                val avatarHashBytes = if (myProfile.avatarHashHex.isNotEmpty()) {
                    CryptoEngine.hexToBytes(myProfile.avatarHashHex)
                } else {
                    ProfilePayload.EMPTY_AVATAR_HASH
                }
                val payload = ProfilePayload(
                    nodeId = myProfile.nodeId,
                    version = myProfile.version,
                    displayName = myProfile.displayName,
                    bio = myProfile.bio,
                    avatarHash = avatarHashBytes,
                    signingPublicKey = cryptoEngine.signingPublicKey,
                    signature = myProfile.signature ?: ByteArray(64)
                )
                val responsePacket = MeshPacket(
                    type = PacketType.PROFILE_UPDATE,
                    messageId = UUID.randomUUID(),
                    senderId = cryptoEngine.nodeId,
                    recipientId = packet.senderId,
                    ttl = MeshPacket.DEFAULT_TTL,
                    timestamp = System.currentTimeMillis() / 1000L,
                    payload = payload.serialize()
                )
                val responseRaw = MeshPacket.serialize(responsePacket)
                if (isPeerDirectlyConnected(packet.senderId)) {
                    sendDirectToNode(packet.senderId, responseRaw)
                } else {
                    broadcastPacket(responseRaw)
                }
                logPacket("TX", responsePacket, responseRaw.size, "Sent direct profile response to ${packet.senderId} (v${myProfile.version})")
            }
        } else if (packet.ttl > 1) {
            val relayedPacket = packet.decrementTtl()
            val relayedBytes = MeshPacket.serialize(relayedPacket)
            broadcastPacket(relayedBytes, ingressAddress)
            _relayedPacketsCount.value += 1
            logPacket("RELAY", relayedPacket, relayedBytes.size, "Relaying profile request for ${packet.recipientId}")
        }
    }

    suspend fun broadcastProfileUpdate(displayName: String, bio: String, avatarBytes: ByteArray? = null): Long {
        val current = database.profileDao().getProfile(cryptoEngine.nodeId)
        val nextVersion = (current?.version ?: 0L) + 1L

        val avatarFile = java.io.File(context.filesDir, "avatars/my_avatar.jpg")
        val finalAvatarBytes = avatarBytes ?: if (avatarFile.exists()) avatarFile.readBytes() else null
        val avatarHash = if (finalAvatarBytes != null && finalAvatarBytes.isNotEmpty()) {
            com.meshwhisper.app.media.MediaCompressor.computeSha256(finalAvatarBytes)
        } else {
            ProfilePayload.EMPTY_AVATAR_HASH
        }

        val signingPub = cryptoEngine.signingPublicKey
        val canonical = ProfilePayload.computeCanonicalBytes(
            nodeId = cryptoEngine.nodeId,
            version = nextVersion,
            displayName = displayName,
            bio = bio,
            avatarHash = avatarHash,
            signingPublicKey = signingPub
        )
        val signature = cryptoEngine.sign(canonical)

        val payload = ProfilePayload(
            nodeId = cryptoEngine.nodeId,
            version = nextVersion,
            displayName = displayName,
            bio = bio,
            avatarHash = avatarHash,
            signingPublicKey = signingPub,
            signature = signature
        )

        val profileEntity = com.meshwhisper.app.data.model.ProfileEntity(
            nodeId = cryptoEngine.nodeId,
            displayName = displayName,
            bio = bio,
            avatarHashHex = CryptoEngine.bytesToHex(avatarHash),
            avatarUri = if (avatarFile.exists()) avatarFile.absolutePath else null,
            version = nextVersion,
            signature = signature,
            updatedAt = System.currentTimeMillis()
        )
        database.profileDao().upsertProfile(profileEntity)

        val packet = MeshPacket(
            type = PacketType.PROFILE_UPDATE,
            messageId = UUID.randomUUID(),
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = payload.serialize()
        )
        val raw = MeshPacket.serialize(packet)
        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Broadcasted profile update v$nextVersion ('$displayName')")
        return nextVersion
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

    companion object {
        const val MAX_STORE_FORWARD_PER_RECIPIENT = 50
        const val MAX_TOTAL_STORE_FORWARD = 500
    }
}

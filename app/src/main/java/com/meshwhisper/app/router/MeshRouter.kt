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
import android.os.PowerManager
import com.meshwhisper.app.telemetry.PeerLiveTelemetry
import com.meshwhisper.app.telemetry.PacketJournalExporter
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import com.meshwhisper.core.protocol.*
import com.meshwhisper.app.crypto.AppPipelineFactory
import com.meshwhisper.core.router.MeshRouteEngine
import com.meshwhisper.core.router.RouteLookupResult
import com.meshwhisper.core.router.RouteEdge
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import com.meshwhisper.core.util.RandomSource
import com.meshwhisper.core.util.DefaultRandomSource
import com.meshwhisper.core.transport.LinkAuthLocalCredentials
import com.meshwhisper.core.transport.LinkAuthProof
import com.meshwhisper.core.transport.LinkAuthSession
import com.meshwhisper.core.transport.LinkAuthState
import com.meshwhisper.core.transport.LinkAuthStepResult
import com.meshwhisper.core.custody.*

class MeshRouter(
    private val context: Context,
    private val bleEngine: MeshBleEngine,
    val wifiEngine: com.meshwhisper.app.wifi.MeshWifiEngine,
    private val cryptoEngine: CryptoEngine,
    private val database: MeshDatabase,
    val clock: Clock = SystemClock(),
    val randomSource: RandomSource = DefaultRandomSource(),
    val identityRepository: com.meshwhisper.app.identity.IdentityRepository = com.meshwhisper.app.identity.IdentityRepository(database, cryptoEngine, clock)
) {
    private val tag = "MeshRouter"
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshRouter: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + exceptionHandler)

    // Deduplication Cache (Capacity: 4000 keys) - Keyed by messageId:packetType to prevent ACK/DM collision
    private val dedupCache = LruDedupCache<String, Long>(4000)
    private val peerPublicKeyCache = ConcurrentHashMap<Long, ByteArray>()
    val packetStore: PacketStore = RoomPacketStore(database.processedPacketDao())
    val pipeline: PacketPipeline = AppPipelineFactory.create(
        cryptoEngine = cryptoEngine,
        database = database,
        packetStore = packetStore,
        clock = clock,
        dedupCache = dedupCache,
        peerPublicKeyCache = peerPublicKeyCache,
        identityRepository = identityRepository
    )

    // QoS Traffic Controller (4-tier bounded priority queues, anti-starvation scheduling)
    val trafficController = MeshTrafficController(
        maxQueuePerTier = com.meshwhisper.core.protocol.ResourceLimits.EGRESS_QUEUE_MAX_PER_TIER,
        maxPacketLifetimeMs = com.meshwhisper.core.protocol.ResourceLimits.EGRESS_PACKET_MAX_LIFETIME_MS,
        maxRelaySlotsPerIdentity = com.meshwhisper.core.protocol.ResourceLimits.EGRESS_QUEUE_RELAY_SLOTS_PER_IDENTITY
    )

    // Statistics
    private val _relayedPacketsCount = MutableStateFlow(0)
    val relayedPacketsCount: StateFlow<Int> = _relayedPacketsCount.asStateFlow()

    private val _totalPacketsReceived = MutableStateFlow(0)
    val totalPacketsReceived: StateFlow<Int> = _totalPacketsReceived.asStateFlow()

    // Live empirical peer telemetry (Phase 0)
    private val _peerTelemetry = MutableStateFlow<List<PeerLiveTelemetry>>(emptyList())
    val peerTelemetry: StateFlow<List<PeerLiveTelemetry>> = _peerTelemetry.asStateFlow()

    private data class PeerTrafficStats(
        var lastHopCount: Int = 1,
        var lastPacketType: String = "NONE",
        var lastPacketTimestamp: Long = 0L,
        var packetsReceived: Int = 0,
        var packetsSent: Int = 0
    )
    private val peerTrafficStats = ConcurrentHashMap<Long, PeerTrafficStats>()
    private val breadcrumbNotificationRateLimiter = ConcurrentHashMap<Long, Long>()

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
        if (bleEngine.sendDirectPacket(nodeId, rawBytes)) {
            return true
        }
        return false
    }

    suspend fun broadcastPacket(rawBytes: ByteArray, ingressAddress: String? = null) {
        val type = if (rawBytes.isNotEmpty()) PacketType.fromCode(rawBytes[0]) ?: PacketType.BROADCAST_MESSAGE else PacketType.BROADCAST_MESSAGE
        val priority = TrafficPriority.fromPacketType(type)
        if (priority != TrafficPriority.BULK_TRANSFER) {
            broadcastPacketDirect(rawBytes, ingressAddress)
        } else {
            trafficController.enqueue(rawBytes, packetType = type, targetNodeId = null, excludeAddress = ingressAddress)
        }
    }

    suspend fun broadcastPacketDirect(rawBytes: ByteArray, ingressAddress: String? = null) {
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
        isDirectPeer = { bleEngine.isDirectlyConnected(it) || wifiEngine.isPeerConnected(it) },
        directPacketSender = { recipientId, bytes -> sendDirectToNode(recipientId, bytes) },
        isWifiPeer = { wifiEngine.isPeerConnected(it) }
    )

    val routeEngine = MeshRouteEngine(cryptoEngine.nodeId)
    val custodyManager = CustodyManager(cryptoEngine.nodeId, cryptoEngine.identityHash)

    var audioStreamerFactory: () -> com.meshwhisper.app.voice.AudioStreamer = {
        com.meshwhisper.app.voice.AndroidAudioStreamer(context, scope)
    }

    val voiceCallManager by lazy {
        com.meshwhisper.app.voice.VoiceCallManager(
            myNodeId = cryptoEngine.nodeId,
            isPeerDirectlyConnected = { isPeerDirectlyConnected(it) },
            sendSignalPacket = { recipientId, signalBytes ->
                sendVoiceCallSignalPacket(recipientId, signalBytes)
            },
            sendFramePacket = { recipientId, frameBytes ->
                sendVoiceFramePacket(recipientId, frameBytes)
            },
            audioStreamer = audioStreamerFactory(),
            scope = scope,
            callKeyDeriver = { peerId, timestampSec, sessionId ->
                val peerPubKey = peerPublicKeyCache[peerId]
                if (peerPubKey != null) {
                    val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)
                    val callSessionIdBytes = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.BIG_ENDIAN)
                        .putLong(sessionId.mostSignificantBits)
                        .putLong(sessionId.leastSignificantBits)
                        .array()
                    cryptoEngine.deriveCallKey(sessionKey, callSessionIdBytes)
                } else null
            }
        )
    }

    fun syncDirectNeighbors() {
        val blePeers = bleEngine.connectedNodeIds.value
        val wifiPeers = wifiEngine.connectedWifiPeers.value.keys
        val allDirect = (blePeers + wifiPeers).filter { it != cryptoEngine.nodeId && it != 0L }.toSet()
        routeEngine.updateDirectNeighbors(allDirect)
    }

    private val lastDrainTimes = java.util.concurrent.ConcurrentHashMap<Long, Long>()
    private val logCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private val voiceSessionKeyCache = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()

    var onTypingIndicatorListener: ((senderId: Long, isTyping: Boolean) -> Unit)? = null
    var onIncomingMessageListener: ((senderId: Long, senderAlias: String, text: String, isBroadcast: Boolean) -> Unit)? = null
    var onSosAlertReceivedListener: ((senderId: Long, senderAlias: String, text: String, lat: Double?, lon: Double?, fixTimestamp: Long?) -> Unit)? = null

    init {
        scope.launch {
            try {
                identityRepository.initialize()
            } catch (e: Exception) {
                Log.e(tag, "Failed to initialize identityRepository: ${e.message}", e)
            }
        }

        scope.launch {
            try {
                val peers = database.peerDao().getAllPeersList()
                for (peer in peers) {
                    peerPublicKeyCache[peer.nodeId] = CryptoEngine.hexToBytes(peer.publicKeyHex)
                }
            } catch (_: Exception) {}
        }

        bleEngine.onPacketReceivedListener = { packetBytes, ingressAddress ->
            handleIncomingPacket(packetBytes, ingressAddress)
        }

        bleEngine.onPeerReadyListener = { address ->
            scope.launch {
                initiateBleLinkAuth(address)
            }
        }

        bleEngine.onPeerDisconnectedListener = { address ->
            scope.launch {
                bleLinkAuthSessions.remove(address)?.close()
                unbindLink(address)
                bleEngine.onLinkDisconnected(address)
                val directNodeId = bleEngine.getDirectNodeId(address)
                if (directNodeId != null && directNodeId != 0L) {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, directNodeId)
                    voiceCallManager.onDirectPeerDisconnected(directNodeId)
                }
                syncDirectNeighbors()
            }
        }

        bleEngine.onPeerDiscoveredListener = { address, rssi ->
            scope.launch {
                val directNodeId = bleEngine.getDirectNodeId(address)
                if (directNodeId != null && directNodeId != 0L) {
                    database.peerDao().updateRssi(directNodeId, rssi)
                    updatePeerTelemetry()
                }
            }
        }

        bleEngine.onRssiUpdatedListener = { nodeId, rssi ->
            scope.launch {
                database.peerDao().updateRssi(nodeId, rssi)
                updatePeerTelemetry()
            }
        }

        bleEngine.onChunkSessionDroppedListener = { addr, sessId, recv, total, reason ->
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val isInteractive = powerManager?.isInteractive ?: true
            PacketJournalExporter.logChunkTimeout(
                context = context,
                deviceAddress = addr,
                sessionId = sessId,
                receivedChunks = recv,
                totalChunks = total,
                reason = reason,
                isScreenInteractive = isInteractive
            )
            logPacket("DROP", null, 0, "ChunkSession $sessId dropped on $addr: $recv/$total chunks ($reason)")
        }

        bleEngine.onBackgroundScanMatchListener = { addr, rssi ->
            PacketJournalExporter.logScanMatch(
                context = context,
                deviceAddress = addr,
                rssi = rssi,
                isScreenInteractive = false
            )
        }

        // Periodic live telemetry poller & logcat dumper loop (Phase 0)
        scope.launch {
            while (isActive) {
                delay(2500L)
                try {
                    updatePeerTelemetry()
                    dumpTelemetryLogcat()
                } catch (_: Exception) {}
            }
        }

        wifiEngine.clock = clock
        wifiEngine.credentialsProvider = { linkAuthLocalCredentials }
        wifiEngine.onLinkAuthenticatedListener = { proof ->
            bindLink(proof)
        }
        wifiEngine.onLinkDisconnectedListener = { linkHandle ->
            unbindLink(linkHandle)
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
                voiceCallManager.onDirectPeerDisconnected(peerId)
                syncDirectNeighbors()
            }
        }

        // Periodic Store-and-Forward Drain & Custody / Topology Sweep
        scope.launch {
            while (isActive) {
                delay(30_000L)
                try {
                    // 1. Prune stale topology entries (cadence: 30s)
                    routeEngine.pruneStaleEntries()
                    database.topologyEdgeDao().pruneStaleEdges(System.currentTimeMillis() - MeshRouteEngine.EDGE_EVICT_MS)

                    // 2. Check custody timeouts (24h expiry)
                    val expiredRecords = custodyManager.checkTimeouts()
                    for (expired in expiredRecords) {
                        database.messageDao().updateStatus(expired.messageId, MessageStatus.EXPIRED)
                        database.storeForwardDao().delete(expired.messageId)
                        logPacket("CUSTODY_EXPIRED", null, 0, "Message ${expired.messageId} expired without delivery proof after 24h")
                    }

                    // 3. Drain pending S&F queues
                    val pendingRecipients = database.storeForwardDao().getPendingRecipients()
                    for (recipientId in pendingRecipients) {
                        drainStoreAndForwardQueueForPeer(recipientId)
                    }
                } catch (e: Exception) {
                    Log.d(tag, "Store-and-Forward periodic sweep error: ${e.message}")
                }
            }
        }

        // QoS Egress Dispatcher: 4-tier starvation-free transmission scheduler
        scope.launch {
            while (isActive) {
                val next = trafficController.pollNext()
                if (next != null) {
                    try {
                        val target = next.targetNodeId
                        if (target != null) {
                            sendDirectToNode(target, next.rawBytes)
                        } else {
                            broadcastPacketDirect(next.rawBytes, next.excludeAddress)
                        }
                    } catch (e: Exception) {
                        Log.d(tag, "Egress dispatcher error: ${e.message}")
                    }
                } else {
                    delay(10L)
                }
            }
        }
    }

    // P4 LINK_AUTH transport state binding (strictly authority-controlled via LinkAuthProof).
    // Unauthenticated raw transport ingress strictly defaults to LinkState.PENDING with null boundIdentity.
    private val authenticatedLinks = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun bindLink(proof: LinkAuthProof) {
        require(proof.peerIdentityHash.size == 32) { "identityHash must be 32 bytes" }
        authenticatedLinks[proof.linkHandle] = proof.peerIdentityHash
        scope.launch {
            try {
                identityRepository.onLinkEstablished(proof.peerIdentityHash)
            } catch (e: Exception) {
                Log.w(tag, "identityRepository.onLinkEstablished error: ${e.message}")
            }
        }
    }

    fun unbindLink(linkHandle: String) {
        val hash = authenticatedLinks.remove(linkHandle)
        if (hash != null) {
            scope.launch {
                try {
                    identityRepository.onLinkClosed(hash)
                } catch (e: Exception) {
                    Log.w(tag, "identityRepository.onLinkClosed error: ${e.message}")
                }
            }
        }
    }

    fun isLinkAuthenticated(linkHandle: String): Boolean {
        return authenticatedLinks.containsKey(linkHandle)
    }

    private val bleLinkAuthSessions = ConcurrentHashMap<String, LinkAuthSession>()

    private val linkAuthLocalCredentials: LinkAuthLocalCredentials by lazy {
        LinkAuthLocalCredentials(
            identitySeed = cryptoEngine.privateKeyBytes,
            ikPub = cryptoEngine.ikPublicKeyBytes,
            ekPub = cryptoEngine.ekPublicKeyBytes,
            ekPriv = cryptoEngine.ekPrivateKeyBytes,
            keyVersion = cryptoEngine.keyVersion,
            notBefore = 0L,
            ibcSignature = cryptoEngine.ibcSignature
        )
    }

    private suspend fun initiateBleLinkAuth(address: String) {
        if (authenticatedLinks.containsKey(address)) return
        val session = bleLinkAuthSessions.computeIfAbsent(address) {
            LinkAuthSession(
                linkHandle = address,
                localCredentials = linkAuthLocalCredentials,
                clock = clock
            )
        }
        val helloPacket = synchronized(session) {
            if (session.state == LinkAuthState.IDLE) {
                session.createHelloPacket()
            } else null
        }
        if (helloPacket != null) {
            val helloBytes = MeshPacket.serialize(helloPacket)
            bleEngine.sendDirectToDevice(address, helloBytes)
        }
    }

    private fun handleBleLinkAuthPacket(rawBytes: ByteArray, ingressAddress: String) {
        scope.launch {
            val session = bleLinkAuthSessions.computeIfAbsent(ingressAddress) {
                LinkAuthSession(
                    linkHandle = ingressAddress,
                    localCredentials = linkAuthLocalCredentials,
                    clock = clock
                )
            }

            try {
                val packet = MeshPacket.deserialize(rawBytes) ?: return@launch
                if (packet.payload.isEmpty()) return@launch
                val stage = packet.payload[0]

                if (stage == 0x01.toByte()) { // HELLO
                    val stepResult = synchronized(session) {
                        session.processIncomingPacket(packet)
                    }
                    if (stepResult is LinkAuthStepResult.Failed) {
                        logPacket("AUTH_FAIL", packet, rawBytes.size, "BLE HELLO failed from $ingressAddress: ${stepResult.reason}")
                        bleLinkAuthSessions.remove(ingressAddress)
                        bleEngine.disconnectDevice(ingressAddress)
                        return@launch
                    }
                    synchronized(session) {
                        if (session.state == LinkAuthState.HELLO_RECEIVED) {
                            val localHello = session.createHelloPacket()
                            val helloBytes = MeshPacket.serialize(localHello)
                            launch { bleEngine.sendDirectToDevice(ingressAddress, helloBytes) }
                        }
                        if (session.state == LinkAuthState.HELLO_EXCHANGED) {
                            val localConfirm = session.createConfirmPacket()
                            val confirmBytes = MeshPacket.serialize(localConfirm)
                            launch { bleEngine.sendDirectToDevice(ingressAddress, confirmBytes) }
                        }
                    }
                } else if (stage == 0x02.toByte()) { // CONFIRM
                    val result = synchronized(session) {
                        session.processIncomingPacket(packet)
                    }
                    when (result) {
                        is LinkAuthStepResult.Completed -> {
                            val proof = result.proof
                            bindLink(proof)
                            bleEngine.onLinkAuthenticated(proof)
                            bleLinkAuthSessions.remove(ingressAddress)
                            logPacket("AUTH", packet, rawBytes.size, "BLE link $ingressAddress authenticated as 0x${String.format("%016X", proof.peerNodeId64)}")

                            syncDirectNeighbors()
                            announcePresence()
                            delay(1200L)
                            announcePresence()
                            drainStoreAndForwardQueueForPeer(proof.peerNodeId64, forceImmediate = true)
                        }
                        is LinkAuthStepResult.Failed -> {
                            logPacket("AUTH_FAIL", packet, rawBytes.size, "BLE link $ingressAddress auth failed: ${result.reason}")
                            bleLinkAuthSessions.remove(ingressAddress)
                            bleEngine.disconnectDevice(ingressAddress)
                        }
                        is LinkAuthStepResult.InProgress -> {}
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Error processing LINK_AUTH packet from $ingressAddress: ${e.message}")
                bleLinkAuthSessions.remove(ingressAddress)
                bleEngine.disconnectDevice(ingressAddress)
            }
        }
    }

    /**
     * Entry point for incoming raw packets from BLE / Wi-Fi.
     * Single mandatory production authentication chokepoint through PacketPipeline.
     */
    fun handleIncomingPacket(rawBytes: ByteArray, ingressAddress: String? = null) {
        val handle = ingressAddress ?: "local"
        val boundId = authenticatedLinks[handle]

        // Pre-auth LINK_AUTH bypass to transport authentication coordinator (FROZEN §3.2, §4)
        if (ingressAddress != null && boundId == null && rawBytes.isNotEmpty() && rawBytes[0] == PacketType.LINK_AUTH.code) {
            handleBleLinkAuthPacket(rawBytes, ingressAddress)
            return
        }

        val transport = if (handle.contains(".") || handle.contains(":")) TransportType.WIFI_TCP else TransportType.BLE
        val linkContext = LinkContext(
            linkHandle = handle,
            transport = transport,
            boundIdentity = boundId,
            state = if (boundId != null) LinkState.AUTHENTICATED else LinkState.PENDING
        )

        when (val result = pipeline.ingest(rawBytes, linkContext)) {
            is IngestResult.Accepted -> {
                dispatchAuthenticatedPacket(result.packet, ingressAddress)
            }
            is IngestResult.Admitted -> {
                scope.launch {
                    val chunk = result.chunk
                    if (!chunk.isRelayOnly && (chunk.packet.recipientId == cryptoEngine.nodeId || chunk.isBroadcast)) {
                        mediaTransferManager.handleMediaChunk(chunk.packet, chunk.isBroadcast)
                    }
                    if (chunk.packet.ttl > 1 && chunk.packet.senderId != cryptoEngine.nodeId && chunk.packet.recipientId != cryptoEngine.nodeId) {
                        relayMediaChunkIfEligible(chunk, ingressAddress)
                    }
                }
            }
            is IngestResult.Dropped -> {
                val dup = result.duplicateDmPacket
                if (result.isDuplicateDmForUs && dup != null) {
                    scope.launch {
                        sendAck(dup.senderId, dup.messageId)
                    }
                }
                logPacket("DROP", null, rawBytes.size, "Dropped incoming packet at ${result.stage}: ${result.reason}")
            }
        }
    }

    private fun dispatchAuthenticatedPacket(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        _totalPacketsReceived.value += 1

        if (packet.senderId == cryptoEngine.nodeId) {
            return
        }

        if (packet.senderId != 0L) {
            val hopCount = maxOf(1, MeshPacket.DEFAULT_TTL - packet.ttl)
            val stats = peerTrafficStats.computeIfAbsent(packet.senderId) { PeerTrafficStats() }
            stats.lastHopCount = hopCount
            stats.lastPacketType = packet.type.name
            stats.lastPacketTimestamp = System.currentTimeMillis()
            stats.packetsReceived++
        }


        scope.launch {
            // Post-auth: record topology edge
            database.topologyEdgeDao().insertOrUpdate(
                com.meshwhisper.app.data.model.TopologyEdgeEntity(
                    fromNode = packet.senderId,
                    toNode = cryptoEngine.nodeId,
                    rssi = -55,
                    lastSeen = System.currentTimeMillis()
                )
            )

            logPacket("RX", packet, packet.payload.size, "From $ingressAddress (TTL=${packet.ttl})")

            when (packet.type) {
                PacketType.PEER_ANNOUNCE -> handlePeerAnnounce(authPacket, ingressAddress)
                PacketType.BROADCAST_MESSAGE -> handleBroadcastMessage(authPacket, ingressAddress)
                PacketType.DIRECT_MESSAGE -> handleDirectMessage(authPacket, ingressAddress)
                PacketType.ACK -> handleAck(authPacket, ingressAddress)
                PacketType.SOS_MESSAGE -> handleSosMessage(authPacket, ingressAddress)
                PacketType.MEDIA_INIT -> handleMediaInit(authPacket, ingressAddress)
                PacketType.MEDIA_NACK -> handleMediaNack(authPacket, ingressAddress)
                PacketType.MEDIA_ACK -> handleMediaAck(authPacket, ingressAddress)
                PacketType.MEDIA_ABORT -> handleMediaAbort(authPacket, ingressAddress)
                PacketType.AVATAR_REQUEST -> handleAvatarRequest(authPacket, ingressAddress)
                PacketType.TYPING_INDICATOR -> handleTypingIndicator(authPacket, ingressAddress)
                PacketType.PROFILE_UPDATE -> handleProfileUpdate(authPacket, ingressAddress)
                PacketType.PROFILE_REQUEST -> handleProfileRequest(authPacket, ingressAddress)
                PacketType.VOICE_CALL_SIGNAL -> handleVoiceCallSignal(authPacket, ingressAddress)
                PacketType.VOICE_FRAME -> handleVoiceFrame(authPacket, ingressAddress)
                PacketType.CUSTODY_ACK -> handleCustodyAck(authPacket, ingressAddress)
                else -> {}
            }

            // Post-auth flood relay
            if (packet.ttl > 1 && packet.senderId != cryptoEngine.nodeId && packet.recipientId != cryptoEngine.nodeId) {
                val relayedPacket = packet.decrementTtl()
                val isPrioritySos = (packet.type == PacketType.SOS_MESSAGE)
                relayPacketWithJitter(
                    relayedPacket = relayedPacket,
                    ingressAddress = ingressAddress,
                    logDescription = "Relaying ${packet.type.name} msg",
                    isPrioritySos = isPrioritySos,
                    originIdentityHash = authPacket.senderIdentity.identityHash
                )
            }
        }
    }

    private suspend fun handlePeerAnnounce(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val announce = PeerAnnouncePayload.deserialize(authPacket.decryptedPayload, packet.senderId, packet.ttl) ?: return

        peerPublicKeyCache[packet.senderId] = announce.ekPub

        val trustResult = identityRepository.onAuthenticatedAnnounce(authPacket, announce)

        routeEngine.updateOriginNeighbors(packet.senderId, announce.neighbors, packet.timestamp * 1000L)

        val loc = announce.location
        if (announce.hasLocation && loc != null) {
            database.locationDao().insertOrUpdate(
                com.meshwhisper.app.data.model.LastKnownLocationEntity(
                    nodeId = packet.senderId,
                    alias = announce.alias,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    accuracyMeters = loc.accuracy,
                    timestamp = loc.fixTime
                )
            )
        }

        logPacket("PEER_ANNOUNCE", packet, packet.payload.size, "Authenticated announce from ${announce.alias} (${packet.senderId}, trust=$trustResult)")
    }

    private suspend fun handleBroadcastMessage(
        authPacket: AuthenticatedPacket,
        ingressAddress: String?
    ) {
        val packet = authPacket.packet
        val decryptedBytes = authPacket.decryptedPayload

        val text = if (decryptedBytes.size >= 2) {
            val textLen = ((decryptedBytes[0].toInt() and 0xFF) shl 8) or (decryptedBytes[1].toInt() and 0xFF)
            if (textLen in 0..decryptedBytes.size - 2) {
                String(decryptedBytes.copyOfRange(2, 2 + textLen), Charsets.UTF_8)
            } else {
                String(decryptedBytes, Charsets.UTF_8)
            }
        } else {
            String(decryptedBytes, Charsets.UTF_8)
        }

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
    }

    private suspend fun relayPacketWithJitter(
        relayedPacket: MeshPacket,
        ingressAddress: String?,
        logDescription: String,
        isPrioritySos: Boolean = false,
        originIdentityHash: ByteArray? = null
    ) {
        val jitterMs = if (isPrioritySos) {
            java.util.concurrent.ThreadLocalRandom.current().nextLong(5L, 20L)
        } else {
            java.util.concurrent.ThreadLocalRandom.current().nextLong(15L, 75L)
        }
        delay(jitterMs)

        val relayedBytes = MeshPacket.serialize(relayedPacket)
        val enqueued = trafficController.enqueue(
            rawBytes = relayedBytes,
            packetType = relayedPacket.type,
            targetNodeId = null,
            excludeAddress = ingressAddress,
            isRelay = true,
            originIdentityHash = originIdentityHash
        )
        if (!enqueued) {
            Log.w(tag, "Relay packet dropped by MeshTrafficController limits: $logDescription")
            return
        }
        _relayedPacketsCount.value += 1
        logPacket("RELAY", relayedPacket, relayedBytes.size, logDescription)
    }

    private suspend fun relayMediaChunkIfEligible(chunk: com.meshwhisper.core.protocol.AdmittedChunk, ingressAddress: String?) {
        val eligible = mediaTransferManager.checkAndRecordChunkRelay(
            senderId = chunk.packet.senderId,
            mediaId = chunk.mediaId,
            chunkIndex = chunk.chunkIndex,
            chunkSize = chunk.chunkData.size
        )
        if (!eligible) {
            Log.w(tag, "Relay chunk rejected by accounting limit: ${chunk.chunkIndex} for ${chunk.mediaId}")
            return
        }
        val relayedPacket = chunk.packet.decrementTtl()
        relayPacketWithJitter(
            relayedPacket = relayedPacket,
            ingressAddress = ingressAddress,
            logDescription = "Relaying MEDIA_CHUNK ${chunk.chunkIndex} for ${chunk.mediaId}",
            originIdentityHash = chunk.senderIdentity.identityHash
        )
    }

    private suspend fun handleDirectMessage(
        authPacket: AuthenticatedPacket,
        ingressAddress: String?
    ) {
        val packet = authPacket.packet
        if (packet.recipientId == cryptoEngine.nodeId) {
            // Check if this payload is an emergency location breadcrumb BEFORE UTF-8 decode
            if (LocationBreadcrumbPayload.isBreadcrumb(authPacket.decryptedPayload)) {
                handleLocationBreadcrumb(authPacket, ingressAddress)
                return
            }

            val senderPeer = database.peerDao().getPeerById(packet.senderId)
            val senderAlias = senderPeer?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
            val text = String(authPacket.decryptedPayload, Charsets.UTF_8)

            val messageEntity = MessageEntity(
                messageId = packet.messageId.toString(),
                senderId = packet.senderId,
                recipientId = cryptoEngine.nodeId,
                senderAlias = senderAlias,
                text = text,
                timestamp = packet.timestamp * 1000L,
                isOutgoing = false,
                isBroadcast = false,
                status = MessageStatus.DELIVERED,
                hopCount = MeshPacket.DEFAULT_TTL - packet.ttl
            )
            database.messageDao().insert(messageEntity)

            onIncomingMessageListener?.invoke(packet.senderId, senderAlias, text, false)

            sendAck(packet.senderId, packet.messageId)
        } else {
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
                            directDelivered = true
                            _relayedPacketsCount.value += 1
                            logPacket("RELAY_NEXTHOP", relayedPacket, relayedBytes.size, "Forwarded DM for ${packet.recipientId} to next-hop $nextHop (hops=${routeResult.hopCount})")
                        } else {
                            routeEngine.markLinkFailed(cryptoEngine.nodeId, nextHop)
                        }
                    }
                    RouteLookupResult.Unreachable -> {}
                }

                if (!directDelivered) {
                    val rawBytes = MeshPacket.serialize(packet)
                    val sfEntity = StoreForwardEntity(
                        messageId = packet.messageId.toString(),
                        recipientId = packet.recipientId,
                        packetData = rawBytes,
                        createdAt = System.currentTimeMillis(),
                        expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
                    )
                    custodyManager.acceptRelayCustody(
                        messageId = packet.messageId.toString(),
                        recipientNodeId = packet.recipientId,
                        originIdentityHash = authPacket.senderIdentity.identityHash,
                        packetData = rawBytes
                    )
                    database.storeForwardDao().insertPartitioned(sfEntity, cryptoEngine.nodeId)
                    database.storeForwardDao().trimRecipientQueue(packet.recipientId, MAX_STORE_FORWARD_PER_RECIPIENT)

                    // Emit CUSTODY_ACK back to the node that handed off custody
                    try {
                        val custodyAckBytes = custodyManager.buildCustodyAckPacket(
                            custodyMessageId = packet.messageId,
                            originIdentityHash = authPacket.senderIdentity.identityHash,
                            recipientNodeId64 = packet.senderId,
                            senderNodeId64 = cryptoEngine.nodeId,
                            publicChannelKey = cryptoEngine.publicChannelKey,
                            signingPrivateKey = cryptoEngine.privateKeyBytes
                        )
                        sendDirectToNode(packet.senderId, custodyAckBytes)
                    } catch (e: Exception) {
                        Log.d(tag, "Failed to emit CUSTODY_ACK: ${e.message}")
                    }

                    relayPacketWithJitter(relayedPacket, ingressAddress, "Relaying private DM for ${packet.recipientId}")
                }
            }
        }
    }

    private suspend fun handleLocationBreadcrumb(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val payload = LocationBreadcrumbPayload.deserialize(authPacket.decryptedPayload) ?: return

        // 1. Anti-Spoofing: Verify sender is VERIFIED
        val senderIdentity = authPacket.senderIdentity
        if (senderIdentity.trustState != com.meshwhisper.core.identity.TrustState.VERIFIED) {
            Log.w(tag, "Dropping location breadcrumb from unverified node ${packet.senderId}")
            return
        }

        // 2. Clock Skew Check: Reject if fix timestamp is > 10 min in future
        val nowSec = System.currentTimeMillis() / 1000L
        if (payload.fixTimestampSec > nowSec + 600) {
            Log.w(tag, "Dropping location breadcrumb from ${packet.senderId}: future timestamp drift (${payload.fixTimestampSec} vs $nowSec)")
            return
        }

        val senderPeer = database.peerDao().getPeerById(packet.senderId)
        val senderAlias = senderPeer?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"

        // 3. Handle REVOKE trigger: purge stored locations
        if (payload.triggerType == BreadcrumbTriggerType.REVOKE) {
            Log.i(tag, "Received location REVOKE from $senderAlias (${packet.senderId})")
            database.locationDao().deleteLocationForNode(packet.senderId)
            database.locationDao().deleteHistoryForNode(packet.senderId)
            sendAck(packet.senderId, packet.messageId)
            return
        }

        // 4. Atomic conditional update: (fixTimestamp > current) OR (fixTimestamp == current AND seq > current)
        val fixTimestampMs = payload.fixTimestampSec * 1000L
        val receivedTimestampMs = System.currentTimeMillis()

        val rowsUpdated = database.locationDao().updateIfNewer(
            nodeId = packet.senderId,
            alias = senderAlias,
            latitude = payload.latitude,
            longitude = payload.longitude,
            accuracyMeters = payload.accuracyMeters,
            fixTimestamp = fixTimestampMs,
            sequenceNumber = payload.sequenceNumber,
            receivedTimestamp = receivedTimestampMs,
            altitude = payload.altitude,
            batteryPercent = payload.batteryPercent,
            triggerType = payload.triggerType.code.toInt(),
            note = payload.note
        )

        if (rowsUpdated == 0) {
            val existing = database.locationDao().getLocationForNode(packet.senderId)
            if (existing == null) {
                // First time receiving location for this node
                database.locationDao().insertOrUpdate(
                    com.meshwhisper.app.data.model.LastKnownLocationEntity(
                        nodeId = packet.senderId,
                        alias = senderAlias,
                        latitude = payload.latitude,
                        longitude = payload.longitude,
                        accuracyMeters = payload.accuracyMeters,
                        timestamp = fixTimestampMs,
                        sequenceNumber = payload.sequenceNumber,
                        receivedTimestamp = receivedTimestampMs,
                        altitude = payload.altitude,
                        batteryPercent = payload.batteryPercent,
                        triggerType = payload.triggerType.code.toInt(),
                        note = payload.note
                    )
                )
            } else {
                Log.d(tag, "Dropped replayed/stale breadcrumb from ${packet.senderId} (incoming fix=$fixTimestampMs, seq=${payload.sequenceNumber} vs existing fix=${existing.timestamp}, seq=${existing.sequenceNumber})")
                sendAck(packet.senderId, packet.messageId)
                return
            }
        }

        // 5. Insert into history trail (unique index ignores duplicates)
        database.locationDao().insertHistory(
            com.meshwhisper.app.data.model.BreadcrumbHistoryEntity(
                nodeId = packet.senderId,
                sequenceNumber = payload.sequenceNumber,
                latitude = payload.latitude,
                longitude = payload.longitude,
                altitude = payload.altitude,
                accuracyMeters = payload.accuracyMeters,
                batteryPercent = payload.batteryPercent,
                triggerType = payload.triggerType.code.toInt(),
                sentTimestamp = fixTimestampMs,
                receivedTimestamp = receivedTimestampMs,
                note = payload.note
            )
        )
        database.locationDao().pruneHistory(packet.senderId, 5)

        // 6. Rate-limited audible/heads-up notification (Max 1 per 60s per sender unless emergency)
        val lastNotif = breadcrumbNotificationRateLimiter[packet.senderId] ?: 0L
        val isEmergency = payload.triggerType == BreadcrumbTriggerType.BATTERY_CRITICAL_5 || payload.triggerType == BreadcrumbTriggerType.MANUAL_SOS
        if (receivedTimestampMs - lastNotif >= 60_000L || isEmergency) {
            breadcrumbNotificationRateLimiter[packet.senderId] = receivedTimestampMs
            val alertDesc = when (payload.triggerType) {
                BreadcrumbTriggerType.BATTERY_CRITICAL_5 -> "⚠️ Critical Dying Gasp (Battery: ${payload.batteryPercent}%)"
                BreadcrumbTriggerType.MANUAL_SOS -> "🆘 Emergency Beacon from $senderAlias"
                else -> "📍 Location beacon updated"
            }
            onIncomingMessageListener?.invoke(packet.senderId, senderAlias, alertDesc, false)
        }

        sendAck(packet.senderId, packet.messageId)
        logPacket("RX_BREADCRUMB", packet, packet.payload.size, "From $senderAlias (seq=${payload.sequenceNumber}, trigger=${payload.triggerType})")
    }

    private suspend fun handleCustodyAck(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val decrypted = authPacket.decryptedPayload
        if (decrypted.size != 48) return

        val buf = ByteBuffer.wrap(decrypted).order(ByteOrder.BIG_ENDIAN)
        val custodyMsgId = UUID(buf.long, buf.long).toString()
        val originIdentityHash = decrypted.copyOfRange(16, 48)

        val senderIdentityHash = authPacket.senderIdentity.identityHash
        val released = custodyManager.handleCustodyAck(
            custodyMessageId = custodyMsgId,
            senderNodeId = packet.senderId,
            senderIdentityHash = senderIdentityHash,
            originIdentityHash = originIdentityHash
        )

        if (released) {
            database.storeForwardDao().delete(custodyMsgId)
            logPacket("CUSTODY_RELEASE", packet, packet.payload.size, "Relay custody released for msg $custodyMsgId via CUSTODY_ACK from ${packet.senderId}")
        } else {
            val rec = custodyManager.getRecord(custodyMsgId)
            if (rec != null && rec.role == CustodyRole.ORIGINATOR) {
                database.messageDao().updateStatus(custodyMsgId, MessageStatus.CUSTODY_HELD)
                logPacket("CUSTODY_HELD", packet, packet.payload.size, "Originator custody held for msg $custodyMsgId (acknowledged by relay ${packet.senderId})")
            }
        }
    }

    private suspend fun handleAck(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        if (packet.recipientId == cryptoEngine.nodeId) {
            val decryptedPayload = authPacket.decryptedPayload
            val originalMsgId: String = if (decryptedPayload.size >= 16) {
                val buf = ByteBuffer.wrap(decryptedPayload)
                UUID(buf.long, buf.long).toString()
            } else {
                packet.messageId.toString()
            }

            logPacket("ACK_RX", packet, packet.payload.size, "Authenticated delivery ACK received for msg $originalMsgId (ackId=${packet.messageId})")
            database.messageDao().updateStatus(originalMsgId, MessageStatus.DELIVERED)
            database.storeForwardDao().delete(originalMsgId)
            custodyManager.handleEndToEndAck(originalMsgId)
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

    private suspend fun handleMediaInit(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        val isBroadcast = (packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)

        if (isForMe) {
            val peer = database.peerDao().getPeerById(packet.senderId)
            val senderAlias = peer?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
            mediaTransferManager.handleMediaInit(packet, senderAlias, isBroadcast)
            logPacket("RX", packet, packet.payload.size, "Received MEDIA_INIT from $senderAlias")
        }
    }

    private suspend fun handleMediaNack(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        if (isForMe) {
            mediaTransferManager.handleMediaNack(packet)
            logPacket("NACK_RX", packet, packet.payload.size, "Received MEDIA_NACK from ${packet.senderId}")
        }
    }

    private suspend fun handleMediaAck(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val isForMe = (packet.recipientId == cryptoEngine.nodeId)
        if (isForMe) {
            mediaTransferManager.handleMediaAck(packet)
            logPacket("MEDIA_ACK_RX", packet, packet.payload.size, "Received MEDIA_ACK from ${packet.senderId}")
        }
    }

    private suspend fun handleMediaAbort(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val isForMe = (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
        if (isForMe) {
            mediaTransferManager.handleMediaAbort(packet)
            logPacket("ABORT_RX", packet, packet.payload.size, "Received MEDIA_ABORT from ${packet.senderId}")
        }
    }

    suspend fun announcePresence(latitude: Double? = null, longitude: Double? = null, accuracyMeters: Float = 0f) {
        val directNeighbors = bleEngine.connectedNodeIds.value
            .filter { it != cryptoEngine.nodeId && it != 0L }
            .sorted()
            .take(10)
            .toList()

        val hasLocation = (latitude != null && longitude != null)
        val loc = if (hasLocation) {
            AnnounceLocation(
                latitude = latitude!!,
                longitude = longitude!!,
                accuracy = accuracyMeters,
                fixTime = System.currentTimeMillis()
            )
        } else null

        val hasNeighbors = directNeighbors.isNotEmpty()
        var flagsInt = 0
        if (hasLocation) flagsInt = flagsInt or 0x01
        if (hasNeighbors) flagsInt = flagsInt or 0x02
        val flags = flagsInt.toByte()
        val announceCounter = System.currentTimeMillis()

        val announcePayload = PeerAnnouncePayload(
            announceVersion = 0x02,
            flags = flags,
            ikPub = cryptoEngine.ikPublicKeyBytes,
            ekPub = cryptoEngine.ekPublicKeyBytes,
            keyVersion = cryptoEngine.keyVersion,
            notBefore = 0L,
            ibcSignature = cryptoEngine.ibcSignature,
            announceCounter = announceCounter,
            alias = cryptoEngine.alias,
            neighbors = directNeighbors.map { NeighborEntry(it, 100.toByte()) },
            location = loc
        )
        val plainBytes = announcePayload.serialize()
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
            plaintext = plainBytes,
            messageId = msgId,
            aesKey = cryptoEngine.publicChannelKey,
            aad = aad
        )

        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = com.meshwhisper.core.protocol.ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = com.meshwhisper.core.protocol.ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.PEER_ANNOUNCE.wireByte,
            messageId = msgId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderNodeId64 = cryptoEngine.nodeId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = cryptoEngine.sign(transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = if (hasLocation) 1 else MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        packetStore.commitSeen(msgId, PacketType.PEER_ANNOUNCE.code, timestamp)
        dedupCache.put("${msgId}:${PacketType.PEER_ANNOUNCE.code}", System.currentTimeMillis())

        try {
            val nowSec = System.currentTimeMillis() / 1000L
            database.processedPacketDao().purgeOld(nowSec - 86400L)
            database.storeForwardDao().purgeExpired(System.currentTimeMillis())
            database.storeForwardDao().trimTotalQueue(MAX_TOTAL_STORE_FORWARD)
            database.topologyEdgeDao().pruneStaleEdges(System.currentTimeMillis() - 120_000L)
        } catch (e: Exception) {
            Log.e(tag, "Failed to purge old records: ${e.message}")
        }

        broadcastPacket(raw)
        logPacket("TX", packet, raw.size, "Broadcasted peer announce (${directNeighbors.size} neighbors)")

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
        val locationSize = if (hasLocation) 8 + 8 + 4 + 8 else 0
        val payloadBuf = ByteBuffer.allocate(1 + 2 + textBytes.size + locationSize)
        payloadBuf.put(if (hasLocation) 0x01.toByte() else 0x00.toByte())
        payloadBuf.putShort((textBytes.size and 0xFFFF).toShort())
        payloadBuf.put(textBytes)
        if (hasLocation) {
            payloadBuf.putDouble(latitude!!)
            payloadBuf.putDouble(longitude!!)
            payloadBuf.putFloat(accuracyMeters)
            payloadBuf.putLong(locationFixTimestamp)
        }
        val plainBytes = payloadBuf.array()

        val aad = MeshPacket.computeAad(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = cryptoEngine.encrypt(
            plaintext = plainBytes,
            messageId = msgId,
            aesKey = cryptoEngine.publicChannelKey,
            aad = aad
        )

        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = com.meshwhisper.core.protocol.ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = com.meshwhisper.core.protocol.ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.SOS_MESSAGE.wireByte,
            messageId = msgId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderNodeId64 = cryptoEngine.nodeId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = cryptoEngine.sign(transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        packetStore.commitSeen(msgId, PacketType.SOS_MESSAGE.code, timestamp)
        dedupCache.put("${msgId}:${PacketType.SOS_MESSAGE.code}", System.currentTimeMillis())

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

    private suspend fun handleSosMessage(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        logPacket("RX", packet, packet.payload.size, "EMERGENCY SOS broadcast from ${packet.senderId}")

        val decryptedBytes = authPacket.decryptedPayload
        var sosText = ""
        var lat: Double? = null
        var lon: Double? = null
        var fixTimestamp: Long? = null

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
                    fixTimestamp = if (buf.remaining() >= 8) buf.long else (packet.timestamp * 1000L)

                    val sender = database.peerDao().getPeerById(packet.senderId)
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
        onSosAlertReceivedListener?.invoke(packet.senderId, senderAlias, sosText, lat, lon, fixTimestamp)
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
            aesKey = cryptoEngine.getActiveBroadcastKey(),
            aad = aad
        )

        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = com.meshwhisper.core.protocol.ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = com.meshwhisper.core.protocol.ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.BROADCAST_MESSAGE.wireByte,
            messageId = msgId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderNodeId64 = cryptoEngine.nodeId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = cryptoEngine.sign(transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.BROADCAST_MESSAGE,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
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
        packetStore.commitSeen(msgId, PacketType.BROADCAST_MESSAGE.code, timestamp)
        dedupCache.put("$msgId:${PacketType.BROADCAST_MESSAGE.code}", System.currentTimeMillis())

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
        if (identityRepository.isNodeConflicted(recipientNodeId)) {
            Log.w(tag, "Cannot send message to conflicted peer $recipientNodeId: Unicast suspended (C-23)")
            return null
        }

        val msgId = UUID.randomUUID()
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis() / 1000L

        val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
        val packet = DirectMessagePacketBuilder.build(
            senderNodeId64 = cryptoEngine.nodeId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderPrivateKey = cryptoEngine.getPrivateKey()!!,
            recipientNodeId64 = recipientNodeId,
            peerPublicKey = peerPubKey,
            plaintext = textBytes,
            timestampSec = timestamp,
            messageId = msgId,
            ttl = MeshPacket.DEFAULT_TTL
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
        packetStore.commitSeen(msgId, PacketType.DIRECT_MESSAGE.code, timestamp)
        dedupCache.put("$msgId:${PacketType.DIRECT_MESSAGE.code}", System.currentTimeMillis())
        val sf = StoreForwardEntity(
            messageId = msgId.toString(),
            recipientId = recipientNodeId,
            packetData = raw,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
        )
        custodyManager.registerOriginatorMessage(
            messageId = msgId.toString(),
            recipientNodeId = recipientNodeId,
            packetData = raw
        )
        database.storeForwardDao().insertPartitioned(sf, cryptoEngine.nodeId)
        database.storeForwardDao().trimRecipientQueue(recipientNodeId, MAX_STORE_FORWARD_PER_RECIPIENT)

        syncDirectNeighbors()
        val routeResult = routeEngine.resolveRoute(recipientNodeId)
        var dispatched = false

        when (routeResult) {
            is RouteLookupResult.Direct -> {
                dispatched = sendDirectToNode(recipientNodeId, raw)
                if (dispatched) {
                    custodyManager.recordHandoffAttempt(msgId.toString(), recipientNodeId)
                }
            }
            is RouteLookupResult.NextHop -> {
                val nextHop = routeResult.nextHopNodeId
                dispatched = sendDirectToNode(nextHop, raw)
                if (dispatched) {
                    custodyManager.recordHandoffAttempt(msgId.toString(), nextHop)
                    database.messageDao().updateStatus(msgId.toString(), MessageStatus.RELAYED)
                    logPacket("TX_RELAY_HOP", packet, raw.size, "Forwarded DM for $recipientNodeId via next-hop $nextHop (hops=${routeResult.hopCount})")
                } else {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, nextHop)
                }
            }
            RouteLookupResult.Unreachable -> {}
        }

        if (!dispatched) {
            logPacket("TX_PENDING", packet, raw.size, "Peer $recipientNodeId not reachable directly; stored in Store-and-Forward queue")
        }

        return msgId.toString()
    }

    suspend fun sendBreadcrumbDirect(
        recipientNodeId: Long,
        payload: LocationBreadcrumbPayload,
        isEmergency: Boolean = false
    ): Boolean {
        val peer = database.peerDao().getPeerById(recipientNodeId) ?: return false
        if (peer.isBlocked) return false
        if (identityRepository.isNodeConflicted(recipientNodeId)) return false

        val peerPubKey = try {
            CryptoEngine.hexToBytes(peer.publicKeyHex)
        } catch (_: Exception) {
            return false
        }

        val msgId = UUID.randomUUID()
        val payloadBytes = payload.serialize()
        val timestamp = System.currentTimeMillis() / 1000L

        val packet = DirectMessagePacketBuilder.build(
            senderNodeId64 = cryptoEngine.nodeId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderPrivateKey = cryptoEngine.getPrivateKey()!!,
            recipientNodeId64 = recipientNodeId,
            peerPublicKey = peerPubKey,
            plaintext = payloadBytes,
            timestampSec = timestamp,
            messageId = msgId,
            ttl = MeshPacket.DEFAULT_TTL
        )

        val raw = MeshPacket.serialize(packet)
        packetStore.commitSeen(msgId, PacketType.DIRECT_MESSAGE.code, timestamp)
        dedupCache.put("$msgId:${PacketType.DIRECT_MESSAGE.code}", System.currentTimeMillis())

        val sf = StoreForwardEntity(
            messageId = msgId.toString(),
            recipientId = recipientNodeId,
            packetData = raw,
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L)
        )
        custodyManager.registerOriginatorMessage(
            messageId = msgId.toString(),
            recipientNodeId = recipientNodeId,
            packetData = raw
        )
        database.storeForwardDao().insertPartitioned(sf, cryptoEngine.nodeId)
        database.storeForwardDao().trimRecipientQueue(recipientNodeId, MAX_STORE_FORWARD_PER_RECIPIENT)

        syncDirectNeighbors()
        val routeResult = routeEngine.resolveRoute(recipientNodeId)
        var dispatched = false

        when (routeResult) {
            is RouteLookupResult.Direct -> {
                dispatched = sendDirectToNode(recipientNodeId, raw)
                if (dispatched) {
                    custodyManager.recordHandoffAttempt(msgId.toString(), recipientNodeId)
                }
            }
            is RouteLookupResult.NextHop -> {
                val nextHop = routeResult.nextHopNodeId
                dispatched = sendDirectToNode(nextHop, raw)
                if (dispatched) {
                    custodyManager.recordHandoffAttempt(msgId.toString(), nextHop)
                } else {
                    routeEngine.markLinkFailed(cryptoEngine.nodeId, nextHop)
                }
            }
            RouteLookupResult.Unreachable -> {}
        }

        // If emergency (dying gasp/SOS) or destination unreachable:
        // Broadcast custody packet to ALL reachable 1-hop neighbors so anyone meeting recipient can deliver
        if (isEmergency || !dispatched) {
            broadcastPacketDirect(raw)
            dispatched = true
        }

        logPacket("TX_BREADCRUMB", packet, raw.size, "Sent breadcrumb to $recipientNodeId (emergency=$isEmergency, trigger=${payload.triggerType})")
        return dispatched
    }

    private suspend fun sendAck(recipientNodeId: Long, originalMsgId: UUID) {
        val peer = database.peerDao().getPeerById(recipientNodeId) ?: return
        val timestamp = System.currentTimeMillis() / 1000L
        val ackPacketId = UUID.randomUUID()

        val plainPayload = ByteBuffer.allocate(16).apply {
            putLong(originalMsgId.mostSignificantBits)
            putLong(originalMsgId.leastSignificantBits)
        }.array()

        val aad = MeshPacket.computeAad(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val peerPubKey = CryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)
        val encResult = cryptoEngine.encrypt(
            plaintext = plainPayload,
            messageId = ackPacketId,
            aesKey = sessionKey,
            aad = aad
        )
        val authTag = encResult.authTag
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = com.meshwhisper.core.protocol.ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = com.meshwhisper.core.protocol.ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.ACK.wireByte,
            messageId = ackPacketId,
            senderIdentityHash = cryptoEngine.identityHash,
            senderNodeId64 = cryptoEngine.nodeId,
            recipientNodeId64 = recipientNodeId,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = cryptoEngine.sign(transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
            authTag = authTag
        )

        val raw = MeshPacket.serialize(ackPacket)
        packetStore.commitSeen(ackPacketId, PacketType.ACK.code, timestamp)
        dedupCache.put("${ackPacketId}:${PacketType.ACK.code}", System.currentTimeMillis())

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
            broadcastPacketDirect(raw)
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
                    custodyManager.recordHandoffAttempt(item.messageId, recipientNodeId)
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
                        custodyManager.recordHandoffAttempt(item.messageId, route.nextHopNodeId)
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

    private suspend fun handleAvatarRequest(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        if (packet.recipientId == cryptoEngine.nodeId) {
            logPacket("RX", packet, packet.payload.size, "Authenticated avatar request from ${packet.senderId}")
            val avatarFile = java.io.File(context.filesDir, "avatars/my_avatar.jpg")
            if (avatarFile.exists()) {
                val avatarBytes = avatarFile.readBytes()
                mediaTransferManager.sendMedia(
                    recipientNodeId = packet.senderId,
                    mediaType = com.meshwhisper.app.data.model.MediaType.AVATAR,
                    mediaBytes = avatarBytes
                )
            }
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

    private fun handleTypingIndicator(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        if (packet.recipientId == cryptoEngine.nodeId || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID) {
            val isTyping = authPacket.decryptedPayload.isNotEmpty() && authPacket.decryptedPayload[0] == 1.toByte()
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
        if (identityRepository.isNodeConflicted(recipientNodeId)) {
            Log.w(tag, "Cannot send media to conflicted peer $recipientNodeId: Unicast suspended (C-23)")
            return ""
        }
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

    private suspend fun handleProfileUpdate(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        val payload = ProfilePayload.deserialize(authPacket.decryptedPayload)
        if (payload == null) {
            logPacket("DROP", packet, packet.payload.size, "REJECTED: Malformed ProfilePayload from ${packet.senderId}")
            return
        }

        // Strict Key-to-Identity Binding
        if (payload.nodeId != packet.senderId) {
            logPacket("DROP", packet, packet.payload.size, "SECURITY ALERT: Dropped forged profile - sender ${packet.senderId} claimed node ${payload.nodeId}")
            Log.w(tag, "SECURITY ALERT: Profile senderId ${packet.senderId} does not match payload nodeId ${payload.nodeId}")
            return
        }

        // Anti-Rollback & Conflict Resolution (Strict Monotonicity: version > cached.version)
        val existing = database.profileDao().getProfile(payload.nodeId)
        if (existing != null && payload.version <= existing.version) {
            logPacket("DROP", packet, packet.payload.size, "REJECTED: Stale/duplicate profile version ${payload.version} <= ${existing.version} from ${payload.nodeId}")
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
            signature = existing?.signature,
            updatedAt = System.currentTimeMillis()
        )
        database.profileDao().upsertProfile(newProfile)
        logPacket("RX", packet, packet.payload.size, "Applied verified profile v${payload.version} for '${payload.displayName}' (${payload.nodeId})")

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
    }

    private suspend fun handleProfileRequest(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
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
                    avatarHash = avatarHashBytes
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

        val payload = ProfilePayload(
            nodeId = cryptoEngine.nodeId,
            version = nextVersion,
            displayName = displayName,
            bio = bio,
            avatarHash = avatarHash
        )

        val profileEntity = com.meshwhisper.app.data.model.ProfileEntity(
            nodeId = cryptoEngine.nodeId,
            displayName = displayName,
            bio = bio,
            avatarHashHex = CryptoEngine.bytesToHex(avatarHash),
            avatarUri = if (avatarFile.exists()) avatarFile.absolutePath else null,
            version = nextVersion,
            signature = null,
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

            // Stream to persistent CSV journal file (survives app restarts & log trims)
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isInteractive = powerManager?.isInteractive ?: true
                val hopCount = if (packet != null) maxOf(1, MeshPacket.DEFAULT_TTL - packet.ttl) else 0
                val peerRssi = if (packet != null && packet.senderId != 0L) {
                    val directAddr = bleEngine.getAllConnectedAddresses().find { bleEngine.getDirectNodeId(it) == packet.senderId }
                    if (directAddr != null) bleEngine.getPeerRssi(directAddr).first else null
                } else null

                PacketJournalExporter.logEvent(
                    context = context,
                    eventType = direction,
                    packetType = packet?.type?.name ?: "EVENT",
                    messageId = packet?.messageId?.toString() ?: "",
                    senderId = packet?.senderId ?: 0L,
                    recipientId = packet?.recipientId ?: 0L,
                    ttl = packet?.ttl ?: 0,
                    hopCount = hopCount,
                    bytes = size,
                    rssi = peerRssi,
                    isScreenInteractive = isInteractive,
                    details = details
                )
            } catch (e: Exception) {
                Log.w(tag, "Journal log failed: ${e.message}")
            }
        }
    }

    suspend fun updatePeerTelemetry() = withContext(Dispatchers.IO) {
        val list = mutableListOf<PeerLiveTelemetry>()
        val connectedAddresses = bleEngine.getAllConnectedAddresses()

        for (addr in connectedAddresses) {
            val nodeId = bleEngine.getDirectNodeId(addr) ?: 0L
            val peer = if (nodeId != 0L) database.peerDao().getPeerById(nodeId) else null
            val alias = peer?.alias ?: if (nodeId != 0L) "Node-${String.format(Locale.US, "%04X", nodeId and 0xFFFF)}" else "Peer-$addr"
            val role = bleEngine.getPeerRole(addr)
            val mtu = bleEngine.getPeerMtu(addr)
            val (rssi, rssiSource) = bleEngine.getPeerRssi(addr)
            val stats = if (nodeId != 0L) peerTrafficStats[nodeId] else null

            list.add(
                PeerLiveTelemetry(
                    nodeId = nodeId,
                    alias = alias,
                    address = addr,
                    isDirect = true,
                    role = role,
                    rssi = rssi,
                    rssiSource = rssiSource,
                    mtu = mtu,
                    lastHopCount = stats?.lastHopCount ?: 1,
                    lastPacketType = stats?.lastPacketType ?: "IDLE",
                    lastPacketTimestamp = stats?.lastPacketTimestamp ?: 0L,
                    packetsReceived = stats?.packetsReceived ?: 0,
                    packetsSent = stats?.packetsSent ?: 0
                )
            )
        }
        _peerTelemetry.value = list
    }

    private fun dumpTelemetryLogcat() {
        val list = _peerTelemetry.value
        if (list.isEmpty()) return
        val sb = StringBuilder()
        sb.append("\n===================== [MESH_TELEMETRY] =====================\n")
        sb.append("Connected Peers: ").append(list.size)
            .append(" | Relayed Total: ").append(relayedPacketsCount.value)
            .append(" | Total RX: ").append(totalPacketsReceived.value).append("\n")
        for (peer in list) {
            sb.append("  * Node: ").append(peer.alias)
                .append(" (0x").append(String.format(Locale.US, "%04X", peer.nodeId and 0xFFFF)).append(")")
                .append(" | Role: ").append(peer.role.name)
                .append(" | RSSI: ").append(peer.rssi).append(" dBm [").append(peer.rssiSource.name).append("]")
                .append(" | MTU: ").append(peer.mtu).append("B")
                .append(" | LastHop: ").append(peer.lastHopCount)
                .append(" (").append(peer.lastPacketType).append(")")
                .append(" | RX/TX: ").append(peer.packetsReceived).append("/").append(peer.packetsSent)
                .append("\n")
        }
        sb.append("============================================================\n")
        Log.i(tag, sb.toString())
    }

    suspend fun exportJournal(context: Context): Pair<java.io.File, android.net.Uri>? {
        return PacketJournalExporter.exportJournal(context)
    }

    fun clearJournal(context: Context) {
        PacketJournalExporter.clearJournal(context)
    }

    private suspend fun handleVoiceCallSignal(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        if (packet.recipientId != cryptoEngine.nodeId) {
            // Strict 1-hop: never forward voice call signals
            return
        }
        val plainBytes = authPacket.decryptedPayload
        if (plainBytes.isEmpty()) {
            Log.w(tag, "Empty decrypted payload for voice call signal from ${packet.senderId}")
            return
        }
        val signalPayload = com.meshwhisper.app.voice.VoiceSignalPayload.deserialize(plainBytes) ?: return
        val peer = database.peerDao().getPeerById(packet.senderId)

        when (signalPayload.action) {
            com.meshwhisper.app.voice.CallAction.OFFER -> {
                val alias = peer?.alias ?: "Node-${String.format("%016X", packet.senderId).takeLast(4)}"
                if (!com.meshwhisper.app.service.MeshForegroundService.isActivityInForeground) {
                    com.meshwhisper.app.service.MessageNotifier.showIncomingCallNotification(context, packet.senderId, alias)
                }
            }
            com.meshwhisper.app.voice.CallAction.HANGUP,
            com.meshwhisper.app.voice.CallAction.DECLINE,
            com.meshwhisper.app.voice.CallAction.BUSY,
            com.meshwhisper.app.voice.CallAction.ANSWER -> {
                com.meshwhisper.app.service.MessageNotifier.clearCallNotification(context)
            }
        }

        voiceCallManager.handleIncomingSignal(packet.senderId, signalPayload)
    }

    private suspend fun handleVoiceFrame(authPacket: AuthenticatedPacket, ingressAddress: String?) {
        val packet = authPacket.packet
        if (packet.recipientId != cryptoEngine.nodeId) {
            // Strict 1-hop: never forward voice frames
            return
        }
        voiceCallManager.handleIncomingVoicePacket(authPacket)
    }

    suspend fun sendVoiceCallSignalPacket(recipientId: Long, signalBytes: ByteArray): Boolean {
        if (identityRepository.isNodeConflicted(recipientId)) {
            Log.w(tag, "Cannot send voice signal to conflicted peer $recipientId: Unicast suspended (C-23)")
            return false
        }
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L

        val peer = database.peerDao().getPeerById(recipientId)
        val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else peerPublicKeyCache[recipientId]
        val (ciphertext, authTag) = if (peerPubKey != null) {
            try {
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)
                voiceSessionKeyCache[recipientId] = sessionKey
                val aad = MeshPacket.computeAad(
                    type = PacketType.VOICE_CALL_SIGNAL,
                    messageId = msgId,
                    senderId = cryptoEngine.nodeId,
                    recipientId = recipientId,
                    timestamp = timestamp
                )
                val enc = cryptoEngine.encrypt(signalBytes, msgId, sessionKey, aad)
                Pair(enc.ciphertext, enc.authTag)
            } catch (e: Exception) {
                Log.w(tag, "Voice signal encryption fallback: ${e.message}")
                Pair(signalBytes, ByteArray(16))
            }
        } else {
            Pair(signalBytes, ByteArray(16))
        }

        val packet = MeshPacket(
            type = PacketType.VOICE_CALL_SIGNAL,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientId,
            ttl = 1,
            timestamp = timestamp,
            payload = ciphertext,
            authTag = authTag
        )
        val rawBytes = MeshPacket.serialize(packet)
        val delivered = sendDirectToNode(recipientId, rawBytes)
        if (!delivered) {
            Log.w(tag, "sendDirectToNode failed for voice call signal to $recipientId; dispatching via broadcastPacketDirect fallback")
            broadcastPacketDirect(rawBytes)
        }
        return true
    }

    suspend fun sendVoiceFramePacket(recipientId: Long, frameBytes: ByteArray): Boolean {
        if (frameBytes.isNotEmpty() && frameBytes[0] == PacketType.VOICE_FRAME.code) {
            val delivered = sendDirectToNode(recipientId, frameBytes)
            if (!delivered) {
                broadcastPacketDirect(frameBytes)
            }
            return true
        }

        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L

        val peer = database.peerDao().getPeerById(recipientId)
        val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else peerPublicKeyCache[recipientId]
        val (ciphertext, authTag) = if (peerPubKey != null) {
            try {
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestamp)
                voiceSessionKeyCache[recipientId] = sessionKey
                val aad = MeshPacket.computeAad(
                    type = PacketType.VOICE_FRAME,
                    messageId = msgId,
                    senderId = cryptoEngine.nodeId,
                    recipientId = recipientId,
                    timestamp = timestamp
                )
                val enc = cryptoEngine.encrypt(frameBytes, msgId, sessionKey, aad)
                Pair(enc.ciphertext, enc.authTag)
            } catch (e: Exception) {
                Pair(frameBytes, ByteArray(16))
            }
        } else {
            Pair(frameBytes, ByteArray(16))
        }

        val packet = MeshPacket(
            type = PacketType.VOICE_FRAME,
            messageId = msgId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientId,
            ttl = 1,
            timestamp = timestamp,
            payload = ciphertext,
            authTag = authTag
        )
        val rawBytes = MeshPacket.serialize(packet)
        val delivered = sendDirectToNode(recipientId, rawBytes)
        if (!delivered) {
            broadcastPacketDirect(rawBytes)
        }
        return true
    }

    companion object {
        const val MAX_STORE_FORWARD_PER_RECIPIENT = 50
        const val MAX_TOTAL_STORE_FORWARD = 500
    }
}

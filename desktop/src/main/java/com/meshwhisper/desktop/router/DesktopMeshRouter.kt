package com.meshwhisper.desktop.router

import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.logging.StdoutLogger
import com.meshwhisper.core.protocol.*
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.util.*
import com.meshwhisper.desktop.crypto.DesktopCryptoEngine
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.crypto.DesktopPipelineFactory
import com.meshwhisper.desktop.db.*
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID

/**
 * Desktop Mesh Router for Windows and macOS nodes.
 * Coordinates dispatch, LRU deduplication, flood routing over Wi-Fi, and SQLite persistence.
 * Uses the shared :core PacketPipeline for all ingress validation (T-ARCH-01, §9.1).
 */
class DesktopMeshRouter(
    val keyStorage: DesktopPassphraseKeyStorage,
    val database: DesktopDatabase,
    val wifiEngine: DesktopWifiEngine,
    val logger: MeshLogger = StdoutLogger,
    val clock: Clock = SystemClock(),
    val randomSource: RandomSource = DefaultRandomSource()
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
    var myIdentityHash: ByteArray
        private set

    private var currentAnnounceCounter: Long = 1L
    private var currentKeyVersion: Long = 1L

    private val _incomingMessages = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val incomingMessages: SharedFlow<DesktopMessage> = _incomingMessages.asSharedFlow()

    private val _sosAlerts = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 32)
    val sosAlerts: SharedFlow<DesktopMessage> = _sosAlerts.asSharedFlow()

    lateinit var mediaManager: com.meshwhisper.desktop.media.DesktopMediaManager
        private set

    private val packetStore = DesktopPacketStore(database)
    private val pipeline: PacketPipeline

    init {
        val existingPriv = keyStorage.getPrivateKey()
        if (existingPriv != null && existingPriv.isNotEmpty()) {
            myPrivateKey = existingPriv
            myPublicKey = DesktopCryptoEngine.derivePublicKey(existingPriv)
        } else {
            val (priv, pub) = DesktopCryptoEngine.generateX25519KeyPair()
            keyStorage.storePrivateKey(priv)
            myPrivateKey = priv
            myPublicKey = pub
        }

        val ikPub = DesktopCryptoEngine.deriveSigningPublicKey(myPrivateKey)
        myIdentityHash = DesktopCryptoEngine.deriveIdentityHash(ikPub)
        myNodeId = DesktopCryptoEngine.deriveNodeId64(myIdentityHash)
        myNodeIdHex = java.lang.Long.toUnsignedString(myNodeId, 16).padStart(16, '0').uppercase()
        myAlias = keyStorage.readAlias() ?: "Desktop-${myNodeIdHex.takeLast(4)}"

        pipeline = DesktopPipelineFactory.create(
            myNodeId = myNodeId,
            myIdentityHash = myIdentityHash,
            myPublicKey = myPublicKey,
            myPrivateKey = myPrivateKey,
            currentKeyVersion = currentKeyVersion,
            packetStore = packetStore,
            database = database,
            clock = clock,
            dedupCache = dedupCache
        )

        mediaManager = com.meshwhisper.desktop.media.DesktopMediaManager(
            myNodeId = myNodeId,
            myPrivateKey = myPrivateKey,
            database = database,
            wifiEngine = wifiEngine,
            logger = logger,
            scope = scope
        )

        scope.launch {
            mediaManager.mediaTransfersUpdated.collect { updatedMsg ->
                _incomingMessages.tryEmit(updatedMsg)
            }
        }

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

    // Clean seam for P4 LINK_AUTH transport state binding.
    // Unauthenticated raw transport ingress strictly defaults to LinkState.PENDING with null boundIdentity.
    // P4 LINK_AUTH will invoke bindLink upon handshake completion.
    private val authenticatedLinks = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    fun bindLink(linkHandle: String, identityHash: ByteArray) {
        require(identityHash.size == 32) { "identityHash must be 32 bytes" }
        authenticatedLinks[linkHandle] = identityHash
    }

    fun unbindLink(linkHandle: String) {
        authenticatedLinks.remove(linkHandle)
    }

    private fun handleIncomingRawPacket(rawBytes: ByteArray, ingressSource: String) {
        val boundId = authenticatedLinks[ingressSource]
        val linkContext = LinkContext(
            linkHandle = ingressSource,
            transport = TransportType.WIFI_TCP,
            boundIdentity = boundId,
            state = if (boundId != null) LinkState.AUTHENTICATED else LinkState.PENDING
        )

        when (val result = pipeline.ingest(rawBytes, linkContext)) {
            is IngestResult.Accepted -> {
                dispatchAuthenticatedPacket(result.packet, ingressSource)
            }
            is IngestResult.Admitted -> {
                mediaManager.handleMediaChunk(result.chunk)
            }
            is IngestResult.Dropped -> {
                val dup = result.duplicateDmPacket
                if (result.isDuplicateDmForUs && dup != null) {
                    sendAck(dup.senderId, dup.messageId)
                }
                logger.d(TAG, "Dropped incoming packet at ${result.stage}: ${result.reason}")
            }
        }
    }

    private fun dispatchAuthenticatedPacket(authPacket: AuthenticatedPacket, ingressSource: String) {
        val packet = authPacket.packet

        // Post-auth: record topology edge
        database.upsertTopologyEdge(
            DesktopTopologyEdge(
                sourceNodeId = packet.senderId,
                targetNodeId = myNodeId,
                rssi = -55,
                updatedAt = System.currentTimeMillis()
            )
        )

        logPacket("RX", packet, packet.payload.size, "From $ingressSource (TTL=${packet.ttl})")

        when (packet.type) {
            PacketType.BROADCAST_MESSAGE -> handleBroadcastMessage(authPacket, isSos = false)
            PacketType.SOS_MESSAGE -> handleBroadcastMessage(authPacket, isSos = true)
            PacketType.DIRECT_MESSAGE -> handleDirectMessage(authPacket)
            PacketType.ACK -> handleAck(authPacket)
            PacketType.PEER_ANNOUNCE -> handlePeerAnnounce(authPacket)
            PacketType.MEDIA_INIT -> mediaManager.handleMediaInit(
                packet = packet,
                plainBytes = authPacket.decryptedPayload,
                isBroadcast = (packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
            )
            else -> {}
        }

        // Flood Relay post-auth (if TTL > 1 and not addressed exclusively to me)
        if (packet.ttl > 1 && packet.senderId != myNodeId && packet.recipientId != myNodeId) {
            val relayPacket = packet.decrementTtl()
            val relayBytes = MeshPacket.serialize(relayPacket)
            wifiEngine.broadcastPacket(relayBytes)
            logPacket("RELAY", relayPacket, relayBytes.size, "Relayed flood (TTL=${relayPacket.ttl})")
        }
    }

    private fun handleBroadcastMessage(authPacket: AuthenticatedPacket, isSos: Boolean) {
        val packet = authPacket.packet
        val decryptedBytes = authPacket.decryptedPayload

        val text = if (isSos) {
            if (decryptedBytes.size >= 3) {
                val textLen = ((decryptedBytes[1].toInt() and 0xFF) shl 8) or (decryptedBytes[2].toInt() and 0xFF)
                val textEnd = minOf(3 + textLen, decryptedBytes.size)
                String(decryptedBytes.copyOfRange(3, textEnd), Charsets.UTF_8)
            } else ""
        } else {
            if (decryptedBytes.size >= 2) {
                val textLen = ((decryptedBytes[0].toInt() and 0xFF) shl 8) or (decryptedBytes[1].toInt() and 0xFF)
                val textEnd = minOf(2 + textLen, decryptedBytes.size)
                String(decryptedBytes.copyOfRange(2, textEnd), Charsets.UTF_8)
            } else ""
        }

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
    }

    private fun handleDirectMessage(authPacket: AuthenticatedPacket) {
        val packet = authPacket.packet
        if (packet.recipientId != myNodeId) return

        val text = String(authPacket.decryptedPayload, Charsets.UTF_8)
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
    }

    private fun handleAck(authPacket: AuthenticatedPacket) {
        val packet = authPacket.packet
        if (packet.recipientId != myNodeId) return

        if (authPacket.decryptedPayload.size >= 16) {
            val buf = ByteBuffer.wrap(authPacket.decryptedPayload).order(ByteOrder.BIG_ENDIAN)
            val most = buf.getLong()
            val least = buf.getLong()
            val originalMsgId = UUID(most, least).toString()
            logger.i(TAG, "✅ [ACK RECEIVED] for message $originalMsgId from 0x${String.format("%016X", packet.senderId)}")
        }
    }

    private fun handlePeerAnnounce(authPacket: AuthenticatedPacket) {
        val packet = authPacket.packet
        val announce = PeerAnnouncePayload.deserialize(authPacket.decryptedPayload, packet.senderId, packet.ttl) ?: return

        val peer = DesktopPeer(
            nodeId = packet.senderId,
            publicKeyHex = DesktopCryptoEngine.bytesToHex(announce.ekPub),
            alias = announce.alias,
            rssi = -50,
            hops = maxOf(1, MeshPacket.DEFAULT_TTL - packet.ttl),
            lastSeen = System.currentTimeMillis(),
            publicFingerprint = DesktopCryptoEngine.generateFingerprint(announce.ikPub)
        )
        database.upsertPeer(peer)
        logger.i(TAG, "Discovered mesh peer: ${announce.alias} (0x${String.format("%016X", packet.senderId)})")
    }

    fun sendPublicMessage(text: String, isSos: Boolean = false): String {
        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val textBytes = text.toByteArray(Charsets.UTF_8)

        val plainBytes = if (isSos) {
            val buf = ByteBuffer.allocate(1 + 2 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
            buf.put(0x00.toByte()) // flags
            buf.putShort(textBytes.size.toShort())
            buf.put(textBytes)
            buf.array()
        } else {
            val buf = ByteBuffer.allocate(2 + textBytes.size).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(textBytes.size.toShort())
            buf.put(textBytes)
            buf.array()
        }

        val type = if (isSos) PacketType.SOS_MESSAGE else PacketType.BROADCAST_MESSAGE
        val publicChannelKey = DesktopCryptoEngine.derivePublicChannelKey()
        val aad = MeshPacket.computeAad(type, msgId, myNodeId, MeshPacket.BROADCAST_RECIPIENT_ID, timestamp)

        val encResult = DesktopCryptoEngine.encrypt(plainBytes, msgId, publicChannelKey, aad)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = type.wireByte,
            messageId = msgId,
            senderIdentityHash = myIdentityHash,
            senderNodeId64 = myNodeId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = DesktopCryptoEngine.sign(myPrivateKey, transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = type,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
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

        val peerPubKey = DesktopCryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = DesktopCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestamp)
        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            timestamp = timestamp
        )

        val encResult = DesktopCryptoEngine.encrypt(plainBytes, msgId, sessionKey, aad)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.DIRECT_MESSAGE.wireByte,
            messageId = msgId,
            senderIdentityHash = myIdentityHash,
            senderNodeId64 = myNodeId,
            recipientNodeId64 = recipientNodeId,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = DesktopCryptoEngine.sign(myPrivateKey, transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
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
                isIncoming = false
            )
        )

        if (wifiEngine.isPeerConnected(recipientNodeId)) {
            wifiEngine.sendDirectPacket(recipientNodeId, raw)
        } else {
            wifiEngine.broadcastPacket(raw)
        }
        logPacket("TX", packet, raw.size, "Sent DM to 0x${String.format("%016X", recipientNodeId)}")
        return msgId.toString()
    }

    private fun sendAck(recipientNodeId: Long, originalMsgId: UUID) {
        val peer = database.getPeer(recipientNodeId) ?: return
        val ackPacketId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L

        val plainPayload = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN).apply {
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

        val peerPubKey = DesktopCryptoEngine.hexToBytes(peer.publicKeyHex)
        val sessionKey = DesktopCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestamp)
        val encResult = DesktopCryptoEngine.encrypt(plainPayload, ackPacketId, sessionKey, aad)

        val md = MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.ACK.wireByte,
            messageId = ackPacketId,
            senderIdentityHash = myIdentityHash,
            senderNodeId64 = myNodeId,
            recipientNodeId64 = recipientNodeId,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = DesktopCryptoEngine.sign(myPrivateKey, transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = myNodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
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
        val ikPub = DesktopCryptoEngine.deriveSigningPublicKey(myPrivateKey)
        val ibcSig = DesktopCryptoEngine.signIbc(
            identitySeed = myPrivateKey,
            ekPub = myPublicKey,
            keyVersion = currentKeyVersion,
            notBefore = 0L
        )

        val announcePayload = PeerAnnouncePayload(
            announceVersion = 0x02,
            flags = 0x00,
            ikPub = ikPub,
            ekPub = myPublicKey,
            keyVersion = currentKeyVersion,
            notBefore = 0L,
            ibcSignature = ibcSig,
            announceCounter = ++currentAnnounceCounter,
            alias = myAlias
        )
        val plainBytes = announcePayload.serialize()

        val msgId = UUID.randomUUID()
        val timestamp = System.currentTimeMillis() / 1000L
        val publicChannelKey = DesktopCryptoEngine.derivePublicChannelKey()

        val aad = MeshPacket.computeAad(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )

        val encResult = DesktopCryptoEngine.encrypt(
            plaintext = plainBytes,
            messageId = msgId,
            aesKey = publicChannelKey,
            aad = aad
        )

        val md = MessageDigest.getInstance("SHA-256")
        md.update(encResult.ciphertext)
        md.update(encResult.authTag)
        val cipherHash = md.digest()

        val transcript = MeshPacket.buildSigTranscript(
            purposeTag = ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.PEER_ANNOUNCE.wireByte,
            messageId = msgId,
            senderIdentityHash = myIdentityHash,
            senderNodeId64 = myNodeId,
            recipientNodeId64 = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp,
            payloadLenExcludingSig = encResult.ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = DesktopCryptoEngine.sign(myPrivateKey, transcript)
        val fullPayload = encResult.ciphertext + hopSig

        val packet = MeshPacket(
            type = PacketType.PEER_ANNOUNCE,
            messageId = msgId,
            senderId = myNodeId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = fullPayload,
            authTag = encResult.authTag
        )

        val raw = MeshPacket.serialize(packet)
        wifiEngine.broadcastPacket(raw)
    }

    private fun drainStoreAndForward(peerNodeId: Long) {
        scope.launch {
            val pending = database.getPendingPacketsForPeer(peerNodeId)
            for (sf in pending) {
                if (wifiEngine.isPeerConnected(peerNodeId)) {
                    wifiEngine.sendDirectPacket(peerNodeId, sf.packetData)
                } else {
                    wifiEngine.broadcastPacket(sf.packetData)
                }
                database.deleteStoreAndForward(sf.messageId)
            }
        }
    }

    private fun logPacket(direction: String, packet: MeshPacket, rawByteCount: Int, info: String) {
        val entry = DesktopPacketLog(
            timestamp = packet.timestamp,
            direction = direction,
            type = packet.type.name,
            messageIdHex = packet.messageId.toString(),
            sizeBytes = rawByteCount,
            info = info
        )
        database.insertPacketLog(entry)
    }
}

package com.meshwhisper.app.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.ResourceLimits
import com.meshwhisper.core.transport.*
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * 100% Offline Wi-Fi LAN / Hotspot Transport Engine for MeshWhisper (vNext Phase P4).
 * 
 * Enforces:
 * 1. UDP Discovery Beacons on port 42425 (<= 128 B only, rate-limited, no raw mesh packets).
 * 2. Dedicated 4-thread handshake dispatcher and bounded pending pool (max 8 concurrent).
 * 3. Exact K_link cutover moment upon mutual cryptographically verified CONFIRM.
 * 4. Post-auth AES-256-GCM encrypted transport frames (12 IV + 2104 plaintext + 16 tag <= 2132 B).
 * 5. Atomic active-identity session registration (keyed by identityHash, max 5 sessions).
 * 6. Frame allocation protection (length validated before allocation; close on 3 violations).
 * 7. 120-second idle session timeout.
 */
class MeshWifiEngine(private val context: Context) {

    companion object {
        const val UDP_DISCOVERY_PORT = 42425
        const val TCP_DATA_PORT = 42426
        const val MAX_CONCURRENT_WIFI_CONNECTIONS = ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS
        const val TCP_HANDSHAKE_TIMEOUT_MS = 5000
        private val BEACON_MAGIC = byteArrayOf(0x4D, 0x57, 0x49, 0x46) // 'MWIF'
    }

    private val tag = "MeshWifiEngine"
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshWifiEngine: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    var clock: Clock = SystemClock()
    var credentialsProvider: (() -> LinkAuthLocalCredentials)? = null
    var onLinkAuthenticatedListener: ((proof: LinkAuthProof) -> Unit)? = null
    var onLinkDisconnectedListener: ((linkHandle: String) -> Unit)? = null

    private var myNodeId: Long = 0L
    private var myAlias: String = "Node"
    private var isEngineRunning = false

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var udpDiscoveryJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var tcpAcceptJob: Job? = null

    // Session Registry, UDP Rate Limiter & Wi-Fi Frame Limiter (P4)
    private val sessionRegistry = WifiSessionRegistry(ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS)
    private val udpBeaconLimiter = UdpBeaconLimiter()
    private val frameRateLimiter = WifiFrameRateLimiter()

    // State flows
    private val _isWifiActive = MutableStateFlow(false)
    val isWifiActive: StateFlow<Boolean> = _isWifiActive.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress: StateFlow<String?> = _localIpAddress.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val _connectedWifiPeers = MutableStateFlow<Map<Long, String>>(emptyMap())
    val connectedWifiPeers: StateFlow<Map<Long, String>> = _connectedWifiPeers.asStateFlow()

    // Callbacks
    var onPacketReceivedListener: ((packetBytes: ByteArray, ingressSource: String) -> Unit)? = null
    var onPeerConnectedListener: ((nodeId: Long, ipAddress: String) -> Unit)? = null
    var onPeerDisconnectedListener: ((nodeId: Long) -> Unit)? = null

    @Synchronized
    fun start(nodeId: Long, alias: String = "Node") {
        if (isEngineRunning) return
        isEngineRunning = true
        myNodeId = nodeId
        myAlias = alias

        Log.i(tag, "Starting MeshWifiEngine for node 0x${String.format("%016X", nodeId)} ($alias)")
        acquireMulticastLock()
        refreshLocalIp()

        startTcpServer()
        startUdpDiscovery()
        startUdpBeacon()
    }

    @Synchronized
    fun stop() {
        if (!isEngineRunning) return
        isEngineRunning = false

        Log.i(tag, "Stopping MeshWifiEngine")
        releaseMulticastLock()
        udpBeaconJob?.cancel()
        udpDiscoveryJob?.cancel()
        tcpAcceptJob?.cancel()

        try {
            udpSocket?.close()
        } catch (_: Exception) {}
        udpSocket = null

        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null

        for (session in sessionRegistry.getAllSessions()) {
            try {
                session.socket.close()
            } catch (_: Exception) {}
        }
        sessionRegistry.clear()
        udpBeaconLimiter.clear()
        frameRateLimiter.clear()
        updatePeerStates()
        _isWifiActive.value = false
    }

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager != null) {
                multicastLock = wifiManager.createMulticastLock("MeshWhisper:WifiMulticastLock").apply {
                    setReferenceCounted(true)
                    acquire()
                }
                Log.d(tag, "Acquired WifiManager.MulticastLock")
            }
        } catch (e: Exception) {
            Log.w(tag, "Could not acquire MulticastLock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                Log.d(tag, "Released WifiManager.MulticastLock")
            }
        } catch (_: Exception) {}
        multicastLock = null
    }

    fun updateAlias(newAlias: String) {
        myAlias = newAlias
    }

    fun isPeerConnected(peerId: Long): Boolean {
        return sessionRegistry.getSessionByNodeId(peerId) != null
    }

    /**
     * Broadcasts a raw MeshPacket across all connected authenticated Wi-Fi TCP streams.
     * FROZEN §8: NO raw mesh packets over UDP.
     */
    fun broadcastPacket(rawBytes: ByteArray, excludeIp: String? = null) {
        if (!isEngineRunning) return

        for (session in sessionRegistry.getAllSessions()) {
            if (excludeIp != null && session.ipAddress == excludeIp) continue
            try {
                WifiFrameCodec.writeEncryptedFrame(session.outStream, rawBytes, session.linkKey)
            } catch (e: Exception) {
                Log.w(tag, "Failed to broadcast to ${session.ipAddress}: ${e.message}")
                disconnectSession(session)
            }
        }
    }

    /**
     * Sends a raw MeshPacket directly to a specific target node over high-speed encrypted TCP.
     */
    fun sendDirectPacket(peerNodeId: Long, rawBytes: ByteArray): Boolean {
        val session = sessionRegistry.getSessionByNodeId(peerNodeId) ?: return false
        return try {
            WifiFrameCodec.writeEncryptedFrame(session.outStream, rawBytes, session.linkKey)
            true
        } catch (e: Exception) {
            Log.w(tag, "Failed to send packet over TCP to ${session.ipAddress}: ${e.message}")
            disconnectSession(session)
            false
        }
    }

    private fun disconnectSession(session: AuthenticatedWifiSession) {
        sessionRegistry.removeSession(session.identityHashHex, session)
        val linkHandle = "${session.ipAddress}:${session.socket.port}"
        frameRateLimiter.remove(linkHandle)
        onLinkDisconnectedListener?.invoke(linkHandle)
        onPeerDisconnectedListener?.invoke(session.peerNodeId64)
        try { session.socket.close() } catch (_: Exception) {}
        updatePeerStates()
    }

    fun disconnectPeer(peerId: Long) {
        val session = sessionRegistry.getSessionByNodeId(peerId) ?: return
        disconnectSession(session)
    }

    private fun startTcpServer() {
        tcpAcceptJob = scope.launch {
            try {
                val server = ServerSocket(TCP_DATA_PORT)
                serverSocket = server
                _isWifiActive.value = true
                Log.i(tag, "TCP ServerSocket listening on port $TCP_DATA_PORT")

                while (isActive && isEngineRunning) {
                    try {
                        val clientSocket = server.accept()
                        clientSocket.tcpNoDelay = true
                        val remoteIp = clientSocket.inetAddress.hostAddress ?: "unknown"

                        if (sessionRegistry.size() >= ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS) {
                            Log.d(tag, "Wi-Fi TCP connection limit (${ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS}) reached. Rejecting $remoteIp")
                            try { clientSocket.close() } catch (_: Exception) {}
                            continue
                        }

                        // Check bounded pending handshake pool (max 8 globally)
                        if (!WifiTransportPool.acquirePendingSlot()) {
                            Log.w(tag, "Pending handshake pool exhausted (max ${ResourceLimits.WIFI_PENDING_HANDSHAKES_GLOBAL}). Dropping $remoteIp")
                            try { clientSocket.close() } catch (_: Exception) {}
                            continue
                        }

                        // Execute handshake on dedicated 4-thread pool
                        scope.launch(WifiTransportPool.handshakeDispatcher) {
                            try {
                                handleTcpHandshakeAndLoop(clientSocket, remoteIp)
                            } finally {
                                WifiTransportPool.releasePendingSlot()
                            }
                        }
                    } catch (e: Exception) {
                        if (!isEngineRunning) break
                        Log.w(tag, "TCP accept exception: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to start TCP ServerSocket: ${e.message}", e)
            }
        }
    }

    private fun connectToPeer(peerId: Long, ip: String, tcpPort: Int) {
        if (sessionRegistry.size() >= ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS) return
        if (sessionRegistry.getSessionByNodeId(peerId) != null) return

        if (!WifiTransportPool.acquirePendingSlot()) return

        scope.launch(WifiTransportPool.handshakeDispatcher) {
            var socket: Socket? = null
            try {
                val s = Socket()
                socket = s
                withContext(Dispatchers.IO) {
                    s.connect(InetSocketAddress(ip, tcpPort), 3000)
                }
                s.tcpNoDelay = true
                handleTcpHandshakeAndLoop(s, ip)
            } catch (e: Exception) {
                Log.d(tag, "Could not connect to peer $ip:$tcpPort: ${e.message}")
                try { socket?.close() } catch (_: Exception) {}
            } finally {
                WifiTransportPool.releasePendingSlot()
            }
        }
    }

    private suspend fun handleTcpHandshakeAndLoop(socket: Socket, remoteIp: String) {
        val linkHandle = "$remoteIp:${socket.port}"
        val violationCounter = AtomicInteger(0)
        var sessionToClean: AuthenticatedWifiSession? = null

        try {
            val credentials = credentialsProvider?.invoke() ?: run {
                Log.e(tag, "CredentialsProvider not configured; closing connection from $remoteIp")
                socket.close()
                return
            }

            val linkAuthSession = LinkAuthSession(
                linkHandle = linkHandle,
                localCredentials = credentials,
                clock = clock
            )

            // Strict 3-second timeout for handshake
            val proof = withTimeout(ResourceLimits.WIFI_PENDING_HANDSHAKE_TIMEOUT_SEC * 1000L) {
                socket.soTimeout = (ResourceLimits.WIFI_PENDING_HANDSHAKE_TIMEOUT_SEC * 1000L).toInt()
                val inStream = DataInputStream(socket.getInputStream())
                val outStream = DataOutputStream(socket.getOutputStream())

                // 1. Send local HELLO
                val localHelloPacket = linkAuthSession.createHelloPacket()
                val localHelloBytes = MeshPacket.serialize(localHelloPacket)
                WifiFrameCodec.writePlaintextFrame(outStream, localHelloBytes)

                // 2. Read and process remote HELLO (pre-auth frame <= 2104 B validated before allocation)
                val remoteHelloBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = false, violationCounter)
                    ?: throw IllegalStateException("Failed to read remote HELLO")
                val remoteHelloPacket = MeshPacket.deserialize(remoteHelloBytes)
                    ?: throw IllegalStateException("Failed to deserialize remote HELLO")
                val helloResult = linkAuthSession.processIncomingPacket(remoteHelloPacket)
                if (helloResult is LinkAuthStepResult.Failed) {
                    throw IllegalStateException("Remote HELLO rejected: ${helloResult.reason}")
                }

                // 3. Send local CONFIRM
                val localConfirmPacket = linkAuthSession.createConfirmPacket()
                val localConfirmBytes = MeshPacket.serialize(localConfirmPacket)
                WifiFrameCodec.writePlaintextFrame(outStream, localConfirmBytes)

                // 4. Read and process remote CONFIRM (pre-auth frame <= 2104 B validated before allocation)
                val remoteConfirmBytes = WifiFrameCodec.readFrame(inStream, isPostAuth = false, violationCounter)
                    ?: throw IllegalStateException("Failed to read remote CONFIRM")
                val remoteConfirmPacket = MeshPacket.deserialize(remoteConfirmBytes)
                    ?: throw IllegalStateException("Failed to deserialize remote CONFIRM")
                val confirmResult = linkAuthSession.processIncomingPacket(remoteConfirmPacket)
                if (confirmResult !is LinkAuthStepResult.Completed) {
                    throw IllegalStateException("Remote CONFIRM verification failed")
                }

                confirmResult.proof
            }

            // ATOMIC REGISTRATION (Requirement 3)
            val identityHashHex = PureCryptoEngine.bytesToHex(proof.peerIdentityHash)
            val outStream = DataOutputStream(socket.getOutputStream())
            val session = AuthenticatedWifiSession(
                identityHashHex = identityHashHex,
                peerIdentityHash = proof.peerIdentityHash,
                peerNodeId64 = proof.peerNodeId64,
                ipAddress = remoteIp,
                socket = socket,
                outStream = outStream,
                linkKey = proof.linkKey,
                proof = proof
            )

            if (!sessionRegistry.registerSession(session)) {
                Log.w(tag, "Duplicate active identity $identityHashHex rejected. Closing second connection from $remoteIp")
                socket.close()
                return
            }
            sessionToClean = session

            // BIND AUTHORITY (Requirement 5)
            onLinkAuthenticatedListener?.invoke(proof)
            onPeerConnectedListener?.invoke(proof.peerNodeId64, remoteIp)
            updatePeerStates()

            Log.i(tag, "Wi-Fi TCP authenticated with 0x${String.format("%016X", proof.peerNodeId64)} at $remoteIp")

            // POST-AUTH ENCRYPTED LOOP (runs on normal IO dispatcher)
            socket.keepAlive = true
            socket.soTimeout = (ResourceLimits.WIFI_SESSION_IDLE_TIMEOUT_SEC * 1000L).toInt()
            val inStream = DataInputStream(socket.getInputStream())

            withContext(Dispatchers.IO) {
                while (isActive && isEngineRunning && !socket.isClosed) {
                    val frameBytes = try {
                        WifiFrameCodec.readFrame(inStream, isPostAuth = true, violationCounter)
                    } catch (te: SocketTimeoutException) {
                        Log.i(tag, "Wi-Fi TCP session reached idle timeout (${ResourceLimits.WIFI_SESSION_IDLE_TIMEOUT_SEC}s) for $remoteIp. Closing.")
                        break
                    }
                    if (frameBytes == null) continue

                    // Enforce Wi-Fi 50 transport frames / sec / link limit (Requirement 3)
                    if (!frameRateLimiter.isFrameAllowed(linkHandle)) {
                        Log.w(tag, "Wi-Fi TCP frame rate limit exceeded for $linkHandle (50 fps). Dropping frame.")
                        continue
                    }

                    val decryptedPlaintext = try {
                        PureCryptoEngine.decryptTransportFrame(frameBytes, proof.linkKey)
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to decrypt K_link transport frame from $remoteIp: ${e.message}")
                        break
                    }

                    onPacketReceivedListener?.invoke(decryptedPlaintext, linkHandle)
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "TCP session ended for $remoteIp: ${e.message}")
        } finally {
            sessionToClean?.let { disconnectSession(it) }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun startUdpDiscovery() {
        udpDiscoveryJob = scope.launch {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                udpSocket = socket
                val rxBuffer = ByteArray(256)

                Log.i(tag, "UDP Discovery listening on port $UDP_DISCOVERY_PORT")

                while (isActive && isEngineRunning) {
                    try {
                        val packet = DatagramPacket(rxBuffer, rxBuffer.size)
                        socket.receive(packet)
                        val senderIp = packet.address.hostAddress ?: continue
                        val myIp = _localIpAddress.value
                        if (senderIp == myIp || senderIp == "127.0.0.1") continue

                        val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        parseUdpPacket(data, senderIp)
                    } catch (e: Exception) {
                        if (!isEngineRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to bind UDP Discovery socket: ${e.message}", e)
            }
        }
    }

    private fun parseUdpPacket(data: ByteArray, senderIp: String) {
        // Enforce beacon bounds and rate limits (Requirement 8)
        if (!udpBeaconLimiter.isBeaconAllowed(data.size, senderIp)) {
            return
        }

        if (data.size < 4) return

        // Must be UDP Beacon ('MWIF'). Raw mesh packets over UDP are unconditionally dropped.
        if (data[0] != BEACON_MAGIC[0] || data[1] != BEACON_MAGIC[1] ||
            data[2] != BEACON_MAGIC[2] || data[3] != BEACON_MAGIC[3]) {
            return
        }

        if (data.size < 4 + 8 + 2 + 1) return
        val buf = ByteBuffer.wrap(data)
        buf.position(4) // Skip magic
        val peerId = buf.long
        val tcpPort = buf.short.toInt() and 0xFFFF
        val aliasLen = buf.get().toInt() and 0xFF
        if (buf.remaining() < aliasLen) return
        if (peerId == myNodeId) return

        if (sessionRegistry.size() < ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS) {
            if (sessionRegistry.getSessionByNodeId(peerId) == null) {
                connectToPeer(peerId, senderIp, tcpPort)
            }
        }
    }

    private fun startUdpBeacon() {
        udpBeaconJob = scope.launch {
            while (isActive && isEngineRunning) {
                try {
                    refreshLocalIp()
                    val myIp = _localIpAddress.value
                    if (myIp != null) {
                        val aliasBytes = myAlias.toByteArray(Charsets.UTF_8)
                        val beaconBytes = ByteArray(4 + 8 + 2 + 1 + aliasBytes.size)
                        val buf = ByteBuffer.wrap(beaconBytes)
                        buf.put(BEACON_MAGIC)
                        buf.putLong(myNodeId)
                        buf.putShort(TCP_DATA_PORT.toShort())
                        buf.put(aliasBytes.size.toByte())
                        buf.put(aliasBytes)

                        val targets = getBroadcastAddresses()
                        for (targetAddr in targets) {
                            try {
                                val datagram = DatagramPacket(beaconBytes, beaconBytes.size, targetAddr, UDP_DISCOVERY_PORT)
                                udpSocket?.send(datagram)
                            } catch (_: Exception) {}
                        }
                    }
                } catch (e: Exception) {
                    Log.w(tag, "UDP Beacon broadcast error: ${e.message}")
                }
                delay(3000L)
            }
        }
    }

    private fun refreshLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                if (name.contains("wlan") || name.contains("ap") || name.contains("eth") || name.contains("swlan")) {
                    val addrs = intf.inetAddresses
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            _localIpAddress.value = addr.hostAddress
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return broadcastList
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                for (interfaceAddress in intf.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && interfaceAddress.address is Inet4Address) {
                        broadcastList.add(broadcast)
                    }
                }
            }
            if (broadcastList.isEmpty()) {
                broadcastList.add(InetAddress.getByName("255.255.255.255"))
            }
        } catch (_: Exception) {
            try { broadcastList.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        }
        return broadcastList
    }

    private fun updatePeerStates() {
        val sessions = sessionRegistry.getAllSessions()
        _connectedWifiPeers.value = sessions.associate { it.peerNodeId64 to it.ipAddress }
        _connectedPeersCount.value = sessions.size
    }
}

package com.meshwhisper.app.wifi

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 100% Offline Wi-Fi LAN / Hotspot Transport Engine for MeshWhisper.
 * 
 * Provides:
 * 1. UDP Discovery Beacons on port 42425 across local subnet / mobile hotspot.
 * 2. Persistent TCP Bidirectional Socket streaming on port 42426 for high-speed multi-megabyte transfers.
 * 3. Zero internet dependency — works over portable battery hotspot or offline access point.
 */
class MeshWifiEngine(private val context: Context) {

    companion object {
        const val UDP_DISCOVERY_PORT = 42425
        const val TCP_DATA_PORT = 42426
        const val MAX_WIFI_PACKETS_PER_SEC = 50
        const val MAX_CONCURRENT_WIFI_CONNECTIONS = 5
        const val TCP_HANDSHAKE_TIMEOUT_MS = 5000
        private val BEACON_MAGIC = byteArrayOf(0x4D, 0x57, 0x49, 0x46) // 'MWIF'
        private const val MAX_PACKET_SIZE = 10 * 1024 * 1024 // 10MB max stream frame
    }

    private val tag = "MeshWifiEngine"
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshWifiEngine: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private var myNodeId: Long = 0L
    private var myAlias: String = "Node"
    private var isEngineRunning = false

    private var multicastLock: WifiManager.MulticastLock? = null
    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var udpDiscoveryJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var tcpAcceptJob: Job? = null

    // NodeId -> Active Peer Socket Session
    private val activePeers = ConcurrentHashMap<Long, PeerTcpSession>()
    private val peerIpToNodeId = ConcurrentHashMap<String, Long>()
    private val ipRateLimits = ConcurrentHashMap<String, MutableList<Long>>()

    private fun isRateLimitExceeded(ip: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = ipRateLimits.getOrPut(ip) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > 1000L }
            if (timestamps.size >= MAX_WIFI_PACKETS_PER_SEC) {
                return true
            }
            timestamps.add(now)
            return false
        }
    }

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

    private class PeerTcpSession(
        val nodeId: Long,
        val ipAddress: String,
        val socket: Socket,
        val outStream: DataOutputStream
    )

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

        for ((_, session) in activePeers) {
            try {
                session.socket.close()
            } catch (_: Exception) {}
        }
        activePeers.clear()
        peerIpToNodeId.clear()
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
        return activePeers.containsKey(peerId)
    }

    /**
     * Broadcasts a raw MeshPacket across all connected Wi-Fi TCP streams and directed UDP subnet broadcasts.
     */
    fun broadcastPacket(rawBytes: ByteArray, excludeIp: String? = null) {
        if (!isEngineRunning) return

        // 1. Send via TCP streams to all connected peer sockets
        for ((nodeId, session) in activePeers) {
            if (excludeIp != null && session.ipAddress == excludeIp) continue
            sendOverTcpSession(session, rawBytes)
        }

        // 2. Also send over UDP broadcast across all subnet broadcast addresses for discovering nodes
        scope.launch {
            try {
                val targets = getBroadcastAddresses()
                for (targetAddr in targets) {
                    try {
                        val datagram = DatagramPacket(rawBytes, rawBytes.size, targetAddr, UDP_DISCOVERY_PORT)
                        udpSocket?.send(datagram)
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Sends a raw MeshPacket directly to a specific target node over high-speed TCP.
     */
    fun sendDirectPacket(peerNodeId: Long, rawBytes: ByteArray): Boolean {
        val session = activePeers[peerNodeId] ?: return false
        return sendOverTcpSession(session, rawBytes)
    }

    private fun sendOverTcpSession(session: PeerTcpSession, rawBytes: ByteArray): Boolean {
        return try {
            synchronized(session.outStream) {
                session.outStream.writeInt(rawBytes.size)
                session.outStream.write(rawBytes)
                session.outStream.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(tag, "Failed to send packet over TCP to ${session.ipAddress}: ${e.message}")
            disconnectPeer(session.nodeId)
            false
        }
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
                        if (activePeers.size >= MAX_CONCURRENT_WIFI_CONNECTIONS) {
                            Log.d(tag, "Wi-Fi TCP connection limit ($MAX_CONCURRENT_WIFI_CONNECTIONS) reached. Rejecting incoming connection from $remoteIp")
                            try { clientSocket.close() } catch (_: Exception) {}
                            continue
                        }
                        clientSocket.soTimeout = 0
                        Log.i(tag, "Incoming TCP connection from $remoteIp")
                        handleIncomingTcpConnection(clientSocket, remoteIp)
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

    private fun handleIncomingTcpConnection(socket: Socket, remoteIp: String) {
        scope.launch {
            if (activePeers.size >= MAX_CONCURRENT_WIFI_CONNECTIONS) {
                Log.d(tag, "Wi-Fi TCP connection limit ($MAX_CONCURRENT_WIFI_CONNECTIONS) reached in worker. Closing connection from $remoteIp")
                try { socket.close() } catch (_: Exception) {}
                return@launch
            }
            var peerNodeId: Long? = null
            try {
                socket.soTimeout = TCP_HANDSHAKE_TIMEOUT_MS
                val inStream = DataInputStream(socket.getInputStream())
                val outStream = DataOutputStream(socket.getOutputStream())

                // Handshake: exchange node ID & alias
                outStream.writeLong(myNodeId)
                val aliasBytes = myAlias.toByteArray(Charsets.UTF_8)
                outStream.writeByte(aliasBytes.size)
                outStream.write(aliasBytes)
                outStream.flush()

                val remoteNodeId = inStream.readLong()
                val remoteAliasLen = inStream.readByte().toInt() and 0xFF
                val rAliasBytes = ByteArray(remoteAliasLen)
                inStream.readFully(rAliasBytes)
                val remoteAlias = String(rAliasBytes, Charsets.UTF_8)

                peerNodeId = remoteNodeId
                val session = PeerTcpSession(remoteNodeId, remoteIp, socket, outStream)
                activePeers[remoteNodeId] = session
                peerIpToNodeId[remoteIp] = remoteNodeId
                updatePeerStates()

                // Reset timeout for persistent mesh streaming after handshake completes
                socket.soTimeout = 0

                Log.i(tag, "TCP Handshake established with 0x${String.format("%016X", remoteNodeId)} ($remoteAlias) at $remoteIp")
                onPeerConnectedListener?.invoke(remoteNodeId, remoteIp)

                // Continuous packet read loop
                while (isActive && isEngineRunning) {
                    val frameLen = inStream.readInt()
                    if (frameLen <= 0 || frameLen > MAX_PACKET_SIZE) {
                        Log.w(tag, "Invalid frame length from $remoteIp: $frameLen bytes")
                        break
                    }
                    val frameBytes = ByteArray(frameLen)
                    inStream.readFully(frameBytes)

                    if (isRateLimitExceeded(remoteIp)) {
                        Log.w(tag, "Wi-Fi TCP rate limit exceeded for $remoteIp (> $MAX_WIFI_PACKETS_PER_SEC frames/sec), throttling packet")
                    } else {
                        onPacketReceivedListener?.invoke(frameBytes, remoteIp)
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "TCP connection ended for $remoteIp: ${e.message}")
            } finally {
                peerNodeId?.let { disconnectPeer(it) }
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun startUdpDiscovery() {
        udpDiscoveryJob = scope.launch {
            try {
                val socket = DatagramSocket(null)
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                udpSocket = socket
                val rxBuffer = ByteArray(65535)

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
        if (data.size < 4) return

        // Check if it is a UDP Beacon (MWIF)
        if (data[0] == BEACON_MAGIC[0] && data[1] == BEACON_MAGIC[1] && data[2] == BEACON_MAGIC[2] && data[3] == BEACON_MAGIC[3]) {
            if (data.size < 4 + 8 + 2 + 1) return
            val buf = ByteBuffer.wrap(data)
            buf.position(4) // Skip magic
            val peerId = buf.long
            val tcpPort = buf.short.toInt() and 0xFFFF
            val aliasLen = buf.get().toInt() and 0xFF
            val alias = if (buf.remaining() >= aliasLen) {
                val aBytes = ByteArray(aliasLen)
                buf.get(aBytes)
                String(aBytes, Charsets.UTF_8)
            } else "Node"

            if (peerId == myNodeId) return

            // If not connected to this peer, connect via TCP client
            if (activePeers.size < MAX_CONCURRENT_WIFI_CONNECTIONS) {
                if (!activePeers.containsKey(peerId)) {
                    connectToPeer(peerId, senderIp, tcpPort)
                }
            } else {
                Log.d(tag, "Wi-Fi connection limit ($MAX_CONCURRENT_WIFI_CONNECTIONS) reached. Peer 0x${String.format("%016X", peerId)} will communicate via mesh flood relay.")
            }
        } else {
            // Raw MeshPacket broadcast over UDP
            onPacketReceivedListener?.invoke(data, senderIp)
        }
    }

    private fun connectToPeer(peerId: Long, ip: String, tcpPort: Int) {
        if (activePeers.size >= MAX_CONCURRENT_WIFI_CONNECTIONS) {
            Log.d(tag, "Wi-Fi TCP connection limit ($MAX_CONCURRENT_WIFI_CONNECTIONS) reached. Skipping outbound connection to $ip:$tcpPort")
            return
        }
        scope.launch {
            try {
                Log.i(tag, "Attempting outbound TCP connection to peer 0x${String.format("%016X", peerId)} at $ip:$tcpPort")
                val socket = Socket()
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(ip, tcpPort), 3000)
                }
                socket.tcpNoDelay = true
                socket.soTimeout = 0

                handleIncomingTcpConnection(socket, ip)
            } catch (e: Exception) {
                Log.d(tag, "Could not connect to peer $ip:$tcpPort: ${e.message}")
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
                    Log.d(tag, "Error sending UDP beacon: ${e.message}")
                }
                delay(3500L)
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            // Limited global broadcast
            broadcastList.add(InetAddress.getByName("255.255.255.255"))

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is java.net.Inet4Address) {
                        broadcastList.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) {}
        return broadcastList.distinct()
    }

    private fun disconnectPeer(nodeId: Long) {
        val session = activePeers.remove(nodeId)
        if (session != null) {
            peerIpToNodeId.remove(session.ipAddress)
            try {
                session.socket.close()
            } catch (_: Exception) {}
            updatePeerStates()
            onPeerDisconnectedListener?.invoke(nodeId)
            Log.i(tag, "Disconnected Wi-Fi peer 0x${String.format("%016X", nodeId)}")
        }
    }

    private fun updatePeerStates() {
        val count = activePeers.size
        _connectedPeersCount.value = count
        _connectedWifiPeers.value = activePeers.mapValues { it.value.ipAddress }
        _isWifiActive.value = count > 0 || _localIpAddress.value != null
    }

    private fun refreshLocalIp() {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        val ip = addr.hostAddress
                        if (ip != null && !ip.startsWith("127.")) {
                            _localIpAddress.value = ip
                            return
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

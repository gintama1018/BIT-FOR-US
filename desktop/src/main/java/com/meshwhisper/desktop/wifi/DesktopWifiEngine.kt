package com.meshwhisper.desktop.wifi

import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.logging.StdoutLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure JVM Offline Wi-Fi Transport Engine for Windows and macOS desktop nodes.
 * Reuses identical UDP beacon format (port 42425) and TCP data streaming framing (port 42426).
 */
class DesktopWifiEngine(
    private val logger: MeshLogger = StdoutLogger
) {
    companion object {
        const val UDP_DISCOVERY_PORT = 42425
        const val TCP_DATA_PORT = 42426
        val BEACON_MAGIC = "MWIF".toByteArray(Charsets.UTF_8)
        private const val TAG = "DesktopWifiEngine"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var myNodeId: Long = 0L
    private var myAlias: String = "DesktopNode"
    private var isEngineRunning = false

    private var udpSocket: DatagramSocket? = null
    private var serverSocket: ServerSocket? = null
    private var udpDiscoveryJob: Job? = null
    private var udpBeaconJob: Job? = null
    private var tcpAcceptJob: Job? = null

    // NodeId -> Active Peer TCP Session
    private val activePeers = ConcurrentHashMap<Long, PeerTcpSession>()
    private val peerIpToNodeId = ConcurrentHashMap<String, Long>()

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
    fun start(nodeId: Long, alias: String = "DesktopNode") {
        if (isEngineRunning) return
        isEngineRunning = true
        myNodeId = nodeId
        myAlias = alias

        logger.i(TAG, "Starting DesktopWifiEngine for node 0x${String.format("%016X", nodeId)} ($alias)")
        refreshLocalIp()

        startTcpServer()
        startUdpDiscovery()
        startUdpBeacon()
    }

    @Synchronized
    fun stop() {
        if (!isEngineRunning) return
        isEngineRunning = false

        logger.i(TAG, "Stopping DesktopWifiEngine")
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
    fun sendDirectPacket(targetNodeId: Long, rawBytes: ByteArray): Boolean {
        val session = activePeers[targetNodeId] ?: return false
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
            logger.w(TAG, "Failed to send TCP packet to 0x${String.format("%016X", session.nodeId)}: ${e.message}")
            disconnectPeer(session.nodeId)
            false
        }
    }

    private fun startTcpServer() {
        tcpAcceptJob = scope.launch {
            try {
                serverSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_DATA_PORT))
                }
                logger.i(TAG, "TCP Server listening on port $TCP_DATA_PORT")

                while (isActive && isEngineRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        socket.tcpNoDelay = true
                        val remoteIp = socket.inetAddress.hostAddress ?: "unknown"
                        logger.i(TAG, "Accepted inbound TCP connection from $remoteIp")
                        handleIncomingTcpConnection(socket, remoteIp)
                    } catch (e: Exception) {
                        if (isEngineRunning) {
                            logger.d(TAG, "TCP accept loop error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Could not start TCP server: ${e.message}", e)
            }
        }
    }

    private fun handleIncomingTcpConnection(socket: Socket, remoteIp: String) {
        scope.launch {
            var peerNodeId: Long? = null
            try {
                val inStream = DataInputStream(socket.getInputStream())
                val outStream = DataOutputStream(socket.getOutputStream())

                // 1. Send handshake frame
                val myAliasBytes = myAlias.toByteArray(Charsets.UTF_8)
                val handshakePayload = ByteBuffer.allocate(8 + 1 + myAliasBytes.size).apply {
                    putLong(myNodeId)
                    put(myAliasBytes.size.toByte())
                    put(myAliasBytes)
                }.array()

                synchronized(outStream) {
                    outStream.writeInt(handshakePayload.size)
                    outStream.write(handshakePayload)
                    outStream.flush()
                }

                // 2. Read remote handshake frame
                val remoteHandshakeLen = inStream.readInt()
                if (remoteHandshakeLen in 9..256) {
                    val remoteHandshakeBytes = ByteArray(remoteHandshakeLen)
                    inStream.readFully(remoteHandshakeBytes)
                    val buf = ByteBuffer.wrap(remoteHandshakeBytes)
                    val remoteNodeId = buf.getLong()
                    peerNodeId = remoteNodeId

                    val session = PeerTcpSession(remoteNodeId, remoteIp, socket, outStream)
                    activePeers[remoteNodeId] = session
                    peerIpToNodeId[remoteIp] = remoteNodeId
                    updatePeerStates()
                    onPeerConnectedListener?.invoke(remoteNodeId, remoteIp)
                    logger.i(TAG, "Registered peer session for 0x${String.format("%016X", remoteNodeId)} at $remoteIp")

                    // 3. Continuous frame read loop
                    while (isActive && isEngineRunning) {
                        val frameLen = inStream.readInt()
                        if (frameLen in 1..65535) {
                            val frameBytes = ByteArray(frameLen)
                            inStream.readFully(frameBytes)
                            onPacketReceivedListener?.invoke(frameBytes, "WIFI_TCP:$remoteIp")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.d(TAG, "TCP session ended for $remoteIp: ${e.message}")
            } finally {
                peerNodeId?.let { disconnectPeer(it) }
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    private fun startUdpDiscovery() {
        udpDiscoveryJob = scope.launch {
            try {
                udpSocket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(UDP_DISCOVERY_PORT))
                }
                logger.i(TAG, "UDP Discovery listening on port $UDP_DISCOVERY_PORT")

                val receiveBuf = ByteArray(4096)
                val packet = DatagramPacket(receiveBuf, receiveBuf.size)

                while (isActive && isEngineRunning) {
                    try {
                        udpSocket?.receive(packet)
                        val senderIp = packet.address.hostAddress ?: continue
                        val myIp = _localIpAddress.value
                        if (senderIp == myIp || senderIp == "127.0.0.1") continue

                        val length = packet.length
                        if (length >= 15 && packet.data.copyOfRange(0, 4).contentEquals(BEACON_MAGIC)) {
                            // Discovery Beacon
                            val buf = ByteBuffer.wrap(packet.data, 4, length - 4)
                            val peerNodeId = buf.getLong()
                            val peerTcpPort = buf.getShort().toInt() and 0xFFFF

                            if (peerNodeId != myNodeId && !activePeers.containsKey(peerNodeId)) {
                                if (myNodeId < peerNodeId) {
                                    connectToPeer(peerNodeId, senderIp, peerTcpPort)
                                }
                            }
                        } else if (length >= 56) {
                            // Direct UDP flood packet
                            val data = packet.data.copyOf(length)
                            onPacketReceivedListener?.invoke(data, "WIFI_UDP:$senderIp")
                        }
                    } catch (e: Exception) {
                        if (isEngineRunning) {
                            logger.d(TAG, "UDP receive loop error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.e(TAG, "Could not start UDP discovery: ${e.message}", e)
            }
        }
    }

    private fun connectToPeer(peerId: Long, ip: String, tcpPort: Int) {
        scope.launch {
            try {
                logger.i(TAG, "Attempting outbound TCP connection to peer 0x${String.format("%016X", peerId)} at $ip:$tcpPort")
                val socket = Socket()
                withContext(Dispatchers.IO) {
                    socket.connect(InetSocketAddress(ip, tcpPort), 3000)
                }
                socket.tcpNoDelay = true
                socket.soTimeout = 0

                handleIncomingTcpConnection(socket, ip)
            } catch (e: Exception) {
                logger.d(TAG, "Could not connect to peer $ip:$tcpPort: ${e.message}")
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
                    logger.d(TAG, "Error sending UDP beacon: ${e.message}")
                }
                delay(3500L)
            }
        }
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val broadcastList = mutableListOf<InetAddress>()
        try {
            broadcastList.add(InetAddress.getByName("255.255.255.255"))

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue

                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && broadcast is Inet4Address) {
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
            logger.i(TAG, "Disconnected Wi-Fi peer 0x${String.format("%016X", nodeId)}")
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
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
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

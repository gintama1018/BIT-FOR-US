package com.meshwhisper.core.transport

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.ResourceLimits
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Exception thrown when a peer exceeds the oversized frame violation limit (3 violations).
 */
class OversizedFrameException(message: String) : Exception(message)

/**
 * Validates and enforces TCP frame size limits before buffer allocation.
 * Pre-auth: Plaintext MeshPacket limit = 2104 B.
 * Post-auth: Encrypted K_link frame limit = 2132 B (12-byte IV + 2104-byte MeshPacket + 16-byte AES-GCM tag).
 * Enforces immediate connection closure after 3 oversized frame violations.
 */
object WifiFrameCodec {

    /**
     * Reads a length-prefixed frame from the input stream.
     * Validates the declared length against the appropriate limit BEFORE allocating memory.
     */
    fun readFrame(
        inStream: DataInputStream,
        isPostAuth: Boolean,
        violationCounter: AtomicInteger
    ): ByteArray? {
        val declaredLength = inStream.readInt()
        val maxAllowed = if (isPostAuth) {
            ResourceLimits.MAX_WIFI_TCP_ENCRYPTED_FRAME_SIZE
        } else {
            ResourceLimits.MAX_WIFI_TCP_FRAME_SIZE
        }

        if (declaredLength <= 0 || declaredLength > maxAllowed) {
            val violations = violationCounter.incrementAndGet()
            if (violations >= ResourceLimits.WIFI_MAX_OVERSIZE_FRAMES_BEFORE_CLOSE) {
                throw OversizedFrameException(
                    "Oversized frame limit exceeded: declared=$declaredLength > maxAllowed=$maxAllowed (violations=$violations)"
                )
            }
            return null
        }

        // Allocate ONLY after validation passes
        val buffer = ByteArray(declaredLength)
        inStream.readFully(buffer)
        return buffer
    }

    /**
     * Writes a pre-auth plaintext MeshPacket frame.
     */
    fun writePlaintextFrame(outStream: DataOutputStream, meshPacketBytes: ByteArray) {
        require(meshPacketBytes.size <= ResourceLimits.MAX_WIFI_TCP_FRAME_SIZE) {
            "Plaintext packet size ${meshPacketBytes.size} exceeds maximum ${ResourceLimits.MAX_WIFI_TCP_FRAME_SIZE}"
        }
        synchronized(outStream) {
            outStream.writeInt(meshPacketBytes.size)
            outStream.write(meshPacketBytes)
            outStream.flush()
        }
    }

    /**
     * Encrypts and writes a post-auth K_link transport frame.
     * Output format: 4-byte length prefix followed by [12-byte IV] + [ciphertext] + [16-byte AES-GCM tag].
     */
    fun writeEncryptedFrame(outStream: DataOutputStream, meshPacketBytes: ByteArray, linkKey: ByteArray) {
        require(meshPacketBytes.size <= ResourceLimits.MAX_MESH_PACKET_SIZE) {
            "Plaintext packet size ${meshPacketBytes.size} exceeds maximum ${ResourceLimits.MAX_MESH_PACKET_SIZE}"
        }
        val encryptedFrame = PureCryptoEngine.encryptTransportFrame(meshPacketBytes, linkKey)
        require(encryptedFrame.size <= ResourceLimits.MAX_WIFI_TCP_ENCRYPTED_FRAME_SIZE) {
            "Encrypted frame size ${encryptedFrame.size} exceeds maximum ${ResourceLimits.MAX_WIFI_TCP_ENCRYPTED_FRAME_SIZE}"
        }
        synchronized(outStream) {
            outStream.writeInt(encryptedFrame.size)
            outStream.write(encryptedFrame)
            outStream.flush()
        }
    }
}

/**
 * An active authenticated Wi-Fi TCP session.
 */
class AuthenticatedWifiSession(
    val identityHashHex: String,
    val peerIdentityHash: ByteArray,
    val peerNodeId64: Long,
    val ipAddress: String,
    val socket: Socket,
    val outStream: DataOutputStream,
    val linkKey: ByteArray,
    val proof: LinkAuthProof
)

/**
 * Concurrency-safe registry for active authenticated Wi-Fi transport sessions.
 * Keyed strictly by identityHashHex.
 * Invariant: At most ONE authenticated transport session per identityHash.
 * Duplicate connections claiming an active identity are atomically rejected.
 * Total active sessions bounded to MAX_WIFI_AUTHENTICATED_SESSIONS (5).
 */
class WifiSessionRegistry(
    private val maxSessions: Int = ResourceLimits.MAX_WIFI_AUTHENTICATED_SESSIONS
) {
    private val activeSessions = ConcurrentHashMap<String, AuthenticatedWifiSession>()

    /**
     * Atomically registers an authenticated session.
     * Returns true if registered, or false if a session with this identityHash already exists
     * or the registry is full.
     */
    fun registerSession(session: AuthenticatedWifiSession): Boolean {
        if (activeSessions.size >= maxSessions && !activeSessions.containsKey(session.identityHashHex)) {
            return false
        }
        val existing = activeSessions.putIfAbsent(session.identityHashHex, session)
        return existing == null
    }

    /**
     * Atomically removes an authenticated session if the current value matches.
     */
    fun removeSession(identityHashHex: String, session: AuthenticatedWifiSession): Boolean {
        return activeSessions.remove(identityHashHex, session)
    }

    fun removeByIdentityHash(identityHashHex: String): AuthenticatedWifiSession? {
        return activeSessions.remove(identityHashHex)
    }

    fun getSession(identityHashHex: String): AuthenticatedWifiSession? = activeSessions[identityHashHex]

    fun getSessionByNodeId(nodeId: Long): AuthenticatedWifiSession? =
        activeSessions.values.firstOrNull { it.peerNodeId64 == nodeId }

    fun getAllSessions(): List<AuthenticatedWifiSession> = activeSessions.values.toList()

    fun size(): Int = activeSessions.size

    fun clear() {
        activeSessions.clear()
    }
}

/**
 * Rate limiter and validator for UDP discovery beacons.
 * Bounded to:
 * - Beacons <= 128 B only
 * - 10 beacons/sec per source IP
 * - 100 beacons/sec globally
 */
class UdpBeaconLimiter(
    private val maxPerIpPerSec: Int = ResourceLimits.UDP_BEACONS_PER_SEC_PER_IP,
    private val maxGlobalPerSec: Int = ResourceLimits.UDP_BEACONS_PER_SEC_GLOBAL
) {
    private val ipTimestamps = ConcurrentHashMap<String, MutableList<Long>>()
    private val globalTimestamps = mutableListOf<Long>()

    fun isBeaconAllowed(dataSize: Int, senderIp: String, now: Long = System.currentTimeMillis()): Boolean {
        // Size bound: beacons > 128 B dropped unconditionally
        if (dataSize <= 0 || dataSize > ResourceLimits.MAX_UDP_BEACON_SIZE) {
            return false
        }

        val list = ipTimestamps.computeIfAbsent(senderIp) { mutableListOf() }
        synchronized(this) {
            globalTimestamps.removeAll { now - it >= 1000L }
            if (globalTimestamps.size >= maxGlobalPerSec) {
                return false
            }

            list.removeAll { now - it >= 1000L }
            if (list.size >= maxPerIpPerSec) {
                return false
            }

            globalTimestamps.add(now)
            list.add(now)
            return true
        }
    }

    fun clear() {
        synchronized(this) {
            ipTimestamps.clear()
            globalTimestamps.clear()
        }
    }
}

/**
 * Dedicated 4-thread handshake dispatcher and bounded pending pool for Wi-Fi transport.
 */
object WifiTransportPool {
    private val handshakeExecutor = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "wifi-handshake-worker").apply { isDaemon = true }
    }
    val handshakeDispatcher = handshakeExecutor.asCoroutineDispatcher()

    val pendingHandshakeCount = AtomicInteger(0)

    fun acquirePendingSlot(): Boolean {
        while (true) {
            val current = pendingHandshakeCount.get()
            if (current >= ResourceLimits.WIFI_PENDING_HANDSHAKES_GLOBAL) {
                return false
            }
            if (pendingHandshakeCount.compareAndSet(current, current + 1)) {
                return true
            }
        }
    }

    fun releasePendingSlot() {
        pendingHandshakeCount.decrementAndGet()
    }
}

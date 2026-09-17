package com.meshwhisper.app.ble

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Handles framing, fragmentation (chunking) and reassembly of raw packets over BLE GATT.
 */
class BleFrameFramer {

    private data class ChunkSession(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val sessionCounter = AtomicInteger(SecureRandom().nextInt(0xFFFF))
    private val sessions = ConcurrentHashMap<String, ChunkSession>()

    /**
     * Listener invoked whenever a ChunkSession is dropped due to timeout, capacity eviction, or corruption.
     * Essential for Phase 0 chunk-level packet loss tracking.
     */
    var onChunkSessionDroppedListener: ((deviceAddress: String, sessionId: Short, receivedChunks: Int, totalChunks: Int, reason: String) -> Unit)? = null

    /**
     * Splits a raw packet byte array into transmit frames according to negotiated MTU.
     */
    fun fragment(packetBytes: ByteArray, maxTransmissionUnit: Int): List<ByteArray> {
        val maxSafePayload = maxOf(16, maxTransmissionUnit - BleConstants.GATT_HEADER_SIZE - 4)

        // If entire packet fits in a single frame
        if (packetBytes.size <= maxSafePayload) {
            val singleFrame = ByteArray(packetBytes.size + 1)
            singleFrame[0] = BleConstants.FRAME_TYPE_SINGLE
            System.arraycopy(packetBytes, 0, singleFrame, 1, packetBytes.size)
            return listOf(singleFrame)
        }

        // Chunking needed
        val chunkSize = maxSafePayload - 5 // 1B type + 2B sessionId + 1B index + 1B total = 5 bytes overhead
        val totalChunks = (packetBytes.size + chunkSize - 1) / chunkSize
        require(totalChunks <= 255) { "Frame fragmentation chunk count $totalChunks exceeds 1-byte protocol limit (255)" }
        val sessionId = (sessionCounter.incrementAndGet() and 0xFFFF).toShort()
        val frames = mutableListOf<ByteArray>()

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val length = minOf(chunkSize, packetBytes.size - start)

            val frame = ByteArray(5 + length)
            val buffer = ByteBuffer.wrap(frame)
            buffer.put(BleConstants.FRAME_TYPE_CHUNK)
            buffer.putShort(sessionId)
            buffer.put(i.toByte())
            buffer.put(totalChunks.toByte())
            buffer.put(packetBytes, start, length)

            frames.add(frame)
        }

        return frames
    }

    /**
     * Feeds an incoming raw frame and returns the reassembled packet byte array if complete.
     * Returns null if more chunks are required or if frame is invalid.
     */
    /**
     * Feeds an incoming raw frame and returns the reassembled packet byte array if complete.
     * Returns null if more chunks are required or if frame is invalid.
     */
    fun receiveFrame(deviceAddress: String, frameBytes: ByteArray): ByteArray? {
        if (frameBytes.isEmpty() || frameBytes.size > BleConstants.MAX_BLE_FRAME_SIZE) return null

        val frameType = frameBytes[0]

        if (frameType == BleConstants.FRAME_TYPE_SINGLE) {
            val packet = ByteArray(frameBytes.size - 1)
            System.arraycopy(frameBytes, 1, packet, 0, packet.size)
            return packet
        }

        if (frameType == BleConstants.FRAME_TYPE_CHUNK) {
            if (frameBytes.size < 5) return null
            val buffer = ByteBuffer.wrap(frameBytes)
            buffer.get() // Skip type byte
            val sessionId = buffer.getShort()
            val chunkIndex = buffer.get().toInt() and 0xFF
            val totalChunks = buffer.get().toInt() and 0xFF

            if (totalChunks <= 0 || chunkIndex >= totalChunks) return null

            val chunkData = ByteArray(buffer.remaining())
            buffer.get(chunkData)

            val sessionKey = "$deviceAddress-$sessionId"

            // Bound active chunk sessions per link (max 2 per §8)
            val peerSessions = sessions.keys.filter { it.startsWith("$deviceAddress-") }
            if (peerSessions.size >= BleConstants.MAX_BLE_SESSIONS_PER_LINK && !sessions.containsKey(sessionKey)) {
                val oldestKey = peerSessions.minByOrNull { sessions[it]?.createdAt ?: 0L }
                if (oldestKey != null) {
                    val dropped = sessions.remove(oldestKey)
                    if (dropped != null) {
                        val sessId = oldestKey.substringAfterLast("-").toShortOrNull() ?: 0
                        onChunkSessionDroppedListener?.invoke(
                            deviceAddress,
                            sessId,
                            dropped.chunks.size,
                            dropped.totalChunks,
                            "SESSION_EVICTION_LRU"
                        )
                    }
                }
            }

            // Bound global active chunk sessions (max 16 per §8)
            if (sessions.size >= BleConstants.MAX_BLE_SESSIONS_GLOBAL && !sessions.containsKey(sessionKey)) {
                val oldestGlobalKey = sessions.entries.minByOrNull { it.value.createdAt }?.key
                if (oldestGlobalKey != null) {
                    val dropped = sessions.remove(oldestGlobalKey)
                    if (dropped != null) {
                        val devAddr = oldestGlobalKey.substringBeforeLast("-")
                        val sessId = oldestGlobalKey.substringAfterLast("-").toShortOrNull() ?: 0
                        onChunkSessionDroppedListener?.invoke(
                            devAddr,
                            sessId,
                            dropped.chunks.size,
                            dropped.totalChunks,
                            "GLOBAL_SESSION_EVICTION_LRU"
                        )
                    }
                }
            }

            // If session already exists with mismatched totalChunks, evict and start fresh
            val existing = sessions[sessionKey]
            val session = if (existing != null && existing.totalChunks == totalChunks) {
                existing
            } else {
                if (existing != null) {
                    onChunkSessionDroppedListener?.invoke(
                        deviceAddress,
                        sessionId,
                        existing.chunks.size,
                        existing.totalChunks,
                        "SESSION_MISMATCHED_TOTAL_CHUNKS"
                    )
                }
                ChunkSession(totalChunks = totalChunks).also { sessions[sessionKey] = it }
            }

            synchronized(session) {
                session.chunks[chunkIndex] = chunkData

                // Check cumulative size limit (max 2104 B per §8, T-LINK-11)
                var currentTotalSize = 0
                for (chunk in session.chunks.values) {
                    currentTotalSize += chunk.size
                }
                if (currentTotalSize > BleConstants.MAX_BLE_REASSEMBLY_SIZE) {
                    sessions.remove(sessionKey)
                    onChunkSessionDroppedListener?.invoke(
                        deviceAddress,
                        sessionId,
                        session.chunks.size,
                        session.totalChunks,
                        "REASSEMBLY_SIZE_EXCEEDED"
                    )
                    return null
                }

                // Prune expired sessions older than 10 seconds (§8)
                val now = System.currentTimeMillis()
                val expiredKeys = sessions.entries.filter { now - it.value.createdAt > BleConstants.BLE_REASSEMBLY_TIMEOUT_MS }.map { it.key }
                for (expKey in expiredKeys) {
                    val expired = sessions.remove(expKey)
                    if (expired != null) {
                        val expDevAddr = expKey.substringBeforeLast("-")
                        val expSessId = expKey.substringAfterLast("-").toShortOrNull() ?: 0
                        onChunkSessionDroppedListener?.invoke(
                            expDevAddr,
                            expSessId,
                            expired.chunks.size,
                            expired.totalChunks,
                            "SESSION_TIMEOUT_10S"
                        )
                    }
                }

                if (session.chunks.size == session.totalChunks) {
                    // Reassemble complete packet
                    var totalSize = 0
                    for (i in 0 until session.totalChunks) {
                        val c = session.chunks[i] ?: return null
                        totalSize += c.size
                    }

                    if (totalSize > BleConstants.MAX_BLE_REASSEMBLY_SIZE) {
                        sessions.remove(sessionKey)
                        return null
                    }

                    val fullPacket = ByteArray(totalSize)
                    var offset = 0
                    for (i in 0 until session.totalChunks) {
                        val c = session.chunks[i] ?: return null
                        System.arraycopy(c, 0, fullPacket, offset, c.size)
                        offset += c.size
                    }

                    sessions.remove(sessionKey)
                    return fullPacket
                }
            }
        }

        return null
    }

    fun clearDevice(deviceAddress: String) {
        sessions.keys.filter { it.startsWith("$deviceAddress-") }.forEach { sessions.remove(it) }
    }
}

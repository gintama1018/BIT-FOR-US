package com.meshwhisper.app.ble

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles framing, fragmentation (chunking) and reassembly of raw packets over BLE GATT.
 */
class BleFrameFramer {

    private data class ChunkSession(
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf(),
        val createdAt: Long = System.currentTimeMillis()
    )

    private val sessions = ConcurrentHashMap<String, ChunkSession>()

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
        val chunkSize = maxSafePayload - 4 // 1B type + 2B sessionId + 1B index + 1B total
        val totalChunks = (packetBytes.size + chunkSize - 1) / chunkSize
        val sessionId = (packetBytes.hashCode() and 0xFFFF).toShort()
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
    fun receiveFrame(deviceAddress: String, frameBytes: ByteArray): ByteArray? {
        if (frameBytes.isEmpty()) return null

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

            val chunkData = ByteArray(buffer.remaining())
            buffer.get(chunkData)

            val sessionKey = "$deviceAddress-$sessionId"
            val session = sessions.getOrPut(sessionKey) {
                ChunkSession(totalChunks = totalChunks)
            }

            session.chunks[chunkIndex] = chunkData

            // Prune expired sessions older than 30 seconds
            val now = System.currentTimeMillis()
            sessions.entries.removeIf { now - it.value.createdAt > 30000 }

            if (session.chunks.size == session.totalChunks) {
                // Reassemble complete packet
                var totalSize = 0
                for (i in 0 until session.totalChunks) {
                    val c = session.chunks[i] ?: return null
                    totalSize += c.size
                }

                val fullPacket = ByteArray(totalSize)
                var offset = 0
                for (i in 0 until session.totalChunks) {
                    val c = session.chunks[i]!!
                    System.arraycopy(c, 0, fullPacket, offset, c.size)
                    offset += c.size
                }

                sessions.remove(sessionKey)
                return fullPacket
            }
        }

        return null
    }
}

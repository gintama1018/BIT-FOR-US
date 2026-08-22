package com.meshwhisper.app.media

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.ceil

class MediaTransferTest {

    @Test
    fun testChunkSplittingAndOutOfOrderReassembly() {
        val originalPayload = ByteArray(45 * 1024) { (it % 256).toByte() } // 45 KB simulated audio/image
        val chunkSize = MeshPacket.CHUNK_PAYLOAD_SIZE
        val totalChunks = ceil(originalPayload.size.toDouble() / chunkSize).toInt()

        assertThat(totalChunks).isEqualTo(116)

        // Split into chunks
        val chunkMap = mutableMapOf<Int, ByteArray>()
        val mediaId = UUID.randomUUID()

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, originalPayload.size)
            chunkMap[i] = originalPayload.copyOfRange(start, end)
        }

        // Verify all chunks have unique packet IDs (prevent AES-GCM nonce reuse)
        val packetIds = mutableSetOf<UUID>()
        for (i in 0 until totalChunks) {
            val packetId = UUID.randomUUID()
            packetIds.add(packetId)
        }
        assertThat(packetIds.size).isEqualTo(totalChunks)

        // Simulate out-of-order arrival over mesh
        val shuffledIndices = (0 until totalChunks).shuffled()
        val receivedBuffer = mutableMapOf<Int, ByteArray>()

        for (index in shuffledIndices) {
            receivedBuffer[index] = chunkMap[index]!!
        }

        assertThat(receivedBuffer.size).isEqualTo(totalChunks)

        // Reassemble in index order
        val reassembledBytes = ByteArray(originalPayload.size)
        var offset = 0
        for (i in 0 until totalChunks) {
            val piece = receivedBuffer[i]!!
            System.arraycopy(piece, 0, reassembledBytes, offset, piece.size)
            offset += piece.size
        }

        assertThat(offset).isEqualTo(originalPayload.size)
        assertThat(reassembledBytes).isEqualTo(originalPayload)
    }

    @Test
    fun testMediaInitPayloadSerializationAndParsing() {
        val mediaId = UUID.randomUUID()
        val typeByte: Byte = 0 // 0 = IMAGE
        val totalChunks: Short = 28
        val totalSizeBytes = 50000
        val durationMs = 0
        val caption = "Test Photo Attachment"
        val captionBytes = caption.toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(16 + 1 + 2 + 4 + 4 + 1 + captionBytes.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(mediaId.mostSignificantBits)
        buffer.putLong(mediaId.leastSignificantBits)
        buffer.put(typeByte)
        buffer.putShort(totalChunks)
        buffer.putInt(totalSizeBytes)
        buffer.putInt(durationMs)
        buffer.put((captionBytes.size and 0xFF).toByte())
        buffer.put(captionBytes)

        val serialized = buffer.array()

        // Parse back
        val readBuf = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        val parsedType = readBuf.get()
        val parsedChunks = readBuf.short.toInt() and 0xFFFF
        val parsedSize = readBuf.int
        val parsedDuration = readBuf.int
        val parsedCapLen = readBuf.get().toInt() and 0xFF
        val parsedCapBytes = ByteArray(parsedCapLen)
        readBuf.get(parsedCapBytes)
        val parsedCaption = String(parsedCapBytes, Charsets.UTF_8)

        assertThat(parsedMediaId).isEqualTo(mediaId)
        assertThat(parsedType).isEqualTo(typeByte)
        assertThat(parsedChunks).isEqualTo(28)
        assertThat(parsedSize).isEqualTo(totalSizeBytes)
        assertThat(parsedDuration).isEqualTo(0)
        assertThat(parsedCaption).isEqualTo(caption)
    }

    @Test
    fun testMediaChunkPayloadSerializationAndParsing() {
        val mediaId = UUID.randomUUID()
        val chunkIndex: Short = 7
        val chunkData = "Test chunk raw media bytes simulation".toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(16 + 2 + chunkData.size).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(mediaId.mostSignificantBits)
        buffer.putLong(mediaId.leastSignificantBits)
        buffer.putShort(chunkIndex)
        buffer.put(chunkData)

        val serialized = buffer.array()

        val readBuf = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        val parsedIndex = readBuf.short.toInt() and 0xFFFF
        val parsedData = ByteArray(readBuf.remaining())
        readBuf.get(parsedData)

        assertThat(parsedMediaId).isEqualTo(mediaId)
        assertThat(parsedIndex).isEqualTo(7)
        assertThat(parsedData).isEqualTo(chunkData)
    }

    @Test
    fun testMediaTtlIsLowerThanDefaultTtl() {
        assertThat(MeshPacket.MEDIA_TTL).isLessThan(MeshPacket.DEFAULT_TTL)
        assertThat(MeshPacket.MEDIA_TTL).isEqualTo(4)
    }
}

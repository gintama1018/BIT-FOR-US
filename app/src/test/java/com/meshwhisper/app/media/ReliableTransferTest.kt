package com.meshwhisper.app.media

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Arrays
import java.util.UUID

class ReliableTransferTest {

    @Test
    fun testSha256IntegrityVerification() {
        val payload = "Important Document Contents".toByteArray(Charsets.UTF_8)
        val sha256 = MediaCompressor.computeSha256(payload)

        assertThat(sha256.size).isEqualTo(32)

        // Verify with Java standard MessageDigest
        val expected = MessageDigest.getInstance("SHA-256").digest(payload)
        assertThat(Arrays.equals(sha256, expected)).isTrue()

        // Tampered payload fails verification
        val tamperedPayload = "Important Document Contents!".toByteArray(Charsets.UTF_8)
        val tamperedSha = MediaCompressor.computeSha256(tamperedPayload)
        assertThat(Arrays.equals(sha256, tamperedSha)).isFalse()
    }

    @Test
    fun testNackPayloadSerializationAndParsing() {
        val mediaId = UUID.randomUUID()
        val missingChunks = listOf(3, 7, 12, 18, 45)

        val buffer = ByteBuffer.allocate(16 + 2 + (missingChunks.size * 2)).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(mediaId.mostSignificantBits)
        buffer.putLong(mediaId.leastSignificantBits)
        buffer.putShort((missingChunks.size and 0xFFFF).toShort())
        for (idx in missingChunks) {
            buffer.putShort((idx and 0xFFFF).toShort())
        }

        val serialized = buffer.array()

        // Parse back
        val readBuf = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        val count = readBuf.short.toInt() and 0xFFFF
        val parsedMissing = mutableListOf<Int>()
        for (i in 0 until count) {
            parsedMissing.add(readBuf.short.toInt() and 0xFFFF)
        }

        assertThat(parsedMediaId).isEqualTo(mediaId)
        assertThat(count).isEqualTo(5)
        assertThat(parsedMissing).isEqualTo(missingChunks)
    }

    @Test
    fun testAckPayloadSerializationAndParsing() {
        val mediaId = UUID.randomUUID()
        val buffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(mediaId.mostSignificantBits)
        buffer.putLong(mediaId.leastSignificantBits)

        val serialized = buffer.array()

        val readBuf = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        assertThat(parsedMediaId).isEqualTo(mediaId)
    }

    @Test
    fun testSelectiveRetransmissionReconstruction() {
        val totalChunks = 50
        val simulatedChunks = (0 until totalChunks).associateWith { "chunk_$it".toByteArray(Charsets.UTF_8) }

        // Simulate dropped chunks: 5, 14, 29, 41
        val received = simulatedChunks.toMutableMap()
        val dropped = listOf(5, 14, 29, 41)
        dropped.forEach { received.remove(it) }

        assertThat(received.size).isEqualTo(46)

        // Generate NACK
        val missingIndices = (0 until totalChunks).filter { !received.containsKey(it) }
        assertThat(missingIndices).isEqualTo(dropped)

        // Retransmit missing
        missingIndices.forEach { idx ->
            received[idx] = simulatedChunks[idx]!!
        }

        assertThat(received.size).isEqualTo(totalChunks)
    }

    @Test
    fun testDirectMediaTtlIsStrictlySingleHop() {
        assertThat(MeshPacket.MEDIA_DIRECT_TTL).isEqualTo(1)
    }
}

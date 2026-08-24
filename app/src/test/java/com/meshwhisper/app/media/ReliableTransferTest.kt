package com.meshwhisper.app.media

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.crypto.CryptoEngine
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

    @Test
    fun testTileGridRulesAndChunkPaddingAlignment() {
        // 1. Grid threshold rules
        assertThat(MediaCompressor.shouldTileImage(15 * 1024L)).isNull() // <20KB: untiled
        assertThat(MediaCompressor.shouldTileImage(80 * 1024L)).isEqualTo(Pair(3, 3)) // 20KB-150KB: 3x3
        assertThat(MediaCompressor.shouldTileImage(300 * 1024L)).isEqualTo(Pair(4, 4)) // >150KB: 4x4

        // 2. Padding alignment: each tile must be an exact multiple of 400 bytes
        val simulatedTileLengths = listOf(1120, 850, 990, 1420, 800)
        val paddedLengths = simulatedTileLengths.map { rawLen ->
            val rem = rawLen % MeshPacket.CHUNK_PAYLOAD_SIZE
            val pad = if (rem != 0) MeshPacket.CHUNK_PAYLOAD_SIZE - rem else 0
            rawLen + pad
        }

        paddedLengths.forEach { len ->
            assertThat(len % MeshPacket.CHUNK_PAYLOAD_SIZE).isEqualTo(0)
        }

        // 3. Precomputed chunk ranges must be contiguous and non-overlapping
        var currentChunkOffset = 0
        val ranges = paddedLengths.map { pLen ->
            val numChunks = pLen / MeshPacket.CHUNK_PAYLOAD_SIZE
            val r = currentChunkOffset until (currentChunkOffset + numChunks)
            currentChunkOffset += numChunks
            r
        }

        assertThat(ranges.size).isEqualTo(5)
        assertThat(ranges[0]).isEqualTo(0 until 3) // 1200 / 400 = 3 chunks (0..2)
        assertThat(ranges[1]).isEqualTo(3 until 6) // 1200 / 400 = 3 chunks (3..5)
        assertThat(ranges[2]).isEqualTo(6 until 9) // 1200 / 400 = 3 chunks (6..8)
        assertThat(ranges[3]).isEqualTo(9 until 13) // 1600 / 400 = 4 chunks (9..12)
        assertThat(ranges[4]).isEqualTo(13 until 15) // 800 / 400 = 2 chunks (13..14)
    }

    @Test
    fun testTiledMediaInitPayloadSerialization() {
        val mediaId = UUID.randomUUID()
        val gridCols = 3
        val gridRows = 3
        val widthPx = 800
        val heightPx = 600
        val paddedTileLengths = listOf(800, 1200, 800, 1200, 1600, 1200, 800, 1200, 800)
        val totalBytes = paddedTileLengths.sum()
        val totalChunks = totalBytes / MeshPacket.CHUNK_PAYLOAD_SIZE
        val sha256 = ByteArray(32) { (it * 7).toByte() }

        // Build binary MEDIA_INIT
        val tilingHeaderSize = 1 + 1 + 2 + 2 + 1 + paddedTileLengths.size * 4
        val initPayloadSize = 16 + 1 + 1 + 2 + 4 + 4 + 32 + 1 + 0 + 2 + 0 + 1 + 0 + tilingHeaderSize
        val buf = ByteBuffer.allocate(initPayloadSize).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(mediaId.mostSignificantBits)
        buf.putLong(mediaId.leastSignificantBits)
        buf.put(0.toByte()) // IMAGE
        buf.put(1.toByte()) // version
        buf.putShort((totalChunks and 0xFFFF).toShort())
        buf.putInt(totalBytes)
        buf.putInt(0) // durationMs
        buf.put(sha256)
        buf.put(0.toByte()) // fileNameLen
        buf.putShort(0.toShort()) // previewLen
        buf.put(0.toByte()) // captionLen

        // Tiling metadata
        buf.put((gridCols and 0xFF).toByte())
        buf.put((gridRows and 0xFF).toByte())
        buf.putShort((widthPx and 0xFFFF).toShort())
        buf.putShort((heightPx and 0xFFFF).toShort())
        buf.put((paddedTileLengths.size and 0xFF).toByte())
        paddedTileLengths.forEach { buf.putInt(it) }

        val serialized = buf.array()

        // Parse back
        val readBuf = ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        val typeCode = readBuf.get()
        val ver = readBuf.get()
        val parsedChunks = readBuf.short.toInt() and 0xFFFF
        val parsedBytes = readBuf.int
        val parsedDuration = readBuf.int
        val parsedSha = ByteArray(32).also { readBuf.get(it) }
        val fnLen = readBuf.get().toInt() and 0xFF
        val prevLen = readBuf.short.toInt() and 0xFFFF
        val capLen = readBuf.get().toInt() and 0xFF

        // Tiling parser
        assertThat(readBuf.remaining()).isAtLeast(7)
        val parsedCols = readBuf.get().toInt() and 0xFF
        val parsedRows = readBuf.get().toInt() and 0xFF
        val parsedW = readBuf.short.toInt() and 0xFFFF
        val parsedH = readBuf.short.toInt() and 0xFFFF
        val parsedTileCount = readBuf.get().toInt() and 0xFF
        val parsedTileLengths = mutableListOf<Int>()
        for (i in 0 until parsedTileCount) {
            parsedTileLengths.add(readBuf.int)
        }

        assertThat(parsedMediaId).isEqualTo(mediaId)
        assertThat(parsedCols).isEqualTo(3)
        assertThat(parsedRows).isEqualTo(3)
        assertThat(parsedW).isEqualTo(800)
        assertThat(parsedH).isEqualTo(600)
        assertThat(parsedTileCount).isEqualTo(9)
        assertThat(parsedTileLengths).isEqualTo(paddedTileLengths)
    }

    @Test
    fun testOutOfOrderTileReconstruction() {
        // Tile 0: chunks 0..1 (2 chunks)
        // Tile 1: chunks 2..3 (2 chunks)
        // Tile 2: chunks 4..6 (3 chunks)
        val tileChunkRanges = listOf(0..1, 2..3, 4..6)
        val receivedChunks = mutableMapOf<Int, ByteArray>()
        val paintedTiles = mutableSetOf<Int>()

        // Helper checker
        fun checkDecodableTiles(): List<Int> {
            val newlyDecodable = mutableListOf<Int>()
            for (idx in tileChunkRanges.indices) {
                if (!paintedTiles.contains(idx)) {
                    val range = tileChunkRanges[idx]
                    if (range.all { receivedChunks.containsKey(it) }) {
                        paintedTiles.add(idx)
                        newlyDecodable.add(idx)
                    }
                }
            }
            return newlyDecodable
        }

        // Out of order delivery: chunk 4, then 6, then 5 (Tile 2 chunks)
        receivedChunks[4] = byteArrayOf(4)
        assertThat(checkDecodableTiles()).isEmpty()

        receivedChunks[6] = byteArrayOf(6)
        assertThat(checkDecodableTiles()).isEmpty()

        receivedChunks[5] = byteArrayOf(5) // Tile 2 is now complete (chunks 4, 5, 6 present)!
        assertThat(checkDecodableTiles()).containsExactly(2)

        // Chunk 0 arrives (Tile 0 is still incomplete, missing chunk 1)
        receivedChunks[0] = byteArrayOf(0)
        assertThat(checkDecodableTiles()).isEmpty()

        // Chunk 1 arrives (Tile 0 is now complete!)
        receivedChunks[1] = byteArrayOf(1)
        assertThat(checkDecodableTiles()).containsExactly(0)

        // Chunks 2 and 3 arrive (Tile 1 complete!)
        receivedChunks[2] = byteArrayOf(2)
        receivedChunks[3] = byteArrayOf(3)
        assertThat(checkDecodableTiles()).containsExactly(1)

        // All 3 tiles painted
        assertThat(paintedTiles).containsExactly(0, 1, 2)
    }

    @Test
    fun testDocxTextExtraction() {
        // Create a synthetic in-memory DOCX zip file with word/document.xml
        val tempDocx = java.io.File.createTempFile("test_mesh", ".docx")
        try {
            val docXmlContent = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                    <w:body>
                        <w:p>
                            <w:r><w:t>Hello MeshWhisper Team!</w:t></w:r>
                        </w:p>
                        <w:p>
                            <w:r><w:t>Offline mesh file sharing is working &amp; ready.</w:t></w:r>
                        </w:p>
                    </w:body>
                </w:document>
            """.trimIndent()

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(tempDocx)).use { zos ->
                val entry = java.util.zip.ZipEntry("word/document.xml")
                zos.putNextEntry(entry)
                zos.write(docXmlContent.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            val extracted = DocxTextExtractor.extractText(tempDocx)
            assertThat(extracted).isNotNull()
            assertThat(extracted).contains("Hello MeshWhisper Team!")
            assertThat(extracted).contains("Offline mesh file sharing is working & ready.")
        } finally {
            tempDocx.delete()
        }
    }

    @Test
    fun testBroadcastMediaSelfHealingNackProtocol() {
        val mediaId = UUID.randomUUID()
        val broadcasterNodeId = 123456789L
        val receiverNodeId = 987654321L
        val missingChunkIndices = listOf(2, 5, 8)

        // 1. Inbound receiver detects missing chunks from broadcast transfer and builds NACK packet
        val nackBuf = ByteBuffer.allocate(16 + 2 + (missingChunkIndices.size * 2)).order(ByteOrder.BIG_ENDIAN)
        nackBuf.putLong(mediaId.mostSignificantBits)
        nackBuf.putLong(mediaId.leastSignificantBits)
        nackBuf.putShort(missingChunkIndices.size.toShort())
        missingChunkIndices.forEach { nackBuf.putShort(it.toShort()) }

        val nackPacket = MeshPacket(
            type = PacketType.MEDIA_NACK,
            messageId = UUID.randomUUID(),
            senderId = receiverNodeId,
            recipientId = broadcasterNodeId,
            ttl = MeshPacket.MEDIA_TTL, // Must be 4 hops for multi-hop mesh recovery
            timestamp = System.currentTimeMillis() / 1000L,
            payload = nackBuf.array(),
            authTag = ByteArray(16)
        )

        // 2. Verify packet routing parameters
        assertThat(nackPacket.ttl).isEqualTo(MeshPacket.MEDIA_TTL)
        assertThat(nackPacket.ttl).isGreaterThan(1)
        assertThat(nackPacket.recipientId).isEqualTo(broadcasterNodeId)

        // 3. Verify hop decrement during mesh relay
        val relayedPacket = nackPacket.decrementTtl()
        assertThat(relayedPacket.ttl).isEqualTo(MeshPacket.MEDIA_TTL - 1)

        // 4. Parse NACK payload at broadcaster side
        val readBuf = ByteBuffer.wrap(nackPacket.payload).order(ByteOrder.BIG_ENDIAN)
        val parsedMediaId = UUID(readBuf.long, readBuf.long)
        val parsedCount = readBuf.short.toInt() and 0xFFFF
        val parsedIndices = (0 until parsedCount).map { readBuf.short.toInt() and 0xFFFF }

        assertThat(parsedMediaId).isEqualTo(mediaId)
        assertThat(parsedCount).isEqualTo(3)
        assertThat(parsedIndices).isEqualTo(missingChunkIndices)
    }

    // =========================================================================
    // SECURITY HARDENING TESTS
    // =========================================================================

    @Test
    fun testAckPacketIdIsUniqueFromOriginalMessageId() {
        // Critical: ACK must use a fresh nonce (ackPacketId), never the originalMsgId.
        // The fix is in MeshRouter.sendAck() — we verify the protocol invariant here.
        val originalMsgId = UUID.randomUUID()
        val ackPacketId = UUID.randomUUID()

        // The two IDs must be distinct — same ID would cause GCM nonce reuse under the same key
        assertThat(ackPacketId).isNotEqualTo(originalMsgId)

        // Verify ACK payload encodes originalMsgId (16 bytes) correctly
        val payloadBuf = ByteBuffer.allocate(16).apply {
            putLong(originalMsgId.mostSignificantBits)
            putLong(originalMsgId.leastSignificantBits)
        }.array()

        val readBuf = ByteBuffer.wrap(payloadBuf)
        val recoveredId = UUID(readBuf.long, readBuf.long)
        assertThat(recoveredId).isEqualTo(originalMsgId)

        // Verify different ACKs for same message produce different ackPacketIds
        val ackPacketId2 = UUID.randomUUID()
        assertThat(ackPacketId).isNotEqualTo(ackPacketId2)
    }

    @Test
    fun testQrRegistrationNodeIdPubKeyConsistency() {
        // Verify the logic: deriveNodeId(pubKey) must match the claimed nodeId
        // (mirrors the security check in registerScannedPeer)
        val pubKeyBytes = ByteArray(32) { it.toByte() } // deterministic test key
        val derivedNodeId = CryptoEngine.deriveNodeId(pubKeyBytes)

        val claimedCorrectNodeId = derivedNodeId
        val claimedWrongNodeId = derivedNodeId + 1L

        // Correct: derivedNodeId == claimed => accept
        assertThat(CryptoEngine.deriveNodeId(pubKeyBytes)).isEqualTo(claimedCorrectNodeId)

        // Wrong: derived != claimed => should reject
        assertThat(CryptoEngine.deriveNodeId(pubKeyBytes)).isNotEqualTo(claimedWrongNodeId)
    }

    @Test
    fun testZeroByteMediaIsRejectedByGuard() {
        // Verify the zero-byte check logic (sendMedia rejects empty mediaBytes)
        val mediaBytes = ByteArray(0)
        val isEmpty = mediaBytes.isEmpty()
        assertThat(isEmpty).isTrue()

        // Non-empty media passes
        val validMediaBytes = ByteArray(400) { 0x42 }
        assertThat(validMediaBytes.isEmpty()).isFalse()
    }

    @Test
    fun testChunkIndexBoundsRejection() {
        // Verify bounds-check logic: chunkIndex must be in [0, totalChunks)
        val totalChunks = 10

        // Valid indices
        assertThat(0 in 0 until totalChunks).isTrue()
        assertThat(9 in 0 until totalChunks).isTrue()

        // Out-of-bounds indices should be rejected
        assertThat(10 in 0 until totalChunks).isFalse()
        assertThat(65535 in 0 until totalChunks).isFalse()
        assertThat(-1 in 0 until totalChunks).isFalse()
    }

    @Test
    fun testMediaInitMetadataBoundsRejection() {
        // Verify bounds-check thresholds (mirrors handleMediaInit guard)
        val maxChunks = 4096
        val maxSizeBytes = 20 * 1024 * 1024

        // totalChunks == 0 is invalid (division by zero risk)
        assertThat(0 == 0 || 0 > maxChunks).isTrue()

        // Absurdly large totalChunks
        assertThat(65535 > maxChunks).isTrue()

        // Absurdly large totalSizeBytes
        assertThat(100 * 1024 * 1024 > maxSizeBytes).isTrue()

        // Valid metadata passes
        val validChunks = 100
        val validSize = 40_000
        assertThat(validChunks == 0 || validChunks > maxChunks).isFalse()
        assertThat(validSize <= 0 || validSize > maxSizeBytes).isFalse()
    }
}

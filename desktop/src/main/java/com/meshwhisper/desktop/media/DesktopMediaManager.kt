package com.meshwhisper.desktop.media

import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.protocol.AdmittedChunk
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.desktop.crypto.DesktopCryptoEngine
import com.meshwhisper.desktop.crypto.DesktopPassphraseKeyStorage
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopMessage
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.imageio.ImageIO

data class InboundMediaSession(
    val mediaId: UUID,
    val senderId: Long,
    val recipientId: Long,
    val typeCode: Byte,
    val mediaTypeName: String,
    val totalChunks: Int,
    val totalSizeBytes: Int,
    val durationMs: Long,
    val originalFileName: String,
    val caption: String,
    val sha256: ByteArray,
    val previewBytes: ByteArray = ByteArray(0),
    val gridCols: Int = 1,
    val gridRows: Int = 1,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val paddedTileByteLengths: List<Int> = emptyList(),
    val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
    var lastActivityMs: Long = System.currentTimeMillis()
)

/**
 * Desktop Media Transfer Engine with Media At-Rest Encryption (Phase P7 / P9 parity).
 *
 * Security Invariants:
 * - Plaintext media NEVER touches persistent disk or temporary files.
 * - Files on disk are encrypted with AES-256-GCM under HKDF(mediaFileKey, salt=fileId, info="MW/FILE/v2").
 * - File format: [8B MAGIC_HEADER "MWMEDIA1"] + [12B IV] + [Ciphertext + 16B Tag].
 * - Atomic encrypted write via temporary .enc.tmp file.
 * - Decryption happens in memory upon retrieval; corrupted files or invalid keys fail closed.
 */
class DesktopMediaManager(
    private val myNodeId: Long,
    private val myPrivateKey: ByteArray,
    private val keyStorage: DesktopPassphraseKeyStorage,
    private val database: DesktopDatabase,
    private val wifiEngine: DesktopWifiEngine,
    private val logger: MeshLogger,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DesktopMediaManager"
        const val CHUNK_PAYLOAD_SIZE = 400
        val MAGIC_HEADER = "MWMEDIA1".toByteArray(Charsets.US_ASCII)
        val FILE_HKDF_INFO = "MW/FILE/v2".toByteArray(Charsets.UTF_8)
    }

    private val mediaStorageDir = File(File(System.getProperty("user.home"), ".meshwhisper"), "media").apply {
        if (!exists()) mkdirs()
    }

    private val inboundSessions = ConcurrentHashMap<String, InboundMediaSession>()
    private val secureRandom = SecureRandom()

    private val _mediaTransfersUpdated = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val mediaTransfersUpdated = _mediaTransfersUpdated.asSharedFlow()

    fun getOrCreateMediaFileKey(): ByteArray {
        val existing = keyStorage.getMediaFileKey()
        if (existing != null && existing.size == 32) return existing

        val newKey = ByteArray(32).also { secureRandom.nextBytes(it) }
        keyStorage.storeMediaFileKey(newKey)
        return newKey
    }

    private fun derivePerFileKey(fileId: String): ByteArray {
        val rootKey = getOrCreateMediaFileKey()
        val salt = fileId.toByteArray(Charsets.UTF_8)
        return DesktopCryptoEngine.hkdf(
            ikm = rootKey,
            salt = salt,
            info = FILE_HKDF_INFO,
            outputLength = 32
        )
    }

    /**
     * Encrypts plaintext bytes and writes to [destinationFile] atomically.
     * Zero plaintext reaches persistent disk.
     */
    fun encryptAndWriteMediaFile(fileId: String, plaintextBytes: ByteArray, destinationFile: File) {
        val fileKey = derivePerFileKey(fileId)
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintextBytes)

        val parentDir = destinationFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.enc.tmp")
        try {
            FileOutputStream(tempFile).use { fos ->
                fos.write(MAGIC_HEADER)
                fos.write(iv)
                fos.write(ciphertext)
                fos.flush()
            }
            if (!tempFile.renameTo(destinationFile)) {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
            }
        } catch (e: Exception) {
            tempFile.delete()
            throw e
        }
    }

    /**
     * Reads and decrypts an encrypted media file from disk into memory.
     * Fails closed if corrupted or tampered.
     */
    fun readAndDecryptMediaFile(fileId: String, sourceFile: File): ByteArray {
        if (!sourceFile.exists()) {
            throw java.io.FileNotFoundException("Media file not found: ${sourceFile.absolutePath}")
        }
        val fileBytes = sourceFile.readBytes()
        if (fileBytes.size < MAGIC_HEADER.size + 12 + 16) {
            throw IllegalStateException("Corrupted or incomplete encrypted media file: ${sourceFile.name}")
        }

        val header = fileBytes.copyOfRange(0, MAGIC_HEADER.size)
        if (!header.contentEquals(MAGIC_HEADER)) {
            throw IllegalStateException("Invalid magic header on media file: ${sourceFile.name}")
        }

        val iv = fileBytes.copyOfRange(MAGIC_HEADER.size, MAGIC_HEADER.size + 12)
        val ciphertext = fileBytes.copyOfRange(MAGIC_HEADER.size + 12, fileBytes.size)

        val fileKey = derivePerFileKey(fileId)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(fileKey, "AES"), GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertext)
    }

    fun isMediaFileEncrypted(file: File): Boolean {
        if (!file.exists() || file.length() < MAGIC_HEADER.size) return false
        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(MAGIC_HEADER.size)
                val read = fis.read(buf)
                read == MAGIC_HEADER.size && buf.contentEquals(MAGIC_HEADER)
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun handleMediaInit(packet: MeshPacket, plainBytes: ByteArray, isBroadcast: Boolean) {
        if (plainBytes.size < 62) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaIdMost = buffer.getLong()
        val mediaIdLeast = buffer.getLong()
        val mediaId = UUID(mediaIdMost, mediaIdLeast)
        val typeCode = buffer.get()
        val typeName = when (typeCode) {
            0.toByte() -> "IMAGE"
            1.toByte() -> "VOICE"
            2.toByte() -> "AVATAR"
            3.toByte() -> "FILE"
            else -> "MEDIA"
        }
        val mediaVersion = buffer.get()
        val totalChunks = buffer.getShort().toInt() and 0xFFFF
        val totalSizeBytes = buffer.getInt()
        val durationMs = buffer.getInt().toLong()

        val sha256 = ByteArray(32)
        buffer.get(sha256)

        val fileNameLen = buffer.get().toInt() and 0xFF
        val originalFileName = if (fileNameLen > 0 && buffer.remaining() >= fileNameLen) {
            val fn = ByteArray(fileNameLen)
            buffer.get(fn)
            String(fn, Charsets.UTF_8)
        } else ""

        val previewLen = buffer.getShort().toInt() and 0xFFFF
        val previewBytes = if (previewLen > 0 && buffer.remaining() >= previewLen) {
            val pb = ByteArray(previewLen)
            buffer.get(pb)
            pb
        } else ByteArray(0)

        val captionLen = buffer.get().toInt() and 0xFF
        val caption = if (captionLen > 0 && buffer.remaining() >= captionLen) {
            val cBytes = ByteArray(captionLen)
            buffer.get(cBytes)
            String(cBytes, Charsets.UTF_8)
        } else ""

        var gridCols = 1
        var gridRows = 1
        var imageWidthPx = 0
        var imageHeightPx = 0
        val paddedTileByteLengths = mutableListOf<Int>()

        if (buffer.remaining() >= 7) {
            val gCols = buffer.get().toInt() and 0xFF
            val gRows = buffer.get().toInt() and 0xFF
            val wPx = buffer.getShort().toInt() and 0xFFFF
            val hPx = buffer.getShort().toInt() and 0xFFFF
            val tCount = buffer.get().toInt() and 0xFF
            if (gCols > 1 && gRows > 1 && tCount > 0 && buffer.remaining() >= tCount * 4) {
                gridCols = gCols
                gridRows = gRows
                imageWidthPx = wPx
                imageHeightPx = hPx
                for (i in 0 until tCount) {
                    paddedTileByteLengths.add(buffer.getInt())
                }
            }
        }

        val sessionKey = "${packet.senderId}_$mediaId"
        val session = InboundMediaSession(
            mediaId = mediaId,
            senderId = packet.senderId,
            recipientId = packet.recipientId,
            typeCode = typeCode,
            mediaTypeName = typeName,
            totalChunks = totalChunks,
            totalSizeBytes = totalSizeBytes,
            durationMs = durationMs,
            originalFileName = originalFileName,
            caption = caption,
            sha256 = sha256,
            previewBytes = previewBytes,
            gridCols = gridCols,
            gridRows = gridRows,
            imageWidthPx = imageWidthPx,
            imageHeightPx = imageHeightPx,
            paddedTileByteLengths = paddedTileByteLengths
        )
        inboundSessions[sessionKey] = session

        logger.i(TAG, "Incoming $typeName transfer: $mediaId ($totalChunks chunks, $totalSizeBytes bytes) from 0x${String.format("%016X", packet.senderId)}")

        val textLabel = when (typeName) {
            "IMAGE" -> "Image Incoming (${gridCols}x${gridRows} Tiles) [0/$totalChunks]"
            "VOICE" -> "Voice Note Incoming (${durationMs / 1000}s) [0/$totalChunks]"
            "FILE" -> "File: $originalFileName [0/$totalChunks]"
            else -> "Media: $originalFileName [0/$totalChunks]"
        }

        val msg = DesktopMessage(
            messageId = mediaId.toString(),
            senderNodeId = packet.senderId,
            recipientNodeId = packet.recipientId,
            text = textLabel,
            timestamp = packet.timestamp,
            isIncoming = true,
            isDelivered = true,
            ttlRemaining = packet.ttl,
            isChannelBroadcast = isBroadcast,
            channelName = if (isBroadcast) "public" else null,
            mediaType = typeName,
            mediaSizeBytes = totalSizeBytes.toLong()
        )
        database.insertMessage(msg)
        _mediaTransfersUpdated.tryEmit(msg)
    }

    fun handleMediaChunk(chunk: AdmittedChunk) {
        val packet = chunk.packet
        val mediaId = chunk.mediaId
        val chunkIndex = chunk.chunkIndex
        val chunkData = chunk.chunkData

        val sessionKey = "${packet.senderId}_$mediaId"
        val session = inboundSessions[sessionKey] ?: return
        session.lastActivityMs = System.currentTimeMillis()
        session.chunks[chunkIndex] = chunkData

        val received = session.chunks.size
        if (received % 10 == 0 || received == session.totalChunks) {
            logger.i(TAG, "Received chunk $received/${session.totalChunks} for ${session.mediaTypeName} $mediaId")
        }

        if (received >= session.totalChunks) {
            assembleAndSaveMedia(session, chunk.isBroadcast)
            inboundSessions.remove(sessionKey)
        }
    }

    private fun assembleAndSaveMedia(session: InboundMediaSession, isBroadcast: Boolean) {
        val ext = when (session.typeCode) {
            0.toByte() -> ".jpg"
            1.toByte() -> ".m4a"
            2.toByte() -> ".jpg"
            3.toByte() -> if (session.originalFileName.contains(".")) ".${session.originalFileName.substringAfterLast('.')}" else ".bin"
            else -> ".bin"
        }

        val safeName = if (session.originalFileName.isNotBlank()) {
            session.originalFileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        } else {
            "media_${session.mediaId.toString().take(8)}$ext"
        }

        val targetFile = File(mediaStorageDir, safeName)
        try {
            // Concatenate all chunks in memory
            val totalBytesSize = session.chunks.values.sumOf { it.size }
            val fullConcatenated = ByteArray(totalBytesSize)
            var offset = 0
            for (i in 0 until session.totalChunks) {
                val chunk = session.chunks[i] ?: continue
                System.arraycopy(chunk, 0, fullConcatenated, offset, chunk.size)
                offset += chunk.size
            }

            val finalPlaintext = if (session.gridCols > 1 && session.gridRows > 1 &&
                session.paddedTileByteLengths.isNotEmpty() &&
                session.imageWidthPx > 0 && session.imageHeightPx > 0
            ) {
                stitchTiledImageInMemory(fullConcatenated, session)
            } else {
                fullConcatenated
            }

            // ENCRYPT AT REST (Phase P7 / P9 parity): Zero plaintext on persistent disk
            encryptAndWriteMediaFile(
                fileId = safeName,
                plaintextBytes = finalPlaintext,
                destinationFile = targetFile
            )

            val label = when (session.mediaTypeName) {
                "IMAGE" -> "Image Received (${session.gridCols}x${session.gridRows} Tiled)" + if (session.caption.isNotBlank()) ": ${session.caption}" else ""
                "VOICE" -> "Voice Note Received (${session.durationMs / 1000}s)"
                "FILE" -> "File Received: $safeName (${session.totalSizeBytes / 1024} KB)"
                else -> "Media: $safeName"
            }

            val updatedMsg = DesktopMessage(
                messageId = session.mediaId.toString(),
                senderNodeId = session.senderId,
                recipientNodeId = session.recipientId,
                text = label,
                timestamp = session.lastActivityMs / 1000L,
                isIncoming = true,
                isDelivered = true,
                ttlRemaining = 7,
                isChannelBroadcast = isBroadcast,
                channelName = if (isBroadcast) "public" else null,
                mediaType = session.mediaTypeName,
                mediaUri = targetFile.absolutePath,
                mediaSizeBytes = targetFile.length()
            )
            database.insertMessage(updatedMsg)
            _mediaTransfersUpdated.tryEmit(updatedMsg)

            logger.i(TAG, "Reassembled & Encrypted at rest ${session.mediaTypeName} -> ${targetFile.absolutePath} (${targetFile.length()} bytes)")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to assemble and save encrypted media: ${e.message}")
        }
    }

    private fun stitchTiledImageInMemory(
        concatenatedBytes: ByteArray,
        session: InboundMediaSession
    ): ByteArray {
        val imgW = session.imageWidthPx
        val imgH = session.imageHeightPx
        val cols = session.gridCols
        val rows = session.gridRows
        val composite = java.awt.image.BufferedImage(imgW, imgH, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val g2 = composite.createGraphics()

        val baseTileW = imgW / cols
        val baseTileH = imgH / rows

        var byteOffset = 0
        var tileIdx = 0

        for (r in 0 until rows) {
            val y = r * baseTileH
            for (c in 0 until cols) {
                if (tileIdx >= session.paddedTileByteLengths.size) break
                val pLen = session.paddedTileByteLengths[tileIdx]
                if (byteOffset + pLen <= concatenatedBytes.size) {
                    val tileBytes = concatenatedBytes.copyOfRange(byteOffset, byteOffset + pLen)
                    try {
                        val tileImg = ImageIO.read(ByteArrayInputStream(tileBytes))
                        if (tileImg != null) {
                            val x = c * baseTileW
                            g2.drawImage(tileImg, x, y, null)
                        }
                    } catch (_: Exception) {}
                }
                byteOffset += pLen
                tileIdx++
            }
        }
        g2.dispose()

        val baos = ByteArrayOutputStream()
        ImageIO.write(composite, "jpg", baos)
        return baos.toByteArray()
    }

    fun sendMediaFile(
        recipientNodeId: Long,
        file: File,
        mediaType: String,
        caption: String = ""
    ) {
        if (!file.exists() || !file.isFile) return

        // Read and decrypt into memory if encrypted, or read plain if fresh local file
        val fileBytes = if (isMediaFileEncrypted(file)) {
            readAndDecryptMediaFile(file.name, file)
        } else {
            file.readBytes()
        }

        val mediaId = UUID.randomUUID()
        val timestampSec = System.currentTimeMillis() / 1000L
        val isBroadcast = (recipientNodeId == MeshPacket.BROADCAST_RECIPIENT_ID)

        val typeCode = when (mediaType.uppercase()) {
            "IMAGE" -> 0.toByte()
            "VOICE" -> 1.toByte()
            "AVATAR" -> 2.toByte()
            else -> 3.toByte()
        }

        val totalChunks = (fileBytes.size + CHUNK_PAYLOAD_SIZE - 1) / CHUNK_PAYLOAD_SIZE
        val sha256 = MessageDigest.getInstance("SHA-256").digest(fileBytes)
        val fnBytes = file.name.toByteArray(Charsets.UTF_8)
        val captionBytes = caption.toByteArray(Charsets.UTF_8)

        val plainInit = ByteBuffer.allocate(
            8 + 8 + 1 + 1 + 2 + 4 + 4 + 32 + 1 + fnBytes.size + 2 + 0 + 1 + captionBytes.size + 1
        ).apply {
            putLong(mediaId.mostSignificantBits)
            putLong(mediaId.leastSignificantBits)
            put(typeCode)
            put(1.toByte())
            putShort(totalChunks.toShort())
            putInt(fileBytes.size)
            putInt(0)
            put(sha256)
            put(fnBytes.size.toByte())
            if (fnBytes.isNotEmpty()) put(fnBytes)
            putShort(0.toShort())
            put(captionBytes.size.toByte())
            if (captionBytes.isNotEmpty()) put(captionBytes)
            put(0.toByte())
        }.array()

        scope.launch(Dispatchers.IO) {
            val initPacketId = UUID.randomUUID()
            val aadInit = MeshPacket.computeAad(
                type = PacketType.MEDIA_INIT,
                messageId = initPacketId,
                senderId = myNodeId,
                recipientId = recipientNodeId,
                timestamp = timestampSec
            )

            val sessionKey = if (isBroadcast) {
                DesktopCryptoEngine.derivePublicChannelKey()
            } else {
                val peer = database.getPeer(recipientNodeId)
                val peerPubKey = if (peer != null) DesktopCryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) return@launch
                DesktopCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestampSec)
            }

            val encInit = DesktopCryptoEngine.encrypt(plainInit, initPacketId, sessionKey, aadInit)
            val initPacket = MeshPacket(
                type = PacketType.MEDIA_INIT,
                messageId = initPacketId,
                senderId = myNodeId,
                recipientId = recipientNodeId,
                ttl = MeshPacket.DEFAULT_TTL,
                timestamp = timestampSec,
                payload = encInit.ciphertext,
                authTag = encInit.authTag
            )

            val rawInit = MeshPacket.serialize(initPacket)
            if (wifiEngine.isPeerConnected(recipientNodeId)) {
                wifiEngine.sendDirectPacket(recipientNodeId, rawInit)
            } else {
                wifiEngine.broadcastPacket(rawInit)
            }

            delay(40L)

            // Send all Chunks
            for (chunkIdx in 0 until totalChunks) {
                val start = chunkIdx * CHUNK_PAYLOAD_SIZE
                val end = minOf(start + CHUNK_PAYLOAD_SIZE, fileBytes.size)
                val chunkSlice = fileBytes.copyOfRange(start, end)

                val plainChunk = ByteBuffer.allocate(8 + 8 + 2 + chunkSlice.size).apply {
                    putLong(mediaId.mostSignificantBits)
                    putLong(mediaId.leastSignificantBits)
                    putShort(chunkIdx.toShort())
                    put(chunkSlice)
                }.array()

                val chunkPacketId = UUID.randomUUID()
                val aadChunk = MeshPacket.computeAad(
                    type = PacketType.MEDIA_CHUNK,
                    messageId = chunkPacketId,
                    senderId = myNodeId,
                    recipientId = recipientNodeId,
                    timestamp = timestampSec
                )
                val encChunk = DesktopCryptoEngine.encrypt(plainChunk, chunkPacketId, sessionKey, aadChunk)
                val chunkPacket = MeshPacket(
                    type = PacketType.MEDIA_CHUNK,
                    messageId = chunkPacketId,
                    senderId = myNodeId,
                    recipientId = recipientNodeId,
                    ttl = MeshPacket.DEFAULT_TTL,
                    timestamp = timestampSec,
                    payload = encChunk.ciphertext,
                    authTag = encChunk.authTag
                )

                val rawChunk = MeshPacket.serialize(chunkPacket)
                if (wifiEngine.isPeerConnected(recipientNodeId)) {
                    wifiEngine.sendDirectPacket(recipientNodeId, rawChunk)
                } else {
                    wifiEngine.broadcastPacket(rawChunk)
                }
                delay(15L)
            }

            val sentMsg = DesktopMessage(
                messageId = mediaId.toString(),
                senderNodeId = myNodeId,
                recipientNodeId = recipientNodeId,
                text = "Sent $mediaType: ${file.name} (${fileBytes.size / 1024} KB)",
                timestamp = timestampSec,
                isIncoming = false,
                isDelivered = true,
                mediaType = mediaType,
                mediaUri = file.absolutePath,
                mediaSizeBytes = file.length()
            )
            database.insertMessage(sentMsg)
            _mediaTransfersUpdated.tryEmit(sentMsg)
            logger.i(TAG, "Sent all $totalChunks chunks for ${file.name} to 0x${String.format("%016X", recipientNodeId)}")
        }
    }
}

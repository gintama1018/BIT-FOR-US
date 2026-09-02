package com.meshwhisper.desktop.media

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.logging.MeshLogger
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.desktop.db.DesktopDatabase
import com.meshwhisper.desktop.db.DesktopMessage
import com.meshwhisper.desktop.wifi.DesktopWifiEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    val chunks: MutableMap<Int, ByteArray> = ConcurrentHashMap(),
    var lastActivityMs: Long = System.currentTimeMillis()
)

/**
 * Desktop Media Transfer Engine.
 * Handles receiving, chunk reassembly, disk persistence, and sending of Images, Voice Notes, and Files across the Mesh.
 */
class DesktopMediaManager(
    private val myNodeId: Long,
    private val myPrivateKey: ByteArray,
    private val database: DesktopDatabase,
    private val wifiEngine: DesktopWifiEngine,
    private val logger: MeshLogger,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "DesktopMediaManager"
        const val CHUNK_PAYLOAD_SIZE = 400
    }

    private val mediaStorageDir = File(File(System.getProperty("user.home"), ".meshwhisper"), "media").apply {
        if (!exists()) mkdirs()
    }

    private val inboundSessions = ConcurrentHashMap<String, InboundMediaSession>()

    private val _mediaTransfersUpdated = MutableSharedFlow<DesktopMessage>(extraBufferCapacity = 64)
    val mediaTransfersUpdated = _mediaTransfersUpdated.asSharedFlow()

    fun handleMediaInit(packet: MeshPacket, isBroadcast: Boolean) {
        val aad = packet.getAuthenticatedHeaderBytes()
        val plainBytes = try {
            if (isBroadcast) {
                val publicChannelKey = PureCryptoEngine.derivePublicChannelKey()
                PureCryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, publicChannelKey, aad)
            } else {
                val peer = database.getPeer(packet.senderId)
                val peerPubKey = if (peer != null) PureCryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) {
                    logger.w(TAG, "Cannot decrypt MEDIA_INIT from unknown peer 0x${String.format("%016X", packet.senderId)}")
                    return
                }
                val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, packet.timestamp)
                PureCryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
            }
        } catch (e: Exception) {
            logger.e(TAG, "Failed to decrypt MEDIA_INIT from 0x${String.format("%016X", packet.senderId)}: ${e.message}")
            return
        }

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
        if (previewLen > 0 && buffer.remaining() >= previewLen) {
            buffer.position(buffer.position() + previewLen)
        }

        val captionLen = buffer.get().toInt() and 0xFF
        val caption = if (captionLen > 0 && buffer.remaining() >= captionLen) {
            val cBytes = ByteArray(captionLen)
            buffer.get(cBytes)
            String(cBytes, Charsets.UTF_8)
        } else ""

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
            sha256 = sha256
        )
        inboundSessions[sessionKey] = session

        logger.i(TAG, "📥 Incoming $typeName transfer: $mediaId ($totalChunks chunks, $totalSizeBytes bytes) from 0x${String.format("%016X", packet.senderId)}")

        val textLabel = when (typeName) {
            "IMAGE" -> "📷 Image Transfer (0/$totalChunks)"
            "VOICE" -> "🎙️ Voice Note (${durationMs / 1000}s) (0/$totalChunks)"
            "FILE" -> "📁 File: $originalFileName (0/$totalChunks)"
            else -> "📦 Media: $originalFileName (0/$totalChunks)"
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

    fun handleMediaChunk(packet: MeshPacket, isBroadcast: Boolean) {
        val aad = packet.getAuthenticatedHeaderBytes()
        val plainBytes = try {
            if (isBroadcast) {
                val publicChannelKey = PureCryptoEngine.derivePublicChannelKey()
                PureCryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, publicChannelKey, aad)
            } else {
                val peer = database.getPeer(packet.senderId)
                val peerPubKey = if (peer != null) PureCryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) return
                val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, packet.timestamp)
                PureCryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
            }
        } catch (e: Exception) {
            return
        }

        if (plainBytes.size < 18) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaIdMost = buffer.getLong()
        val mediaIdLeast = buffer.getLong()
        val mediaId = UUID(mediaIdMost, mediaIdLeast)
        val chunkIndex = buffer.getShort().toInt() and 0xFFFF
        val chunkData = ByteArray(buffer.remaining())
        buffer.get(chunkData)

        val sessionKey = "${packet.senderId}_$mediaId"
        val session = inboundSessions[sessionKey] ?: return
        session.lastActivityMs = System.currentTimeMillis()
        session.chunks[chunkIndex] = chunkData

        val received = session.chunks.size
        if (received % 10 == 0 || received == session.totalChunks) {
            logger.i(TAG, "📦 Received chunk $received/${session.totalChunks} for ${session.mediaTypeName} $mediaId")
        }

        if (received >= session.totalChunks) {
            // Reassemble complete media file
            assembleAndSaveMedia(session, isBroadcast)
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
            FileOutputStream(targetFile).use { fos ->
                for (i in 0 until session.totalChunks) {
                    val data = session.chunks[i]
                    if (data != null) {
                        fos.write(data)
                    }
                }
            }

            val label = when (session.mediaTypeName) {
                "IMAGE" -> "📷 Image Received (${session.totalSizeBytes / 1024} KB)" + if (session.caption.isNotBlank()) ": ${session.caption}" else ""
                "VOICE" -> "🎙️ Voice Note Received (${session.durationMs / 1000}s)"
                "FILE" -> "📁 File Received: $safeName (${session.totalSizeBytes / 1024} KB)"
                else -> "📦 Media: $safeName"
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
                mediaSizeBytes = session.totalSizeBytes.toLong()
            )
            database.insertMessage(updatedMsg)
            _mediaTransfersUpdated.tryEmit(updatedMsg)

            logger.i(TAG, "✅ Reassembled & Saved ${session.mediaTypeName} -> ${targetFile.absolutePath}")
        } catch (e: Exception) {
            logger.e(TAG, "Failed to save reassembled media: ${e.message}")
        }
    }

    fun sendMediaFile(
        recipientNodeId: Long,
        file: File,
        mediaType: String,
        caption: String = ""
    ) {
        if (!file.exists() || !file.isFile) return
        val fileBytes = file.readBytes()
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
            put(1.toByte()) // version
            putShort(totalChunks.toShort())
            putInt(fileBytes.size)
            putInt(0) // durationMs
            put(sha256)
            put(fnBytes.size.toByte())
            if (fnBytes.isNotEmpty()) put(fnBytes)
            putShort(0.toShort()) // preview len = 0
            put(captionBytes.size.toByte())
            if (captionBytes.isNotEmpty()) put(captionBytes)
            put(0.toByte()) // tile count = 0
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
                PureCryptoEngine.derivePublicChannelKey()
            } else {
                val peer = database.getPeer(recipientNodeId)
                val peerPubKey = if (peer != null) PureCryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) return@launch
                PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPubKey, timestampSec)
            }

            val encInit = PureCryptoEngine.encrypt(plainInit, initPacketId, sessionKey, aadInit)
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
                val encChunk = PureCryptoEngine.encrypt(plainChunk, chunkPacketId, sessionKey, aadChunk)
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
                text = "📁 Sent $mediaType: ${file.name} (${fileBytes.size / 1024} KB)",
                timestamp = timestampSec,
                isIncoming = false,
                isDelivered = true,
                mediaType = mediaType,
                mediaUri = file.absolutePath,
                mediaSizeBytes = fileBytes.size.toLong()
            )
            database.insertMessage(sentMsg)
            _mediaTransfersUpdated.tryEmit(sentMsg)
            logger.i(TAG, "🚀 Sent all $totalChunks chunks for ${file.name} to 0x${String.format("%016X", recipientNodeId)}")
        }
    }
}

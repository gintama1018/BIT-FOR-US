package com.meshwhisper.app.media

import android.content.Context
import android.util.Log
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil

data class MediaTransferProgress(
    val mediaId: UUID,
    val isOutgoing: Boolean,
    val chunksCompleted: Int,
    val totalChunks: Int,
    val progress: Float
)

class MediaTransferManager(
    private val context: Context,
    private val database: MeshDatabase,
    private val cryptoEngine: CryptoEngine,
    private val packetBroadcaster: suspend (ByteArray) -> Unit,
    private val ackSender: (recipientId: Long, messageId: UUID) -> Unit
) {
    private val tag = "MediaTransferManager"
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        android.util.Log.e(tag, "Uncaught coroutine exception in MediaTransferManager: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val outboundMutex = Mutex() // Strict 1 concurrent outbound media transfer cap

    private val _transferProgress = MutableSharedFlow<MediaTransferProgress>(extraBufferCapacity = 64)
    val transferProgress: SharedFlow<MediaTransferProgress> = _transferProgress.asSharedFlow()

    // Inbound Reassembly Buffer
    private class InboundMediaSession(
        val mediaId: UUID,
        val mediaType: MediaType,
        val totalChunks: Int,
        val totalSizeBytes: Int,
        val durationMs: Long,
        val caption: String,
        val senderId: Long,
        val recipientId: Long,
        val isBroadcast: Boolean,
        val timestamp: Long
    ) {
        var lastActivityMs: Long = System.currentTimeMillis()
        val chunks = ConcurrentHashMap<Int, ByteArray>()
    }

    private val inboundSessions = ConcurrentHashMap<String, InboundMediaSession>()

    init {
        // Ensure private media directory exists
        val mediaDir = File(context.filesDir, "media")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }

        // Periodic cleanup for stale/incomplete inbound media sessions (60s inactivity timeout)
        scope.launch {
            while (isActive) {
                delay(15_000L)
                val now = System.currentTimeMillis()
                val iterator = inboundSessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    if (now - session.lastActivityMs > 60_000L) {
                        iterator.remove()
                        Log.w(tag, "Inbound media transfer ${session.mediaId} timed out after 60s without chunks")
                        try {
                            database.messageDao().updateStatus(session.mediaId.toString(), MessageStatus.FAILED)
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to update timed out media status: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // 1. OUTBOUND MEDIA TRANSMISSION
    // =========================================================================

    suspend fun sendMedia(
        recipientNodeId: Long,
        mediaType: MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L
    ): String = outboundMutex.withLock {
        val mediaId = UUID.randomUUID()
        val isBroadcast = (recipientNodeId == MeshPacket.BROADCAST_RECIPIENT_ID)
        val timestampSec = System.currentTimeMillis() / 1000L

        // Save local copy in app-private storage
        val ext = when (mediaType) {
            MediaType.IMAGE -> "jpg"
            MediaType.VOICE -> "m4a"
            MediaType.AVATAR -> "jpg"
            MediaType.NONE -> "bin"
        }
        val localDir = if (mediaType == MediaType.AVATAR) {
            File(context.filesDir, "avatars").also { if (!it.exists()) it.mkdirs() }
        } else {
            File(context.filesDir, "media").also { if (!it.exists()) it.mkdirs() }
        }
        val localFile = File(localDir, "${mediaId}.$ext")
        localFile.writeBytes(mediaBytes)

        val totalChunks = ceil(mediaBytes.size.toDouble() / MeshPacket.CHUNK_PAYLOAD_SIZE).toInt()
        val captionBytes = caption.toByteArray(Charsets.UTF_8).take(255).toByteArray()

        // 1. Insert local DB record as PENDING (only for chat media, not avatar transfers)
        if (mediaType != MediaType.AVATAR) {
            val message = MessageEntity(
                messageId = mediaId.toString(),
                senderId = cryptoEngine.nodeId,
                recipientId = recipientNodeId,
                senderAlias = cryptoEngine.alias,
                text = caption.ifBlank { if (mediaType == MediaType.IMAGE) "📷 Photo" else "🎤 Voice note" },
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isBroadcast = isBroadcast,
                status = MessageStatus.PENDING,
                mediaType = mediaType,
                mediaUri = localFile.absolutePath,
                mediaSizeBytes = mediaBytes.size.toLong(),
                mediaProgress = 0.0f,
                mediaDurationMs = durationMs
            )
            database.messageDao().insert(message)
        }

        // 2. Build and Send MEDIA_INIT packet (fresh packet.messageId for unique AES-GCM IV)
        val typeByte = when (mediaType) {
            MediaType.IMAGE -> 0.toByte()
            MediaType.VOICE -> 1.toByte()
            MediaType.AVATAR -> 2.toByte()
            MediaType.NONE -> 0.toByte()
        }
        val initPayloadSize = 16 + 1 + 2 + 4 + 4 + 1 + captionBytes.size
        val initBuffer = ByteBuffer.allocate(initPayloadSize).order(ByteOrder.BIG_ENDIAN)
        initBuffer.putLong(mediaId.mostSignificantBits)
        initBuffer.putLong(mediaId.leastSignificantBits)
        initBuffer.put(typeByte)
        initBuffer.putShort((totalChunks and 0xFFFF).toShort())
        initBuffer.putInt(mediaBytes.size)
        initBuffer.putInt(durationMs.toInt())
        initBuffer.put((captionBytes.size and 0xFF).toByte())
        initBuffer.put(captionBytes)

        val plainInit = initBuffer.array()
        val initPacketId = UUID.randomUUID() // Fresh unique IV/nonce per packet!

        val aadInit = MeshPacket.computeAad(
            type = PacketType.MEDIA_INIT,
            messageId = initPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestampSec
        )

        val encryptedInit = if (isBroadcast) {
            cryptoEngine.encrypt(plainInit, initPacketId, cryptoEngine.publicChannelKey, aadInit)
        } else {
            val peer = database.peerDao().getPeerById(recipientNodeId)
            val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else null
            if (peerPubKey == null) {
                Log.e(tag, "Cannot send media to unknown peer $recipientNodeId")
                database.messageDao().updateStatus(mediaId.toString(), MessageStatus.FAILED)
                return@withLock ""
            }
            val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)
            cryptoEngine.encrypt(plainInit, initPacketId, sessionKey, aadInit)
        }

        val initPacket = MeshPacket(
            type = PacketType.MEDIA_INIT,
            messageId = initPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestampSec,
            payload = encryptedInit.ciphertext,
            authTag = encryptedInit.authTag
        )

        packetBroadcaster(MeshPacket.serialize(initPacket))
        delay(70L) // Paced transmission

        // 3. Send MEDIA_CHUNK packets with pacing & lower TTL (fresh packet.messageId per chunk!)
        val peerPubKey = if (!isBroadcast) {
            val p = database.peerDao().getPeerById(recipientNodeId)
            if (p != null) CryptoEngine.hexToBytes(p.publicKeyHex) else null
        } else null

        val sessionKey = if (!isBroadcast && peerPubKey != null) {
            cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)
        } else null

        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * MeshPacket.CHUNK_PAYLOAD_SIZE
            val end = minOf(start + MeshPacket.CHUNK_PAYLOAD_SIZE, mediaBytes.size)
            val chunkSlice = mediaBytes.copyOfRange(start, end)

            val chunkBuffer = ByteBuffer.allocate(16 + 2 + chunkSlice.size).order(ByteOrder.BIG_ENDIAN)
            chunkBuffer.putLong(mediaId.mostSignificantBits)
            chunkBuffer.putLong(mediaId.leastSignificantBits)
            chunkBuffer.putShort((chunkIndex and 0xFFFF).toShort())
            chunkBuffer.put(chunkSlice)

            val plainChunk = chunkBuffer.array()
            val chunkPacketId = UUID.randomUUID() // Fresh unique IV/nonce per chunk packet!

            val aadChunk = MeshPacket.computeAad(
                type = PacketType.MEDIA_CHUNK,
                messageId = chunkPacketId,
                senderId = cryptoEngine.nodeId,
                recipientId = recipientNodeId,
                timestamp = timestampSec
            )

            val encryptedChunk = if (isBroadcast) {
                cryptoEngine.encrypt(plainChunk, chunkPacketId, cryptoEngine.publicChannelKey, aadChunk)
            } else {
                if (sessionKey == null) {
                    database.messageDao().updateStatus(mediaId.toString(), MessageStatus.FAILED)
                    return@withLock ""
                }
                cryptoEngine.encrypt(plainChunk, chunkPacketId, sessionKey, aadChunk)
            }

            val chunkPacket = MeshPacket(
                type = PacketType.MEDIA_CHUNK,
                messageId = chunkPacketId,
                senderId = cryptoEngine.nodeId,
                recipientId = recipientNodeId,
                ttl = MeshPacket.MEDIA_TTL, // Lower TTL specifically for media chunks to protect mesh
                timestamp = timestampSec,
                payload = encryptedChunk.ciphertext,
                authTag = encryptedChunk.authTag
            )

            packetBroadcaster(MeshPacket.serialize(chunkPacket))

            val progress = (chunkIndex + 1).toFloat() / totalChunks
            if (mediaType != MediaType.AVATAR) {
                database.messageDao().updateMediaTransfer(
                    messageId = mediaId.toString(),
                    progress = progress,
                    mediaUri = localFile.absolutePath,
                    status = if (chunkIndex == totalChunks - 1) MessageStatus.SENT else MessageStatus.PENDING
                )
            }

            _transferProgress.emit(
                MediaTransferProgress(
                    mediaId = mediaId,
                    isOutgoing = true,
                    chunksCompleted = chunkIndex + 1,
                    totalChunks = totalChunks,
                    progress = progress
                )
            )

            delay(70L) // Paced inter-chunk gap
        }

        mediaId.toString()
    }

    // =========================================================================
    // 2. INBOUND MEDIA REASSEMBLY
    // =========================================================================

    suspend fun handleMediaInit(
        packet: MeshPacket,
        senderAlias: String,
        isBroadcast: Boolean
    ) {
        val aad = packet.getAuthenticatedHeaderBytes()
        val plainBytes = try {
            if (isBroadcast) {
                cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, cryptoEngine.publicChannelKey, aad)
            } else {
                val peer = database.peerDao().getPeerById(packet.senderId)
                val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) {
                    Log.w(tag, "Cannot decrypt MEDIA_INIT from unknown peer ${packet.senderId}")
                    return
                }
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
                cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt MEDIA_INIT from sender ${packet.senderId}", e)
            return
        }

        if (plainBytes.size < 31) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaIdMost = buffer.getLong()
        val mediaIdLeast = buffer.getLong()
        val mediaId = UUID(mediaIdMost, mediaIdLeast)
        val typeCode = buffer.get()
        val mediaType = when (typeCode) {
            0.toByte() -> MediaType.IMAGE
            1.toByte() -> MediaType.VOICE
            2.toByte() -> MediaType.AVATAR
            else -> MediaType.IMAGE
        }
        val totalChunks = buffer.getShort().toInt() and 0xFFFF
        val totalSizeBytes = buffer.getInt()
        val durationMs = buffer.getInt().toLong()
        val captionLen = buffer.get().toInt() and 0xFF

        val caption = if (captionLen > 0 && buffer.remaining() >= captionLen) {
            val cBytes = ByteArray(captionLen)
            buffer.get(cBytes)
            String(cBytes, Charsets.UTF_8)
        } else {
            ""
        }

        val sessionKey = "${packet.senderId}_$mediaId"
        val session = InboundMediaSession(
            mediaId = mediaId,
            mediaType = mediaType,
            totalChunks = totalChunks,
            totalSizeBytes = totalSizeBytes,
            durationMs = durationMs,
            caption = caption,
            senderId = packet.senderId,
            recipientId = packet.recipientId,
            isBroadcast = isBroadcast,
            timestamp = packet.timestamp * 1000L
        )
        inboundSessions[sessionKey] = session

        // Insert placeholder message in Room (only for chat media, not avatar transfers)
        if (mediaType != MediaType.AVATAR) {
            val placeholder = MessageEntity(
                messageId = mediaId.toString(),
                senderId = packet.senderId,
                recipientId = packet.recipientId,
                senderAlias = senderAlias,
                text = caption.ifBlank { if (mediaType == MediaType.IMAGE) "📷 Photo" else "🎤 Voice note" },
                timestamp = packet.timestamp * 1000L,
                isOutgoing = false,
                isBroadcast = isBroadcast,
                status = MessageStatus.PENDING,
                mediaType = mediaType,
                mediaUri = null,
                mediaSizeBytes = totalSizeBytes.toLong(),
                mediaProgress = 0.0f,
                mediaDurationMs = durationMs
            )
            database.messageDao().insert(placeholder)
        }
        Log.d(tag, "Received MEDIA_INIT: $mediaId ($mediaType, $totalChunks chunks, $totalSizeBytes bytes)")
    }

    suspend fun handleMediaChunk(
        packet: MeshPacket,
        isBroadcast: Boolean
    ) {
        val aad = packet.getAuthenticatedHeaderBytes()
        val plainBytes = try {
            if (isBroadcast) {
                cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, cryptoEngine.publicChannelKey, aad)
            } else {
                val peer = database.peerDao().getPeerById(packet.senderId)
                val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else null
                if (peerPubKey == null) {
                    Log.w(tag, "Cannot decrypt MEDIA_CHUNK from unknown peer ${packet.senderId}")
                    return
                }
                val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
                cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to decrypt MEDIA_CHUNK from sender ${packet.senderId}", e)
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
        val receivedCount = session.chunks.size
        val progress = receivedCount.toFloat() / session.totalChunks

        _transferProgress.emit(
            MediaTransferProgress(
                mediaId = mediaId,
                isOutgoing = false,
                chunksCompleted = receivedCount,
                totalChunks = session.totalChunks,
                progress = progress
            )
        )

        // If not all chunks received yet, update progress in DB
        if (receivedCount < session.totalChunks) {
            if (session.mediaType != MediaType.AVATAR) {
                database.messageDao().updateMediaTransfer(
                    messageId = mediaId.toString(),
                    progress = progress,
                    mediaUri = null,
                    status = MessageStatus.PENDING
                )
            }
            return
        }

        // All chunks received! Reassemble file in order
        inboundSessions.remove(sessionKey)
        val isAvatar = (session.mediaType == MediaType.AVATAR)
        val destDir = if (isAvatar) {
            File(context.filesDir, "avatars").also { if (!it.exists()) it.mkdirs() }
        } else {
            File(context.filesDir, "media").also { if (!it.exists()) it.mkdirs() }
        }
        val ext = if (session.mediaType == MediaType.VOICE) "m4a" else "jpg"
        val fileName = if (isAvatar) "avatar_${session.senderId}.$ext" else "${mediaId}.$ext"
        val destFile = File(destDir, fileName)

        try {
            FileOutputStream(destFile).use { fos ->
                for (i in 0 until session.totalChunks) {
                    val piece = session.chunks[i] ?: continue
                    fos.write(piece)
                }
            }

            if (isAvatar) {
                val fileBytes = destFile.readBytes()
                val avatarHash = (fileBytes.fold(0) { acc, b -> (acc * 31 + b.toInt()) } and 0xFF).toByte()
                database.peerDao().updateAvatar(session.senderId, destFile.absolutePath, avatarHash)
                Log.i(tag, "Successfully updated peer ${session.senderId} avatar: ${destFile.absolutePath} (hash=$avatarHash)")
            } else {
                database.messageDao().updateMediaTransfer(
                    messageId = mediaId.toString(),
                    progress = 1.0f,
                    mediaUri = destFile.absolutePath,
                    status = MessageStatus.DELIVERED
                )
                Log.i(tag, "Successfully reassembled media $mediaId to: ${destFile.absolutePath}")
            }

            // Send delivery ACK back to sender for private DMs
            if (!session.isBroadcast) {
                ackSender(session.senderId, mediaId)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to write reassembled media file to disk", e)
        }
    }
}

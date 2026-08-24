package com.meshwhisper.app.media

import android.content.Context
import android.util.Base64
import android.util.Log
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.app.data.MeshDatabase
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

enum class TransferState {
    QUEUED,
    SENDING,
    RECEIVING,
    RECOVERING,
    VERIFYING,
    COMPLETE,
    FAILED,
    CANCELLED
}

data class TransferStateInfo(
    val mediaId: UUID,
    val state: TransferState,
    val isOutgoing: Boolean,
    val chunksCompleted: Int,
    val totalChunks: Int,
    val bytesCompleted: Long,
    val totalBytes: Long,
    val etaSeconds: Long,
    val error: String? = null
)

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
    private val ackSender: (recipientId: Long, messageId: UUID) -> Unit,
    private val isDirectPeer: (nodeId: Long) -> Boolean = { true }
) {
    private val tag = "MediaTransferManager"
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught exception in MediaTransferManager: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val outboundMutex = Mutex() // Global single outbound transfer cap

    private val _transferProgress = MutableSharedFlow<MediaTransferProgress>(extraBufferCapacity = 64)
    val transferProgress: SharedFlow<MediaTransferProgress> = _transferProgress.asSharedFlow()

    private val _transferStates = MutableStateFlow<Map<UUID, TransferStateInfo>>(emptyMap())
    val transferStates: StateFlow<Map<UUID, TransferStateInfo>> = _transferStates.asStateFlow()

    // Active Outbound Transfer Session Tracker
    private class OutboundMediaSession(
        val mediaId: UUID,
        val recipientNodeId: Long,
        val mediaType: MediaType,
        val mediaBytes: ByteArray,
        val totalChunks: Int,
        val sha256: ByteArray,
        val isBroadcast: Boolean
    ) {
        val isCancelled = AtomicBoolean(false)
        var nackRetryCount: Int = 0
        val ackDeferred = CompletableDeferred<Boolean>()
        val throughputTracker = mutableListOf<Pair<Long, Long>>() // timestampMs -> cumulativeBytes
    }

    private val activeOutboundSessions = ConcurrentHashMap<UUID, OutboundMediaSession>()

    // Active Inbound Reassembly Session Tracker
    private class InboundMediaSession(
        val mediaId: UUID,
        val mediaType: MediaType,
        val totalChunks: Int,
        val totalSizeBytes: Int,
        val durationMs: Long,
        val sha256: ByteArray,
        val originalFileName: String,
        val previewBytes: ByteArray,
        val caption: String,
        val senderId: Long,
        val recipientId: Long,
        val isBroadcast: Boolean,
        val timestamp: Long
    ) {
        var lastActivityMs: Long = System.currentTimeMillis()
        var nackRoundCount: Int = 0
        val chunks = ConcurrentHashMap<Int, ByteArray>()
        val throughputTracker = mutableListOf<Pair<Long, Long>>() // timestampMs -> cumulativeBytes
    }

    private val inboundSessions = ConcurrentHashMap<String, InboundMediaSession>()

    init {
        // Ensure private media directory exists
        val mediaDir = File(context.filesDir, "media")
        if (!mediaDir.exists()) {
            mediaDir.mkdirs()
        }
        val avatarDir = File(context.filesDir, "avatars")
        if (!avatarDir.exists()) {
            avatarDir.mkdirs()
        }

        // Periodic watchdog: NACK generator for incomplete inbound sessions & timeout cleanup
        scope.launch {
            while (isActive) {
                delay(2500L)
                val now = System.currentTimeMillis()
                val iterator = inboundSessions.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    val session = entry.value
                    val isInactive = (now - session.lastActivityMs > 3000L)

                    // 1. Single-hop direct inbound NACK selective retransmission trigger
                    if (!session.isBroadcast && isInactive && session.chunks.size < session.totalChunks) {
                        if (session.nackRoundCount < 5) {
                            session.nackRoundCount += 1
                            session.lastActivityMs = now
                            val missingIndices = (0 until session.totalChunks).filter { !session.chunks.containsKey(it) }
                            if (missingIndices.isNotEmpty()) {
                                Log.w(tag, "Inbound transfer ${session.mediaId} missing ${missingIndices.size} chunks (round ${session.nackRoundCount}/5). Emitting MEDIA_NACK...")
                                updateTransferState(
                                    session.mediaId,
                                    TransferState.RECOVERING,
                                    false,
                                    session.chunks.size,
                                    session.totalChunks,
                                    (session.chunks.size * MeshPacket.CHUNK_PAYLOAD_SIZE).toLong(),
                                    session.totalSizeBytes.toLong(),
                                    0L
                                )
                                sendNackPacket(session.senderId, session.mediaId, missingIndices)
                            }
                        } else {
                            // Max retries exceeded
                            iterator.remove()
                            Log.e(tag, "Inbound media transfer ${session.mediaId} failed after 5 NACK retry rounds")
                            sendAbortPacket(session.senderId, session.mediaId)
                            updateTransferState(
                                session.mediaId,
                                TransferState.FAILED,
                                false,
                                session.chunks.size,
                                session.totalChunks,
                                0L,
                                session.totalSizeBytes.toLong(),
                                0L,
                                "Transfer failed after 5 retry rounds"
                            )
                            database.messageDao().updateStatus(session.mediaId.toString(), MessageStatus.FAILED)
                        }
                    }

                    // 2. Cleanup stale sessions (60s total inactivity)
                    if (now - session.lastActivityMs > 60_000L) {
                        iterator.remove()
                        Log.w(tag, "Inbound media transfer ${session.mediaId} timed out after 60s")
                        updateTransferState(
                            session.mediaId,
                            TransferState.FAILED,
                            false,
                            session.chunks.size,
                            session.totalChunks,
                            0L,
                            session.totalSizeBytes.toLong(),
                            0L,
                            "Transfer timed out"
                        )
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

    private fun updateTransferState(
        mediaId: UUID,
        state: TransferState,
        isOutgoing: Boolean,
        chunksCompleted: Int,
        totalChunks: Int,
        bytesCompleted: Long,
        totalBytes: Long,
        etaSeconds: Long,
        error: String? = null
    ) {
        val map = _transferStates.value.toMutableMap()
        map[mediaId] = TransferStateInfo(
            mediaId = mediaId,
            state = state,
            isOutgoing = isOutgoing,
            chunksCompleted = chunksCompleted,
            totalChunks = totalChunks,
            bytesCompleted = bytesCompleted,
            totalBytes = totalBytes,
            etaSeconds = etaSeconds,
            error = error
        )
        _transferStates.value = map
    }

    private fun calculateEta(
        tracker: MutableList<Pair<Long, Long>>,
        currentBytes: Long,
        totalBytes: Long
    ): Long {
        val now = System.currentTimeMillis()
        tracker.add(now to currentBytes)
        tracker.removeAll { now - it.first > 5000L }
        if (tracker.size < 2) return 0L
        val oldest = tracker.first()
        val durationSec = (now - oldest.first) / 1000.0
        if (durationSec <= 0.2) return 0L
        val bytesDelta = currentBytes - oldest.second
        val throughputBps = bytesDelta / durationSec
        if (throughputBps <= 0) return 0L
        val remainingBytes = maxOf(0L, totalBytes - currentBytes)
        return (remainingBytes / throughputBps).toLong()
    }

    // =========================================================================
    // 1. OUTBOUND MEDIA TRANSMISSION
    // =========================================================================

    suspend fun sendMedia(
        recipientNodeId: Long,
        mediaType: MediaType,
        mediaBytes: ByteArray,
        caption: String = "",
        durationMs: Long = 0L,
        originalFileName: String = "",
        previewBytes: ByteArray = ByteArray(0)
    ): String = outboundMutex.withLock {
        val mediaId = UUID.randomUUID()
        val isBroadcast = (recipientNodeId == MeshPacket.BROADCAST_RECIPIENT_ID)
        val timestampSec = System.currentTimeMillis() / 1000L

        // Ground-Truth Single-Hop Enforcement for direct 1-to-1 media
        if (!isBroadcast && !isDirectPeer(recipientNodeId)) {
            Log.e(tag, "Blocked direct media send: peer $recipientNodeId has no active direct BLE GATT connection")
            val message = MessageEntity(
                messageId = mediaId.toString(),
                senderId = cryptoEngine.nodeId,
                recipientId = recipientNodeId,
                senderAlias = cryptoEngine.alias,
                text = caption.ifBlank { defaultLabelFor(mediaType, originalFileName) },
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isBroadcast = false,
                status = MessageStatus.FAILED,
                mediaType = mediaType,
                mediaUri = null,
                mediaSizeBytes = mediaBytes.size.toLong(),
                mediaProgress = 0.0f,
                mediaDurationMs = durationMs,
                originalFileName = originalFileName.ifBlank { null }
            )
            database.messageDao().insert(message)
            updateTransferState(
                mediaId,
                TransferState.FAILED,
                true,
                0,
                0,
                0L,
                mediaBytes.size.toLong(),
                0L,
                "Direct BLE connection required for media transfer"
            )
            return@withLock ""
        }

        // Save local copy in app-private storage
        val ext = when (mediaType) {
            MediaType.IMAGE -> "jpg"
            MediaType.VOICE -> "m4a"
            MediaType.AVATAR -> "jpg"
            MediaType.FILE -> if (originalFileName.contains(".")) originalFileName.substringAfterLast(".") else "bin"
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
        val fileNameBytes = originalFileName.toByteArray(Charsets.UTF_8).take(128).toByteArray()
        val cappedPreviewBytes = previewBytes.take(350).toByteArray()
        val sha256 = MediaCompressor.computeSha256(mediaBytes)
        val previewBase64 = if (cappedPreviewBytes.isNotEmpty()) Base64.encodeToString(cappedPreviewBytes, Base64.NO_WRAP) else null

        // 1. Insert local DB record as PENDING (only for chat media, not avatar transfers)
        if (mediaType != MediaType.AVATAR) {
            val message = MessageEntity(
                messageId = mediaId.toString(),
                senderId = cryptoEngine.nodeId,
                recipientId = recipientNodeId,
                senderAlias = cryptoEngine.alias,
                text = caption.ifBlank { defaultLabelFor(mediaType, originalFileName) },
                timestamp = System.currentTimeMillis(),
                isOutgoing = true,
                isBroadcast = isBroadcast,
                status = MessageStatus.PENDING,
                mediaType = mediaType,
                mediaUri = localFile.absolutePath,
                mediaSizeBytes = mediaBytes.size.toLong(),
                mediaProgress = 0.0f,
                mediaDurationMs = durationMs,
                originalFileName = originalFileName.ifBlank { null },
                mediaPreviewBase64 = previewBase64
            )
            database.messageDao().insert(message)
        }

        updateTransferState(
            mediaId = mediaId,
            state = TransferState.SENDING,
            isOutgoing = true,
            chunksCompleted = 0,
            totalChunks = totalChunks,
            bytesCompleted = 0L,
            totalBytes = mediaBytes.size.toLong(),
            etaSeconds = 0L
        )

        val session = OutboundMediaSession(
            mediaId = mediaId,
            recipientNodeId = recipientNodeId,
            mediaType = mediaType,
            mediaBytes = mediaBytes,
            totalChunks = totalChunks,
            sha256 = sha256,
            isBroadcast = isBroadcast
        )
        activeOutboundSessions[mediaId] = session

        // 2. Build and Send extended MEDIA_INIT packet
        val typeByte = when (mediaType) {
            MediaType.IMAGE -> 0.toByte()
            MediaType.VOICE -> 1.toByte()
            MediaType.AVATAR -> 2.toByte()
            MediaType.FILE -> 3.toByte()
            MediaType.NONE -> 0.toByte()
        }
        val mediaVersionByte = 1.toByte()

        val initPayloadSize = 16 + 1 + 1 + 2 + 4 + 4 + 32 + 1 + fileNameBytes.size + 2 + cappedPreviewBytes.size + 1 + captionBytes.size
        val initBuffer = ByteBuffer.allocate(initPayloadSize).order(ByteOrder.BIG_ENDIAN)
        initBuffer.putLong(mediaId.mostSignificantBits)
        initBuffer.putLong(mediaId.leastSignificantBits)
        initBuffer.put(typeByte)
        initBuffer.put(mediaVersionByte)
        initBuffer.putShort((totalChunks and 0xFFFF).toShort())
        initBuffer.putInt(mediaBytes.size)
        initBuffer.putInt(durationMs.toInt())
        initBuffer.put(sha256)
        initBuffer.put((fileNameBytes.size and 0xFF).toByte())
        initBuffer.put(fileNameBytes)
        initBuffer.putShort((cappedPreviewBytes.size and 0xFFFF).toShort())
        initBuffer.put(cappedPreviewBytes)
        initBuffer.put((captionBytes.size and 0xFF).toByte())
        initBuffer.put(captionBytes)

        val plainInit = initBuffer.array()
        val initPacketId = UUID.randomUUID()

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
                activeOutboundSessions.remove(mediaId)
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
            ttl = if (isBroadcast) MeshPacket.MEDIA_TTL else MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = timestampSec,
            payload = encryptedInit.ciphertext,
            authTag = encryptedInit.authTag
        )

        packetBroadcaster(MeshPacket.serialize(initPacket))
        delay(70L)

        // 3. Send MEDIA_CHUNK packets with pacing
        val peerPubKey = if (!isBroadcast) {
            val p = database.peerDao().getPeerById(recipientNodeId)
            if (p != null) CryptoEngine.hexToBytes(p.publicKeyHex) else null
        } else null

        val sessionKey = if (!isBroadcast && peerPubKey != null) {
            cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)
        } else null

        for (chunkIndex in 0 until totalChunks) {
            if (session.isCancelled.get()) {
                Log.w(tag, "Outbound media send cancelled for $mediaId")
                activeOutboundSessions.remove(mediaId)
                updateTransferState(
                    mediaId,
                    TransferState.CANCELLED,
                    true,
                    chunkIndex,
                    totalChunks,
                    (chunkIndex * MeshPacket.CHUNK_PAYLOAD_SIZE).toLong(),
                    mediaBytes.size.toLong(),
                    0L
                )
                return@withLock mediaId.toString()
            }

            sendChunk(session, chunkIndex, recipientNodeId, timestampSec, isBroadcast, sessionKey)

            val currentBytes = minOf(((chunkIndex + 1) * MeshPacket.CHUNK_PAYLOAD_SIZE).toLong(), mediaBytes.size.toLong())
            val progress = (chunkIndex + 1).toFloat() / totalChunks
            val eta = calculateEta(session.throughputTracker, currentBytes, mediaBytes.size.toLong())

            if (mediaType != MediaType.AVATAR) {
                database.messageDao().updateMediaTransfer(
                    messageId = mediaId.toString(),
                    progress = progress,
                    mediaUri = localFile.absolutePath,
                    status = if (chunkIndex == totalChunks - 1 && isBroadcast) MessageStatus.SENT else MessageStatus.PENDING
                )
            }

            updateTransferState(
                mediaId = mediaId,
                state = if (chunkIndex == totalChunks - 1 && isBroadcast) TransferState.COMPLETE else TransferState.SENDING,
                isOutgoing = true,
                chunksCompleted = chunkIndex + 1,
                totalChunks = totalChunks,
                bytesCompleted = currentBytes,
                totalBytes = mediaBytes.size.toLong(),
                etaSeconds = eta
            )

            _transferProgress.emit(
                MediaTransferProgress(
                    mediaId = mediaId,
                    isOutgoing = true,
                    chunksCompleted = chunkIndex + 1,
                    totalChunks = totalChunks,
                    progress = progress
                )
            )

            delay(70L)
        }

        if (isBroadcast) {
            activeOutboundSessions.remove(mediaId)
        }

        mediaId.toString()
    }

    private suspend fun sendChunk(
        session: OutboundMediaSession,
        chunkIndex: Int,
        recipientNodeId: Long,
        timestampSec: Long,
        isBroadcast: Boolean,
        sessionKey: ByteArray?
    ) {
        val start = chunkIndex * MeshPacket.CHUNK_PAYLOAD_SIZE
        val end = minOf(start + MeshPacket.CHUNK_PAYLOAD_SIZE, session.mediaBytes.size)
        val chunkSlice = session.mediaBytes.copyOfRange(start, end)

        val chunkBuffer = ByteBuffer.allocate(16 + 2 + chunkSlice.size).order(ByteOrder.BIG_ENDIAN)
        chunkBuffer.putLong(session.mediaId.mostSignificantBits)
        chunkBuffer.putLong(session.mediaId.leastSignificantBits)
        chunkBuffer.putShort((chunkIndex and 0xFFFF).toShort())
        chunkBuffer.put(chunkSlice)

        val plainChunk = chunkBuffer.array()
        val chunkPacketId = UUID.randomUUID()

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
            if (sessionKey == null) return
            cryptoEngine.encrypt(plainChunk, chunkPacketId, sessionKey, aadChunk)
        }

        val chunkPacket = MeshPacket(
            type = PacketType.MEDIA_CHUNK,
            messageId = chunkPacketId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = if (isBroadcast) MeshPacket.MEDIA_TTL else MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = timestampSec,
            payload = encryptedChunk.ciphertext,
            authTag = encryptedChunk.authTag
        )

        packetBroadcaster(MeshPacket.serialize(chunkPacket))
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

        if (plainBytes.size < 62) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaIdMost = buffer.getLong()
        val mediaIdLeast = buffer.getLong()
        val mediaId = UUID(mediaIdMost, mediaIdLeast)
        val typeCode = buffer.get()
        val mediaType = when (typeCode) {
            0.toByte() -> MediaType.IMAGE
            1.toByte() -> MediaType.VOICE
            2.toByte() -> MediaType.AVATAR
            3.toByte() -> MediaType.FILE
            else -> MediaType.IMAGE
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

        val previewBase64 = if (previewBytes.isNotEmpty()) Base64.encodeToString(previewBytes, Base64.NO_WRAP) else null

        // 4.1 Avatars optimization: check if receiver already has matching hash
        if (mediaType == MediaType.AVATAR) {
            val existingPeer = database.peerDao().getPeerById(packet.senderId)
            if (existingPeer?.avatarUri != null && File(existingPeer.avatarUri).exists()) {
                val currentFileBytes = File(existingPeer.avatarUri).readBytes()
                val currentSha = MediaCompressor.computeSha256(currentFileBytes)
                if (Arrays.equals(currentSha, sha256)) {
                    Log.i(tag, "Avatar for peer ${packet.senderId} already has identical SHA-256; skipping transfer")
                    return
                }
            }
        }

        val sessionKey = "${packet.senderId}_$mediaId"
        val session = InboundMediaSession(
            mediaId = mediaId,
            mediaType = mediaType,
            totalChunks = totalChunks,
            totalSizeBytes = totalSizeBytes,
            durationMs = durationMs,
            sha256 = sha256,
            originalFileName = originalFileName,
            previewBytes = previewBytes,
            caption = caption,
            senderId = packet.senderId,
            recipientId = packet.recipientId,
            isBroadcast = isBroadcast,
            timestamp = packet.timestamp * 1000L
        )
        inboundSessions[sessionKey] = session

        updateTransferState(
            mediaId = mediaId,
            state = TransferState.RECEIVING,
            isOutgoing = false,
            chunksCompleted = 0,
            totalChunks = totalChunks,
            bytesCompleted = 0L,
            totalBytes = totalSizeBytes.toLong(),
            etaSeconds = 0L
        )

        // Insert placeholder message in Room
        if (mediaType != MediaType.AVATAR) {
            val placeholder = MessageEntity(
                messageId = mediaId.toString(),
                senderId = packet.senderId,
                recipientId = packet.recipientId,
                senderAlias = senderAlias,
                text = caption.ifBlank { defaultLabelFor(mediaType, originalFileName) },
                timestamp = packet.timestamp * 1000L,
                isOutgoing = false,
                isBroadcast = isBroadcast,
                status = MessageStatus.PENDING,
                mediaType = mediaType,
                mediaUri = null,
                mediaSizeBytes = totalSizeBytes.toLong(),
                mediaProgress = 0.0f,
                mediaDurationMs = durationMs,
                originalFileName = originalFileName.ifBlank { null },
                mediaPreviewBase64 = previewBase64
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
        val currentBytes = minOf((receivedCount * MeshPacket.CHUNK_PAYLOAD_SIZE).toLong(), session.totalSizeBytes.toLong())
        val progress = receivedCount.toFloat() / session.totalChunks
        val eta = calculateEta(session.throughputTracker, currentBytes, session.totalSizeBytes.toLong())

        updateTransferState(
            mediaId = mediaId,
            state = if (receivedCount < session.totalChunks) TransferState.RECEIVING else TransferState.VERIFYING,
            isOutgoing = false,
            chunksCompleted = receivedCount,
            totalChunks = session.totalChunks,
            bytesCompleted = currentBytes,
            totalBytes = session.totalSizeBytes.toLong(),
            etaSeconds = eta
        )

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

        // All chunks received! 3.7 Final Verification with SHA-256 target hash
        inboundSessions.remove(sessionKey)
        val isAvatar = (session.mediaType == MediaType.AVATAR)
        val destDir = if (isAvatar) {
            File(context.filesDir, "avatars").also { if (!it.exists()) it.mkdirs() }
        } else {
            File(context.filesDir, "media").also { if (!it.exists()) it.mkdirs() }
        }
        val ext = when (session.mediaType) {
            MediaType.VOICE -> "m4a"
            MediaType.IMAGE -> "jpg"
            MediaType.AVATAR -> "jpg"
            MediaType.FILE -> if (session.originalFileName.contains(".")) session.originalFileName.substringAfterLast(".") else "bin"
            MediaType.NONE -> "bin"
        }
        val fileName = if (isAvatar) "avatar_${session.senderId}.$ext" else "${mediaId}.$ext"
        val destFile = File(destDir, fileName)

        try {
            val totalBytesStream = java.io.ByteArrayOutputStream()
            for (i in 0 until session.totalChunks) {
                val piece = session.chunks[i] ?: continue
                totalBytesStream.write(piece)
            }
            val reassembledBytes = totalBytesStream.toByteArray()
            val computedSha256 = MediaCompressor.computeSha256(reassembledBytes)

            if (!Arrays.equals(computedSha256, session.sha256)) {
                Log.e(tag, "SHA-256 verification failed for media $mediaId! Discarding reassembly.")
                if (!session.isBroadcast) {
                    sendNackPacket(session.senderId, session.mediaId, (0 until session.totalChunks).toList())
                }
                updateTransferState(
                    mediaId,
                    TransferState.FAILED,
                    false,
                    0,
                    session.totalChunks,
                    0L,
                    session.totalSizeBytes.toLong(),
                    0L,
                    "Integrity verification failed (SHA-256 mismatch)"
                )
                return
            }

            // Target hash matches! Write verified file to disk
            FileOutputStream(destFile).use { it.write(reassembledBytes) }

            if (isAvatar) {
                val avatarHash = (reassembledBytes.fold(0) { acc, b -> (acc * 31 + b.toInt()) } and 0xFF).toByte()
                database.peerDao().updateAvatar(session.senderId, destFile.absolutePath, avatarHash)
                Log.i(tag, "Verified peer ${session.senderId} avatar: ${destFile.absolutePath} (hash=$avatarHash)")
            } else {
                database.messageDao().updateMediaTransfer(
                    messageId = mediaId.toString(),
                    progress = 1.0f,
                    mediaUri = destFile.absolutePath,
                    status = MessageStatus.DELIVERED
                )
                Log.i(tag, "Verified media reassembly $mediaId to: ${destFile.absolutePath}")
            }

            updateTransferState(
                mediaId,
                TransferState.COMPLETE,
                false,
                session.totalChunks,
                session.totalChunks,
                reassembledBytes.size.toLong(),
                reassembledBytes.size.toLong(),
                0L
            )

            // Send point-to-point MEDIA_ACK
            if (!session.isBroadcast) {
                sendAckPacket(session.senderId, session.mediaId)
                ackSender(session.senderId, mediaId)
            }
        } catch (e: Exception) {
            Log.e(tag, "Failed to write reassembled media file to disk", e)
            updateTransferState(
                mediaId,
                TransferState.FAILED,
                false,
                0,
                session.totalChunks,
                0L,
                session.totalSizeBytes.toLong(),
                0L,
                "Disk write error"
            )
        }
    }

    // =========================================================================
    // 3. RELIABILITY PROTOCOL PACKET HANDLERS (NACK / ACK / ABORT)
    // =========================================================================

    private suspend fun sendNackPacket(recipientNodeId: Long, mediaId: UUID, missingIndices: List<Int>) {
        val timestampSec = System.currentTimeMillis() / 1000L
        val nackBuffer = ByteBuffer.allocate(16 + 2 + (missingIndices.size * 2)).order(ByteOrder.BIG_ENDIAN)
        nackBuffer.putLong(mediaId.mostSignificantBits)
        nackBuffer.putLong(mediaId.leastSignificantBits)
        nackBuffer.putShort((missingIndices.size and 0xFFFF).toShort())
        for (idx in missingIndices) {
            nackBuffer.putShort((idx and 0xFFFF).toShort())
        }

        val plainNack = nackBuffer.array()
        val packetId = UUID.randomUUID()
        val peer = database.peerDao().getPeerById(recipientNodeId)
        val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else return
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)

        val aad = MeshPacket.computeAad(
            type = PacketType.MEDIA_NACK,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestampSec
        )

        val encrypted = cryptoEngine.encrypt(plainNack, packetId, sessionKey, aad)
        val packet = MeshPacket(
            type = PacketType.MEDIA_NACK,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = timestampSec,
            payload = encrypted.ciphertext,
            authTag = encrypted.authTag
        )
        packetBroadcaster(MeshPacket.serialize(packet))
    }

    suspend fun handleMediaNack(packet: MeshPacket) {
        val senderPeer = database.peerDao().getPeerById(packet.senderId) ?: return
        val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
        val aad = packet.getAuthenticatedHeaderBytes()

        val plainBytes = try {
            cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
        } catch (e: Exception) {
            Log.w(tag, "Failed to decrypt MEDIA_NACK from ${packet.senderId}")
            return
        }

        if (plainBytes.size < 18) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaId = UUID(buffer.getLong(), buffer.getLong())
        val missingCount = buffer.getShort().toInt() and 0xFFFF
        val missingIndices = mutableListOf<Int>()
        for (i in 0 until missingCount) {
            if (buffer.remaining() >= 2) {
                missingIndices.add(buffer.getShort().toInt() and 0xFFFF)
            }
        }

        val session = activeOutboundSessions[mediaId] ?: return
        if (session.nackRetryCount >= 5) {
            Log.e(tag, "Outbound transfer $mediaId exceeded 5 NACK retry rounds; aborting")
            sendAbortPacket(session.recipientNodeId, mediaId)
            activeOutboundSessions.remove(mediaId)
            updateTransferState(
                mediaId,
                TransferState.FAILED,
                true,
                0,
                session.totalChunks,
                0L,
                session.mediaBytes.size.toLong(),
                0L,
                "Transfer failed after 5 retry rounds"
            )
            database.messageDao().updateStatus(mediaId.toString(), MessageStatus.FAILED)
            return
        }

        session.nackRetryCount += 1
        Log.i(tag, "Received MEDIA_NACK for $mediaId: resending ${missingIndices.size} missing chunks (round ${session.nackRetryCount}/5)")
        updateTransferState(
            mediaId,
            TransferState.RECOVERING,
            true,
            session.totalChunks - missingIndices.size,
            session.totalChunks,
            ((session.totalChunks - missingIndices.size) * MeshPacket.CHUNK_PAYLOAD_SIZE).toLong(),
            session.mediaBytes.size.toLong(),
            0L
        )

        scope.launch {
            val timestampSec = System.currentTimeMillis() / 1000L
            for (idx in missingIndices) {
                if (session.isCancelled.get()) return@launch
                sendChunk(session, idx, session.recipientNodeId, timestampSec, false, sessionKey)
                delay(70L)
            }
        }
    }

    private suspend fun sendAckPacket(recipientNodeId: Long, mediaId: UUID) {
        val timestampSec = System.currentTimeMillis() / 1000L
        val ackBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        ackBuffer.putLong(mediaId.mostSignificantBits)
        ackBuffer.putLong(mediaId.leastSignificantBits)

        val plainAck = ackBuffer.array()
        val packetId = UUID.randomUUID()
        val peer = database.peerDao().getPeerById(recipientNodeId)
        val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else return
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)

        val aad = MeshPacket.computeAad(
            type = PacketType.MEDIA_ACK,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestampSec
        )

        val encrypted = cryptoEngine.encrypt(plainAck, packetId, sessionKey, aad)
        val packet = MeshPacket(
            type = PacketType.MEDIA_ACK,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = timestampSec,
            payload = encrypted.ciphertext,
            authTag = encrypted.authTag
        )
        packetBroadcaster(MeshPacket.serialize(packet))
    }

    suspend fun handleMediaAck(packet: MeshPacket) {
        val senderPeer = database.peerDao().getPeerById(packet.senderId) ?: return
        val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
        val aad = packet.getAuthenticatedHeaderBytes()

        val plainBytes = try {
            cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
        } catch (e: Exception) {
            Log.w(tag, "Failed to decrypt MEDIA_ACK from ${packet.senderId}")
            return
        }

        if (plainBytes.size < 16) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaId = UUID(buffer.getLong(), buffer.getLong())

        val session = activeOutboundSessions.remove(mediaId)
        val totalBytes = session?.mediaBytes?.size?.toLong() ?: 0L
        val totalChunks = session?.totalChunks ?: 0

        database.messageDao().updateStatus(mediaId.toString(), MessageStatus.DELIVERED)
        updateTransferState(
            mediaId,
            TransferState.COMPLETE,
            true,
            totalChunks,
            totalChunks,
            totalBytes,
            totalBytes,
            0L
        )
        Log.i(tag, "Received verified MEDIA_ACK for $mediaId; transfer marked DELIVERED")
    }

    private suspend fun sendAbortPacket(recipientNodeId: Long, mediaId: UUID) {
        val timestampSec = System.currentTimeMillis() / 1000L
        val abortBuffer = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        abortBuffer.putLong(mediaId.mostSignificantBits)
        abortBuffer.putLong(mediaId.leastSignificantBits)

        val plainAbort = abortBuffer.array()
        val packetId = UUID.randomUUID()
        val peer = database.peerDao().getPeerById(recipientNodeId)
        val peerPubKey = if (peer != null) CryptoEngine.hexToBytes(peer.publicKeyHex) else return
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, timestampSec)

        val aad = MeshPacket.computeAad(
            type = PacketType.MEDIA_ABORT,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            timestamp = timestampSec
        )

        val encrypted = cryptoEngine.encrypt(plainAbort, packetId, sessionKey, aad)
        val packet = MeshPacket(
            type = PacketType.MEDIA_ABORT,
            messageId = packetId,
            senderId = cryptoEngine.nodeId,
            recipientId = recipientNodeId,
            ttl = MeshPacket.MEDIA_DIRECT_TTL,
            timestamp = timestampSec,
            payload = encrypted.ciphertext,
            authTag = encrypted.authTag
        )
        packetBroadcaster(MeshPacket.serialize(packet))
    }

    suspend fun handleMediaAbort(packet: MeshPacket) {
        val senderPeer = database.peerDao().getPeerById(packet.senderId) ?: return
        val peerPubKey = CryptoEngine.hexToBytes(senderPeer.publicKeyHex)
        val sessionKey = cryptoEngine.derivePeerSessionKey(peerPubKey, packet.timestamp)
        val aad = packet.getAuthenticatedHeaderBytes()

        val plainBytes = try {
            cryptoEngine.decrypt(packet.payload, packet.authTag, packet.messageId, sessionKey, aad)
        } catch (e: Exception) {
            Log.w(tag, "Failed to decrypt MEDIA_ABORT from ${packet.senderId}")
            return
        }

        if (plainBytes.size < 16) return
        val buffer = ByteBuffer.wrap(plainBytes).order(ByteOrder.BIG_ENDIAN)
        val mediaId = UUID(buffer.getLong(), buffer.getLong())

        activeOutboundSessions.remove(mediaId)?.isCancelled?.set(true)
        inboundSessions.remove("${packet.senderId}_$mediaId")

        database.messageDao().updateStatus(mediaId.toString(), MessageStatus.FAILED)
        updateTransferState(
            mediaId,
            TransferState.FAILED,
            false,
            0,
            0,
            0L,
            0L,
            0L,
            "Transfer aborted by peer"
        )
        Log.w(tag, "Media transfer $mediaId aborted by peer ${packet.senderId}")
    }

    // =========================================================================
    // 4. CANCELLATION & RETRY
    // =========================================================================

    suspend fun cancelTransfer(mediaId: UUID) {
        val outbound = activeOutboundSessions.remove(mediaId)
        if (outbound != null) {
            outbound.isCancelled.set(true)
            if (!outbound.isBroadcast) {
                sendAbortPacket(outbound.recipientNodeId, mediaId)
            }
        }
        database.messageDao().updateStatus(mediaId.toString(), MessageStatus.CANCELLED)
        updateTransferState(
            mediaId,
            TransferState.CANCELLED,
            true,
            0,
            0,
            0L,
            0L,
            0L,
            "Cancelled by user"
        )
    }

    suspend fun retryTransfer(mediaId: UUID) {
        val message = database.messageDao().getMessageById(mediaId.toString()) ?: return
        val uri = message.mediaUri ?: return
        val file = File(uri)
        if (!file.exists()) return

        val bytes = file.readBytes()
        sendMedia(
            recipientNodeId = message.recipientId,
            mediaType = message.mediaType,
            mediaBytes = bytes,
            caption = message.text,
            durationMs = message.mediaDurationMs,
            originalFileName = message.originalFileName ?: ""
        )
    }

    private fun defaultLabelFor(mediaType: MediaType, originalFileName: String): String {
        return when (mediaType) {
            MediaType.IMAGE -> "📷 Photo"
            MediaType.VOICE -> "🎤 Voice note"
            MediaType.AVATAR -> "Profile photo"
            MediaType.FILE -> if (originalFileName.isNotBlank()) "📄 $originalFileName" else "📄 Document"
            MediaType.NONE -> "Message"
        }
    }
}

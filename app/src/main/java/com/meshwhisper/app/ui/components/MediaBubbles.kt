package com.meshwhisper.app.ui.components

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.media.AudioPlayer
import com.meshwhisper.app.media.TransferState
import com.meshwhisper.app.media.TransferStateInfo
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaContainer
import com.meshwhisper.app.ui.theme.BurntSiennaDim
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmGreen
import com.meshwhisper.app.ui.theme.WarmRed
import com.meshwhisper.app.ui.theme.WarmSurface
import java.io.File
import java.util.UUID

@Composable
fun TransferCard(
    message: MessageEntity,
    transferInfo: TransferStateInfo?,
    onCancel: (UUID) -> Unit,
    onRetry: (UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    val mediaId = remember(message.messageId) {
        try { UUID.fromString(message.messageId) } catch (_: Exception) { UUID.randomUUID() }
    }
    val isOutgoing = message.isOutgoing
    val state = transferInfo?.state ?: when (message.status) {
        MessageStatus.PENDING -> if (isOutgoing) TransferState.SENDING else TransferState.RECEIVING
        MessageStatus.DELIVERED, MessageStatus.SENT -> TransferState.COMPLETE
        MessageStatus.CANCELLED -> TransferState.CANCELLED
        MessageStatus.FAILED -> TransferState.FAILED
        else -> TransferState.SENDING
    }

    val progress = transferInfo?.let {
        if (it.totalChunks > 0) it.chunksCompleted.toFloat() / it.totalChunks else message.mediaProgress
    } ?: message.mediaProgress

    val totalBytes = if ((transferInfo?.totalBytes ?: 0L) > 0L) transferInfo!!.totalBytes else message.mediaSizeBytes
    val bytesCompleted = transferInfo?.bytesCompleted ?: (totalBytes * progress).toLong()
    val eta = transferInfo?.etaSeconds ?: 0L

    val statusText = when (state) {
        TransferState.QUEUED -> "Queued..."
        TransferState.SENDING -> "Sending ${(progress * 100).toInt()}% ${if (eta > 0) "• ~${eta}s" else ""}"
        TransferState.RECEIVING -> "Receiving ${(progress * 100).toInt()}% ${if (eta > 0) "• ~${eta}s" else ""}"
        TransferState.RECOVERING -> "Recovering missing chunks..."
        TransferState.VERIFYING -> "Verifying SHA-256..."
        TransferState.COMPLETE -> "Complete"
        TransferState.CANCELLED -> "Cancelled"
        TransferState.FAILED -> transferInfo?.error ?: "Transfer failed"
    }

    val iconVector = when (message.mediaType) {
        MediaType.IMAGE -> Icons.Default.Image
        MediaType.VOICE -> Icons.Default.Mic
        MediaType.FILE -> Icons.Default.Description
        else -> Icons.Default.Description
    }

    Box(
        modifier = modifier
            .widthIn(min = 220.dp, max = 280.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WarmSurface)
            .border(0.8.dp, if (state == TransferState.FAILED) WarmRed.copy(alpha = 0.5f) else WarmCardBorder, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (state == TransferState.FAILED) WarmRed.copy(alpha = 0.15f) else BurntSiennaContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = null,
                        tint = if (state == TransferState.FAILED) WarmRed else BurntSienna,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.originalFileName ?: if (message.mediaType == MediaType.IMAGE) "Photo" else if (message.mediaType == MediaType.VOICE) "Voice note" else "Document",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = formatFileSize(totalBytes),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }

                if (state == TransferState.SENDING || state == TransferState.RECEIVING || state == TransferState.RECOVERING) {
                    IconButton(
                        onClick = { onCancel(mediaId) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (state == TransferState.SENDING || state == TransferState.RECEIVING || state == TransferState.RECOVERING || state == TransferState.VERIFYING) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = BurntSienna,
                    trackColor = BurntSiennaDim.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = statusText,
                    color = if (state == TransferState.FAILED) WarmRed else if (state == TransferState.COMPLETE) WarmGreen else TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (state == TransferState.FAILED || state == TransferState.CANCELLED) {
                    Button(
                        onClick = { onRetry(mediaId) },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntSiennaContainer),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.height(26.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, tint = BurntSienna, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(text = "Retry", color = BurntSienna, fontSize = 11.sp, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun FileMessageBubble(
    message: MessageEntity,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val file = message.mediaUri?.let { File(it) }

    Box(
        modifier = modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(WarmSurface)
            .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null && file.exists()) {
                if (file != null && file.exists()) {
                    openFileWithSystem(context, file)
                }
            }
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(BurntSiennaContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = "File attachment",
                    tint = BurntSienna,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.originalFileName ?: "Document",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatFileSize(message.mediaSizeBytes),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily
                )
            }
        }
    }
}

@Composable
fun ImageMessageBubble(
    message: MessageEntity,
    isOutgoing: Boolean,
    modifier: Modifier = Modifier
) {
    var isFullscreenOpen by remember { mutableStateOf(false) }
    val filePath = message.mediaUri
    val bitmap = remember(filePath) {
        if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            BitmapFactory.decodeFile(filePath)
        } else {
            null
        }
    }

    val previewBitmap = remember(message.mediaPreviewBase64) {
        if (!message.mediaPreviewBase64.isNullOrBlank()) {
            try {
                val bytes = Base64.decode(message.mediaPreviewBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
                null
            }
        } else null
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .widthIn(max = 240.dp)
                .height(200.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(WarmSurface)
                .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
                .clickable(enabled = bitmap != null) {
                    isFullscreenOpen = true
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Image attachment",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (previewBitmap != null) {
                Image(
                    bitmap = previewBitmap.asImageBitmap(),
                    contentDescription = "Preview thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(8.dp)
                )
            }

            if (message.status == MessageStatus.PENDING || bitmap == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            progress = { message.mediaProgress.coerceIn(0.05f, 1.0f) },
                            modifier = Modifier.size(38.dp),
                            color = BurntSienna,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isOutgoing) "Sending ${(message.mediaProgress * 100).toInt()}%" else "Receiving ${(message.mediaProgress * 100).toInt()}%",
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (message.text.isNotBlank() && message.text != "📷 Photo") {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = message.text,
                color = TextPrimary,
                fontFamily = ManropeFamily,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }

    if (isFullscreenOpen && bitmap != null) {
        Dialog(
            onDismissRequest = { isFullscreenOpen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Black
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Fullscreen image preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )

                    IconButton(
                        onClick = { isFullscreenOpen = false },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp)
                            .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun VoiceNoteBubble(
    message: MessageEntity,
    isOutgoing: Boolean,
    audioPlayer: AudioPlayer,
    modifier: Modifier = Modifier
) {
    val isPlaying by audioPlayer.isPlaying.collectAsState()
    val activeUri by audioPlayer.activeUri.collectAsState()
    val playProgressMs by audioPlayer.progressMs.collectAsState()

    val isCurrentPlaying = (activeUri == message.mediaUri && isPlaying)
    val totalDurationMs = if (message.mediaDurationMs > 0) message.mediaDurationMs else 10000L

    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val waveAnim by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(550, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "waveAnim"
    )

    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 260.dp)
            .padding(vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(BurntSienna)
                    .clickable(enabled = message.mediaUri != null && message.status != MessageStatus.PENDING) {
                        message.mediaUri?.let { path ->
                            audioPlayer.play(path)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (message.status == MessageStatus.PENDING) {
                    CircularProgressIndicator(
                        progress = { message.mediaProgress.coerceIn(0.05f, 1.0f) },
                        modifier = Modifier.size(22.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isCurrentPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isCurrentPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Waveform simulation / amplitude bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                ) {
                    val barHeights = listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 0.8f, 0.4f, 0.7f, 0.5f, 0.9f, 0.3f, 0.6f, 0.7f, 0.5f)
                    barHeights.forEachIndexed { _, heightMultiplier ->
                        val currentHeight = if (isCurrentPlaying) {
                            (heightMultiplier * waveAnim).coerceIn(0.25f, 1.0f)
                        } else {
                            heightMultiplier
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height((currentHeight * 18).dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (isOutgoing) BurntSienna else BurntSiennaDim)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val displayTimeSec = if (isCurrentPlaying) {
                        (playProgressMs / 1000L).coerceAtMost(totalDurationMs / 1000L)
                    } else {
                        totalDurationMs / 1000L
                    }
                    val minutes = displayTimeSec / 60
                    val seconds = displayTimeSec % 60
                    Text(
                        text = String.format("%d:%02d", minutes, seconds),
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily,
                        color = TextSecondary
                    )

                    if (message.status == MessageStatus.PENDING) {
                        Text(
                            text = "${(message.mediaProgress * 100).toInt()}%",
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold,
                            color = BurntSienna
                        )
                    }
                }
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes.toDouble() / (1024 * 1024))
        bytes >= 1024 -> String.format("%.0f KB", bytes.toDouble() / 1024)
        else -> "$bytes B"
    }
}

private fun openFileWithSystem(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open file"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
    }
}

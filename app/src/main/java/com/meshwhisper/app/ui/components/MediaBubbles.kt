package com.meshwhisper.app.ui.components

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.media.AudioPlayer
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaDim
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmSurface
import java.io.File

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
                // Waveform simulation bars
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(20.dp)
                ) {
                    val barHeights = listOf(0.4f, 0.7f, 0.5f, 0.9f, 0.6f, 0.8f, 0.4f, 0.7f, 0.5f, 0.9f, 0.3f, 0.6f)
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

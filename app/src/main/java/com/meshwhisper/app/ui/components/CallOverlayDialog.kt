package com.meshwhisper.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.voice.ActiveCallInfo
import com.meshwhisper.app.voice.CallEndReason
import com.meshwhisper.app.voice.CallState

@Composable
fun CallOverlayDialog(
    callInfo: ActiveCallInfo,
    peerAlias: String,
    avatarUri: String?,
    durationSeconds: Long,
    isMuted: Boolean,
    isSpeakerOn: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onDismiss: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val isRinging = callInfo.callState == CallState.OUTGOING_RINGING || callInfo.callState == CallState.INCOMING_RINGING

    Dialog(
        onDismissRequest = {
            if (callInfo.callState == CallState.ENDED) {
                onDismiss()
            }
        },
        properties = DialogProperties(
            dismissOnBackPress = callInfo.callState == CallState.ENDED,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = SaharaBackground,
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Peer Avatar with Ringing Pulse
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(100.dp)
                ) {
                    if (isRinging) {
                        Box(
                            modifier = Modifier
                                .size(96.dp)
                                .scale(pulseScale)
                                .clip(CircleShape)
                                .background(SaharaPrimary.copy(alpha = 0.2f))
                        )
                    }

                    NodeAvatar(
                        nodeId = callInfo.peerNodeId,
                        alias = peerAlias,
                        size = 80.dp,
                        avatarUri = avatarUri,
                        isDirect = true
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Peer Name
                Text(
                    text = peerAlias.ifBlank { "Peer 0x${String.format("%016X", callInfo.peerNodeId).takeLast(4)}" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = SaharaOnSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Status & Duration
                when (callInfo.callState) {
                    CallState.OUTGOING_RINGING -> {
                        Text(
                            text = "Ringing...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SaharaPrimary,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Direct 1-hop audio session",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaharaOnSurfaceVariant
                        )
                    }
                    CallState.INCOMING_RINGING -> {
                        Text(
                            text = "Incoming Voice Call",
                            style = MaterialTheme.typography.bodyMedium,
                            color = SaharaPrimary,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Direct 1-hop audio session",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaharaOnSurfaceVariant
                        )
                    }
                    CallState.CONNECTED -> {
                        val minutes = durationSeconds / 60
                        val seconds = durationSeconds % 60
                        Text(
                            text = String.format("%02d:%02d", minutes, seconds),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = SaharaPrimary
                        )
                        Text(
                            text = "Direct Full-Duplex • 8 kHz 4-bit ADPCM",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    CallState.ENDED -> {
                        val reasonText = when (callInfo.endReason) {
                            CallEndReason.DECLINED -> "Call Declined"
                            CallEndReason.BUSY -> "Peer Busy"
                            CallEndReason.TIMEOUT -> "No Answer"
                            CallEndReason.LINK_LOST -> "Connection Lost"
                            else -> "Call Ended"
                        }
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = SaharaError,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    CallState.IDLE -> {}
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Action Controls
                when (callInfo.callState) {
                    CallState.INCOMING_RINGING -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Decline Button
                            IconButton(
                                onClick = onDecline,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(SaharaError)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Decline Call",
                                    tint = SaharaOnError
                                )
                            }

                            // Accept Button
                            IconButton(
                                onClick = onAccept,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2E7D32))
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Call,
                                    contentDescription = "Accept Call",
                                    tint = Color.White
                                )
                            }
                        }
                    }

                    CallState.OUTGOING_RINGING -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center
                        ) {
                            IconButton(
                                onClick = onEndCall,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(SaharaError)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "Cancel Call",
                                    tint = SaharaOnError
                                )
                            }
                        }
                    }

                    CallState.CONNECTED -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Mute Toggle
                            IconButton(
                                onClick = onToggleMute,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isMuted) SaharaError.copy(alpha = 0.15f) else SaharaSurfaceContainerHigh)
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                                    contentDescription = if (isMuted) "Unmute" else "Mute",
                                    tint = if (isMuted) SaharaError else SaharaPrimary
                                )
                            }

                            // End Call Button
                            IconButton(
                                onClick = onEndCall,
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(CircleShape)
                                    .background(SaharaError)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CallEnd,
                                    contentDescription = "End Call",
                                    tint = SaharaOnError
                                )
                            }

                            // Speaker Toggle
                            IconButton(
                                onClick = onToggleSpeaker,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isSpeakerOn) SaharaPrimary.copy(alpha = 0.2f) else SaharaSurfaceContainerHigh)
                            ) {
                                Icon(
                                    imageVector = if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeDown,
                                    contentDescription = if (isSpeakerOn) "Earpiece" else "Speaker",
                                    tint = SaharaPrimary
                                )
                            }
                        }
                    }

                    CallState.ENDED -> {
                        Button(
                            onClick = onDismiss,
                            colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Dismiss", color = SaharaOnPrimary)
                        }
                    }

                    CallState.IDLE -> {}
                }
            }
        }
    }
}

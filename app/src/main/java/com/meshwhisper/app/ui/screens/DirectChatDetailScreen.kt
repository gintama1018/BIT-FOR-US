package com.meshwhisper.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaDim
import com.meshwhisper.app.ui.theme.DustyRose
import com.meshwhisper.app.ui.theme.EBGaramondFamily
import com.meshwhisper.app.ui.theme.IncomingBubble
import com.meshwhisper.app.ui.theme.IncomingBubbleBorder
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.OutgoingBubble
import com.meshwhisper.app.ui.theme.OutgoingBubbleBorder
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmAmber
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmGreen
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.theme.WarmRed
import com.meshwhisper.app.ui.theme.WarmSurface
import com.meshwhisper.app.ui.theme.WarmSurfaceContainer
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@Composable
fun DirectChatDetailScreen(
    peerNodeId: Long,
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.getDirectMessagesForPeer(peerNodeId).collectAsState(initial = emptyList())
    val peers by viewModel.peers.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()
    val typingPeers by viewModel.typingPeers.collectAsState()
    val peer = peers.firstOrNull { it.nodeId == peerNodeId }
    val isDirect = connectedNodeIds.contains(peerNodeId)

    val isPeerTyping = remember(typingPeers, peerNodeId) {
        val lastTyping = typingPeers[peerNodeId] ?: 0L
        System.currentTimeMillis() - lastTyping < 4000L
    }

    var textInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }
    var lastTypingSentMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()

    // Track active chat lifecycle for Smart Notifications (suppresses notifications when open)
    androidx.compose.runtime.DisposableEffect(peerNodeId) {
        viewModel.setCurrentOpenChat(peerNodeId)
        onDispose {
            viewModel.setCurrentOpenChat(null)
        }
    }

    // Auto-scroll on new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmLinen)
    ) {
        // Direct Top App Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = WarmSurface),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = BurntSienna
                        )
                    }

                    NodeAvatar(
                        nodeId = peerNodeId,
                        alias = peer?.alias ?: "Node",
                        size = 40.dp,
                        avatarUri = peer?.avatarUri,
                        isDirect = isDirect,
                        showOnlineBadge = true
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = peer?.alias ?: "Node-${String.format("%016X", peerNodeId).takeLast(4)}",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontFamily = EBGaramondFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val subtitleText = when {
                                isPeerTyping -> "Typing..."
                                isDirect -> "⚡ Direct BLE link"
                                peer != null -> {
                                    val diffMs = System.currentTimeMillis() - peer.lastSeen
                                    if (diffMs < 60_000L) "⚡ Active just now"
                                    else if (diffMs < 3600_000L) "Active ${diffMs / 60_000L}m ago (${peer.hopCount} hops)"
                                    else "Offline"
                                }
                                else -> "Offline"
                            }
                            Text(
                                text = subtitleText,
                                color = if (isPeerTyping) BurntSienna else if (isDirect) WarmGreen else TextSecondary,
                                fontSize = 11.sp,
                                fontFamily = ManropeFamily,
                                fontWeight = if (isPeerTyping || isDirect) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = TextSecondary
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            modifier = Modifier
                                .background(WarmSurface)
                                .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
                        ) {
                            val isBlocked = peer?.isBlocked == true
                            val isMuted = peer?.isMuted == true

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isMuted) "Unmute Notifications" else "Mute Notifications",
                                        color = TextPrimary,
                                        fontFamily = ManropeFamily,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.setPeerMuted(peerNodeId, !isMuted)
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isBlocked) "Unblock Contact" else "Block Contact",
                                        color = if (isBlocked) TextPrimary else WarmRed,
                                        fontFamily = ManropeFamily,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    viewModel.toggleBlockPeer(peerNodeId, !isBlocked)
                                }
                            )
                        }
                    }
                }
                HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
            }
        }

        // Safety Number Warning Banner
        if (peer?.hasKeyChanged == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = WarmSurfaceContainer),
                shape = RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, WarmAmber),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp)
                ) {
                    Text(
                        text = "⚠️ Safety Number Changed",
                        color = WarmAmber,
                        fontSize = 14.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This contact's cryptographic identity key has changed (Previous: ${peer.previousFingerprint ?: "N/A"}). This happens if they reinstalled or an adversary is attempting an impersonation.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Button(
                        onClick = { viewModel.acknowledgeSafetyWarning(peerNodeId) },
                        colors = ButtonDefaults.buttonColors(containerColor = BurntSienna),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Trust & Verify New Safety Number",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Blocked Banner
        if (peer?.isBlocked == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = WarmSurfaceContainer),
                shape = RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, WarmRed),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🚫 You have blocked this contact. Incoming messages are dropped.",
                    color = WarmRed,
                    fontSize = 12.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        // Messages List
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = BurntSienna.copy(alpha = 0.5f),
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "End-to-End Encrypted",
                        color = TextPrimary,
                        fontSize = 16.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Messages are end-to-end encrypted with X25519 & AES-256-GCM.\nNo relay node can inspect plaintext contents.",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(messages, key = { _, msg -> msg.messageId }) { index, msg ->
                    val prevMsg = if (index > 0) messages[index - 1] else null
                    val nextMsg = if (index < messages.size - 1) messages[index + 1] else null

                    val isNewDay = prevMsg == null || !isSameDay(prevMsg.timestamp, msg.timestamp)
                    val isFirstInRun = prevMsg == null || prevMsg.isOutgoing != msg.isOutgoing ||
                            isNewDay || abs(msg.timestamp - prevMsg.timestamp) > 60_000L
                    val isLastInRun = nextMsg == null || nextMsg.isOutgoing != msg.isOutgoing ||
                            !isSameDay(msg.timestamp, nextMsg.timestamp) || abs(nextMsg.timestamp - msg.timestamp) > 60_000L

                    if (isNewDay) {
                        DateSeparatorPill(timestamp = msg.timestamp)
                    }

                    DirectMessageBubble(
                        msg = msg,
                        isFirstInRun = isFirstInRun,
                        isLastInRun = isLastInRun,
                        viewModel = viewModel
                    )

                    if (isLastInRun) {
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        // Message Input Field (Disabled if peer is blocked)
        if (peer?.isBlocked == true) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(WarmSurface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Unblock this contact to send messages",
                    color = TextMuted,
                    fontSize = 13.sp,
                    fontFamily = ManropeFamily
                )
            }
        } else {
            ChatInputBar(
                text = textInput,
                onTextChanged = {
                    textInput = it
                    val now = System.currentTimeMillis()
                    if (now - lastTypingSentMs > 2500L) {
                        lastTypingSentMs = now
                        viewModel.sendTyping(peerNodeId, true)
                    }
                },
                onSend = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendDirect(peerNodeId, textInput)
                        textInput = ""
                        viewModel.sendTyping(peerNodeId, false)
                    }
                },
                onSendMedia = { mediaType, bytes, caption, durationMs ->
                    viewModel.sendMediaDirect(peerNodeId, mediaType, bytes, caption, durationMs)
                },
                audioRecorder = viewModel.audioRecorder,
                placeholder = "Message..."
            )
        }
    }
}

@Composable
fun DateSeparatorPill(timestamp: Long) {
    val dateLabel = remember(timestamp) { formatDateSeparator(timestamp) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(WarmSurface)
                .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = dateLabel,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun DirectMessageBubble(
    msg: MessageEntity,
    isFirstInRun: Boolean,
    isLastInRun: Boolean,
    viewModel: MeshViewModel? = null
) {
    val isMe = msg.isOutgoing
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    // Dynamic corner shapes based on grouping run
    val bubbleShape = if (isMe) {
        RoundedCornerShape(
            topStart = 12.dp,
            topEnd = if (isFirstInRun) 12.dp else 4.dp,
            bottomStart = 12.dp,
            bottomEnd = if (isLastInRun) 2.dp else 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInRun) 12.dp else 4.dp,
            topEnd = 12.dp,
            bottomStart = if (isLastInRun) 2.dp else 4.dp,
            bottomEnd = 12.dp
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        Box(
            modifier = Modifier
                .clip(bubbleShape)
                .background(if (isMe) OutgoingBubble else IncomingBubble)
                .border(
                    0.8.dp,
                    if (isMe) OutgoingBubbleBorder else IncomingBubbleBorder,
                    bubbleShape
                )
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)
        ) {
            Column {
                when (msg.mediaType) {
                    MediaType.IMAGE -> {
                        ImageMessageBubble(
                            message = msg,
                            isOutgoing = isMe
                        )
                    }
                    MediaType.VOICE -> {
                        if (viewModel != null) {
                            VoiceNoteBubble(
                                message = msg,
                                isOutgoing = isMe,
                                audioPlayer = viewModel.audioPlayer
                            )
                        } else {
                            Text(
                                text = "🎤 Voice note",
                                color = TextPrimary,
                                fontFamily = ManropeFamily,
                                fontSize = 14.sp
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = msg.text,
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = ManropeFamily,
                            lineHeight = 19.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formattedTime,
                        color = if (isMe) TextSecondary else TextMuted,
                        fontSize = 10.sp,
                        fontFamily = ManropeFamily
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        when (msg.status) {
                            MessageStatus.DELIVERED -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Delivered (ACK)",
                                    tint = WarmGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.RELAYED -> {
                                Icon(
                                    imageVector = Icons.Default.DoneAll,
                                    contentDescription = "Relayed",
                                    tint = BurntSiennaDim,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            MessageStatus.FAILED -> {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Failed",
                                    tint = WarmRed,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            else -> {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Sent",
                                    tint = BurntSiennaDim,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isSameDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun formatDateSeparator(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    return if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)
    ) {
        "Today"
    } else if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - msgTime.get(Calendar.DAY_OF_YEAR) == 1
    ) {
        "Yesterday"
    } else {
        SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

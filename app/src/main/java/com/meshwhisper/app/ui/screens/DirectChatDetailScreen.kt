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
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.theme.AmberAccent
import com.meshwhisper.app.ui.theme.DarkBackground
import com.meshwhisper.app.ui.theme.DarkCardBorder
import com.meshwhisper.app.ui.theme.DarkSurface
import com.meshwhisper.app.ui.theme.EmeraldAccent
import com.meshwhisper.app.ui.theme.IncomingBubble
import com.meshwhisper.app.ui.theme.IncomingBubbleBorder
import com.meshwhisper.app.ui.theme.OutgoingBubble
import com.meshwhisper.app.ui.theme.OutgoingBubbleBorder
import com.meshwhisper.app.ui.theme.RedAccent
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WhatsAppGreen
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
    val peers by viewModel.peers.collectAsState()
    val peer = remember(peers, peerNodeId) { peers.firstOrNull { it.nodeId == peerNodeId } }

    val messagesFlow = remember(peerNodeId) { viewModel.getDirectMessagesForPeer(peerNodeId) }
    val messages by messagesFlow.collectAsState(initial = emptyList())

    val listState = rememberLazyListState()
    var textInput by remember { mutableStateOf("") }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Direct Chat Detail Top Bar
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }

                // Avatar
                NodeAvatar(
                    nodeId = peerNodeId,
                    alias = peer?.alias ?: "Node",
                    size = 40.dp,
                    isDirect = peer?.isDirect == true,
                    showOnlineBadge = true
                )

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer?.alias ?: "Node-${String.format("%016X", peerNodeId).takeLast(4)}",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "E2EE",
                            tint = EmeraldAccent,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                    val statusSubtitle = if (peer?.isDirect == true) {
                        "Direct BLE link"
                    } else if (peer != null) {
                        "${peer.hopCount} hops away"
                    } else {
                        "Encrypted channel"
                    }
                    Text(
                        text = statusSubtitle,
                        color = if (peer?.isDirect == true) WhatsAppGreen else TextSecondary,
                        fontSize = 12.sp
                    )
                }

                // Block / Unblock Toggle Button
                val isBlocked = peer?.isBlocked == true
                androidx.compose.material3.TextButton(
                    onClick = { viewModel.toggleBlockPeer(peerNodeId, !isBlocked) }
                ) {
                    Text(
                        text = if (isBlocked) "Unblock" else "Block",
                        color = if (isBlocked) EmeraldAccent else RedAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Safety Number Changed Security Alert Banner (WhatsApp / Signal grade TOFU warning)
        if (peer?.hasKeyChanged == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1E08)),
                shape = RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberAccent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    Text(
                        text = "⚠️ Safety Number Changed",
                        color = AmberAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This contact's cryptographic identity key has changed (Previous: ${peer.previousFingerprint ?: "N/A"}). This happens if they reinstalled or an adversary is attempting an impersonation.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.Button(
                        onClick = { viewModel.acknowledgeSafetyWarning(peerNodeId) },
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = AmberAccent),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        Text("Trust & Verify New Safety Number", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Blocked Banner
        if (peer?.isBlocked == true) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A0000)),
                shape = RoundedCornerShape(0.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, RedAccent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "🚫 You have blocked this contact. Incoming messages are dropped.",
                    color = RedAccent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(10.dp)
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
                        tint = EmeraldAccent.copy(alpha = 0.6f),
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Messages are end-to-end encrypted.\nNo one outside of this chat, not even relay nodes, can read them.",
                        color = TextSecondary,
                        fontSize = 13.sp,
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
                    .background(DarkSurface)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Unblock this contact to send messages",
                    color = TextMuted,
                    fontSize = 13.sp
                )
            }
        } else {
            ChatInputBar(
                text = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendDirect(peerNodeId, textInput)
                        textInput = ""
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
                .background(DarkSurface)
                .border(0.5.dp, DarkCardBorder, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Text(
                text = dateLabel,
                color = TextSecondary,
                fontSize = 11.sp,
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

    // Dynamic WhatsApp-style corner shapes based on grouping run
    val bubbleShape = if (isMe) {
        RoundedCornerShape(
            topStart = 14.dp,
            topEnd = if (isFirstInRun) 14.dp else 4.dp,
            bottomStart = 14.dp,
            bottomEnd = if (isLastInRun) 2.dp else 4.dp
        )
    } else {
        RoundedCornerShape(
            topStart = if (isFirstInRun) 14.dp else 4.dp,
            topEnd = 14.dp,
            bottomStart = if (isLastInRun) 2.dp else 4.dp,
            bottomEnd = 14.dp
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
                    0.5.dp,
                    if (isMe) OutgoingBubbleBorder else IncomingBubbleBorder,
                    bubbleShape
                )
                .padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp)
        ) {
            Column {
                when (msg.mediaType) {
                    com.meshwhisper.app.data.model.MediaType.IMAGE -> {
                        com.meshwhisper.app.ui.components.ImageMessageBubble(
                            message = msg,
                            isOutgoing = isMe
                        )
                    }
                    com.meshwhisper.app.data.model.MediaType.VOICE -> {
                        if (viewModel != null) {
                            com.meshwhisper.app.ui.components.VoiceNoteBubble(
                                message = msg,
                                isOutgoing = isMe,
                                audioPlayer = viewModel.audioPlayer
                            )
                        } else {
                            Text(text = "🎤 Voice note", color = TextPrimary, fontSize = 14.sp)
                        }
                    }
                    else -> {
                        Text(
                            text = msg.text,
                            color = TextPrimary,
                            fontSize = 14.sp,
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
                        color = if (isMe) Color(0xFF90C2B6) else TextMuted,
                        fontSize = 10.sp
                    )
                    if (isMe) {
                        Spacer(modifier = Modifier.width(4.dp))
                        if (msg.status == MessageStatus.DELIVERED) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered (ACK)",
                                tint = WhatsAppGreen,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                tint = Color(0xFF90C2B6),
                                modifier = Modifier.size(14.dp)
                            )
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


package com.meshwhisper.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaContainer
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
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmGreen
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.theme.WarmRed
import com.meshwhisper.app.ui.theme.WarmSurface
import com.meshwhisper.app.ui.theme.WarmSurfaceContainer
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PublicMeshScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.broadcastMessages.collectAsState()
    val connectedNodes by viewModel.connectedPeersCount.collectAsState()
    var textInput by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Track active chat lifecycle for Smart Notifications (suppresses public notifications when open)
    DisposableEffect(Unit) {
        viewModel.setCurrentOpenChat(-1L)
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
        // Channel Top Header
        PublicChannelHeader(connectedNodes = connectedNodes)

        // Messages List
        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CellTower,
                        contentDescription = null,
                        tint = BurntSienna.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Public Mesh Channel",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "All broadcasts flood through every nearby node.\nMessages are authenticated and community encrypted.",
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
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    BroadcastMessageBubble(msg = msg, viewModel = viewModel)
                }
            }
        }

        // Message Input Field with Attachment & Voice Support
        ChatInputBar(
            text = textInput,
            onTextChanged = { textInput = it },
            onSend = {
                if (textInput.isNotBlank()) {
                    viewModel.sendBroadcast(textInput)
                    textInput = ""
                }
            },
            onSendMedia = { mediaType, bytes, caption, durationMs ->
                viewModel.sendMediaBroadcast(mediaType, bytes, caption, durationMs)
            },
            audioRecorder = viewModel.audioRecorder,
            placeholder = "Broadcast to mesh..."
        )
    }
}

@Composable
private fun PublicChannelHeader(connectedNodes: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WarmSurface),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Public Mesh",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontFamily = EBGaramondFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = "Public Mesh",
                            tint = BurntSienna,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Text(
                        text = "Encrypted with mesh-wide community key",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(WarmSurfaceContainer)
                        .border(0.8.dp, WarmCardBorder, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (connectedNodes > 0) WarmGreen else TextMuted)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (connectedNodes > 0) "$connectedNodes peer${if (connectedNodes != 1) "s" else ""}" else "Scanning...",
                            color = if (connectedNodes > 0) TextPrimary else TextMuted,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
        }
    }
}

@Composable
fun BroadcastMessageBubble(
    msg: MessageEntity,
    viewModel: MeshViewModel? = null
) {
    val isMe = msg.isOutgoing
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
    ) {
        // Sender Alias & Hop Badge
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp, start = if (isMe) 0.dp else 4.dp, end = if (isMe) 4.dp else 0.dp)
        ) {
            Text(
                text = if (isMe) "You" else msg.senderAlias,
                color = if (isMe) BurntSienna else DustyRose,
                fontSize = 11.sp,
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.width(6.dp))
            val hopText = if (msg.hopCount == 0) "⚡ Direct" else "⚡ ${msg.hopCount} hops"
            Text(
                text = hopText,
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = ManropeFamily
            )
        }

        // Bubble Content
        Box(
            modifier = Modifier
                .clip(
                    RoundedCornerShape(
                        topStart = 12.dp,
                        topEnd = 12.dp,
                        bottomStart = if (isMe) 12.dp else 2.dp,
                        bottomEnd = if (isMe) 2.dp else 12.dp
                    )
                )
                .background(if (isMe) OutgoingBubble else IncomingBubble)
                .border(
                    0.8.dp,
                    if (isMe) OutgoingBubbleBorder else IncomingBubbleBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
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
                            fontFamily = ManropeFamily,
                            fontSize = 14.sp,
                            lineHeight = 19.sp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = formattedTime,
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = ManropeFamily,
                    modifier = Modifier.align(Alignment.End)
                )
            }
        }
    }
}

@Composable
fun ChatInputBar(
    text: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onSendMedia: ((mediaType: MediaType, bytes: ByteArray, caption: String, durationMs: Long) -> Unit)? = null,
    audioRecorder: com.meshwhisper.app.media.AudioRecorder? = null,
    placeholder: String = "Message..."
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var selectedCategoryIndex by remember { mutableStateOf(0) }
    var recordTimeSec by remember { mutableStateOf(0) }
    var recordOutputFile by remember { mutableStateOf<java.io.File?>(null) }

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && onSendMedia != null) {
            val compressedBytes = com.meshwhisper.app.media.MediaCompressor.compressImage(context, uri)
            if (compressedBytes != null) {
                onSendMedia(MediaType.IMAGE, compressedBytes, "", 0L)
            }
        }
    }

    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordTimeSec = 0
            while (isRecording) {
                kotlinx.coroutines.delay(1000L)
                recordTimeSec += 1
                if (recordTimeSec >= 30) {
                    isRecording = false
                    val durationMs = audioRecorder?.stopRecording() ?: 0L
                    val file = recordOutputFile
                    if (file != null && file.exists() && file.length() > 0) {
                        onSendMedia?.invoke(
                            MediaType.VOICE,
                            file.readBytes(),
                            "",
                            durationMs
                        )
                    }
                }
            }
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = WarmSurface),
        shape = RoundedCornerShape(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
            if (isRecording) {
                // Active Voice Recording Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(WarmRed)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = String.format("Recording: 0:%02d / 0:30", recordTimeSec),
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                isRecording = false
                                audioRecorder?.cancelRecording()
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel",
                                tint = TextMuted
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        IconButton(
                            onClick = {
                                isRecording = false
                                val durationMs = audioRecorder?.stopRecording() ?: 0L
                                val file = recordOutputFile
                                if (file != null && file.exists() && file.length() > 0) {
                                    onSendMedia?.invoke(
                                        MediaType.VOICE,
                                        file.readBytes(),
                                        "",
                                        durationMs
                                    )
                                }
                            },
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(BurntSienna)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send voice note",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            } else {
                // Normal Text & Media Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onSendMedia != null) {
                        IconButton(
                            onClick = {
                                try {
                                    imagePickerLauncher.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                        )
                                    )
                                } catch (e: android.content.ActivityNotFoundException) {
                                    android.widget.Toast.makeText(context, "No photo picker app found on this device", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.AddPhotoAlternate,
                                contentDescription = "Attach image",
                                tint = BurntSienna,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChanged,
                        placeholder = {
                            Text(
                                placeholder,
                                color = TextMuted,
                                fontFamily = ManropeFamily,
                                fontSize = 14.sp
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { showEmojiPicker = !showEmojiPicker }
                            ) {
                                Text(
                                    text = if (showEmojiPicker) "⌨️" else "😊",
                                    fontSize = 18.sp
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = BurntSienna,
                            unfocusedBorderColor = WarmCardBorder,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = BurntSienna,
                            focusedContainerColor = WarmSurface,
                            unfocusedContainerColor = WarmSurface
                        ),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 4.dp),
                        maxLines = 4
                    )

                    if (text.isNotBlank()) {
                        IconButton(
                            onClick = onSend,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(BurntSienna)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else if (audioRecorder != null && onSendMedia != null) {
                        IconButton(
                            onClick = {
                                val tempFile = java.io.File(context.cacheDir, "temp_voice_${System.currentTimeMillis()}.m4a")
                                recordOutputFile = tempFile
                                if (audioRecorder.startRecording(tempFile)) {
                                    isRecording = true
                                }
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(BurntSiennaContainer)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Record voice note",
                                tint = BurntSienna,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }

                // Sahara Emoji Drawer
                if (showEmojiPicker) {
                    val emojiCategories = listOf(
                        "Smileys" to listOf("😊", "😂", "🤣", "😍", "🥰", "😎", "🤔", "🤫", "🤐", "😴", "🥳", "🥺", "😭", "😱", "🤯", "🥵", "🥶", "💀", "💩", "🤡", "🤠", "😈", "😇", "🤩"),
                        "Gestures" to listOf("👍", "👎", "👌", "✌️", "🤞", "🤝", "👏", "🙌", "👐", "🤲", "🙏", "✍️", "💪", "🤙", "👊", "✊", "🤛", "🤜", "🖐", "✋", "🖖", "👋", "👉", "👈"),
                        "Hearts" to listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "🤎", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝", "🔥", "✨", "⚡", "🌟", "⭐", "💫"),
                        "Objects" to listOf("🎉", "🎊", "🎈", "🎁", "🏆", "🥇", "🎯", "🎲", "🎮", "🕹", "🎧", "🎤", "📱", "💻", "📡", "🔋", "💡", "🔑", "🛡", "🔒", "📦", "🚀", "☕", "🍕")
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WarmSurface)
                            .padding(8.dp)
                    ) {
                        // Category Tabs
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround
                        ) {
                            emojiCategories.forEachIndexed { idx, (catName, _) ->
                                val isSelected = (selectedCategoryIndex == idx)
                                androidx.compose.material3.TextButton(
                                    onClick = { selectedCategoryIndex = idx },
                                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                        contentColor = if (isSelected) BurntSienna else TextMuted
                                    )
                                ) {
                                    Text(
                                        text = catName,
                                        fontSize = 12.sp,
                                        fontFamily = ManropeFamily,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        HorizontalDivider(color = WarmCardBorder, thickness = 0.5.dp)
                        Spacer(modifier = Modifier.height(6.dp))

                        // Emoji Grid
                        val currentEmojis = emojiCategories[selectedCategoryIndex].second
                        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                            columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(8),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentPadding = PaddingValues(4.dp)
                        ) {
                            items(currentEmojis.size) { idx ->
                                val em = currentEmojis[idx]
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable {
                                            onTextChanged(text + em)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(text = em, fontSize = 20.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

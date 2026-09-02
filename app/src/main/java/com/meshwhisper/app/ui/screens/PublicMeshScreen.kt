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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
    val activeSos by viewModel.activeSosAlert.collectAsState()
    var textInput by remember { mutableStateOf("") }
    var showSosDialog by remember { mutableStateOf(false) }
    var sosCustomText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val isSosKeywordDetected = remember(textInput) {
        viewModel.checkEmergencyKeywords(textInput)
    }

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

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚨", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Broadcast Emergency SOS",
                        color = WarmRed,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "This will transmit a high-priority SOS flood packet to ALL mesh nodes in range with maximum TTL relay priority.",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sosCustomText,
                        onValueChange = { sosCustomText = it },
                        placeholder = { Text("Describe emergency (e.g. Trapped in B-Wing, Medical need)...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = WarmRed,
                            cursorColor = WarmRed
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val finalMsg = if (sosCustomText.isNotBlank()) sosCustomText.trim() else "🚨 EMERGENCY SOS — Assistance needed immediately!"
                        viewModel.sendSosBroadcast(finalMsg)
                        showSosDialog = false
                        sosCustomText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmRed)
                ) {
                    Text("BROADCAST SOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            containerColor = WarmSurface
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmLinen)
    ) {
        // Channel Top Header
        PublicChannelHeader(
            connectedNodes = connectedNodes,
            onSosClick = { showSosDialog = true }
        )

        // Active SOS Banner
        if (activeSos != null) {
            SosAlertBanner(
                alert = activeSos!!,
                onDismiss = { viewModel.dismissSosAlert() }
            )
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

        // Message Input Field with Attachment, Voice, & Keyword Triage
        ChatInputBar(
            text = textInput,
            onTextChanged = { textInput = it },
            onSend = {
                if (textInput.isNotBlank()) {
                    viewModel.sendBroadcast(textInput)
                    textInput = ""
                }
            },
            onSendSos = {
                viewModel.sendSosBroadcast(textInput)
                textInput = ""
            },
            isSosSuggested = isSosKeywordDetected,
            onSendMedia = { mediaType, bytes, caption, durationMs, fileName, previewBytes, gridCols, gridRows, imageWidthPx, imageHeightPx, paddedTileByteLengths ->
                viewModel.sendMediaBroadcast(
                    mediaType,
                    bytes,
                    caption,
                    durationMs,
                    fileName,
                    previewBytes,
                    gridCols,
                    gridRows,
                    imageWidthPx,
                    imageHeightPx,
                    paddedTileByteLengths
                )
            },
            audioRecorder = viewModel.audioRecorder,
            placeholder = "Broadcast to mesh..."
        )
    }
}

@Composable
private fun SosAlertBanner(
    alert: com.meshwhisper.app.ui.viewmodel.SosAlertEvent,
    onDismiss: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.75f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = WarmRed.copy(alpha = 0.12f * pulseAlpha)),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.2.dp, WarmRed.copy(alpha = 0.9f * pulseAlpha), RoundedCornerShape(8.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "🚨", fontSize = 22.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "EMERGENCY SOS: ${alert.senderAlias}",
                            color = WarmRed,
                            fontSize = 13.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Text(
                        text = alert.text,
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2
                    )
                    if (alert.latitude != null && alert.longitude != null) {
                        Text(
                            text = String.format("📍 %.4f, %.4f", alert.latitude, alert.longitude),
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily
                        )
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = TextSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun PublicChannelHeader(
    connectedNodes: Int,
    onSosClick: () -> Unit = {}
) {
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // SOS Fast-Action Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(WarmRed.copy(alpha = 0.15f))
                            .border(1.dp, WarmRed.copy(alpha = 0.6f), RoundedCornerShape(16.dp))
                            .clickable { onSosClick() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("🚨", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "SOS",
                                color = WarmRed,
                                fontSize = 11.sp,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

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
            if (msg.isSos) {
                Text(
                    text = "🚨 SOS",
                    color = WarmRed,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = if (isMe) "You" else msg.senderAlias,
                color = if (msg.isSos) WarmRed else if (isMe) BurntSienna else DustyRose,
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
                .background(if (msg.isSos) WarmRed.copy(alpha = 0.18f) else if (isMe) OutgoingBubble else IncomingBubble)
                .border(
                    if (msg.isSos) 1.2.dp else 0.8.dp,
                    if (msg.isSos) WarmRed.copy(alpha = 0.7f) else if (isMe) OutgoingBubbleBorder else IncomingBubbleBorder,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Column {
                val transferStates = viewModel?.transferStates?.collectAsState()?.value
                val mediaUuid = try { java.util.UUID.fromString(msg.messageId) } catch (_: Exception) { null }
                val transferInfo = if (mediaUuid != null) transferStates?.get(mediaUuid) else null

                if (transferInfo != null && (transferInfo.state == com.meshwhisper.app.media.TransferState.SENDING || transferInfo.state == com.meshwhisper.app.media.TransferState.RECEIVING || transferInfo.state == com.meshwhisper.app.media.TransferState.RECOVERING || transferInfo.state == com.meshwhisper.app.media.TransferState.VERIFYING || transferInfo.state == com.meshwhisper.app.media.TransferState.FAILED || transferInfo.state == com.meshwhisper.app.media.TransferState.CANCELLED)) {
                    com.meshwhisper.app.ui.components.TransferCard(
                        message = msg,
                        transferInfo = transferInfo,
                        onCancel = { id -> viewModel?.cancelTransfer(id) },
                        onRetry = { id -> viewModel?.retryTransfer(id) }
                    )
                } else {
                    when (msg.mediaType) {
                        MediaType.FILE -> {
                            com.meshwhisper.app.ui.components.FileMessageBubble(
                                message = msg,
                                isOutgoing = isMe
                            )
                        }
                        MediaType.IMAGE -> {
                            ImageMessageBubble(
                                message = msg,
                                isOutgoing = isMe,
                                tileUpdates = viewModel?.tileUpdates
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
    onSendSos: (() -> Unit)? = null,
    isSosSuggested: Boolean = false,
    onSendMedia: ((
        mediaType: MediaType,
        bytes: ByteArray,
        caption: String,
        durationMs: Long,
        originalFileName: String,
        previewBytes: ByteArray,
        gridCols: Int,
        gridRows: Int,
        imageWidthPx: Int,
        imageHeightPx: Int,
        paddedTileByteLengths: List<Int>
    ) -> Unit)? = null,
    audioRecorder: com.meshwhisper.app.media.AudioRecorder? = null,
    placeholder: String = "Message..."
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isRecording by remember { mutableStateOf(false) }
    var showEmojiPicker by remember { mutableStateOf(false) }
    var showAttachMenu by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var pendingImageUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var selectedCategoryIndex by remember { mutableStateOf(0) }
    var recordTimeSec by remember { mutableStateOf(0) }
    var recordOutputFile by remember { mutableStateOf<java.io.File?>(null) }

    val imagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null && onSendMedia != null) {
            pendingImageUri = uri
            showQualityDialog = true
        }
    }

    val legacyImagePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && onSendMedia != null) {
            pendingImageUri = uri
            showQualityDialog = true
        }
    }

    val documentPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && onSendMedia != null) {
            try {
                var fileName = "Document"
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    onSendMedia(MediaType.FILE, bytes, "", 0L, fileName, ByteArray(0), 1, 1, 0, 0, emptyList())
                }
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Failed to read file", android.widget.Toast.LENGTH_SHORT).show()
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
                        val waveform = com.meshwhisper.app.media.MediaCompressor.extractAudioWaveform(file)
                        onSendMedia?.invoke(
                            MediaType.VOICE,
                            file.readBytes(),
                            "",
                            durationMs,
                            "",
                            waveform,
                            1,
                            1,
                            0,
                            0,
                            emptyList()
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
            // Keyword Triage Prompt Bar
            if (isSosSuggested && onSendSos != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(WarmRed.copy(alpha = 0.12f))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🚨", fontSize = 13.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Distress keyword detected",
                            color = WarmRed,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(WarmRed)
                            .clickable { onSendSos() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "BROADCAST SOS",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

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
                                    val waveform = com.meshwhisper.app.media.MediaCompressor.extractAudioWaveform(file)
                                    onSendMedia?.invoke(
                                        MediaType.VOICE,
                                        file.readBytes(),
                                        "",
                                        durationMs,
                                        "",
                                        waveform,
                                        1,
                                        1,
                                        0,
                                        0,
                                        emptyList()
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
                        Box {
                            IconButton(
                                onClick = { showAttachMenu = true }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Attach",
                                    tint = BurntSienna,
                                    modifier = Modifier.size(24.dp)
                                )
                            }

                            androidx.compose.material3.DropdownMenu(
                                expanded = showAttachMenu,
                                onDismissRequest = { showAttachMenu = false },
                                modifier = Modifier
                                    .background(WarmSurface)
                                    .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
                            ) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("📷 Photo / Image", fontFamily = ManropeFamily, fontSize = 14.sp) },
                                    onClick = {
                                        showAttachMenu = false
                                        try {
                                            imagePickerLauncher.launch(
                                                androidx.activity.result.PickVisualMediaRequest(
                                                    androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            )
                                        } catch (e: Exception) {
                                            android.util.Log.e("PhotoPicker", "PickVisualMedia launch failed, trying GetContent fallback", e)
                                            try {
                                                legacyImagePickerLauncher.launch("image/*")
                                            } catch (e2: Exception) {
                                                android.util.Log.e("PhotoPicker", "GetContent fallback also failed", e2)
                                                android.widget.Toast.makeText(context, "Picker error: ${e2.message}", android.widget.Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                )
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("📄 Document / File (PDF, DOC)", fontFamily = ManropeFamily, fontSize = 14.sp) },
                                    onClick = {
                                        showAttachMenu = false
                                        try {
                                            documentPickerLauncher.launch(arrayOf("*/*"))
                                        } catch (e: Exception) {
                                            android.widget.Toast.makeText(context, "No file picker available", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
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

    if (showQualityDialog && pendingImageUri != null) {
        var selectedQuality by remember { mutableStateOf(com.meshwhisper.app.media.ImageQuality.STANDARD) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = {
                showQualityDialog = false
                pendingImageUri = null
            },
            title = {
                Text("Send Photo", fontFamily = ManropeFamily, fontWeight = FontWeight.Bold, color = TextPrimary)
            },
            text = {
                Column {
                    Text("Select compression quality tier for mesh transmission:", fontSize = 13.sp, color = TextSecondary, fontFamily = ManropeFamily)
                    Spacer(modifier = Modifier.height(12.dp))
                    listOf(
                        com.meshwhisper.app.media.ImageQuality.STANDARD to ("Standard (~150-300 KB)" to "Fast BLE transfer, optimized for mobile"),
                        com.meshwhisper.app.media.ImageQuality.HIGH to ("High Quality (~600KB - 1.2 MB)" to "Crisp details, slightly slower transfer"),
                        com.meshwhisper.app.media.ImageQuality.ORIGINAL to ("Original (Full Size)" to "Raw byte payload, requires strong link")
                    ).forEach { (quality, desc) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selectedQuality == quality) BurntSiennaContainer else WarmSurface)
                                .clickable { selectedQuality = quality }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.RadioButton(
                                selected = (selectedQuality == quality),
                                onClick = { selectedQuality = quality },
                                colors = androidx.compose.material3.RadioButtonDefaults.colors(selectedColor = BurntSienna)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(text = desc.first, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary, fontFamily = ManropeFamily)
                                Text(text = desc.second, fontSize = 11.sp, color = TextSecondary, fontFamily = ManropeFamily)
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.Button(
                    onClick = {
                        val uri = pendingImageUri
                        showQualityDialog = false
                        pendingImageUri = null
                        if (uri != null && onSendMedia != null) {
                            val rawSize = try {
                                context.contentResolver.openFileDescriptor(uri, "r")?.statSize ?: 50000L
                            } catch (_: Exception) { 50000L }

                            val grid = com.meshwhisper.app.media.MediaCompressor.shouldTileImage(rawSize)
                            val microPreview = com.meshwhisper.app.media.MediaCompressor.generateMicroPreview(context, uri)

                            if (grid != null) {
                                val tiledResult = com.meshwhisper.app.media.MediaCompressor.compressImageAsTiles(
                                    context,
                                    uri,
                                    selectedQuality,
                                    grid.first,
                                    grid.second
                                )
                                if (tiledResult != null) {
                                    onSendMedia(
                                        MediaType.IMAGE,
                                        tiledResult.concatenatedBytes,
                                        "",
                                        0L,
                                        "",
                                        microPreview ?: ByteArray(0),
                                        tiledResult.gridCols,
                                        tiledResult.gridRows,
                                        tiledResult.imageWidthPx,
                                        tiledResult.imageHeightPx,
                                        tiledResult.paddedTileByteLengths
                                    )
                                }
                            } else {
                                val compressed = com.meshwhisper.app.media.MediaCompressor.compressImageWithQuality(context, uri, selectedQuality)
                                if (compressed != null) {
                                    onSendMedia(
                                        MediaType.IMAGE,
                                        compressed,
                                        "",
                                        0L,
                                        "",
                                        microPreview ?: ByteArray(0),
                                        1,
                                        1,
                                        0,
                                        0,
                                        emptyList()
                                    )
                                }
                            }
                        }
                    },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = BurntSienna)
                ) {
                    Text("Send", color = Color.White, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        showQualityDialog = false
                        pendingImageUri = null
                    }
                ) {
                    Text("Cancel", color = TextSecondary, fontFamily = ManropeFamily)
                }
            },
            containerColor = WarmSurface
        )
    }
}

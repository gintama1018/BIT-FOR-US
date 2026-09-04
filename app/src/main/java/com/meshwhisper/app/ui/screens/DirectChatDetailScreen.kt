package com.meshwhisper.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.media.MediaCompressor
import com.meshwhisper.app.ui.components.CameraQrScannerDialog
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import com.meshwhisper.app.ui.viewmodel.QrScanResult
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DirectChatDetailScreen(
    peerNodeId: Long,
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages by viewModel.getDirectMessagesForPeer(peerNodeId).collectAsState(initial = emptyList())
    val peers by viewModel.peers.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()
    val peer = peers.firstOrNull { it.nodeId == peerNodeId }
    val isDirect = connectedNodeIds.contains(peerNodeId)
    val isVerified = peer?.isVerified == true
    val peerProfile by viewModel.getPeerProfileFlow(peerNodeId).collectAsState(initial = null)

    var textInput by remember { mutableStateOf("") }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Safety Number Verification Dialog (Cryptographic MITM Defense)
    if (showSafetyNumberDialog) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val safetyNumber = peer?.fingerprint ?: "Awaiting Key Exchange"
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (isVerified) Icons.Default.Verified else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isVerified) Color(0xFF4CAF50) else SaharaPrimary
                    )
                    Text(
                        text = if (isVerified) "Verified Safety Number" else "Verify Safety Number",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Compare this safety number with the number on ${peer?.alias ?: "peer"}'s screen to verify end-to-end encryption integrity and prevent man-in-the-middle attacks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaharaOnSurfaceVariant
                    )
                    Surface(
                        color = SaharaSurfaceContainerLowest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = safetyNumber.chunked(4).joinToString(" "),
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = SaharaPrimary,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Button(
                        onClick = { showCameraScanner = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Peer's Screen with Camera")
                    }
                    if (isVerified) {
                        Surface(
                            color = Color(0xFF1B5E20).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF50), modifier = Modifier.size(16.dp))
                                Text(
                                    text = "Marked as Verified Contact",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF4CAF50),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.togglePeerVerification(peerNodeId, !isVerified)
                        showSafetyNumberDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isVerified) SaharaOutline else Color(0xFF2E7D32)
                    )
                ) {
                    Text(if (isVerified) "Clear Verification" else "Mark as Verified")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(safetyNumber))
                        android.widget.Toast.makeText(context, "Safety number copied", android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Copy")
                    }
                    TextButton(onClick = { showSafetyNumberDialog = false }) {
                        Text("Close")
                    }
                }
            }
        )
    }

    // Live Camera QR Scanner for Safety Number Verification
    if (showCameraScanner) {
        CameraQrScannerDialog(
            onDismissRequest = { showCameraScanner = false },
            onQrCodeScanned = { scannedContent ->
                showCameraScanner = false
                coroutineScope.launch {
                    when (val res = viewModel.handleScannedQrContent(scannedContent, targetPeerNodeId = peerNodeId)) {
                        is QrScanResult.PeerVerified -> {
                            showSafetyNumberDialog = false
                            android.widget.Toast.makeText(
                                context,
                                "✓ Identity Verified! End-to-end cryptographic link authenticated with ${peer?.alias ?: "peer"}.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        is QrScanResult.KeyMismatch -> {
                            android.widget.Toast.makeText(
                                context,
                                "⚠️ MITM WARNING: Scanned QR code does not match ${peer?.alias ?: "peer"}'s public key!",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        is QrScanResult.ChannelConfigured -> {
                            android.widget.Toast.makeText(context, "Configured channel: ${res.channelName}", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        is QrScanResult.Invalid -> {
                            android.widget.Toast.makeText(context, res.reason, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            title = "Verify Safety Number",
            subtitle = "Point camera at ${peer?.alias ?: "peer"}'s screen to authenticate public key"
        )
    }

    // Photo picker for DM
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val tiledResult = MediaCompressor.compressImageAsTiles(context, uri, com.meshwhisper.app.media.ImageQuality.STANDARD, 3, 3)
            if (tiledResult != null) {
                viewModel.sendMediaDirect(
                    recipientNodeId = peerNodeId,
                    mediaType = MediaType.IMAGE,
                    mediaBytes = tiledResult.concatenatedBytes,
                    caption = "",
                    originalFileName = "photo_${System.currentTimeMillis()}.jpg",
                    previewBytes = ByteArray(0),
                    gridCols = tiledResult.gridCols,
                    gridRows = tiledResult.gridRows,
                    imageWidthPx = tiledResult.imageWidthPx,
                    imageHeightPx = tiledResult.imageHeightPx,
                    paddedTileByteLengths = tiledResult.paddedTileByteLengths
                )
            }
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
            .background(SaharaBackground)
    ) {
        // Direct Chat Header matching 4._direct_chat/code.html
        Surface(
            color = SaharaBackground,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = SaharaPrimary
                    )
                }

                NodeAvatar(
                    nodeId = peerNodeId,
                    alias = peer?.alias ?: "",
                    size = 38.dp,
                    avatarUri = peerProfile?.avatarUri ?: peer?.avatarUri,
                    isDirect = isDirect
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = peerProfile?.displayName?.ifBlank { null } ?: peer?.alias?.ifBlank { "Peer 0x${String.format("%016X", peerNodeId).takeLast(4)}" } ?: "Peer",
                            style = MaterialTheme.typography.titleMedium,
                            color = SaharaOnSurface,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isVerified) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = "Verified Contact",
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    if (!peerProfile?.bio.isNullOrBlank()) {
                        Text(
                            text = peerProfile!!.bio,
                            style = MaterialTheme.typography.bodySmall,
                            color = SaharaPrimary,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isDirect) SaharaOnline else SaharaPrimary)
                        )
                        Text(
                            text = if (isVerified) "Verified Safety Number • E2EE" else if (isDirect) "Direct Socket • E2EE" else "Mesh Relay (${peer?.hopCount ?: 1} hops)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isVerified) Color(0xFF4CAF50) else SaharaOnSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                }

                IconButton(onClick = { showSafetyNumberDialog = true }) {
                    Icon(
                        imageVector = if (isVerified) Icons.Default.VerifiedUser else Icons.Default.Shield,
                        contentDescription = "Verify Safety Number",
                        tint = if (isVerified) Color(0xFF4CAF50) else SaharaPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.8.dp)

        // Safety Number Changed Security Alert Banner (Fix P1-1)
        if (peer?.hasKeyChanged == true) {
            Surface(
                color = SaharaErrorContainer.copy(alpha = 0.7f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SaharaError.copy(alpha = 0.6f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = SaharaError,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SECURITY WARNING: SAFETY NUMBER CHANGED",
                            color = SaharaError,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The cryptographic identity key for ${peer.alias} has changed. This can happen if they reinstalled the app or if a Man-In-The-Middle attack is occurring. Verify out-of-band via QR before sharing sensitive data.",
                        color = SaharaOnSurface,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.acknowledgeKeyChange(peerNodeId) },
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = "Acknowledge & Trust New Key",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.messageId }) { msg ->
                    SaharaDirectMessageBubble(
                        msg = msg,
                        viewModel = viewModel
                    )
                }
            }

            // Floating Bottom Composer
            SaharaDirectComposer(
                textInput = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.trim().isNotEmpty()) {
                        viewModel.sendDirect(peerNodeId, textInput.trim())
                        textInput = ""
                    }
                },
                onAttachPhoto = { photoPickerLauncher.launch("image/*") },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Editorial Direct Message Bubble matching 4._direct_chat/code.html
 */
@Composable
private fun SaharaDirectMessageBubble(
    msg: MessageEntity,
    viewModel: MeshViewModel
) {
    val isMe = msg.isOutgoing
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMe) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Surface(
                color = if (isMe) SaharaPrimaryFixed else SaharaSurfaceContainerLowest,
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (isMe) 16.dp else 4.dp,
                    bottomEnd = if (isMe) 4.dp else 16.dp
                ),
                border = androidx.compose.foundation.BorderStroke(
                    0.8.dp,
                    if (isMe) SaharaOutlineVariant.copy(alpha = 0.5f) else SaharaOutlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier.shadow(
                    elevation = 2.dp,
                    shape = RoundedCornerShape(16.dp),
                    spotColor = Color(0x153A302A)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    when (msg.mediaType) {
                        MediaType.IMAGE -> {
                            ImageMessageBubble(
                                message = msg,
                                tileUpdates = viewModel.tileUpdates,
                                isOutgoing = isMe
                            )
                        }
                        MediaType.VOICE -> {
                            VoiceNoteBubble(
                                message = msg,
                                isOutgoing = isMe,
                                audioPlayer = viewModel.audioPlayer
                            )
                        }
                        else -> {
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = SaharaOnSurface,
                                fontSize = 15.sp,
                                lineHeight = 21.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.align(Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = formattedTime,
                            style = MaterialTheme.typography.labelSmall,
                            color = SaharaOnSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 10.sp
                        )

                        if (isMe) {
                            val icon = when (msg.status) {
                                MessageStatus.DELIVERED -> Icons.Default.DoneAll
                                MessageStatus.SENT -> Icons.Default.Check
                                else -> Icons.Default.Schedule
                            }
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = if (msg.status == MessageStatus.DELIVERED) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Floating Direct Chat Composer
 */
@Composable
private fun SaharaDirectComposer(
    textInput: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachPhoto: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SaharaSurfaceContainerLowest,
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp), spotColor = SaharaPrimary.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onAttachPhoto,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = SaharaOnSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            TextField(
                value = textInput,
                onValueChange = onTextChanged,
                placeholder = {
                    Text(
                        text = "Encrypted message...",
                        color = SaharaOnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 15.sp,
                        fontFamily = ManropeFamily
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = SaharaOnSurface,
                    unfocusedTextColor = SaharaOnSurface
                ),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )

            IconButton(
                onClick = onSend,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (textInput.isNotBlank()) SaharaPrimary else SaharaPrimary.copy(alpha = 0.4f))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

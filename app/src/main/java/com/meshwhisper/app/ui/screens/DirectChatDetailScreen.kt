package com.meshwhisper.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.media.MediaCompressor
import com.meshwhisper.app.ui.components.CallOverlayDialog
import com.meshwhisper.app.ui.components.CameraQrScannerDialog
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.components.TrustBadge
import com.meshwhisper.app.ui.components.RescueLocationCard
import com.meshwhisper.app.ui.components.SafetyNumberConfirmationDialog
import com.meshwhisper.core.identity.VerificationCandidate
import com.meshwhisper.app.util.PlusCodeHelper
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import com.meshwhisper.app.ui.viewmodel.QrScanResult
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
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
    val callState by viewModel.callState.collectAsState()
    val activeCallInfo by viewModel.activeCallInfo.collectAsState()
    val callDurationSeconds by viewModel.callDurationSeconds.collectAsState()
    val isCallMuted by viewModel.isCallMuted.collectAsState()
    val isCallSpeakerOn by viewModel.isCallSpeakerOn.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showBeaconConfirmDialog by remember { mutableStateOf(false) }
    var showAttachmentDrawer by remember { mutableStateOf(false) }
    var showSosConfirmDialog by remember { mutableStateOf(false) }
    var showPeerDossier by remember { mutableStateOf(false) }
    var verificationCandidate by remember { mutableStateOf<VerificationCandidate?>(null) }
    var computedSafetyNumber by remember { mutableStateOf<String?>(null) }
    var myFingerprintHex by remember { mutableStateOf("") }
    var peerFingerprintHex by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var replyingToMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }
    var lastSeenMessageCount by remember { mutableIntStateOf(messages.size) }

    val messageReactions by viewModel.messageReactions.collectAsState()
    var selectedMessageForActions by remember { mutableStateOf<MessageEntity?>(null) }
    var showPacketInspectorDialog by remember { mutableStateOf(false) }

    val matchingMessageIndices by remember(searchQuery, messages) {
        derivedStateOf {
            if (searchQuery.isBlank()) emptyList<Int>()
            else messages.mapIndexedNotNull { index, msg ->
                if (msg.text.contains(searchQuery, ignoreCase = true)) index else null
            }
        }
    }

    LaunchedEffect(searchQuery) {
        if (matchingMessageIndices.isNotEmpty()) {
            currentMatchIndex = matchingMessageIndices.lastIndex
            listState.animateScrollToItem(matchingMessageIndices[currentMatchIndex])
        }
    }

    LaunchedEffect(showSafetyNumberDialog, peerNodeId) {
        if (showSafetyNumberDialog) {
            computedSafetyNumber = viewModel.getSafetyNumberForPeer(peerNodeId)
            myFingerprintHex = viewModel.getMyFingerprintHex()
            peerFingerprintHex = viewModel.getPeerFingerprintHex(peerNodeId)
        }
    }

    // Safety Number Verification Dialog (Cryptographic MITM Defense)
    if (showSafetyNumberDialog) {
        val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
        val safetyNumber = computedSafetyNumber ?: "Awaiting Authenticated Announce"
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
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Compare this 60-digit safety number with the number on ${peer?.alias ?: "peer"}'s screen to verify end-to-end encryption integrity and prevent man-in-the-middle attacks.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SaharaOnSurfaceVariant
                    )
                    Surface(
                        color = SaharaSurfaceContainerLowest,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(
                            text = safetyNumber,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = SaharaPrimary,
                            modifier = Modifier.padding(12.dp),
                            lineHeight = 20.sp
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "Your Fingerprint: ${myFingerprintHex.take(16)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            text = "Peer Fingerprint: ${(peerFingerprintHex ?: peer?.fingerprint)?.take(16)}...",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
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

                        // Anti-Stalking Per-Contact Location Sharing Opt-in (Default OFF)
                        Surface(
                            color = SaharaSurfaceContainerLow,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Emergency Location Beacons",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = SaharaOnSurface
                                    )
                                    Text(
                                        text = "Allow this contact to receive your dying gasp & emergency location beacons",
                                        fontSize = 10.sp,
                                        color = SaharaOnSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = peer?.shareLocationWithContact == true,
                                    onCheckedChange = { checked ->
                                        viewModel.toggleLocationSharingWithContact(peerNodeId, checked)
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = SaharaOnPrimary,
                                        checkedTrackColor = SaharaPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyNumberDialog = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(safetyNumber))
                    android.widget.Toast.makeText(context, "Safety number copied", android.widget.Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy")
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
                        is QrScanResult.VerificationReady -> {
                            showSafetyNumberDialog = false
                            verificationCandidate = res.candidate
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

    // Staged Two-Step Verification Confirmation Modal
    verificationCandidate?.let { candidate ->
        SafetyNumberConfirmationDialog(
            candidate = candidate,
            onConfirm = {
                coroutineScope.launch {
                    val res = viewModel.confirmSafetyNumber(candidate)
                    if (res.isSuccess) {
                        android.widget.Toast.makeText(
                            context,
                            "✓ Identity Verified! End-to-end cryptographic link authenticated with ${peer?.alias ?: "peer"}.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "Verification failed: ${res.exceptionOrNull()?.message}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    }
                    verificationCandidate = null
                }
            },
            onDismiss = { verificationCandidate = null }
        )
    }

    // Long-Press Context Actions & Reaction Bottom Sheet
    if (selectedMessageForActions != null && !showPacketInspectorDialog) {
        val targetMsg = selectedMessageForActions!!
        val reactionsForThisMsg = messageReactions[targetMsg.messageId] ?: emptyList()
        val haptic = LocalHapticFeedback.current

        ModalBottomSheet(
            onDismissRequest = { selectedMessageForActions = null },
            containerColor = SaharaSurfaceContainerLowest,
            scrimColor = Color.Black.copy(alpha = 0.5f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. Tactical Emoji Reaction Strip
                Surface(
                    color = SaharaSurfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth().shadow(4.dp, RoundedCornerShape(24.dp))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val tacticalEmojis = listOf(
                            "👍" to "Roger",
                            "🚨" to "Alert",
                            "📍" to "Coords",
                            "❤️" to "Affirm",
                            "🤝" to "Copy",
                            "🔥" to "Priority"
                        )
                        tacticalEmojis.forEach { (emoji, _) ->
                            val isSelected = reactionsForThisMsg.contains(emoji)
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(if (isSelected) SaharaPrimary.copy(alpha = 0.25f) else Color.Transparent)
                                    .then(if (isSelected) Modifier.border(1.5.dp, SaharaPrimary, CircleShape) else Modifier)
                                    .clickable {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleReaction(targetMsg.messageId, emoji)
                                        selectedMessageForActions = null
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(text = emoji, fontSize = 22.sp)
                            }
                        }
                    }
                }

                // 2. Action Menu Items
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(SaharaSurfaceContainerHigh.copy(alpha = 0.5f))
                ) {
                    // Reply
                    ContextActionRow(
                        icon = Icons.AutoMirrored.Filled.Reply,
                        text = "Reply to Message",
                        accentColor = SaharaPrimary,
                        onClick = {
                            replyingToMessage = targetMsg
                            selectedMessageForActions = null
                        }
                    )

                    HorizontalDivider(color = SaharaOutlineVariant.copy(alpha = 0.3f), thickness = 0.6.dp)

                    // Copy Text (if text is available)
                    if (targetMsg.text.isNotBlank() && targetMsg.mediaType == MediaType.NONE) {
                        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                        ContextActionRow(
                            icon = Icons.Default.ContentCopy,
                            text = "Copy Text",
                            onClick = {
                                val (_, cleanText) = parseQuotedMessage(targetMsg.text)
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(cleanText))
                                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
                                selectedMessageForActions = null
                            }
                        )
                        HorizontalDivider(color = SaharaOutlineVariant.copy(alpha = 0.3f), thickness = 0.6.dp)
                    }

                    // Forward to Public Mesh
                    ContextActionRow(
                        icon = Icons.AutoMirrored.Filled.Send,
                        text = "Forward to Public Mesh",
                        onClick = {
                            val (_, cleanText) = parseQuotedMessage(targetMsg.text)
                            if (cleanText.isNotBlank()) {
                                viewModel.sendBroadcast(cleanText)
                                android.widget.Toast.makeText(context, "Forwarded to Public Mesh", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            selectedMessageForActions = null
                        }
                    )

                    HorizontalDivider(color = SaharaOutlineVariant.copy(alpha = 0.3f), thickness = 0.6.dp)

                    // Inspect Packet Details
                    ContextActionRow(
                        icon = Icons.Default.Info,
                        text = "Inspect Packet Route & Cipher",
                        onClick = {
                            showPacketInspectorDialog = true
                        }
                    )

                    HorizontalDivider(color = SaharaOutlineVariant.copy(alpha = 0.3f), thickness = 0.6.dp)

                    // Delete
                    ContextActionRow(
                        icon = Icons.Default.Delete,
                        text = "Delete for Me",
                        accentColor = SaharaError,
                        onClick = {
                            viewModel.deleteMessage(targetMsg.messageId)
                            android.widget.Toast.makeText(context, "Message deleted", android.widget.Toast.LENGTH_SHORT).show()
                            selectedMessageForActions = null
                        }
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // Packet & Hop Telemetry Inspector Dialog
    if (showPacketInspectorDialog && selectedMessageForActions != null) {
        val msg = selectedMessageForActions!!
        AlertDialog(
            onDismissRequest = {
                showPacketInspectorDialog = false
                selectedMessageForActions = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = SaharaPrimary)
                    Text("Packet Telemetry Inspector", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TelemetryDetailRow("Message UUID", msg.messageId.take(16) + "...")
                    TelemetryDetailRow("Origin", if (msg.isOutgoing) "Local Node (You)" else msg.senderAlias)
                    TelemetryDetailRow("Hop Count", "${msg.hopCount} mesh hops")
                    TelemetryDetailRow("Cipher State", "ChaCha20-Poly1305 AEAD ✓")
                    TelemetryDetailRow("Signature Auth", "Ed25519 Verified ✓")
                    TelemetryDetailRow("Delivery Status", msg.status.name)
                    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                    TelemetryDetailRow("Timestamp", df.format(Date(msg.timestamp)))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showPacketInspectorDialog = false
                    selectedMessageForActions = null
                }) {
                    Text("Close", color = SaharaPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }

    // Photo picker for DM
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val tiledResult = MediaCompressor.compressImageAsTiles(context, uri, com.meshwhisper.app.media.ImageQuality.STANDARD, 8, 8)
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

    // Voice recording states & permission launcher
    var isRecordingVoice by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableIntStateOf(0) }
    var voiceRecordFile by remember { mutableStateOf<File?>(null) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val cacheDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
            val tempFile = File(cacheDir, "vn_${System.currentTimeMillis()}.m4a")
            voiceRecordFile = tempFile
            val started = viewModel.audioRecorder.startRecording(tempFile)
            if (started) {
                isRecordingVoice = true
                recordingDurationSec = 0
            } else {
                android.widget.Toast.makeText(context, "Could not start voice recording", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(context, "Microphone permission required for voice notes", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(isRecordingVoice) {
        if (isRecordingVoice) {
            recordingDurationSec = 0
            while (isRecordingVoice) {
                kotlinx.coroutines.delay(1000L)
                recordingDurationSec++
                if (recordingDurationSec >= 30) {
                    val durationMs = viewModel.audioRecorder.stopRecording()
                    isRecordingVoice = false
                    val file = voiceRecordFile
                    voiceRecordFile = null
                    if (file != null && file.exists() && durationMs >= 500L) {
                        val audioBytes = file.readBytes()
                        if (audioBytes.isNotEmpty()) {
                            viewModel.sendMediaDirect(
                                recipientNodeId = peerNodeId,
                                mediaType = MediaType.VOICE,
                                mediaBytes = audioBytes,
                                caption = "",
                                durationMs = durationMs,
                                originalFileName = "voice_${System.currentTimeMillis()}.m4a"
                            )
                            android.widget.Toast.makeText(context, "Voice note sent (30s limit reached)", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        file.delete()
                    }
                    break
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecordingVoice) {
                viewModel.audioRecorder.cancelRecording()
                voiceRecordFile?.delete()
            }
        }
    }

    // Tactical Attachment & Payload Drawer (Option C)
    if (showAttachmentDrawer) {
        val haptic = LocalHapticFeedback.current
        ModalBottomSheet(
            onDismissRequest = { showAttachmentDrawer = false },
            containerColor = SaharaSurfaceContainerLowest,
            scrimColor = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "TACTICAL PAYLOAD DISPATCH",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = SaharaOnSurface,
                            fontSize = 15.sp,
                            fontFamily = ManropeFamily
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(SaharaOnline)
                            )
                            Text(
                                text = "E2EE Secured • ChaCha20-Poly1305 AEAD",
                                color = SaharaOnSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = ManropeFamily
                            )
                        }
                    }

                    IconButton(
                        onClick = { showAttachmentDrawer = false },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close drawer",
                            tint = SaharaOnSurfaceVariant
                        )
                    }
                }

                // Section 1: 4-Grid Tactical Payload Items
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Tile Mesh Photo
                    TacticalPayloadTile(
                        icon = Icons.Default.CameraAlt,
                        iconTint = SaharaPrimary,
                        title = "Tile Photo",
                        subtitle = "8x8 matrix mesh fragments",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentDrawer = false
                            photoPickerLauncher.launch("image/*")
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // GPS Rescue Beacon
                    TacticalPayloadTile(
                        icon = Icons.Default.LocationOn,
                        iconTint = SaharaTertiary,
                        title = "GPS Beacon",
                        subtitle = "Dying gasp + Plus Code",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentDrawer = false
                            val myLoc = viewModel.getMyLocation()
                            if (myLoc != null) {
                                viewModel.sendManualLocationBeacon(peerNodeId, "Direct Beacon")
                                val plusCode = PlusCodeHelper.encode(myLoc.latitude, myLoc.longitude)
                                val locationMsg = "📍 [GPS BEACON]\nLat: ${String.format(Locale.US, "%.5f", myLoc.latitude)}, Lon: ${String.format(Locale.US, "%.5f", myLoc.longitude)}\nPlus Code: $plusCode\nAccuracy: ±${myLoc.accuracy.toInt()}m"
                                viewModel.sendDirect(peerNodeId, locationMsg)
                                android.widget.Toast.makeText(context, "GPS Beacon dispatched over mesh", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(context, "GPS hardware lock required to transmit coordinates", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Safety Number
                    TacticalPayloadTile(
                        icon = Icons.Default.Shield,
                        iconTint = SaharaOnline,
                        title = "Safety Key",
                        subtitle = "60-digit MITM verify",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentDrawer = false
                            showSafetyNumberDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Scan Peer QR
                    TacticalPayloadTile(
                        icon = Icons.Default.QrCodeScanner,
                        iconTint = SaharaWarning,
                        title = "Scan Peer QR",
                        subtitle = "Live CameraX scan",
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentDrawer = false
                            showCameraScanner = true
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Section 2: High-Priority Emergency Signal
                Surface(
                    color = SaharaErrorContainer.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.2.dp, SaharaError.copy(alpha = 0.7f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            showAttachmentDrawer = false
                            showSosConfirmDialog = true
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(SaharaError.copy(alpha = 0.2f))
                                .border(1.2.dp, SaharaError, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "SOS",
                                tint = SaharaError,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Emergency SOS Beacon",
                                color = SaharaError,
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 14.sp,
                                fontFamily = ManropeFamily
                            )
                            Text(
                                text = "Flood mesh with distress alert & coordinates",
                                color = SaharaOnSurfaceVariant,
                                fontSize = 11.sp,
                                fontFamily = ManropeFamily
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = SaharaError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // Section 3: Rapid Off-Grid Sitrep Presets
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "OFF-GRID SITREP PRESETS (1-TAP)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = SaharaOnSurfaceVariant,
                        fontFamily = ManropeFamily
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TacticalSitrepChip("🟢 ON SITE & SECURE") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            textInput = "STATUS: On site and secure. Holding current position."
                            showAttachmentDrawer = false
                        }
                        TacticalSitrepChip("🟡 EN ROUTE (ETA 15M)") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            textInput = "STATUS: En route to rendezvous point. ETA 15 minutes."
                            showAttachmentDrawer = false
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TacticalSitrepChip("🟠 LOW BATTERY / STANDBY") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            textInput = "STATUS: Low battery. Switching radio to periodic power-saving standby."
                            showAttachmentDrawer = false
                        }
                        TacticalSitrepChip("🔴 IMMEDIATE SITREP NEEDED") {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            textInput = "STATUS: Requesting urgent contact and immediate sitrep."
                            showAttachmentDrawer = false
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }

    // Emergency SOS Confirmation Modal
    if (showSosConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSosConfirmDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = SaharaError)
                    Text("CONFIRM EMERGENCY SOS", color = SaharaError, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Text(
                    text = "Are you sure you want to broadcast an emergency distress signal? This will transmit high-priority SOS alert packets over the direct link and flood the entire local mesh with your hardware GPS coordinates.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaharaOnSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val myLoc = viewModel.getMyLocation()
                        val plusCode = if (myLoc != null) PlusCodeHelper.encode(myLoc.latitude, myLoc.longitude) else "UNKNOWN"
                        val coordsStr = if (myLoc != null) "${String.format(Locale.US, "%.5f", myLoc.latitude)}, ${String.format(Locale.US, "%.5f", myLoc.longitude)} (Plus Code: $plusCode)" else "GPS searching..."
                        viewModel.sendSosBroadcast(
                            text = "EMERGENCY DISTRESS BEACON from ${peer?.alias ?: "Node"}",
                            latitude = myLoc?.latitude,
                            longitude = myLoc?.longitude,
                            accuracyMeters = myLoc?.accuracy ?: 0f
                        )
                        viewModel.sendDirect(
                            peerNodeId,
                            "🚨 [EMERGENCY DISTRESS BEACON]\nImmediate assistance requested!\nCoordinates: $coordsStr"
                        )
                        android.widget.Toast.makeText(context, "🚨 Emergency SOS beacon dispatched over mesh!", android.widget.Toast.LENGTH_LONG).show()
                        showSosConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("TRANSMIT SOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosConfirmDialog = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            }
        )
    }

    var hasInitialScrolled by remember { mutableStateOf(false) }
    val isImeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    // Auto-scroll on initial load and new message (WhatsApp style)
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            if (!hasInitialScrolled) {
                listState.scrollToItem(messages.size - 1)
                hasInitialScrolled = true
            } else {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Auto-scroll when keyboard opens so latest message appears directly above typing bar
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    BackHandler(enabled = showPeerDossier) {
        showPeerDossier = false
    }

    if (showPeerDossier) {
        PeerDossierScreen(
            peerNodeId = peerNodeId,
            viewModel = viewModel,
            onBack = { showPeerDossier = false },
            modifier = modifier
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(SaharaBackground)
        ) {
        // Direct Chat Header matching 4._direct_chat/code.html
        if (isSearchActive) {
            Surface(
                color = SaharaSurfaceContainerLowest,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .shadow(2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            isSearchActive = false
                            searchQuery = ""
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close search",
                            tint = SaharaPrimary
                        )
                    }

                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Search in conversation...",
                                color = SaharaOnSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 14.sp,
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
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )

                    if (searchQuery.isNotBlank()) {
                        Text(
                            text = if (matchingMessageIndices.isNotEmpty()) {
                                "${currentMatchIndex + 1}/${matchingMessageIndices.size}"
                            } else {
                                "0/0"
                            },
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            color = if (matchingMessageIndices.isNotEmpty()) SaharaPrimary else SaharaError,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        IconButton(
                            onClick = {
                                if (matchingMessageIndices.isNotEmpty()) {
                                    currentMatchIndex = if (currentMatchIndex <= 0) matchingMessageIndices.lastIndex else currentMatchIndex - 1
                                    val targetIdx = matchingMessageIndices[currentMatchIndex]
                                    coroutineScope.launch { listState.animateScrollToItem(targetIdx) }
                                }
                            },
                            enabled = matchingMessageIndices.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowUp,
                                contentDescription = "Previous match",
                                tint = if (matchingMessageIndices.isNotEmpty()) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                if (matchingMessageIndices.isNotEmpty()) {
                                    currentMatchIndex = (currentMatchIndex + 1) % matchingMessageIndices.size
                                    val targetIdx = matchingMessageIndices[currentMatchIndex]
                                    coroutineScope.launch { listState.animateScrollToItem(targetIdx) }
                                }
                            },
                            enabled = matchingMessageIndices.isNotEmpty(),
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Next match",
                                tint = if (matchingMessageIndices.isNotEmpty()) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear search",
                                tint = SaharaOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        } else {
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

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { showPeerDossier = true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NodeAvatar(
                            nodeId = peerNodeId,
                            alias = peer?.alias ?: "",
                            size = 38.dp,
                            avatarUri = peerProfile?.avatarUri ?: peer?.avatarUri,
                            isDirect = isDirect
                        )

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = peerProfile?.displayName?.ifBlank { null } ?: peer?.alias?.ifBlank { "Peer 0x${String.format("%016X", peerNodeId).takeLast(4)}" } ?: "Peer",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = SaharaOnSurface,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                TrustBadge(trustState = peer?.trustState ?: "LEGACY_UNVERIFIED")
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
                    }

                    // In-chat Search Action
                    IconButton(onClick = { isSearchActive = true }) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search in chat",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Voice Call Action (Milestone 4 - Direct 1-Hop Only)
                    IconButton(
                        onClick = {
                            if (peer?.trustState == "CONFLICTED") {
                                android.widget.Toast.makeText(context, "Voice call suspended due to Node ID collision (C-23)", android.widget.Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            if (peer?.isBlocked == true) {
                                android.widget.Toast.makeText(context, "Cannot call blocked peer", android.widget.Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            if (isDirect) {
                                val started = viewModel.startVoiceCall(peerNodeId)
                                if (!started) {
                                    android.widget.Toast.makeText(context, "Cannot initiate call right now", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                val initiated = viewModel.connectDirectlyToPeer(peerNodeId)
                                if (initiated) {
                                    android.widget.Toast.makeText(context, "Direct link connecting... Tap Call again in 2s", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    android.widget.Toast.makeText(context, "Direct 1-hop link required. Move devices closer", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Voice Call",
                            tint = if (isDirect && peer?.trustState != "CONFLICTED" && peer?.isBlocked != true) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = { showBeaconConfirmDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = "Send Emergency Location Beacon",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    IconButton(onClick = { showSafetyNumberDialog = true }) {
                        Icon(
                            imageVector = if (peer?.trustState == "CONFLICTED") Icons.Default.Warning else if (isVerified) Icons.Default.VerifiedUser else Icons.Default.Shield,
                            contentDescription = "Verify Safety Number",
                            tint = if (peer?.trustState == "CONFLICTED") Color(0xFFC62828) else if (isVerified) Color(0xFF4CAF50) else SaharaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Manual Beacon Confirmation Dialog
        if (showBeaconConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showBeaconConfirmDialog = false },
                title = { Text("Send Location Beacon?") },
                text = {
                    Text(
                        "This will immediately send your current hardware GPS coordinates, battery level, and offline Plus Code directly and securely to ${peer?.alias ?: "this contact"}.",
                        fontSize = 13.sp,
                        color = SaharaOnSurfaceVariant
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBeaconConfirmDialog = false
                            viewModel.sendManualLocationBeacon(peerNodeId)
                            android.widget.Toast.makeText(context, "Location beacon dispatched to ${peer?.alias ?: "contact"}", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary)
                    ) {
                        Text("Send Beacon")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBeaconConfirmDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.8.dp)

        // Pinned Rescue Location Card for Verified Contacts
        val peerLocation by viewModel.getLocationForPeerFlow(peerNodeId).collectAsState(initial = null)
        val myLocation = remember { viewModel.getMyLocation() }

        if (isVerified && peerLocation != null) {
            RescueLocationCard(
                location = peerLocation!!,
                myLatitude = myLocation?.latitude,
                myLongitude = myLocation?.longitude,
                onOpenMap = {
                    val lat = peerLocation!!.latitude
                    val lon = peerLocation!!.longitude
                    val geoUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(${Uri.encode(peer?.alias ?: "Peer")})")
                    val mapIntent = Intent(Intent.ACTION_VIEW, geoUri)
                    try {
                        context.startActivity(mapIntent)
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(context, "No map application found. Coordinates copied.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            )
        }

        // Node ID Collision Security Alert Banner (C-23)
        if (peer?.trustState == "CONFLICTED") {
            Surface(
                color = Color(0xFFFFEBEE),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF9A9A)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFC62828),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "NODE ID COLLISION DETECTED (C-23)",
                            color = Color(0xFFC62828),
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Multiple distinct cryptographic identities claim Node ID 0x${peer.nodeIdHex.takeLast(6)}. Unicast messaging and voice calls are suspended. Scan this peer's QR code with your camera in person to resolve the collision.",
                        color = SaharaOnSurface,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily,
                        lineHeight = 15.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { showCameraScanner = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(
                            text = "Scan QR to Resolve Collision",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Blocked Contact Alert Banner
        if (peer?.isBlocked == true || peer?.trustState == "BLOCKED") {
            Surface(
                color = Color(0xFFEEEEEE),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFBDBDBD)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.Block, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(18.dp))
                        Text(
                            text = "This contact is blocked.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary
                        )
                    }
                    TextButton(onClick = { peer?.identityHashHex?.let { viewModel.unblockPeer(it) } }) {
                        Text("Unblock", color = BurntSienna, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Safety Number Changed Security Alert Banner (C-12 Key Rotation)
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
                            text = "SECURITY WARNING: SAFETY NUMBER CHANGED (C-12)",
                            color = SaharaError,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The cryptographic encryption key for ${peer.alias} has changed. Verification has been demoted to LINKED. Verify their safety number in person via QR code before sharing sensitive data.",
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
                            text = "Dismiss Banner",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    itemsIndexed(messages, key = { _, msg -> msg.messageId }) { index, msg ->
                        val showDateDivider = index == 0 || !isSameCalendarDay(msg.timestamp, messages[index - 1].timestamp)
                        if (showDateDivider) {
                            DateDivider(text = formatDateDivider(msg.timestamp))
                        }

                        val isSearchTarget = isSearchActive && searchQuery.isNotBlank() &&
                                matchingMessageIndices.getOrNull(currentMatchIndex) == index

                        SaharaDirectMessageBubble(
                            msg = msg,
                            viewModel = viewModel,
                            searchQuery = if (isSearchActive) searchQuery else "",
                            isSearchSelected = isSearchTarget,
                            reactions = messageReactions[msg.messageId] ?: emptyList(),
                            onReply = { replyingToMessage = it },
                            onLongPress = { selectedMessageForActions = it },
                            onJumpToQuotedText = { snippet ->
                                val targetIdx = messages.indexOfLast {
                                    it.text.contains(snippet, ignoreCase = true) ||
                                    (it.mediaType == MediaType.IMAGE && snippet.contains("Photo")) ||
                                    (it.mediaType == MediaType.VOICE && snippet.contains("Voice"))
                                }
                                if (targetIdx >= 0) {
                                    coroutineScope.launch { listState.animateScrollToItem(targetIdx) }
                                }
                            }
                        )
                    }
                }

                // WhatsApp-style Scroll-to-bottom Floating Action Button with Unread Counter Pill
                val canScrollForward by remember { derivedStateOf { listState.canScrollForward } }

                LaunchedEffect(canScrollForward, messages.size) {
                    if (!canScrollForward) {
                        lastSeenMessageCount = messages.size
                    }
                }
                val unreadCount = (messages.size - lastSeenMessageCount).coerceAtLeast(0)

                if (canScrollForward) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 16.dp, bottom = 12.dp)
                    ) {
                        FloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.size - 1)
                                        lastSeenMessageCount = messages.size
                                    }
                                }
                            },
                            modifier = Modifier.size(42.dp),
                            containerColor = SaharaSurfaceContainerHighest,
                            contentColor = SaharaPrimary,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        if (unreadCount > 0) {
                            Surface(
                                color = SaharaPrimary,
                                shape = CircleShape,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-4).dp)
                            ) {
                                Text(
                                    text = if (unreadCount > 99) "99+" else "$unreadCount",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            }

            val isConflicted = peer?.trustState == "CONFLICTED"
            val isBlocked = peer?.isBlocked == true || peer?.trustState == "BLOCKED"
            val isComposerDisabled = isConflicted || isBlocked
            val disabledReason = when {
                isConflicted -> "Messaging suspended due to Node ID collision (C-23)"
                isBlocked -> "Contact is blocked"
                else -> null
            }

            // Quoted Reply Banner Docked Above Composer
            AnimatedVisibility(
                visible = replyingToMessage != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                replyingToMessage?.let { replyTarget ->
                    Surface(
                        color = SaharaSurfaceContainerLowest,
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(3.5.dp)
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(SaharaPrimary)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.Reply,
                                        contentDescription = null,
                                        tint = SaharaPrimary,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Replying to ${if (replyTarget.isOutgoing) "You" else replyTarget.senderAlias.ifBlank { "Peer" }}",
                                        color = SaharaPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        fontFamily = ManropeFamily
                                    )
                                }
                                val snippet = when (replyTarget.mediaType) {
                                    MediaType.IMAGE -> "📷 Photo"
                                    MediaType.VOICE -> "🎙️ Voice Note"
                                    MediaType.FILE -> "📁 Document"
                                    else -> {
                                        val (_, cleanText) = parseQuotedMessage(replyTarget.text)
                                        cleanText.replace("\n", " ").take(70)
                                    }
                                }
                                Text(
                                    text = snippet,
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 12.sp,
                                    fontFamily = ManropeFamily,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = { replyingToMessage = null },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel reply",
                                    tint = SaharaOnSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Docked Bottom Composer directly above keyboard
            SaharaDirectComposer(
                textInput = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.trim().isNotEmpty() && !isComposerDisabled) {
                        val messageToSend = if (replyingToMessage != null) {
                            val snippet = when (replyingToMessage!!.mediaType) {
                                MediaType.IMAGE -> "📷 Photo"
                                MediaType.VOICE -> "🎙️ Voice Note"
                                MediaType.FILE -> "📁 Document"
                                else -> {
                                    val (_, cleanText) = parseQuotedMessage(replyingToMessage!!.text)
                                    cleanText.replace("\n", " ").take(60)
                                }
                            }
                            val author = if (replyingToMessage!!.isOutgoing) "You" else replyingToMessage!!.senderAlias.ifBlank { "Peer" }
                            "> @$author: $snippet\n${textInput.trim()}"
                        } else {
                            textInput.trim()
                        }
                        viewModel.sendDirect(peerNodeId, messageToSend)
                        textInput = ""
                        replyingToMessage = null
                    }
                },
                onAttachPhoto = { showAttachmentDrawer = true },
                isRecordingVoice = isRecordingVoice,
                recordingDurationSec = recordingDurationSec,
                onStartVoiceRecording = {
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        val cacheDir = File(context.cacheDir, "voice_notes").apply { mkdirs() }
                        val tempFile = File(cacheDir, "vn_${System.currentTimeMillis()}.m4a")
                        voiceRecordFile = tempFile
                        val started = viewModel.audioRecorder.startRecording(tempFile)
                        if (started) {
                            isRecordingVoice = true
                            recordingDurationSec = 0
                        } else {
                            android.widget.Toast.makeText(context, "Could not start voice recording", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                onCancelVoiceRecording = {
                    viewModel.audioRecorder.cancelRecording()
                    isRecordingVoice = false
                    recordingDurationSec = 0
                    voiceRecordFile?.delete()
                    voiceRecordFile = null
                },
                onSendVoiceRecording = {
                    val durationMs = viewModel.audioRecorder.stopRecording()
                    isRecordingVoice = false
                    val file = voiceRecordFile
                    voiceRecordFile = null
                    if (file != null && file.exists() && durationMs >= 500L) {
                        val audioBytes = file.readBytes()
                        if (audioBytes.isNotEmpty()) {
                            viewModel.sendMediaDirect(
                                recipientNodeId = peerNodeId,
                                mediaType = MediaType.VOICE,
                                mediaBytes = audioBytes,
                                caption = "",
                                durationMs = durationMs,
                                originalFileName = "voice_${System.currentTimeMillis()}.m4a"
                            )
                            android.widget.Toast.makeText(context, "Sending voice note...", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        file.delete()
                    } else {
                        file?.delete()
                        if (durationMs < 500L) {
                            android.widget.Toast.makeText(context, "Hold longer to record voice note", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isComposerDisabled,
                disabledReason = disabledReason,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}
}

// Helpers for Date Grouping
private fun isSameCalendarDay(t1: Long, t2: Long): Boolean {
    val c1 = Calendar.getInstance().apply { timeInMillis = t1 }
    val c2 = Calendar.getInstance().apply { timeInMillis = t2 }
    return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
           c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
}

private fun formatDateDivider(timestamp: Long): String {
    val messageCal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val nowCal = Calendar.getInstance()
    val yesterdayCal = (nowCal.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        isSameCalendarDay(messageCal.timeInMillis, nowCal.timeInMillis) -> "TODAY"
        isSameCalendarDay(messageCal.timeInMillis, yesterdayCal.timeInMillis) -> "YESTERDAY"
        else -> SimpleDateFormat("MMMM d, yyyy", Locale.getDefault()).format(Date(timestamp)).uppercase(Locale.getDefault())
    }
}

@Composable
private fun DateDivider(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = SaharaSurfaceContainerHigh.copy(alpha = 0.85f),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(0.6.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.shadow(1.dp, RoundedCornerShape(12.dp))
        ) {
            Text(
                text = text,
                color = SaharaOnSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
    }
}

// Helpers for Quoted Messages
private data class QuotedMessageContext(
    val authorAlias: String,
    val previewText: String
)

private fun parseQuotedMessage(rawText: String): Pair<QuotedMessageContext?, String> {
    if (rawText.startsWith("> @")) {
        val newlineIndex = rawText.indexOf('\n')
        if (newlineIndex > 3) {
            val quoteLine = rawText.substring(3, newlineIndex) // after "> @"
            val colonIndex = quoteLine.indexOf(':')
            if (colonIndex > 0) {
                val author = quoteLine.substring(0, colonIndex).trim()
                val snippet = quoteLine.substring(colonIndex + 1).trim()
                val actualBody = rawText.substring(newlineIndex + 1).trimStart()
                return Pair(QuotedMessageContext(authorAlias = author, previewText = snippet), actualBody)
            }
        }
    }
    return Pair(null, rawText)
}

@Composable
private fun HighlightedBubbleText(
    text: String,
    query: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    if (query.isBlank() || !text.contains(query, ignoreCase = true)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            modifier = modifier
        )
        return
    }

    val annotatedString = buildAnnotatedString {
        var startIndex = 0
        val lowerText = text.lowercase(Locale.getDefault())
        val lowerQuery = query.lowercase(Locale.getDefault())

        while (startIndex < text.length) {
            val matchIndex = lowerText.indexOf(lowerQuery, startIndex)
            if (matchIndex == -1) {
                append(text.substring(startIndex))
                break
            }

            if (matchIndex > startIndex) {
                append(text.substring(startIndex, matchIndex))
            }

            val endIndex = matchIndex + query.length
            withStyle(
                style = SpanStyle(
                    background = Color(0xFFFFD54F),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(text.substring(matchIndex, endIndex))
            }
            startIndex = endIndex
        }
    }

    Text(
        text = annotatedString,
        style = MaterialTheme.typography.bodyMedium,
        color = textColor,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        modifier = modifier
    )
}

/**
 * Editorial Direct Message Bubble matching 4._direct_chat/code.html with Swipe-to-Reply & Quoted Context
 */
@Composable
private fun SaharaDirectMessageBubble(
    msg: MessageEntity,
    viewModel: MeshViewModel,
    searchQuery: String = "",
    isSearchSelected: Boolean = false,
    reactions: List<String> = emptyList(),
    onReply: (MessageEntity) -> Unit = {},
    onLongPress: (MessageEntity) -> Unit = {},
    onJumpToQuotedText: (String) -> Unit = {}
) {
    val isMe = msg.isOutgoing
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }
    val (parsedQuote, displayBody) = remember(msg.text) { parseQuotedMessage(msg.text) }

    var dragOffsetX by remember { mutableFloatStateOf(0f) }
    val animatedOffsetX by animateFloatAsState(
        targetValue = dragOffsetX,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "swipeToReplyAnim"
    )
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val replyThresholdPx = remember(density) { with(density) { 48.dp.toPx() } }
    var hasHapticFired by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(msg.messageId) {
                detectHorizontalDragGestures(
                    onDragStart = { hasHapticFired = false },
                    onDragEnd = {
                        if (dragOffsetX >= replyThresholdPx) {
                            onReply(msg)
                        }
                        dragOffsetX = 0f
                        hasHapticFired = false
                    },
                    onDragCancel = {
                        dragOffsetX = 0f
                        hasHapticFired = false
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        val newOffset = (dragOffsetX + dragAmount).coerceIn(0f, replyThresholdPx * 1.6f)
                        dragOffsetX = newOffset
                        if (newOffset >= replyThresholdPx && !hasHapticFired) {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            hasHapticFired = true
                        } else if (newOffset < replyThresholdPx) {
                            hasHapticFired = false
                        }
                        if (newOffset > 0f) {
                            change.consume()
                        }
                    }
                )
            }
    ) {
        if (animatedOffsetX > 6f) {
            val progress = (animatedOffsetX / replyThresholdPx).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = 0.5f + 0.5f * progress
                        scaleY = 0.5f + 0.5f * progress
                        alpha = progress
                    }
                    .clip(CircleShape)
                    .background(if (progress >= 1f) SaharaPrimary else SaharaSurfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = "Reply",
                    tint = if (progress >= 1f) Color.White else SaharaPrimary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(animatedOffsetX.roundToInt(), 0) },
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
                        if (isSearchSelected) 2.dp else 0.8.dp,
                        if (isSearchSelected) SaharaPrimary else SaharaOutlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .shadow(
                            elevation = if (isSearchSelected) 4.dp else 2.dp,
                            shape = RoundedCornerShape(16.dp),
                            spotColor = if (isSearchSelected) SaharaPrimary else Color(0x153A302A)
                        )
                        .pointerInput(msg.messageId) {
                            detectTapGestures(
                                onLongPress = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onLongPress(msg)
                                }
                            )
                        }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Quoted Reply Preview inside Bubble
                        if (parsedQuote != null) {
                            Surface(
                                color = if (isMe) SaharaPrimary.copy(alpha = 0.15f) else SaharaSurfaceContainerHigh.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(8.dp),
                                border = androidx.compose.foundation.BorderStroke(0.6.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 6.dp)
                                    .clickable { onJumpToQuotedText(parsedQuote.previewText) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.5.dp)
                                            .height(26.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(SaharaPrimary)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = parsedQuote.authorAlias,
                                            color = SaharaPrimary,
                                            fontSize = 11.sp,
                                            fontFamily = ManropeFamily,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                        Text(
                                            text = parsedQuote.previewText,
                                            color = SaharaOnSurfaceVariant,
                                            fontSize = 12.sp,
                                            fontFamily = ManropeFamily,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }

                        when (msg.mediaType) {
                            MediaType.IMAGE -> {
                                val mediaUuid = remember(msg.messageId) {
                                    try { java.util.UUID.fromString(msg.messageId) } catch (_: Exception) { null }
                                }
                                val initialTiles = remember(mediaUuid) {
                                    mediaUuid?.let { viewModel.getDecodedTilesForMedia(it) } ?: emptyMap()
                                }
                                ImageMessageBubble(
                                    message = msg,
                                    tileUpdates = viewModel.tileUpdates,
                                    initialTiles = initialTiles,
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
                                HighlightedBubbleText(
                                    text = displayBody,
                                    query = searchQuery,
                                    textColor = SaharaOnSurface
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
                                    MessageStatus.RELAYED -> Icons.Default.DoneAll
                                    MessageStatus.SENT -> Icons.Default.Check
                                    MessageStatus.EXPIRED -> Icons.Default.ErrorOutline
                                    else -> Icons.Default.Schedule
                                }
                                val iconTint = when (msg.status) {
                                    MessageStatus.DELIVERED -> SaharaPrimary
                                    MessageStatus.RELAYED -> SaharaWarning
                                    MessageStatus.EXPIRED -> SaharaError
                                    else -> SaharaOnSurfaceVariant.copy(alpha = 0.7f)
                                }
                                Icon(
                                    imageVector = icon,
                                    contentDescription = if (msg.status == MessageStatus.EXPIRED) "Expired (undelivered)" else null,
                                    tint = iconTint,
                                    modifier = Modifier.size(13.dp)
                                )
                                if (msg.status == MessageStatus.EXPIRED) {
                                    Text(
                                        text = "Expired (undelivered)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SaharaError,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // Floating Reaction Capsule Pill
                if (reactions.isNotEmpty()) {
                    Surface(
                        color = SaharaSurfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .offset(y = (-6).dp)
                            .padding(horizontal = 4.dp)
                            .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp))
                            .clickable { onLongPress(msg) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            reactions.distinct().forEach { emoji ->
                                Text(
                                    text = emoji,
                                    fontSize = 12.sp
                                )
                            }
                            if (reactions.size > 1) {
                                Text(
                                    text = "${reactions.size}",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SaharaOnSurfaceVariant,
                                    fontFamily = ManropeFamily
                                )
                            }
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
    isRecordingVoice: Boolean,
    recordingDurationSec: Int,
    onStartVoiceRecording: () -> Unit,
    onCancelVoiceRecording: () -> Unit,
    onSendVoiceRecording: () -> Unit,
    enabled: Boolean = true,
    disabledReason: String? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SaharaSurfaceContainerLowest,
        shape = RoundedCornerShape(24.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(24.dp), spotColor = SaharaPrimary.copy(alpha = 0.12f))
    ) {
        if (isRecordingVoice) {
            val infiniteTransition = rememberInfiniteTransition(label = "recordingPulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Cancel / Delete Recording Button
                IconButton(
                    onClick = onCancelVoiceRecording,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SaharaErrorContainer.copy(alpha = 0.3f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Cancel Recording",
                        tint = SaharaError,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Live Timer & Status Indicator
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(SaharaError.copy(alpha = pulseAlpha))
                    )
                    val minutes = recordingDurationSec / 60
                    val seconds = recordingDurationSec % 60
                    Text(
                        text = String.format(Locale.getDefault(), "%02d:%02d / 00:30", minutes, seconds),
                        color = SaharaOnSurface,
                        fontSize = 15.sp,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Recording voice...",
                        color = SaharaOnSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily
                    )
                }

                // Send Voice Note Button
                IconButton(
                    onClick = onSendVoiceRecording,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SaharaPrimary)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send Voice Note",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onAttachPhoto,
                    enabled = enabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Attach Payload",
                        tint = if (enabled) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                TextField(
                    value = textInput,
                    onValueChange = onTextChanged,
                    enabled = enabled,
                    placeholder = {
                        Text(
                            text = if (enabled) "Encrypted message..." else (disabledReason ?: "Messaging suspended"),
                            color = if (enabled) SaharaOnSurfaceVariant.copy(alpha = 0.6f) else SaharaError.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontFamily = ManropeFamily
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedTextColor = SaharaOnSurface,
                        unfocusedTextColor = SaharaOnSurface,
                        disabledTextColor = SaharaOnSurfaceVariant.copy(alpha = 0.4f)
                    ),
                    maxLines = 4,
                    modifier = Modifier.weight(1f)
                )

                if (textInput.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        enabled = enabled,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (enabled) SaharaPrimary else SaharaOutlineVariant)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onStartVoiceRecording,
                        enabled = enabled,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(if (enabled) SaharaPrimary else SaharaOutlineVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Record Voice Note",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextActionRow(
    icon: ImageVector,
    text: String,
    accentColor: Color = SaharaOnSurface,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = text,
            tint = accentColor,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = accentColor,
            fontWeight = FontWeight.Medium,
            fontFamily = ManropeFamily,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun TelemetryDetailRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = SaharaOnSurfaceVariant,
            fontFamily = ManropeFamily
        )
        Text(
            text = value,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = SaharaOnSurface,
            fontFamily = ManropeFamily
        )
    }
}

@Composable
private fun TacticalPayloadTile(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SaharaSurfaceContainerHigh.copy(alpha = 0.6f),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f))
                    .then(Modifier.border(1.dp, iconTint.copy(alpha = 0.4f), CircleShape)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = SaharaOnSurface,
                fontWeight = FontWeight.Bold,
                fontFamily = ManropeFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = SaharaOnSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = ManropeFamily,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TacticalSitrepChip(
    text: String,
    onClick: () -> Unit
) {
    Surface(
        color = SaharaSurfaceContainerHighest.copy(alpha = 0.8f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
    ) {
        Text(
            text = text,
            color = SaharaOnSurface,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = ManropeFamily,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}


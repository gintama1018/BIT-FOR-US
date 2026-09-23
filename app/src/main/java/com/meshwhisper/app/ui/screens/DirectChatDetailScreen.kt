package com.meshwhisper.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
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
import com.meshwhisper.app.ui.components.CallOverlayDialog
import com.meshwhisper.app.ui.components.CameraQrScannerDialog
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.components.TrustBadge
import com.meshwhisper.app.ui.components.RescueLocationCard
import com.meshwhisper.app.ui.components.SafetyNumberConfirmationDialog
import com.meshwhisper.core.identity.VerificationCandidate
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import com.meshwhisper.app.ui.viewmodel.QrScanResult
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.File
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
    val callState by viewModel.callState.collectAsState()
    val activeCallInfo by viewModel.activeCallInfo.collectAsState()
    val callDurationSeconds by viewModel.callDurationSeconds.collectAsState()
    val isCallMuted by viewModel.isCallMuted.collectAsState()
    val isCallSpeakerOn by viewModel.isCallSpeakerOn.collectAsState()

    var textInput by remember { mutableStateOf("") }
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showBeaconConfirmDialog by remember { mutableStateOf(false) }
    var verificationCandidate by remember { mutableStateOf<VerificationCandidate?>(null) }
    var computedSafetyNumber by remember { mutableStateOf<String?>(null) }
    var myFingerprintHex by remember { mutableStateOf("") }
    var peerFingerprintHex by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

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
                            android.widget.Toast.makeText(context, "Direct 1-hop link required for voice calls", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Call,
                        contentDescription = "Voice Call",
                        tint = if (isDirect && peer?.trustState != "CONFLICTED" && peer?.isBlocked != true) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.35f),
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

            val isConflicted = peer?.trustState == "CONFLICTED"
            val isBlocked = peer?.isBlocked == true || peer?.trustState == "BLOCKED"
            val isComposerDisabled = isConflicted || isBlocked
            val disabledReason = when {
                isConflicted -> "Messaging suspended due to Node ID collision (C-23)"
                isBlocked -> "Contact is blocked"
                else -> null
            }

            // Floating Bottom Composer
            SaharaDirectComposer(
                textInput = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.trim().isNotEmpty() && !isComposerDisabled) {
                        viewModel.sendDirect(peerNodeId, textInput.trim())
                        textInput = ""
                    }
                },
                onAttachPhoto = { photoPickerLauncher.launch("image/*") },
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
        shape = RoundedCornerShape(18.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(18.dp), spotColor = SaharaPrimary.copy(alpha = 0.15f))
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
                        contentDescription = "Attach Photo",
                        tint = if (enabled) SaharaOnSurfaceVariant else SaharaOnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onStartVoiceRecording,
                    enabled = enabled,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = "Record Voice Note",
                        tint = if (enabled) SaharaPrimary else SaharaOnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.size(22.dp)
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

package com.meshwhisper.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.ui.components.CameraQrScannerDialog
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.RescueLocationCard
import com.meshwhisper.app.ui.components.TrustBadge
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import com.meshwhisper.app.ui.viewmodel.QrScanResult
import com.meshwhisper.app.util.PlusCodeHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Peer Dossier & Tactical Contact Info Screen (WhatsApp-Style Contact Info + Mesh Telemetry).
 * Displays hero identity, quick tactical action buttons, shared transmissions vault
 * (photos, voice notes, location breadcrumbs), mesh link routing stats, and privacy/block controls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDossierScreen(
    peerNodeId: Long,
    viewModel: MeshViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    val peers by viewModel.peers.collectAsState()
    val peer = peers.firstOrNull { it.nodeId == peerNodeId }
    val peerProfile by viewModel.getPeerProfileFlow(peerNodeId).collectAsState(initial = null)
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()
    val isDirect = connectedNodeIds.contains(peerNodeId)
    val isVerified = peer?.isVerified == true
    val isMuted = peer?.isMuted == true
    val isBlocked = peer?.isBlocked == true

    val messages by viewModel.getDirectMessagesForPeer(peerNodeId).collectAsState(initial = emptyList())
    val peerLocation by viewModel.getLocationForPeerFlow(peerNodeId).collectAsState(initial = null)

    var selectedVaultTab by remember { mutableIntStateOf(0) } // 0 = Photos, 1 = Voice, 2 = Locations
    var showSafetyNumberDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var showClearChatDialog by remember { mutableStateOf(false) }
    var showBlockConfirmDialog by remember { mutableStateOf(false) }
    var previewImageMessage by remember { mutableStateOf<MessageEntity?>(null) }
    var computedSafetyNumber by remember { mutableStateOf<String?>(null) }

    val photoMessages = remember(messages) {
        messages.filter { it.mediaType == MediaType.IMAGE }
    }
    val voiceMessages = remember(messages) {
        messages.filter { it.mediaType == MediaType.VOICE }
    }
    val locationMessages = remember(messages) {
        messages.filter { it.text.contains("LOCATION BEACON") || it.text.contains("GPS BEACON") || it.text.contains("Plus Code") }
    }

    val totalMediaCount = photoMessages.size + voiceMessages.size + locationMessages.size

    val dateFormat = remember { SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()) }

    LaunchedEffect(showSafetyNumberDialog, peerNodeId) {
        if (showSafetyNumberDialog) {
            computedSafetyNumber = viewModel.getSafetyNumberForPeer(peerNodeId)
        }
    }

    Scaffold(
        topBar = {
            Surface(
                color = SaharaBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = SaharaPrimary
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Peer Dossier",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = SaharaOnSurface,
                            fontFamily = ManropeFamily
                        )
                        Text(
                            text = if (isDirect) "Direct Socket • 1 Hop" else "Mesh Relayed (${peer?.hopCount ?: 1} hops)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isDirect) SaharaOnline else SaharaOnSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = {
                            val idHex = "0x" + String.format(Locale.US, "%016X", peerNodeId)
                            clipboardManager.setText(AnnotatedString(idHex))
                            Toast.makeText(context, "Node ID copied: $idHex", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share Node Info",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        },
        containerColor = SaharaBackground,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. HERO IDENTITY CARD
            item {
                Surface(
                    color = SaharaSurfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(3.dp, RoundedCornerShape(20.dp), spotColor = SaharaPrimary.copy(alpha = 0.1f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Large Avatar with pulse ring
                        Box(contentAlignment = Alignment.Center) {
                            Box(
                                modifier = Modifier
                                    .size(96.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isDirect) SaharaOnline.copy(alpha = 0.18f)
                                        else SaharaWarning.copy(alpha = 0.18f)
                                    )
                                    .border(
                                        2.dp,
                                        if (isDirect) SaharaOnline else SaharaWarning,
                                        CircleShape
                                    )
                            )
                            NodeAvatar(
                                nodeId = peerNodeId,
                                alias = peer?.alias ?: "",
                                size = 84.dp,
                                avatarUri = peerProfile?.avatarUri ?: peer?.avatarUri,
                                isDirect = isDirect
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Display Name & Trust Badge
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = peerProfile?.displayName?.ifBlank { null }
                                    ?: peer?.alias?.ifBlank { "Peer 0x${String.format("%016X", peerNodeId).takeLast(4)}" }
                                    ?: "Peer Node",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SaharaOnSurface,
                                fontFamily = ManropeFamily,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            TrustBadge(trustState = peer?.trustState ?: "LEGACY_UNVERIFIED")
                        }

                        // Tactical Bio / Status
                        if (!peerProfile?.bio.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "“${peerProfile!!.bio}”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = SaharaOnSurfaceVariant,
                                fontSize = 13.sp,
                                fontFamily = ManropeFamily,
                                textAlign = TextAlign.Center
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Node ID Hex Pill with 1-Tap Copy
                        Surface(
                            color = SaharaSurfaceContainerHigh,
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    val idHex = "0x" + String.format(Locale.US, "%016X", peerNodeId)
                                    clipboardManager.setText(AnnotatedString(idHex))
                                    Toast.makeText(context, "Node ID copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "0x" + String.format(Locale.US, "%016X", peerNodeId),
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    color = SaharaPrimary
                                )
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Node ID",
                                    tint = SaharaOnSurfaceVariant,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }

            // 2. WHATSAPP-STYLE 4 ACTION BUTTONS
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Voice Call
                    DossierActionButton(
                        icon = Icons.Default.Call,
                        label = "Voice Call",
                        accentColor = if (isDirect) SaharaOnline else SaharaOnSurfaceVariant,
                        onClick = {
                            if (!isDirect) {
                                val initiated = viewModel.connectDirectlyToPeer(peerNodeId)
                                if (initiated) {
                                    Toast.makeText(context, "Direct link connecting... Tap Call in 2s", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Direct 1-hop link required. Move devices closer", Toast.LENGTH_SHORT).show()
                                }
                            } else if (peer?.trustState == "CONFLICTED") {
                                Toast.makeText(context, "Call suspended due to Node ID collision", Toast.LENGTH_SHORT).show()
                            } else {
                                val started = viewModel.startVoiceCall(peerNodeId)
                                if (!started) {
                                    Toast.makeText(context, "Cannot initiate call right now", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Homing Radar
                    DossierActionButton(
                        icon = Icons.Default.Explore,
                        label = "Homing",
                        accentColor = SaharaPrimary,
                        onClick = {
                            viewModel.selectHomingPeer(peerNodeId)
                            Toast.makeText(context, "🧭 Compass locked on ${peer?.alias ?: "Peer"}", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Verify Safety
                    DossierActionButton(
                        icon = if (isVerified) Icons.Default.Verified else Icons.Default.Shield,
                        label = if (isVerified) "Verified" else "Verify Key",
                        accentColor = if (isVerified) SaharaOnline else SaharaTertiary,
                        onClick = { showSafetyNumberDialog = true },
                        modifier = Modifier.weight(1f)
                    )

                    // Mute / Priority
                    DossierActionButton(
                        icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.Default.Notifications,
                        label = if (isMuted) "Unmute" else "Mute",
                        accentColor = if (isMuted) SaharaWarning else SaharaOnSurfaceVariant,
                        onClick = {
                            val newMuted = !isMuted
                            viewModel.setPeerMuted(peerNodeId, newMuted)
                            Toast.makeText(context, if (newMuted) "Notifications muted for this peer" else "Notifications unmuted", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 3. SHARED MEDIA, AUDIO & LOCATION VAULT (WhatsApp Style)
            item {
                Surface(
                    color = SaharaSurfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Shared Transmissions & Media",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = SaharaOnSurface,
                                    fontFamily = ManropeFamily
                                )
                                Text(
                                    text = "$totalMediaCount items exchanged off-grid",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 11.sp
                                )
                            }

                            Surface(
                                color = SaharaPrimary.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(
                                    text = "$totalMediaCount",
                                    color = SaharaPrimary,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 12.sp,
                                    fontFamily = ManropeFamily,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Segmented Tab Selector
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SaharaSurfaceContainerHigh)
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val tabs = listOf(
                                "Photos (${photoMessages.size})" to 0,
                                "Voice (${voiceMessages.size})" to 1,
                                "Coords (${locationMessages.size})" to 2
                            )
                            tabs.forEach { (title, index) ->
                                val isSelected = selectedVaultTab == index
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (isSelected) SaharaSurfaceContainerLowest else Color.Transparent)
                                        .then(
                                            if (isSelected) Modifier.shadow(2.dp, RoundedCornerShape(10.dp))
                                            else Modifier
                                        )
                                        .clickable { selectedVaultTab = index }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) SaharaPrimary else SaharaOnSurfaceVariant,
                                        fontFamily = ManropeFamily
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Vault Tab Content
                        when (selectedVaultTab) {
                            0 -> {
                                // Photos Tab
                                if (photoMessages.isEmpty()) {
                                    EmptyVaultPlaceholder(
                                        icon = Icons.Default.Image,
                                        title = "No Mesh Photos Exchanged",
                                        subtitle = "Compressed tile photos sent or received will appear here."
                                    )
                                } else {
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        items(photoMessages) { photoMsg ->
                                            Surface(
                                                color = SaharaSurfaceContainerHigh,
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                                                modifier = Modifier
                                                    .width(140.dp)
                                                    .height(130.dp)
                                                    .clip(RoundedCornerShape(12.dp))
                                                    .clickable { previewImageMessage = photoMsg }
                                            ) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    ImageMessageBubble(
                                                        message = photoMsg,
                                                        tileUpdates = viewModel.tileUpdates,
                                                        isOutgoing = photoMsg.isOutgoing
                                                    )
                                                    Surface(
                                                        color = Color.Black.copy(alpha = 0.65f),
                                                        shape = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp),
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .align(Alignment.BottomCenter)
                                                    ) {
                                                        Text(
                                                            text = if (photoMsg.isOutgoing) "Sent • ${dateFormat.format(Date(photoMsg.timestamp))}" else "Recv • ${dateFormat.format(Date(photoMsg.timestamp))}",
                                                            color = Color.White,
                                                            fontSize = 9.sp,
                                                            fontFamily = ManropeFamily,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            1 -> {
                                // Voice Notes Tab
                                if (voiceMessages.isEmpty()) {
                                    EmptyVaultPlaceholder(
                                        icon = Icons.Default.Mic,
                                        title = "No Voice Transmissions",
                                        subtitle = "Voice notes recorded and received will appear here."
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        voiceMessages.forEach { voiceMsg ->
                                            VoiceNoteBubble(
                                                message = voiceMsg,
                                                isOutgoing = voiceMsg.isOutgoing,
                                                audioPlayer = viewModel.audioPlayer
                                            )
                                        }
                                    }
                                }
                            }
                            2 -> {
                                // Location Breadcrumbs Tab
                                if (locationMessages.isEmpty() && peerLocation == null) {
                                    EmptyVaultPlaceholder(
                                        icon = Icons.Default.LocationOn,
                                        title = "No GPS Beacons Exchanged",
                                        subtitle = "Location fixes & emergency coordinates will be cataloged here."
                                    )
                                } else {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        // Pinned Hardware Location Card if active
                                        peerLocation?.let { loc ->
                                            Text(
                                                text = "PINNED HARDWARE FIX",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = SaharaPrimary,
                                                fontFamily = ManropeFamily
                                            )
                                            RescueLocationCard(
                                                location = loc,
                                                onOpenMap = {
                                                    val geoUri = Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}(${Uri.encode(peer?.alias ?: "Peer")})")
                                                    try {
                                                        context.startActivity(Intent(Intent.ACTION_VIEW, geoUri))
                                                    } catch (_: Exception) {
                                                        Toast.makeText(context, "No map application found", Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            )
                                        }

                                        // History of messages containing coordinates
                                        locationMessages.forEach { locMsg ->
                                            Surface(
                                                color = SaharaSurfaceContainerHigh,
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(0.6.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(36.dp)
                                                            .clip(CircleShape)
                                                            .background(SaharaTertiary.copy(alpha = 0.2f)),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.LocationOn,
                                                            contentDescription = null,
                                                            tint = SaharaTertiary,
                                                            modifier = Modifier.size(18.dp)
                                                        )
                                                    }
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = locMsg.text.lines().firstOrNull() ?: "Location Beacon",
                                                            fontWeight = FontWeight.Bold,
                                                            fontSize = 12.sp,
                                                            color = SaharaOnSurface,
                                                            fontFamily = ManropeFamily
                                                        )
                                                        Text(
                                                            text = dateFormat.format(Date(locMsg.timestamp)),
                                                            fontSize = 10.sp,
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
                    }
                }
            }

            // 4. MESH RF LINK & CIPHER TELEMETRY CARD
            item {
                Surface(
                    color = SaharaSurfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "MESH LINK & CIPHER TELEMETRY",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SaharaPrimary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            letterSpacing = 0.5.sp
                        )

                        DossierTelemetryRow(
                            label = "RF Connection Topology",
                            value = if (isDirect) "Direct BLE/Wi-Fi Socket (1 Hop)" else "Mesh Relayed (${peer?.hopCount ?: 1} Hops)"
                        )

                        DossierTelemetryRow(
                            label = "RF Signal Strength (RSSI)",
                            value = "${peer?.rssi ?: -70} dBm"
                        )

                        DossierTelemetryRow(
                            label = "Cipher State",
                            value = "ChaCha20-Poly1305 AEAD ✓"
                        )

                        DossierTelemetryRow(
                            label = "Authentication",
                            value = "Ed25519 Curve Signatures ✓"
                        )

                        DossierTelemetryRow(
                            label = "Safety Number Trust State",
                            value = peer?.trustState ?: "SEEN (Unverified)"
                        )

                        if (peerLocation != null) {
                            val plusCode = PlusCodeHelper.encode(peerLocation!!.latitude, peerLocation!!.longitude)
                            DossierTelemetryRow(
                                label = "Offline Plus Code",
                                value = plusCode
                            )
                        }
                    }
                }
            }

            // 5. PRIVACY & NODE CONTROLS (WhatsApp Settings)
            item {
                Surface(
                    color = SaharaSurfaceContainerLowest,
                    shape = RoundedCornerShape(20.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "PRIVACY & NODE CONTROLS",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = SaharaOnSurfaceVariant,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            letterSpacing = 0.5.sp
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Location Sharing Toggle
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Share Emergency Location",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = SaharaOnSurface,
                                    fontFamily = ManropeFamily
                                )
                                Text(
                                    text = "Send automated dying gasp & emergency breadcrumbs to this node",
                                    fontSize = 11.sp,
                                    color = SaharaOnSurfaceVariant,
                                    fontFamily = ManropeFamily
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

                        HorizontalDivider(
                            color = SaharaOutlineVariant.copy(alpha = 0.4f),
                            thickness = 0.6.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        // Clear Chat
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { showClearChatDialog = true }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Clear Chat",
                                tint = SaharaError,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Clear Chat History",
                                color = SaharaError,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = ManropeFamily
                            )
                        }

                        HorizontalDivider(
                            color = SaharaOutlineVariant.copy(alpha = 0.4f),
                            thickness = 0.6.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        // Block / Unblock Peer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (isBlocked) {
                                        peer?.identityHashHex?.let { viewModel.unblockPeer(it) }
                                        Toast.makeText(context, "Node unblocked", Toast.LENGTH_SHORT).show()
                                    } else {
                                        showBlockConfirmDialog = true
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Block,
                                contentDescription = "Block Node",
                                tint = SaharaError,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = if (isBlocked) "Unblock Node" else "Block Node (Drop Packets)",
                                color = SaharaError,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = ManropeFamily
                            )
                        }
                    }
                }
            }
        }
    }

    // Safety Number Verification Dialog
    if (showSafetyNumberDialog) {
        val safetyNumber = computedSafetyNumber ?: "Awaiting Authenticated Announce"
        AlertDialog(
            onDismissRequest = { showSafetyNumberDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = if (isVerified) Icons.Default.Verified else Icons.Default.Shield,
                        contentDescription = null,
                        tint = if (isVerified) SaharaOnline else SaharaPrimary
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
                        text = "Compare this 60-digit safety number with ${peer?.alias ?: "peer"}'s screen to authenticate E2EE key integrity.",
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
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = SaharaPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSafetyNumberDialog = false }) {
                    Text("Close", color = SaharaPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(safetyNumber))
                    Toast.makeText(context, "Safety number copied", Toast.LENGTH_SHORT).show()
                }) {
                    Text("Copy", color = SaharaOnSurfaceVariant)
                }
            }
        )
    }

    // Clear Chat Confirmation Modal
    if (showClearChatDialog) {
        AlertDialog(
            onDismissRequest = { showClearChatDialog = false },
            title = {
                Text("Clear Chat History?", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Text(
                    text = "Are you sure you want to delete all messages with ${peer?.alias ?: "this node"}? This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaharaOnSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearChatWithPeer(peerNodeId)
                        Toast.makeText(context, "Chat history cleared", Toast.LENGTH_SHORT).show()
                        showClearChatDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete All", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearChatDialog = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            }
        )
    }

    // Block Confirmation Modal
    if (showBlockConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showBlockConfirmDialog = false },
            title = {
                Text("Block Node?", color = SaharaError, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Text(
                    text = "Incoming mesh packets from ${peer?.alias ?: "this node"} will be dropped automatically by the router.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaharaOnSurface
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        peer?.identityHashHex?.let { viewModel.blockPeer(it) }
                        Toast.makeText(context, "Node blocked", Toast.LENGTH_SHORT).show()
                        showBlockConfirmDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Block", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockConfirmDialog = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            }
        )
    }

    // Fullscreen Image Preview Dialog
    previewImageMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { previewImageMessage = null },
            title = {
                Text("Transmitted Photo", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ImageMessageBubble(
                        message = msg,
                        tileUpdates = viewModel.tileUpdates,
                        isOutgoing = msg.isOutgoing
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { previewImageMessage = null }) {
                    Text("Close", color = SaharaPrimary, fontWeight = FontWeight.Bold)
                }
            }
        )
    }
}

@Composable
private fun DossierActionButton(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.14f))
                .border(1.2.dp, accentColor.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = SaharaOnSurface,
            fontFamily = ManropeFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DossierTelemetryRow(
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
private fun EmptyVaultPlaceholder(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SaharaOutlineVariant,
            modifier = Modifier.size(32.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = SaharaOnSurfaceVariant,
            fontFamily = ManropeFamily
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = SaharaOutlineVariant,
            fontSize = 11.sp,
            fontFamily = ManropeFamily,
            textAlign = TextAlign.Center
        )
    }
}

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.media.MediaCompressor
import com.meshwhisper.app.ui.components.ImageMessageBubble
import com.meshwhisper.app.ui.components.SaharaTopAppBar
import com.meshwhisper.app.ui.components.VoiceNoteBubble
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PublicMeshScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val messages by viewModel.broadcastMessages.collectAsState()
    val connectedNodes by viewModel.connectedPeersCount.collectAsState()
    val activeSos by viewModel.activeSosAlert.collectAsState()
    var textInput by remember { mutableStateOf("") }
    var showSosDialog by remember { mutableStateOf(false) }
    var sosCustomText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Media Pickers
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val tiledResult = MediaCompressor.compressImageAsTiles(context, uri, com.meshwhisper.app.media.ImageQuality.STANDARD, 3, 3)
            if (tiledResult != null) {
                viewModel.sendMediaBroadcast(
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

    if (showSosDialog) {
        AlertDialog(
            onDismissRequest = { showSosDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🚨", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Broadcast Emergency SOS",
                        color = SaharaError,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "This will broadcast an urgent emergency SOS packet across all decentralized mesh nodes with high-priority flood relay.",
                        color = SaharaOnSurface,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = sosCustomText,
                        onValueChange = { sosCustomText = it },
                        placeholder = { Text("Describe emergency / needs (optional)...", color = TextMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SaharaError,
                            unfocusedBorderColor = SaharaOutlineVariant
                        ),
                        shape = RoundedCornerShape(10.dp),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val text = if (sosCustomText.isNotBlank()) "🚨 SOS: ${sosCustomText.trim()}" else "🚨 EMERGENCY SOS: Immediate assistance requested!"
                        viewModel.sendSosBroadcast(text)
                        showSosDialog = false
                        sosCustomText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text("BROADCAST SOS", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSosDialog = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            },
            containerColor = SaharaSurfaceContainerLowest,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Sticky Editorial Header matching 2._public_mesh
        SaharaTopAppBar(
            title = "Public Mesh",
            peerCount = connectedNodes,
            onActionClick = { showSosDialog = true }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 8.dp, bottom = 140.dp)
            ) {
                // Active SOS Alert Card
                if (activeSos != null) {
                    item(key = "active_sos") {
                        SaharaSosCard(
                            alert = activeSos!!,
                            onDismiss = { viewModel.dismissSosAlert() }
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }

                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CellTower,
                                    contentDescription = null,
                                    tint = SaharaPrimary.copy(alpha = 0.4f),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "Public Mesh Channel",
                                    color = SaharaOnSurface,
                                    fontSize = 20.sp,
                                    fontFamily = EBGaramondFamily,
                                    fontWeight = FontWeight.Medium
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "All broadcasts flood through nearby offline mesh nodes.\nAuthenticated and community encrypted.",
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 13.sp,
                                    fontFamily = ManropeFamily,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                } else {
                    items(messages, key = { it.messageId }) { msg ->
                        SaharaEditorialMessageCard(
                            msg = msg,
                            viewModel = viewModel
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
            }

            // Floating Bottom Composer matching 2._public_mesh
            SaharaPublicComposer(
                textInput = textInput,
                onTextChanged = { textInput = it },
                onSend = {
                    if (textInput.trim().isNotEmpty()) {
                        viewModel.sendBroadcast(textInput.trim())
                        textInput = ""
                    }
                },
                onAttachPhoto = { photoPickerLauncher.launch("image/*") },
                onSosClick = { showSosDialog = true },
                connectedNodes = connectedNodes,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * Editorial Sahara SOS Card matching 2._public_mesh/code.html
 */
@Composable
private fun SaharaSosCard(
    alert: com.meshwhisper.app.ui.viewmodel.SosAlertEvent,
    onDismiss: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SaharaErrorContainer.copy(alpha = 0.35f)),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaError.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left Solid Red Accent Line
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(SaharaError)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "SOS",
                            tint = SaharaError,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "EMERGENCY SOS",
                            color = SaharaError,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    }

                    Text(
                        text = "From ${alert.senderAlias}",
                        color = SaharaError.copy(alpha = 0.8f),
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = alert.text,
                    color = SaharaOnSurface,
                    fontSize = 15.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                        shape = RoundedCornerShape(18.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Acknowledge", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Editorial Message Card matching 2._public_mesh/code.html
 */
@Composable
private fun SaharaEditorialMessageCard(
    msg: MessageEntity,
    viewModel: MeshViewModel
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val formattedTime = remember(msg.timestamp) { timeFormat.format(Date(msg.timestamp)) }
    val isSystemAdvisory = msg.senderAlias.contains("System", ignoreCase = true) || msg.senderAlias.contains("Advisory", ignoreCase = true)

    if (isSystemAdvisory) {
        Card(
            colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainerLow),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Campaign,
                            contentDescription = null,
                            tint = SaharaPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "OFFICIAL ADVISORY",
                            color = SaharaPrimary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "$formattedTime • Broadcast",
                        color = SaharaOnSurfaceVariant,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg.text,
                    color = SaharaOnSurface,
                    fontSize = 14.sp,
                    fontFamily = ManropeFamily,
                    lineHeight = 20.sp
                )
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp)
        ) {
            // Header: Sender in Burnt Sienna + Time / Hops
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (msg.isOutgoing) "Me (0x${String.format("%016X", msg.senderId).takeLast(4)})" else (msg.senderAlias.ifBlank { "Peer 0x${String.format("%016X", msg.senderId).takeLast(4)}" }),
                    color = SaharaPrimary,
                    fontSize = 13.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Bold
                )

                Text(
                    text = "$formattedTime • ${if (msg.hopCount == 0) "Direct" else "${msg.hopCount} hop(s)"}",
                    color = SaharaOnSurfaceVariant,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Body
            when (msg.mediaType) {
                MediaType.IMAGE -> {
                    ImageMessageBubble(
                        message = msg,
                        tileUpdates = viewModel.tileUpdates,
                        isOutgoing = msg.isOutgoing,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                MediaType.VOICE -> {
                    VoiceNoteBubble(
                        message = msg,
                        isOutgoing = msg.isOutgoing,
                        audioPlayer = viewModel.audioPlayer,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                else -> {
                    Text(
                        text = msg.text,
                        color = SaharaOnSurface,
                        fontSize = 15.sp,
                        fontFamily = ManropeFamily,
                        lineHeight = 22.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.8.dp)
        }
    }
}

/**
 * Floating Sahara Composer matching 2._public_mesh/code.html
 */
@Composable
private fun SaharaPublicComposer(
    textInput: String,
    onTextChanged: (String) -> Unit,
    onSend: () -> Unit,
    onAttachPhoto: () -> Unit,
    onSosClick: () -> Unit,
    connectedNodes: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SaharaSurfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.6f)),
        modifier = modifier
            .fillMaxWidth()
            .shadow(6.dp, RoundedCornerShape(16.dp), spotColor = SaharaPrimary.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Text Input & Send FAB Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = textInput,
                    onValueChange = onTextChanged,
                    placeholder = {
                        Text(
                            text = "Broadcast to the mesh...",
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
                    maxLines = 3,
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

            // Bottom Utility Toolbar
            Surface(
                color = SaharaSurfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = onAttachPhoto,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AttachFile,
                                contentDescription = "Attach",
                                tint = SaharaOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = { /* Voice note trigger */ },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice",
                                tint = SaharaOnSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        IconButton(
                            onClick = onSosClick,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(SaharaErrorContainer.copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Emergency,
                                contentDescription = "SOS",
                                tint = SaharaError,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Text(
                        text = "Public • Est. reach: ${maxOf(1, connectedNodes)} node(s)",
                        color = SaharaOnSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }
            }
        }
    }
}

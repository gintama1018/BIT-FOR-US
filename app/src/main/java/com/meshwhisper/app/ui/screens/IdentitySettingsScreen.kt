package com.meshwhisper.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.meshwhisper.app.ui.components.CameraQrScannerDialog
import com.meshwhisper.app.ui.viewmodel.QrScanResult
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.SaharaTopAppBar
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.util.QrCodeGenerator
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

@Composable
fun IdentitySettingsScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val myAlias by viewModel.myAlias.collectAsState()
    val myAvatarUri by viewModel.myAvatarUri.collectAsState()
    val isNotificationsEnabled by viewModel.isNotificationsEnabled.collectAsState()
    val showNotificationPreviews by viewModel.showNotificationPreviews.collectAsState()
    val supportsPeripheral by viewModel.supportsPeripheral.collectAsState()
    val identityVersion by viewModel.identityVersion.collectAsState()

    var isEditingAlias by remember { mutableStateOf(false) }
    var aliasInput by remember { mutableStateOf(myAlias) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showImportContactDialog by remember { mutableStateOf(false) }
    var showCameraScanner by remember { mutableStateOf(false) }
    var importContactInput by remember { mutableStateOf("") }
    var showPanicDialog by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val avatarPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            viewModel.updateMyAvatar(context, uri)
            Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
        }
    }

    val legacyAvatarPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            viewModel.updateMyAvatar(context, uri)
            Toast.makeText(context, "Profile photo updated", Toast.LENGTH_SHORT).show()
        }
    }

    val qrContent = remember(myAlias, viewModel.myNodeIdHex, viewModel.myPublicKeyHex, identityVersion) {
        "meshwhisper://node?id=${viewModel.myNodeIdHex}&alias=$myAlias&pub=${viewModel.myPublicKeyHex}"
    }
    val qrBitmap = remember(qrContent) { QrCodeGenerator.generateQrBitmap(qrContent, 400) }
    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Sahara Top Bar
        SaharaTopAppBar(
            title = "Station Vault",
            subtitle = "IDENTITY & CIPHER KEYS",
            actionIcon = Icons.Default.QrCode2,
            actionIconTint = SaharaPrimary,
            onActionClick = { showQrDialog = true }
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Identity Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainerLowest),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "LOCAL NODE IDENTITY",
                                color = SaharaPrimary,
                                fontSize = 11.sp,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = { showCameraScanner = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = "Scan Peer QR",
                                        tint = SaharaPrimary
                                    )
                                }
                                IconButton(
                                    onClick = { showQrDialog = true },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCode2,
                                        contentDescription = "Show QR",
                                        tint = SaharaPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Profile Photo & Avatar Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            NodeAvatar(
                                nodeId = viewModel.myNodeId,
                                alias = myAlias,
                                size = 56.dp,
                                avatarUri = myAvatarUri
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            try {
                                                avatarPickerLauncher.launch(
                                                    androidx.activity.result.PickVisualMediaRequest(
                                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                                    )
                                                )
                                            } catch (_: Exception) {
                                                legacyAvatarPickerLauncher.launch("image/*")
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Text(
                                            text = if (myAvatarUri != null) "Change Photo" else "Add Photo",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontFamily = ManropeFamily,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    if (myAvatarUri != null) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        TextButton(
                                            onClick = {
                                                viewModel.removeMyAvatar()
                                                Toast.makeText(context, "Profile photo removed", Toast.LENGTH_SHORT).show()
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) {
                                            Text(
                                                text = "Remove",
                                                color = SaharaError,
                                                fontSize = 11.sp,
                                                fontFamily = ManropeFamily
                                            )
                                        }
                                    }
                                }

                                Text(
                                    text = "Synced automatically with peers over BLE / Wi-Fi",
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontFamily = ManropeFamily,
                                    lineHeight = 14.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Alias Editor
                        if (isEditingAlias) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = aliasInput,
                                    onValueChange = { aliasInput = it },
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = SaharaPrimary,
                                        unfocusedBorderColor = SaharaOutlineVariant,
                                        focusedTextColor = SaharaOnSurface,
                                        unfocusedTextColor = SaharaOnSurface,
                                        cursorColor = SaharaPrimary,
                                        focusedContainerColor = SaharaSurfaceContainerLowest,
                                        unfocusedContainerColor = SaharaSurfaceContainerLowest
                                    ),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        viewModel.updateAlias(aliasInput)
                                        isEditingAlias = false
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Save", tint = SaharaPrimary)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = myAlias,
                                    color = SaharaOnSurface,
                                    fontSize = 18.sp,
                                    fontFamily = EBGaramondFamily,
                                    fontWeight = FontWeight.Bold
                                )
                                IconButton(
                                    onClick = {
                                        aliasInput = myAlias
                                        isEditingAlias = true
                                    }
                                ) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Alias", tint = SaharaOnSurfaceVariant, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 64-bit Hex Node ID
                        Text(text = "NODE ID (64-BIT HEX)", color = SaharaOnSurfaceVariant, fontSize = 10.sp, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(viewModel.myNodeIdHex))
                                    Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = viewModel.myNodeIdHex,
                                color = SaharaPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Node ID",
                                tint = SaharaOnSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Public Key Fingerprint
                        Text(text = "X25519 FINGERPRINT", color = SaharaOnSurfaceVariant, fontSize = 10.sp, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(viewModel.myFingerprint))
                                    Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 4.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = SaharaPrimary, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewModel.myFingerprint,
                                color = SaharaPrimary,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Fingerprint",
                                tint = SaharaOnSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Mesh Network & Battery Management Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainerLowest),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "MESH NETWORK & POWER",
                            color = SaharaPrimary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        val isBackgroundRelay by viewModel.isBackgroundRelayEnabled.collectAsState()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Background Mesh Relay",
                                    color = SaharaOnSurface,
                                    fontSize = 14.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isBackgroundRelay)
                                        "Relaying encrypted packets in background using balanced duty-cycling."
                                    else
                                        "Relay paused when app is closed.",
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = ManropeFamily,
                                    lineHeight = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = isBackgroundRelay,
                                onCheckedChange = { viewModel.setBackgroundRelayEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SaharaPrimary,
                                    uncheckedThumbColor = SaharaOnSurfaceVariant,
                                    uncheckedTrackColor = SaharaSurfaceContainerHigh
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.6.dp)
                        Spacer(modifier = Modifier.height(8.dp))

                        DiagnosticRow(
                            label = "Foreground Radio Mode",
                            value = "High-Speed (Low Latency)",
                            isOk = true
                        )
                        DiagnosticRow(
                            label = "Background Radio Mode",
                            value = if (isBackgroundRelay) "Balanced (~75% Power Saved)" else "Disabled / Inactive",
                            isOk = isBackgroundRelay
                        )
                        DiagnosticRow(
                            label = "Background CPU WakeLock",
                            value = "Event-Driven Only",
                            isOk = true
                        )
                    }
                }
            }

            // Security & Privacy Settings Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainerLowest),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "SECURITY & VAULT",
                            color = SaharaPrimary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Biometric App Lock",
                                    color = SaharaOnSurface,
                                    fontSize = 14.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Require Fingerprint / Face authentication on app resume",
                                    color = SaharaOnSurfaceVariant,
                                    fontSize = 11.sp,
                                    fontFamily = ManropeFamily,
                                    lineHeight = 15.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = isAppLockEnabled,
                                onCheckedChange = { viewModel.setAppLockEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = SaharaPrimary,
                                    uncheckedThumbColor = SaharaOnSurfaceVariant,
                                    uncheckedTrackColor = SaharaSurfaceContainerHigh
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.6.dp)
                        Spacer(modifier = Modifier.height(10.dp))

                        Button(
                            onClick = { showPanicDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SaharaErrorContainer),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "🚨 Emergency Panic Duress Wipe",
                                color = SaharaError,
                                fontSize = 13.sp,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    // QR Verification Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text("Close", color = SaharaPrimary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showQrDialog = false
                    importContactInput = ""
                    showImportContactDialog = true
                }) {
                    Text("Import Peer Link", color = SaharaOnSurfaceVariant)
                }
            },
            title = {
                Text(
                    text = "Out-of-Band Key Verification",
                    color = SaharaPrimary,
                    fontFamily = EBGaramondFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = "Identity QR Code",
                            modifier = Modifier
                                .size(220.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White)
                                .padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scan this QR code with another MeshWhisper device to establish a verified cryptographic link and authenticate safety numbers.",
                        color = SaharaOnSurfaceVariant,
                        fontFamily = ManropeFamily,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 17.sp
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Button(
                        onClick = {
                            showQrDialog = false
                            showCameraScanner = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Peer's Screen with Camera")
                    }
                }
            },
            containerColor = SaharaSurfaceContainerLowest,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Import / Verify Contact Code Dialog (Closes the Trust Loop)
    if (showImportContactDialog) {
        AlertDialog(
            onDismissRequest = { showImportContactDialog = false },
            title = {
                Text(
                    text = "Verify Peer Contact Link",
                    color = SaharaPrimary,
                    fontFamily = EBGaramondFamily,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            showImportContactDialog = false
                            showCameraScanner = true
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Scan Screen with Camera")
                    }
                    Text(
                        text = "Or paste a contact URI (meshwhisper://node?id=...&alias=...&pub=...) shared from another device:",
                        color = SaharaOnSurfaceVariant,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily
                    )
                    OutlinedTextField(
                        value = importContactInput,
                        onValueChange = { importContactInput = it },
                        placeholder = { Text("meshwhisper://node?id=...", color = SaharaOutline) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SaharaPrimary,
                            unfocusedBorderColor = SaharaOutlineVariant
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val uriString = importContactInput.trim()
                        val uri = try { android.net.Uri.parse(uriString) } catch (_: Exception) { null }
                        if (uri != null && uri.scheme == "meshwhisper" && uri.host == "node") {
                            val idHex = uri.getQueryParameter("id")
                            val alias = uri.getQueryParameter("alias") ?: "Verified Peer"
                            val pubHex = uri.getQueryParameter("pub")
                            if (!idHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
                                try {
                                    val nodeId = java.lang.Long.parseUnsignedLong(idHex, 16)
                                    viewModel.registerScannedPeer(nodeId, alias, pubHex)
                                    Toast.makeText(context, "Verified Contact Added: $alias", Toast.LENGTH_SHORT).show()
                                    showImportContactDialog = false
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Invalid node ID format", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                Toast.makeText(context, "Incomplete contact link parameters", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(context, "Invalid MeshWhisper URI", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary)
                ) {
                    Text("Verify & Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportContactDialog = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            },
            containerColor = SaharaSurfaceContainerLowest,
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Live In-App CameraX QR Scanner Viewfinder Modal
    if (showCameraScanner) {
        CameraQrScannerDialog(
            onDismissRequest = { showCameraScanner = false },
            onQrCodeScanned = { scannedContent ->
                showCameraScanner = false
                coroutineScope.launch {
                    when (val res = viewModel.handleScannedQrContent(scannedContent)) {
                        is QrScanResult.PeerVerified -> {
                            Toast.makeText(
                                context,
                                "✓ Authenticated Peer: ${res.alias} (Safety Number Verified)",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is QrScanResult.ChannelConfigured -> {
                            Toast.makeText(
                                context,
                                "✓ Joined Channel: ${res.channelName}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is QrScanResult.KeyMismatch -> {
                            Toast.makeText(
                                context,
                                "⚠️ MITM ALERT: Scanned key does not match peer!",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is QrScanResult.Invalid -> {
                            Toast.makeText(context, res.reason, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            title = "Scan Peer Identity QR",
            subtitle = "Point camera at peer screen to verify out-of-band safety numbers"
        )
    }

    // Emergency Panic Duress Wipe Dialog
    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emergencyPanicWipe()
                        showPanicDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaError),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "NUKE & WIPE EVERYTHING",
                        color = Color.White,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Black
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = SaharaOnSurfaceVariant,
                        fontFamily = ManropeFamily
                    )
                }
            },
            title = {
                Text(
                    text = "🚨 EMERGENCY PANIC WIPE",
                    color = SaharaError,
                    fontFamily = EBGaramondFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = "WARNING: This will instantly and irreversibly destroy your X25519 private keys from Android Keystore, purge the SQLCipher encrypted database, and wipe all messages, contacts, and logs. Use in high-threat duress situations.",
                    color = SaharaOnSurface,
                    fontFamily = ManropeFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            containerColor = SaharaSurfaceContainerLowest,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun DiagnosticRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = SaharaOnSurfaceVariant,
            fontSize = 12.sp,
            fontFamily = ManropeFamily,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = if (isOk) SaharaOnline else SaharaWarning,
            fontSize = 11.sp,
            fontFamily = ManropeFamily,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End
        )
    }
}

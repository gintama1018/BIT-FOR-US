package com.meshwhisper.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaContainer
import com.meshwhisper.app.ui.theme.DustyRose
import com.meshwhisper.app.ui.theme.EBGaramondFamily
import com.meshwhisper.app.ui.theme.ManropeFamily
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
    var showClearDialog by remember { mutableStateOf(false) }

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
    var showPanicDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmLinen)
    ) {
        // Header
        Card(
            colors = CardDefaults.cardColors(containerColor = WarmSurface),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = BurntSienna,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Node Identity & Settings",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontFamily = EBGaramondFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "X25519 Curve25519 Identity & BLE Diagnostics",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily
                        )
                    }
                }
                HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
            }
        }

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Identity Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp), // 8px radius per DESIGN.md
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
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
                                color = BurntSienna,
                                fontSize = 12.sp,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            IconButton(
                                onClick = { showQrDialog = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.QrCode2,
                                    contentDescription = "Show QR",
                                    tint = DustyRose
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Profile Photo & Avatar Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            com.meshwhisper.app.ui.components.NodeAvatar(
                                nodeId = viewModel.myNodeId,
                                alias = myAlias,
                                size = 60.dp,
                                avatarUri = myAvatarUri
                            )

                            Spacer(modifier = Modifier.width(14.dp))

                            Column {
                                Row {
                                    Button(
                                        onClick = {
                                            try {
                                                avatarPickerLauncher.launch(
                                                    androidx.activity.result.PickVisualMediaRequest(
                                                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                                                    )
                                                )
                                            } catch (e: Exception) {
                                                android.util.Log.e("PhotoPicker", "PickVisualMedia failed, trying GetContent fallback", e)
                                                try {
                                                    legacyAvatarPickerLauncher.launch("image/*")
                                                } catch (e2: Exception) {
                                                    android.util.Log.e("PhotoPicker", "GetContent fallback also failed", e2)
                                                    Toast.makeText(context, "Picker error: ${e2.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BurntSiennaContainer),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (myAvatarUri != null) "Change Photo" else "Add Photo",
                                            color = BurntSienna,
                                            fontSize = 12.sp,
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
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = "Remove",
                                                color = WarmRed,
                                                fontSize = 12.sp,
                                                fontFamily = ManropeFamily
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = "Synced automatically with peers over BLE",
                                    color = TextSecondary,
                                    fontSize = 10.sp,
                                    fontFamily = ManropeFamily
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
                                        focusedBorderColor = BurntSienna,
                                        unfocusedBorderColor = WarmCardBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary,
                                        cursorColor = BurntSienna,
                                        focusedContainerColor = WarmSurface,
                                        unfocusedContainerColor = WarmSurface
                                    ),
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
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Save", tint = BurntSienna)
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
                                    color = TextPrimary,
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
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Alias", tint = TextMuted, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 64-bit Hex Node ID with copy action
                        Text(text = "NODE ID (64-BIT HEX)", color = TextSecondary, fontSize = 11.sp, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(viewModel.myNodeIdHex))
                                    Toast.makeText(context, "Node ID copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                text = viewModel.myNodeIdHex,
                                color = DustyRose,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Node ID",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Public Key Fingerprint with copy action
                        Text(text = "X25519 FINGERPRINT", color = TextSecondary, fontSize = 11.sp, fontFamily = ManropeFamily, fontWeight = FontWeight.Bold)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(viewModel.myFingerprint))
                                    Toast.makeText(context, "Fingerprint copied", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = BurntSienna, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewModel.myFingerprint,
                                color = BurntSienna,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Fingerprint",
                                tint = TextMuted,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Mesh Network & Battery Management Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "MESH NETWORK & POWER",
                            color = BurntSienna,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val isBackgroundRelay by viewModel.isBackgroundRelayEnabled.collectAsState()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Background Mesh Relay",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = if (isBackgroundRelay)
                                        "Relaying encrypted packets in background using power-efficient balanced duty-cycling."
                                    else
                                        "Relay paused when app is closed. Mesh only operates while app is open.",
                                    color = TextSecondary,
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
                                    checkedThumbColor = BurntSienna,
                                    checkedTrackColor = BurntSiennaContainer
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        DiagnosticRow(
                            label = "Foreground Radio Mode",
                            value = "High-Speed (Low Latency)",
                            isOk = true
                        )
                        DiagnosticRow(
                            label = "Background Radio Mode",
                            value = if (isBackgroundRelay) "Balanced Duty-Cycle (~75% Power Saved)" else "Disabled / Inactive",
                            isOk = isBackgroundRelay
                        )
                        DiagnosticRow(
                            label = "Background CPU WakeLock",
                            value = "Zero 24/7 Lock (Event-Driven Only)",
                            isOk = true
                        )
                    }
                }
            }

            // Security & Privacy Settings Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "SECURITY & PRIVACY",
                            color = BurntSienna,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "App Lock (Biometric / PIN)",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Require fingerprint or screen lock PIN to open app",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = ManropeFamily
                                )
                            }
                            Switch(
                                checked = isAppLockEnabled,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        val canAuth = com.meshwhisper.app.security.BiometricAuthManager.isBiometricOrDeviceCredentialAvailable(context)
                                        if (canAuth) {
                                            viewModel.setAppLockEnabled(true)
                                        } else {
                                            Toast.makeText(
                                                context,
                                                "Cannot enable App Lock: No Screen Lock (PIN, pattern, or fingerprint) is set up on this device. Please configure a screen lock in Android Settings first.",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        }
                                    } else {
                                        viewModel.setAppLockEnabled(false)
                                    }
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BurntSienna,
                                    checkedTrackColor = BurntSiennaContainer
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // Message Notifications Master Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Message Notifications",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontFamily = ManropeFamily,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Heads-up notification alerts when off-chat or backgrounded",
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = ManropeFamily
                                )
                            }
                            Switch(
                                checked = isNotificationsEnabled,
                                onCheckedChange = { viewModel.setNotificationsEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = BurntSienna,
                                    checkedTrackColor = BurntSiennaContainer
                                )
                            )
                        }

                        if (isNotificationsEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // Show Message Previews Toggle (Signal-style Privacy)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Show Message Previews",
                                        color = TextPrimary,
                                        fontSize = 14.sp,
                                        fontFamily = ManropeFamily,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "Show message text snippet in notification banners",
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = ManropeFamily
                                    )
                                }
                                Switch(
                                    checked = showNotificationPreviews,
                                    onCheckedChange = { viewModel.setShowNotificationPreviews(it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = BurntSienna,
                                        checkedTrackColor = BurntSiennaContainer
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        DiagnosticRow("Database Storage", "SQLCipher Encrypted (AES-256)", true)
                        DiagnosticRow("Identity Key Storage", "Android Keystore Master Key", true)
                        DiagnosticRow("Replay Protection", "Two-Layer (RAM + Room DB)", true)
                        DiagnosticRow("Forward Secrecy", "Hourly Epoch HKDF Ratchet", true)
                    }
                }
            }

            // Diagnostics Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DIAGNOSTICS & PROTOCOL ENGINE",
                            color = BurntSienna,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val isBtOn by viewModel.isBluetoothEnabled.collectAsState()
                        val isWifiOn by viewModel.isWifiActive.collectAsState()
                        val localIp by viewModel.localIpAddress.collectAsState()
                        val wifiPeersCount by viewModel.wifiPeersCount.collectAsState()

                        DiagnosticRow("Bluetooth Radio", if (isBtOn) "Enabled (Radio Active)" else "Disabled (Radio Off)", isBtOn)
                        DiagnosticRow("BLE Peripheral (Advertiser)", if (supportsPeripheral) "Supported (Dual-Role)" else "Degraded (Central only)", supportsPeripheral)
                        DiagnosticRow("Wi-Fi Subnet Sockets", if (isWifiOn) "Active (Port 42425/42426)" else "Searching / Offline", isWifiOn)
                        DiagnosticRow("Local Subnet IP", localIp ?: "No LAN / Hotspot", localIp != null)
                        DiagnosticRow("Wi-Fi Connected Peers", "$wifiPeersCount active TCP link${if (wifiPeersCount != 1) "s" else ""}", wifiPeersCount > 0)
                        DiagnosticRow("GATT Server", "Active (512-byte MTU)", true)
                        DiagnosticRow("BLE Central Scanner", "Active (Low-Latency)", true)
                        DiagnosticRow("Crypto Subsystem", "X25519 + AES-256-GCM + AAD", true)
                        DiagnosticRow("Routing Algorithm", "Dual-Radio Flood + TTL (7)", true)
                    }
                }
            }

            // Actions
            item {
                Button(
                    onClick = { viewModel.announcePresence() },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmSurfaceContainer),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Re-broadcast Presence Announce",
                        color = TextPrimary,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                Button(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmSurfaceContainer),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Clear Messages & History",
                        color = TextPrimary,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                Button(
                    onClick = { showPanicDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = WarmSurfaceContainer),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, WarmRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = WarmRed, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "🚨 Emergency Panic Duress Wipe",
                        color = WarmRed,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // App Brand Footer
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(id = com.meshwhisper.app.R.drawable.meshwhisper_logo),
                        contentDescription = "MeshWhisper Logo",
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, WarmCardBorder, RoundedCornerShape(16.dp))
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "MeshWhisper",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Decentralized Offline Emergency Mesh • v1.0.0",
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily
                    )
                }
            }
        }
    }

    // QR Code Dialog
    if (showQrDialog) {
        AlertDialog(
            onDismissRequest = { showQrDialog = false },
            confirmButton = {
                TextButton(onClick = { showQrDialog = false }) {
                    Text(
                        text = "Close",
                        color = BurntSienna,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            title = {
                Text(
                    text = "Node Identity QR",
                    color = TextPrimary,
                    fontFamily = EBGaramondFamily,
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
                                .size(240.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scan to verify X25519 public key fingerprint",
                        color = TextSecondary,
                        fontFamily = ManropeFamily,
                        fontSize = 12.sp
                    )
                }
            },
            containerColor = WarmSurface
        )
    }

    // Clear Confirmation Dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllData()
                        showClearDialog = false
                    }
                ) {
                    Text(
                        text = "Clear Messages",
                        color = WarmRed,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = TextSecondary,
                        fontFamily = ManropeFamily
                    )
                }
            },
            title = {
                Text(
                    text = "Clear Messages & History?",
                    color = TextPrimary,
                    fontFamily = EBGaramondFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will delete all stored message history, peer records, and packet telemetry. Your cryptographic identity keypair will be preserved.",
                    color = TextSecondary,
                    fontFamily = ManropeFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            containerColor = WarmSurface
        )
    }

    // Emergency Panic Duress Wipe Dialog
    if (showPanicDialog) {
        AlertDialog(
            onDismissRequest = { showPanicDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.emergencyPanicWipe()
                        showPanicDialog = false
                    }
                ) {
                    Text(
                        text = "NUKE & WIPE EVERYTHING",
                        color = WarmRed,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.Black
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text(
                        text = "Cancel",
                        color = TextSecondary,
                        fontFamily = ManropeFamily
                    )
                }
            },
            title = {
                Text(
                    text = "🚨 EMERGENCY PANIC WIPE",
                    color = WarmRed,
                    fontFamily = EBGaramondFamily,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "WARNING: This will instantly and irreversibly destroy your X25519 private keys from Android Keystore, purge the SQLCipher encrypted database, and wipe all messages, contacts, and logs. Use in high-threat duress situations.",
                    color = TextPrimary,
                    fontFamily = ManropeFamily,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            containerColor = WarmSurface
        )
    }
}

@Composable
fun DiagnosticRow(label: String, value: String, isOk: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            fontFamily = ManropeFamily
        )
        Text(
            text = value,
            color = if (isOk) WarmGreen else WarmAmber,
            fontSize = 12.sp,
            fontFamily = ManropeFamily,
            fontWeight = FontWeight.Medium
        )
    }
}

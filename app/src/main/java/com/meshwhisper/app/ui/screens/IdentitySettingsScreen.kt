package com.meshwhisper.app.ui.screens

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.AmberAccent
import com.meshwhisper.app.ui.theme.CyanAccent
import com.meshwhisper.app.ui.theme.DarkBackground
import com.meshwhisper.app.ui.theme.DarkCardBorder
import com.meshwhisper.app.ui.theme.DarkSurface
import com.meshwhisper.app.ui.theme.DarkSurfaceVariant
import com.meshwhisper.app.ui.theme.EmeraldAccent
import com.meshwhisper.app.ui.theme.RedAccent
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.util.QrCodeGenerator
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

@Composable
fun IdentitySettingsScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    val myAlias by viewModel.myAlias.collectAsState()
    val supportsPeripheral by viewModel.supportsPeripheral.collectAsState()
    val identityVersion by viewModel.identityVersion.collectAsState()

    var isEditingAlias by remember { mutableStateOf(false) }
    var aliasInput by remember { mutableStateOf(myAlias) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    val qrContent = remember(myAlias, viewModel.myNodeIdHex, viewModel.myPublicKeyHex, identityVersion) {
        "meshwhisper://node?id=${viewModel.myNodeIdHex}&alias=$myAlias&pub=${viewModel.myPublicKeyHex}"
    }
    val qrBitmap = remember(qrContent) { QrCodeGenerator.generateQrBitmap(qrContent, 400) }

    val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsState()
    var showPanicDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Header
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "NODE IDENTITY & SETTINGS",
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        text = "X25519 Curve25519 Identity & BLE Diagnostics",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
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
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
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
                                color = EmeraldAccent,
                                fontSize = 12.sp,
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
                                    tint = CyanAccent
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

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
                                        focusedBorderColor = EmeraldAccent,
                                        unfocusedBorderColor = DarkCardBorder,
                                        focusedTextColor = TextPrimary,
                                        unfocusedTextColor = TextPrimary
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
                                    Icon(imageVector = Icons.Default.Check, contentDescription = "Save", tint = EmeraldAccent)
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

                        // 64-bit Hex Node ID
                        Text(text = "NODE ID (64-BIT HEX)", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = viewModel.myNodeIdHex,
                            color = CyanAccent,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Public Key Fingerprint
                        Text(text = "X25519 FINGERPRINT", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Fingerprint, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = viewModel.myFingerprint,
                                color = EmeraldAccent,
                                fontSize = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Security & Privacy Settings Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "SECURITY & PRIVACY",
                            color = EmeraldAccent,
                            fontSize = 12.sp,
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
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "Require fingerprint or screen lock PIN to open app",
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                            }
                            Switch(
                                checked = isAppLockEnabled,
                                onCheckedChange = { viewModel.setAppLockEnabled(it) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = EmeraldAccent,
                                    checkedTrackColor = Color(0xFF00381C)
                                )
                            )
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
                    colors = CardDefaults.cardColors(containerColor = DarkSurface),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "DIAGNOSTICS & PROTOCOL ENGINE",
                            color = EmeraldAccent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        val isBtOn by viewModel.isBluetoothEnabled.collectAsState()
                        DiagnosticRow("Bluetooth Radio", if (isBtOn) "Enabled (Radio Active)" else "Disabled (Radio Off)", isBtOn)
                        DiagnosticRow("BLE Peripheral (Advertiser)", if (supportsPeripheral) "Supported (Dual-Role)" else "Degraded (Central only)", supportsPeripheral)
                        DiagnosticRow("GATT Server", "Active (512-byte MTU)", true)
                        DiagnosticRow("BLE Central Scanner", "Active (Low-Latency)", true)
                        DiagnosticRow("Crypto Subsystem", "X25519 + AES-256-GCM + AAD", true)
                        DiagnosticRow("Routing Algorithm", "Flood + TTL (7) + Persistent Dedup", true)
                    }
                }
            }

            // Actions
            item {
                Button(
                    onClick = { viewModel.announcePresence() },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Re-broadcast Presence Announce", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                Button(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = TextMuted, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Clear Messages & History", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                }
            }

            item {
                Button(
                    onClick = { showPanicDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3B0000)),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, RedAccent),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = RedAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("🚨 Emergency Panic Duress Wipe", color = RedAccent, fontWeight = FontWeight.Bold)
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
                    Text("Close", color = EmeraldAccent)
                }
            },
            title = { Text("Node Identity QR", color = TextPrimary, fontWeight = FontWeight.Bold) },
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
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Scan to verify X25519 public key fingerprint",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
            },
            containerColor = DarkSurface
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
                    Text("Clear Messages", color = RedAccent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            title = { Text("Clear Messages & History?", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This will delete all stored message history, peer records, and packet telemetry. Your cryptographic identity keypair will be preserved.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            containerColor = DarkSurface
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
                    Text("NUKE & WIPE EVERYTHING", color = RedAccent, fontWeight = FontWeight.Black)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPanicDialog = false }) {
                    Text("Cancel", color = TextSecondary)
                }
            },
            title = { Text("🚨 EMERGENCY PANIC WIPE", color = RedAccent, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "WARNING: This will instantly and irreversibly destroy your X25519 private keys from Android Keystore, purge the SQLCipher encrypted database, and wipe all messages, contacts, and logs. Use in high-threat duress situations.",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            },
            containerColor = Color(0xFF1E0000)
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
        Text(text = label, color = TextSecondary, fontSize = 12.sp)
        Text(
            text = value,
            color = if (isOk) EmeraldAccent else AmberAccent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

package com.meshwhisper.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.PacketLogEntity
import com.meshwhisper.app.telemetry.GattRole
import com.meshwhisper.app.telemetry.PeerLiveTelemetry
import com.meshwhisper.app.telemetry.RssiSource
import com.meshwhisper.app.ui.components.SaharaTopAppBar
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun PacketInspectorScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    if (!com.meshwhisper.app.BuildConfig.DEBUG) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(SaharaBackground)
                .padding(32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Packet Inspector is restricted to debug builds.",
                color = SaharaOnSurfaceVariant,
                fontSize = 14.sp,
                fontFamily = ManropeFamily,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
        return
    }

    val context = LocalContext.current
    val logs by viewModel.packetLogs.collectAsState()
    val relayedCount by viewModel.relayedPacketsCount.collectAsState()
    val totalRx by viewModel.totalPacketsReceived.collectAsState()
    val connectedNodes by viewModel.connectedPeersCount.collectAsState()
    val peerTelemetry by viewModel.peerTelemetry.collectAsState()
    val isBatteryOptimizationIgnored by viewModel.isBatteryOptimizationIgnored.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") }

    LaunchedEffect(Unit) {
        viewModel.refreshBatteryOptimizationStatus()
    }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == "ALL") logs
        else logs.filter { it.direction.contains(selectedFilter, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Sahara Top Header with Export and Clear Journal Actions
        SaharaTopAppBar(
            title = "Packet Inspector",
            subtitle = "LIVE TELEMETRY & ROUTING",
            actionIcon = Icons.Default.Share,
            actionIconTint = SaharaPrimary,
            onActionClick = {
                viewModel.sharePacketJournal(context) { exportedPath ->
                    Toast.makeText(context, "Journal exported: $exportedPath", Toast.LENGTH_LONG).show()
                }
            },
            secondaryActionIcon = Icons.Default.DeleteSweep,
            secondaryActionIconTint = SaharaOnSurfaceVariant,
            onSecondaryActionClick = {
                viewModel.clearPacketJournal(context)
                Toast.makeText(context, "Journal & packet logs cleared", Toast.LENGTH_SHORT).show()
            }
        )

        // OEM Battery Saver Warning Card (Audit Gap #4)
        if (!isBatteryOptimizationIgnored) {
            Surface(
                color = SaharaSecondary.copy(alpha = 0.12f),
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SaharaSecondary.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = SaharaSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Battery Optimization Active",
                                style = MaterialTheme.typography.labelMedium,
                                color = SaharaSecondary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Xiaomi/Vivo/Oppo may kill background BLE when screen is off. Whitelist app for testing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = SaharaOnSurfaceVariant,
                            fontSize = 11.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { viewModel.requestBatteryOptimizationExemption(context) },
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaSecondary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Whitelist", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Metrics Bento Grid
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SaharaMetricCard(title = "PACKETS", value = totalRx.toString(), accent = SaharaPrimary, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "RELAYED", value = relayedCount.toString(), accent = SaharaOnSurface, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "DROPPED", value = "0", accent = SaharaSecondary, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "NODES", value = connectedNodes.toString(), accent = SaharaPrimary, modifier = Modifier.weight(1f))
        }

        // Live Connected Peers Telemetry Bento Section (Audit Gap #1 & Phase 0 readout)
        if (peerTelemetry.isNotEmpty()) {
            Surface(
                color = SaharaSurfaceContainerLow,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = SaharaPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "LIVE PEER TELEMETRY (${peerTelemetry.size})",
                                style = MaterialTheme.typography.labelSmall,
                                color = SaharaPrimary,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                        Text(
                            text = "POLL 2.5s",
                            style = MaterialTheme.typography.labelSmall,
                            color = SaharaOnSurfaceVariant,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        peerTelemetry.forEach { peer ->
                            PeerLiveTelemetryCard(peer = peer)
                        }
                    }
                }
            }
        }

        // Quick Export / Filter Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Filter Bar Chips
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "TX", "RX", "RELAY", "DROP").forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Surface(
                        color = if (isSelected) SaharaPrimary else SaharaSurfaceContainerLowest,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            0.8.dp,
                            if (isSelected) SaharaPrimary else SaharaOutlineVariant.copy(alpha = 0.6f)
                        ),
                        modifier = Modifier.clickable { selectedFilter = filter }
                    ) {
                        Text(
                            text = filter,
                            color = if (isSelected) Color.White else SaharaOnSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Export Journal Button
            Surface(
                color = SaharaSurfaceContainerLowest,
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaPrimary.copy(alpha = 0.5f)),
                modifier = Modifier.clickable {
                    viewModel.sharePacketJournal(context) { path ->
                        Toast.makeText(context, "Journal exported to $path", Toast.LENGTH_LONG).show()
                    }
                }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FileDownload,
                        contentDescription = "Export",
                        tint = SaharaPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = "CSV",
                        color = SaharaPrimary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Terminal Log List
        if (filteredLogs.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No datagrams logged yet for filter '$selectedFilter'.",
                    color = SaharaOnSurfaceVariant,
                    fontSize = 13.sp,
                    fontFamily = ManropeFamily
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    SaharaPacketLogCard(log = log)
                }
            }
        }
    }
}

@Composable
private fun PeerLiveTelemetryCard(peer: PeerLiveTelemetry) {
    val rssiColor = when {
        peer.rssi >= -70 -> SaharaOnline
        peer.rssi >= -85 -> SaharaSecondary
        peer.rssi != 0 -> SaharaError
        else -> SaharaOnSurfaceVariant
    }

    val roleLabel = when (peer.role) {
        GattRole.CENTRAL_CLIENT -> "CENTRAL (CLIENT)"
        GattRole.PERIPHERAL_SERVER -> "PERIPHERAL (SERVER)"
        GattRole.UNKNOWN -> "PEER"
    }

    val rssiSourceLabel = when (peer.rssiSource) {
        RssiSource.LIVE_POLL -> "LIVE"
        RssiSource.AT_CONNECT_SCAN -> "AT-CONNECT"
        RssiSource.UNKNOWN -> "SCAN"
    }

    Surface(
        color = SaharaSurfaceContainerLowest,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.6.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Header Row: Peer Alias + Hex ID + Role Badge + RSSI Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = peer.alias,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = SaharaOnSurface
                    )
                    Text(
                        text = "0x${String.format(Locale.US, "%04X", peer.nodeId and 0xFFFF)}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = SaharaOnSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    // GATT Role Badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SaharaPrimary.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = roleLabel,
                            color = SaharaPrimary,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // RSSI Badge (Live vs At-Connect indicator)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(rssiColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "$rssiSourceLabel ${if (peer.rssi != 0) "${peer.rssi} dBm" else "N/A"}",
                            color = rssiColor,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Sub-metrics Row: Negotiated MTU, Hop Count, Last Packet Type, RX/TX
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "MTU: ${peer.mtu}B",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = SaharaOnSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Hop: ${peer.lastHopCount}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = SaharaOnSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Last: ${peer.lastPacketType}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = SaharaOnSurfaceVariant,
                        fontSize = 10.sp
                    )
                }

                Text(
                    text = "RX/TX: ${peer.packetsReceived}/${peer.packetsSent}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = SaharaPrimary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.sp
                )
            }
        }
    }
}

@Composable
private fun SaharaMetricCard(
    title: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = SaharaSurfaceContainerLow,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.5f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = SaharaOnSurfaceVariant,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun SaharaPacketLogCard(log: PacketLogEntity) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val formattedTime = remember(log.timestamp) { timeFormat.format(Date(log.timestamp)) }
    var isExpanded by remember { mutableStateOf(false) }

    val dirColor = when (log.direction.uppercase()) {
        "TX" -> SaharaPrimary
        "RX" -> SaharaOnline
        "RELAY" -> SaharaSecondary
        "DROP" -> SaharaError
        else -> SaharaOnSurfaceVariant
    }

    Surface(
        color = SaharaSurfaceContainerLowest,
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, SaharaOutlineVariant.copy(alpha = 0.4f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Direction Tag
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(dirColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = log.direction.uppercase(),
                            color = dirColor,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Packet Type
                    Text(
                        text = log.packetType,
                        style = MaterialTheme.typography.bodyMedium,
                        color = SaharaOnSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "${log.byteSize}B • $formattedTime",
                    style = MaterialTheme.typography.labelSmall,
                    color = SaharaOnSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = log.details,
                style = MaterialTheme.typography.bodySmall,
                color = SaharaOnSurfaceVariant,
                fontSize = 12.sp
            )

            if (isExpanded && log.details.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = SaharaSurfaceContainerLow,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "MessageID: ${log.messageId}\nSender: 0x${String.format("%016X", log.senderId).takeLast(4)}\nRecipient: 0x${String.format("%016X", log.recipientId).takeLast(4)}\nTTL: ${log.ttl}\nDetails: ${log.details}",
                        color = SaharaOnSurface,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }
    }
}

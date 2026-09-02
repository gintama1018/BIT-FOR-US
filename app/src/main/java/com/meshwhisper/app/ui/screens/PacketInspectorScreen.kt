package com.meshwhisper.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.PacketLogEntity
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
    val logs by viewModel.packetLogs.collectAsState()
    val relayedCount by viewModel.relayedPacketsCount.collectAsState()
    val totalRx by viewModel.totalPacketsReceived.collectAsState()
    val connectedNodes by viewModel.connectedPeersCount.collectAsState()

    var selectedFilter by remember { mutableStateOf("ALL") }

    val filteredLogs = remember(logs, selectedFilter) {
        if (selectedFilter == "ALL") logs
        else logs.filter { it.direction.contains(selectedFilter, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Sahara Top Header
        SaharaTopAppBar(
            title = "Packet Inspector",
            subtitle = "LIVE TELEMETRY & ROUTING",
            actionIcon = Icons.Default.DeleteSweep,
            onActionClick = { viewModel.clearLogs() }
        )

        // Metrics Bento Grid matching 10._packet_inspector/code.html
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SaharaMetricCard(title = "PACKETS", value = totalRx.toString(), accent = SaharaPrimary, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "RELAYED", value = relayedCount.toString(), accent = SaharaOnSurface, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "DROPPED", value = "0", accent = SaharaSecondary, modifier = Modifier.weight(1f))
            SaharaMetricCard(title = "NODES", value = connectedNodes.toString(), accent = SaharaPrimary, modifier = Modifier.weight(1f))
        }

        // Filter Bar Chips
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
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
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredLogs, key = { it.id }) { log ->
                    SaharaPacketLogCard(log = log)
                }
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

package com.meshwhisper.app.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.ui.theme.AmberAccent
import com.meshwhisper.app.ui.theme.CyanAccent
import com.meshwhisper.app.ui.theme.DarkBackground
import com.meshwhisper.app.ui.theme.DarkCardBorder
import com.meshwhisper.app.ui.theme.DarkSurface
import com.meshwhisper.app.ui.theme.DarkSurfaceVariant
import com.meshwhisper.app.ui.theme.EmeraldAccent
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshRadarScreen(
    viewModel: MeshViewModel,
    onOpenChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by viewModel.peers.collectAsState()
    val supportsPeripheral by viewModel.supportsPeripheral.collectAsState()
    val connectedCount by viewModel.connectedPeersCount.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Radar Header
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.NetworkCheck,
                        contentDescription = null,
                        tint = EmeraldAccent,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "MESH TOPOLOGY RADAR",
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Active BLE Links & Multi-Hop Map",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Button(
                    onClick = { viewModel.announcePresence() },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ping Mesh", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Hardware Support Warning Banner if Peripheral mode is missing
        if (!supportsPeripheral) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF332000)),
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AmberAccent),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = AmberAccent)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Device lacks BLE Peripheral mode. Operating in Central Relay mode (can scan, connect & relay).",
                        color = Color(0xFFFFE082),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // Animated Radar Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            RadarVisualizerCanvas(peers = peers)
        }

        // Peers List Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "DISCOVERED NODES (${peers.size})",
                color = TextSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp
            )
            Text(
                text = "$connectedCount Direct Links",
                color = EmeraldAccent,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Peers List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(peers, key = { it.nodeId }) { peer ->
                RadarPeerCard(peer = peer, onOpenChat = { onOpenChat(peer.nodeId) })
            }
        }
    }
}

@Composable
fun RadarVisualizerCanvas(peers: List<PeerEntity>) {
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseAnim"
    )

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = minOf(size.width, size.height) / 2 * 0.85f

        // Concentric Rings
        val rings = 3
        for (i in 1..rings) {
            val r = maxRadius * (i.toFloat() / rings)
            drawCircle(
                color = DarkCardBorder,
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Radar Pulse Wave
        val animatedRadius = maxRadius * pulseProgress
        drawCircle(
            color = EmeraldAccent.copy(alpha = 1f - pulseProgress),
            radius = animatedRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Center Node (Local Phone)
        drawCircle(
            color = EmeraldAccent,
            radius = 7.dp.toPx(),
            center = center
        )

        // Surrounding Peer Nodes
        peers.forEachIndexed { index, peer ->
            val angle = (index * (360f / maxOf(1, peers.size))) * (Math.PI / 180f)
            val distanceRatio = if (peer.isDirect) 0.5f else 0.85f
            val nodeRadius = maxRadius * distanceRatio

            val x = center.x + (nodeRadius * cos(angle)).toFloat()
            val y = center.y + (nodeRadius * sin(angle)).toFloat()

            // Mesh Link Line
            drawLine(
                color = if (peer.isDirect) EmeraldAccent.copy(alpha = 0.4f) else CyanAccent.copy(alpha = 0.3f),
                start = center,
                end = Offset(x, y),
                strokeWidth = 1.5.dp.toPx()
            )

            // Peer Node Dot
            drawCircle(
                color = if (peer.isDirect) EmeraldAccent else CyanAccent,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
fun RadarPeerCard(
    peer: PeerEntity,
    onOpenChat: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (peer.isDirect) EmeraldAccent else CyanAccent)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = peer.alias,
                        color = TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ID: ${peer.nodeIdHex.takeLast(6)}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }

                Button(
                    onClick = onOpenChat,
                    colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(6.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder)
                ) {
                    Icon(imageVector = Icons.Default.Chat, contentDescription = null, tint = EmeraldAccent, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Chat", color = TextPrimary, fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Fingerprint: ${peer.fingerprint}",
                    color = EmeraldAccent,
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                )
                val hopLabel = if (peer.isDirect) "⚡ 0 hops (Direct)" else "⚡ ${peer.hopCount} hops (Relay)"
                Text(
                    text = hopLabel,
                    color = if (peer.isDirect) EmeraldAccent else CyanAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

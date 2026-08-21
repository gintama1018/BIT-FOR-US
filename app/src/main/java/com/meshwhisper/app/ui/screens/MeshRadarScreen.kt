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
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.TopologyEdgeEntity
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.graph.GraphEdge
import com.meshwhisper.app.ui.graph.GraphNode
import com.meshwhisper.app.ui.graph.GraphPhysicsSimulation
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
import com.meshwhisper.app.ui.theme.WhatsAppGreen
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshRadarScreen(
    viewModel: MeshViewModel,
    onOpenChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by viewModel.peers.collectAsState()
    val topologyEdges by viewModel.topologyEdges.collectAsState()
    val supportsPeripheral by viewModel.supportsPeripheral.collectAsState()
    val connectedCount by viewModel.connectedPeersCount.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()

    var selectedViewTab by remember { mutableIntStateOf(0) } // 0 = Web of Nodes, 1 = Radar Scope

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
    ) {
        // Top Header
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(0.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, DarkCardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
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
                            text = "Mesh Topology",
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "$connectedCount live direct links • ${peers.size} discovered",
                            color = TextSecondary,
                            fontSize = 12.sp
                        )
                    }
                }

                Button(
                    onClick = { viewModel.announcePresence() },
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldAccent),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Ping Mesh", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
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
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = AmberAccent, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device lacks BLE Peripheral mode. Operating in Central Relay mode.",
                        color = Color(0xFFFFE082),
                        fontSize = 12.sp
                    )
                }
            }
        }

        // View Mode Selector Tab Row
        TabRow(
            selectedTabIndex = selectedViewTab,
            containerColor = DarkSurface,
            contentColor = EmeraldAccent,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedViewTab]),
                    color = EmeraldAccent
                )
            },
            divider = {
                androidx.compose.material3.HorizontalDivider(color = DarkCardBorder, thickness = 0.5.dp)
            }
        ) {
            Tab(
                selected = selectedViewTab == 0,
                onClick = { selectedViewTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Hub, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Web of Nodes", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            )
            Tab(
                selected = selectedViewTab == 1,
                onClick = { selectedViewTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Radar Scope", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            )
        }

        // Interactive Canvas Visualizer
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            if (selectedViewTab == 0) {
                MeshWebVisualizerCanvas(
                    myNodeId = viewModel.myNodeId,
                    myAlias = viewModel.myAlias.collectAsState().value,
                    peers = peers,
                    topologyEdges = topologyEdges,
                    connectedNodeIds = connectedNodeIds,
                    onNodeClick = onOpenChat
                )
            } else {
                RadarVisualizerCanvas(peers = peers)
            }
        }

        // Discovered Nodes Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Discovered Nodes (${peers.size})",
                color = TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Tap a node to chat",
                color = TextMuted,
                fontSize = 11.sp
            )
        }

        // Peers List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(peers, key = { it.nodeId }) { peer ->
                RadarPeerCard(peer = peer, onOpenChat = { onOpenChat(peer.nodeId) })
            }
        }
    }
}

/**
 * Web-of-Nodes Canvas powered by Force-Directed Physics Simulation.
 * Renders the decentralized interconnected mesh graph.
 */
@Composable
fun MeshWebVisualizerCanvas(
    myNodeId: Long,
    myAlias: String,
    peers: List<PeerEntity>,
    topologyEdges: List<TopologyEdgeEntity>,
    connectedNodeIds: Set<Long>,
    onNodeClick: (Long) -> Unit
) {
    val sim = remember { GraphPhysicsSimulation() }
    val graphNodes = remember { mutableStateListOf<GraphNode>() }

    val infiniteTransition = rememberInfiniteTransition(label = "MeshPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "MeshPulseAnim"
    )

    // Build Graph Edges combining live GATT links and gossiped topology edges
    val graphEdges = remember(peers, topologyEdges, connectedNodeIds, myNodeId) {
        val edgeSet = mutableSetOf<Pair<Long, Long>>()
        val edges = mutableListOf<GraphEdge>()

        // 1. Live direct links from self
        for (directId in connectedNodeIds) {
            if (directId != myNodeId) {
                edgeSet.add(Pair(minOf(myNodeId, directId), maxOf(myNodeId, directId)))
                edges.add(GraphEdge(fromId = myNodeId, toId = directId, isDirect = true))
            }
        }

        // 2. Direct links known from peer entities
        for (peer in peers) {
            if (peer.isDirect && peer.nodeId != myNodeId) {
                val pair = Pair(minOf(myNodeId, peer.nodeId), maxOf(myNodeId, peer.nodeId))
                if (!edgeSet.contains(pair)) {
                    edgeSet.add(pair)
                    edges.add(GraphEdge(fromId = myNodeId, toId = peer.nodeId, isDirect = true))
                }
            }
        }

        // 3. Gossiped multi-hop edges between other nodes
        for (edge in topologyEdges) {
            val pair = Pair(minOf(edge.fromNode, edge.toNode), maxOf(edge.fromNode, edge.toNode))
            if (!edgeSet.contains(pair)) {
                edgeSet.add(pair)
                val isDirectFromSelf = (edge.fromNode == myNodeId || edge.toNode == myNodeId)
                edges.add(GraphEdge(fromId = edge.fromNode, toId = edge.toNode, isDirect = isDirectFromSelf))
            }
        }

        edges
    }

    // Synchronize Node list with peers + self
    LaunchedEffect(peers, myNodeId, myAlias) {
        val currentIds = graphNodes.map { it.id }.toSet()
        val neededIds = setOf(myNodeId) + peers.map { it.nodeId }

        // Remove defunct nodes
        graphNodes.removeAll { !neededIds.contains(it.id) }

        // Add self node if missing
        if (!currentIds.contains(myNodeId)) {
            graphNodes.add(
                GraphNode(
                    id = myNodeId,
                    x = 200f,
                    y = 150f,
                    label = myAlias.ifBlank { "You" },
                    isSelf = true
                )
            )
        }

        // Add new peer nodes with initial offset
        peers.forEachIndexed { index, peer ->
            if (!currentIds.contains(peer.nodeId)) {
                val angle = (index * (360f / maxOf(1, peers.size))) * (Math.PI / 180f)
                val initDist = if (peer.isDirect) 90f else 160f
                val initX = 200f + (initDist * cos(angle)).toFloat()
                val initY = 150f + (initDist * sin(angle)).toFloat()

                graphNodes.add(
                    GraphNode(
                        id = peer.nodeId,
                        x = initX,
                        y = initY,
                        label = peer.alias,
                        isSelf = false
                    )
                )
            }
        }
    }

    // Step continuous physics simulation at ~30 FPS
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(peers, graphEdges) {
        while (true) {
            sim.step(graphNodes, graphEdges, width = 400f, height = 300f, dt = 0.03f)
            tick++
            delay(33L)
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurface)
            .border(1.dp, DarkCardBorder, RoundedCornerShape(16.dp))
            .pointerInput(graphNodes) {
                detectTapGestures { tapOffset ->
                    val clickedNode = graphNodes.find { node ->
                        val dx = tapOffset.x - node.x
                        val dy = tapOffset.y - node.y
                        (dx * dx + dy * dy) <= (30.dp.toPx() * 30.dp.toPx())
                    }
                    if (clickedNode != null && !clickedNode.isSelf) {
                        onNodeClick(clickedNode.id)
                    }
                }
            }
    ) {
        // Draw mesh grid background dots
        val dotSpacing = 28.dp.toPx()
        val numX = (size.width / dotSpacing).toInt()
        val numY = (size.height / dotSpacing).toInt()
        for (i in 0..numX) {
            for (j in 0..numY) {
                drawCircle(
                    color = DarkCardBorder.copy(alpha = 0.35f),
                    radius = 1.dp.toPx(),
                    center = Offset(i * dotSpacing, j * dotSpacing)
                )
            }
        }

        // Draw Interconnecting Graph Edges
        for (edge in graphEdges) {
            val a = graphNodes.find { it.id == edge.fromId } ?: continue
            val b = graphNodes.find { it.id == edge.toId } ?: continue

            val start = Offset(a.x, a.y)
            val end = Offset(b.x, b.y)

            if (edge.isDirect) {
                // Solid green link for direct BLE communication
                drawLine(
                    color = EmeraldAccent.copy(alpha = 0.6f),
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx()
                )
            } else {
                // Dashed cyan link for multi-hop relay links between remote peers
                drawLine(
                    color = CyanAccent.copy(alpha = 0.45f),
                    start = start,
                    end = end,
                    strokeWidth = 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
            }

            // Animated energy pulse traveling along the active edge
            val pulseX = start.x + (end.x - start.x) * pulseProgress
            val pulseY = start.y + (end.y - start.y) * pulseProgress
            drawCircle(
                color = if (edge.isDirect) WhatsAppGreen else CyanAccent,
                radius = 2.5.dp.toPx(),
                center = Offset(pulseX, pulseY)
            )
        }

        // Draw Nodes
        for (node in graphNodes) {
            val nodeCenter = Offset(node.x, node.y)

            if (node.isSelf) {
                // Self Node (Center Local Phone)
                val pulseR = 24.dp.toPx() + (8.dp.toPx() * pulseProgress)
                drawCircle(
                    color = EmeraldAccent.copy(alpha = 0.3f * (1f - pulseProgress)),
                    radius = pulseR,
                    center = nodeCenter
                )
                drawCircle(
                    color = Color(0xFF005C4B),
                    radius = 16.dp.toPx(),
                    center = nodeCenter
                )
                drawCircle(
                    color = EmeraldAccent,
                    radius = 16.dp.toPx(),
                    center = nodeCenter,
                    style = Stroke(width = 2.dp.toPx())
                )
            } else {
                // Remote Peer Node
                drawCircle(
                    color = DarkSurfaceVariant,
                    radius = 14.dp.toPx(),
                    center = nodeCenter
                )
                drawCircle(
                    color = EmeraldAccent.copy(alpha = 0.7f),
                    radius = 14.dp.toPx(),
                    center = nodeCenter,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Draw Node Label text via Android Native Canvas Paint
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 10.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                }
                val labelText = if (node.isSelf) "You (${node.label.take(5)})" else node.label.take(8)
                drawText(labelText, node.x, node.y + 24.dp.toPx(), paint)
            }
        }
    }
}

/**
 * Traditional Single-Perspective Radar Scope Canvas
 */
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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                NodeAvatar(
                    nodeId = peer.nodeId,
                    alias = peer.alias,
                    size = 40.dp,
                    isDirect = peer.isDirect,
                    showOnlineBadge = true
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = peer.alias,
                            color = TextPrimary,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val hopBadge = if (peer.isDirect) "Direct BLE" else "${peer.hopCount} hops"
                        Text(
                            text = "• $hopBadge",
                            color = if (peer.isDirect) WhatsAppGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "FP: ${peer.fingerprint.take(9)}... • ID: ${peer.nodeIdHex.takeLast(6)}",
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }
            }

            Button(
                onClick = onOpenChat,
                colors = ButtonDefaults.buttonColors(containerColor = DarkSurfaceVariant),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(6.dp),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, DarkCardBorder)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    tint = EmeraldAccent,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}


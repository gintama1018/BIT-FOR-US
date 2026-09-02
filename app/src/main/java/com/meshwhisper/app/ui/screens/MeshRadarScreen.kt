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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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
import androidx.compose.material.icons.filled.Emergency
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.data.model.TopologyEdgeEntity
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.components.SaharaTopAppBar
import com.meshwhisper.app.ui.graph.GraphEdge
import com.meshwhisper.app.ui.graph.GraphNode
import com.meshwhisper.app.ui.graph.GraphPhysicsSimulation
import com.meshwhisper.app.ui.theme.*
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
    val locations by viewModel.allLocations.collectAsState()
    val selectedHomingPeerId by viewModel.selectedHomingPeerId.collectAsState()
    val supportsPeripheral by viewModel.supportsPeripheral.collectAsState()
    val connectedCount by viewModel.connectedPeersCount.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()

    var selectedViewTab by remember { mutableIntStateOf(0) } // 0 = Web of Nodes, 1 = Radar Scope, 2 = Offline Map, 3 = RSSI Homing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Sahara Header
        SaharaTopAppBar(
            title = "Mesh Radar",
            subtitle = "$connectedCount LIVE DIRECT • ${peers.size} DISCOVERED",
            actionIcon = Icons.Default.Emergency,
            onActionClick = { viewModel.announcePresence() }
        )

        // Hardware Support Warning Banner if Peripheral mode is missing
        if (!supportsPeripheral) {
            Card(
                colors = CardDefaults.cardColors(containerColor = SaharaSurfaceContainerLow),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, SaharaWarning.copy(alpha = 0.5f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = SaharaWarning, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Device operating in Central Relay mode.",
                        color = SaharaOnSurface,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily
                    )
                }
            }
        }

        // View Mode Selector Tab Row
        TabRow(
            selectedTabIndex = selectedViewTab,
            containerColor = SaharaBackground,
            contentColor = SaharaPrimary,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedViewTab]),
                    color = SaharaPrimary
                )
            },
            divider = {
                HorizontalDivider(color = SaharaSurfaceContainerHigh, thickness = 0.8.dp)
            }
        ) {
            Tab(
                selected = selectedViewTab == 0,
                onClick = { selectedViewTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = null,
                            tint = if (selectedViewTab == 0) BurntSienna else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Nodes",
                            color = if (selectedViewTab == 0) BurntSienna else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = if (selectedViewTab == 0) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            )
            Tab(
                selected = selectedViewTab == 1,
                onClick = { selectedViewTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Radar,
                            contentDescription = null,
                            tint = if (selectedViewTab == 1) BurntSienna else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Radar",
                            color = if (selectedViewTab == 1) BurntSienna else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = if (selectedViewTab == 1) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            )
            Tab(
                selected = selectedViewTab == 2,
                onClick = { selectedViewTab = 2 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = if (selectedViewTab == 2) BurntSienna else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Map",
                            color = if (selectedViewTab == 2) BurntSienna else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = if (selectedViewTab == 2) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
            )
            Tab(
                selected = selectedViewTab == 3,
                onClick = { selectedViewTab = 3 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.CenterFocusStrong,
                            contentDescription = null,
                            tint = if (selectedViewTab == 3) BurntSienna else TextMuted,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Homing",
                            color = if (selectedViewTab == 3) BurntSienna else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = if (selectedViewTab == 3) FontWeight.Bold else FontWeight.Medium
                        )
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
            when (selectedViewTab) {
                0 -> {
                    MeshWebVisualizerCanvas(
                        myNodeId = viewModel.myNodeId,
                        myAlias = viewModel.myAlias.collectAsState().value,
                        peers = peers,
                        topologyEdges = topologyEdges,
                        connectedNodeIds = connectedNodeIds,
                        onNodeClick = onOpenChat
                    )
                }
                1 -> {
                    RadarVisualizerCanvas(peers = peers)
                }
                2 -> {
                    OfflineCampusMapView(
                        locations = locations,
                        peers = peers,
                        myNodeId = viewModel.myNodeId,
                        myAlias = viewModel.myAlias.collectAsState().value,
                        onPeerClick = { nodeId ->
                            viewModel.selectHomingPeer(nodeId)
                            selectedViewTab = 3
                        }
                    )
                }
                3 -> {
                    RssiProximityHomingView(
                        peers = peers,
                        selectedPeerId = selectedHomingPeerId,
                        onSelectPeer = { viewModel.selectHomingPeer(it) },
                        onOpenChat = onOpenChat,
                        viewModel = viewModel
                    )
                }
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
                color = TextPrimary,
                fontSize = 15.sp,
                fontFamily = EBGaramondFamily,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Tap a node to chat",
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = ManropeFamily
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
                RadarPeerCard(
                    peer = peer,
                    lastSeenFormatted = viewModel.formatLastSeen(peer.lastSeen),
                    onOpenChat = { onOpenChat(peer.nodeId) },
                    onStartHoming = {
                        viewModel.selectHomingPeer(peer.nodeId)
                        selectedViewTab = 3
                    }
                )
            }
        }
    }
}

/**
 * Web-of-Nodes Canvas powered by Force-Directed Physics Simulation.
 * Renders the decentralized interconnected mesh graph on Warm Canvas.
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
            .clip(RoundedCornerShape(8.dp))
            .background(WarmSurface)
            .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
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
                    color = WarmCardBorder.copy(alpha = 0.65f),
                    radius = 1.2.dp.toPx(),
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
                // Solid Burnt Sienna link for direct BLE communication
                drawLine(
                    color = BurntSienna.copy(alpha = 0.75f),
                    start = start,
                    end = end,
                    strokeWidth = 2.dp.toPx()
                )
            } else {
                // Dashed warm muted link for multi-hop relay links between remote peers
                drawLine(
                    color = TextMuted.copy(alpha = 0.5f),
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
                color = if (edge.isDirect) BurntSienna else DustyRose,
                radius = 2.5.dp.toPx(),
                center = Offset(pulseX, pulseY)
            )
        }

        // Draw Nodes
        for (node in graphNodes) {
            val nodeCenter = Offset(node.x, node.y)

            if (node.isSelf) {
                // Self Node (Center Local Phone)
                val pulseR = 22.dp.toPx() + (8.dp.toPx() * pulseProgress)
                drawCircle(
                    color = BurntSienna.copy(alpha = 0.25f * (1f - pulseProgress)),
                    radius = pulseR,
                    center = nodeCenter
                )
                drawCircle(
                    color = BurntSienna,
                    radius = 16.dp.toPx(),
                    center = nodeCenter
                )
                drawCircle(
                    color = Color.White,
                    radius = 16.dp.toPx(),
                    center = nodeCenter,
                    style = Stroke(width = 2.dp.toPx())
                )
            } else {
                // Remote Peer Node
                drawCircle(
                    color = WarmSurfaceContainer,
                    radius = 14.dp.toPx(),
                    center = nodeCenter
                )
                drawCircle(
                    color = BurntSienna.copy(alpha = 0.8f),
                    radius = 14.dp.toPx(),
                    center = nodeCenter,
                    style = Stroke(width = 1.5.dp.toPx())
                )
            }

            // Draw Node Label text via Android Native Canvas Paint
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.rgb(42, 35, 29) // TextPrimary #2A231D
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
 * Traditional Single-Perspective Radar Scope Canvas on Warm Linen
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
            .clip(RoundedCornerShape(8.dp))
            .background(WarmSurface)
            .border(0.8.dp, WarmCardBorder, RoundedCornerShape(8.dp))
    ) {
        val center = Offset(size.width / 2, size.height / 2)
        val maxRadius = minOf(size.width, size.height) / 2 * 0.85f

        // Concentric Rings
        val rings = 3
        for (i in 1..rings) {
            val r = maxRadius * (i.toFloat() / rings)
            drawCircle(
                color = WarmCardBorder,
                radius = r,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // Radar Pulse Wave
        val animatedRadius = maxRadius * pulseProgress
        drawCircle(
            color = BurntSienna.copy(alpha = 0.6f * (1f - pulseProgress)),
            radius = animatedRadius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Center Node (Local Phone)
        drawCircle(
            color = BurntSienna,
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
                color = if (peer.isDirect) BurntSienna.copy(alpha = 0.5f) else TextMuted.copy(alpha = 0.35f),
                start = center,
                end = Offset(x, y),
                strokeWidth = 1.5.dp.toPx()
            )

            // Peer Node Dot
            drawCircle(
                color = if (peer.isDirect) BurntSienna else DustyRose,
                radius = 6.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

@Composable
private fun RadarPeerCard(
    peer: PeerEntity,
    lastSeenFormatted: String,
    onOpenChat: () -> Unit,
    onStartHoming: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = WarmSurface),
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
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
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val hopBadge = if (peer.isDirect) "Direct BLE" else "${peer.hopCount} hops"
                        Text(
                            text = "• $hopBadge",
                            color = if (peer.isDirect) WarmGreen else TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "$lastSeenFormatted • ID: ${peer.nodeIdHex.takeLast(6)}",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontFamily = ManropeFamily
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // Proximity Homing Button
                Button(
                    onClick = onStartHoming,
                    colors = ButtonDefaults.buttonColors(containerColor = WarmSurfaceContainer),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder)
                ) {
                    Icon(
                        imageVector = Icons.Default.CenterFocusStrong,
                        contentDescription = "Homing",
                        tint = BurntSienna,
                        modifier = Modifier.size(14.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Button(
                    onClick = onOpenChat,
                    colors = ButtonDefaults.buttonColors(containerColor = WarmSurfaceContainer),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = BurntSienna,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Chat",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

/**
 * 100% Offline Vector Campus Map View.
 * Renders an offline coordinate grid overlay with campus sectors & known node location pins.
 */
@Composable
fun OfflineCampusMapView(
    locations: List<com.meshwhisper.app.data.model.LastKnownLocationEntity>,
    peers: List<PeerEntity>,
    myNodeId: Long,
    myAlias: String,
    onPeerClick: (Long) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "MapPulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "MapPulseVal"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(WarmSurface)
            .border(1.dp, WarmCardBorder, RoundedCornerShape(12.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy) * 0.9f

            // 1. Draw Offline Campus Grid & Concentric Zone Rings
            for (r in listOf(0.33f, 0.66f, 1.0f)) {
                drawCircle(
                    color = WarmCardBorder.copy(alpha = 0.8f),
                    radius = maxR * r,
                    center = Offset(cx, cy),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                )
            }

            // Crosshair Coordinate Axes
            drawLine(
                color = WarmCardBorder,
                start = Offset(cx, cy - maxR),
                end = Offset(cx, cy + maxR),
                strokeWidth = 1.dp.toPx()
            )
            drawLine(
                color = WarmCardBorder,
                start = Offset(cx - maxR, cy),
                end = Offset(cx + maxR, cy),
                strokeWidth = 1.dp.toPx()
            )

            // Campus Sector Zone Labels
            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.GRAY
                textSize = 22f
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText("ZONE ALPHA (North)", cx - 90f, cy - maxR + 30f, textPaint)
            drawContext.canvas.nativeCanvas.drawText("ZONE BRAVO (South)", cx - 90f, cy + maxR - 15f, textPaint)
            drawContext.canvas.nativeCanvas.drawText("WEST QUAD", cx - maxR + 15f, cy - 10f, textPaint)
            drawContext.canvas.nativeCanvas.drawText("EAST LABS", cx + maxR - 100f, cy - 10f, textPaint)

            // 2. Draw Self Marker (Center Coordinate)
            drawCircle(
                color = BurntSienna.copy(alpha = 0.3f * pulse),
                radius = 16.dp.toPx(),
                center = Offset(cx, cy)
            )
            drawCircle(
                color = BurntSienna,
                radius = 7.dp.toPx(),
                center = Offset(cx, cy)
            )

            // 3. Draw Discovered Peer Pins
            val activePeers = if (locations.isNotEmpty()) {
                locations
            } else {
                // Synthesize positions from direct peer RSSI/hop count if no GPS fix
                peers.mapIndexed { idx, p ->
                    val angle = (idx.toDouble() / maxOf(1, peers.size)) * 2.0 * Math.PI
                    val dist = (p.hopCount * 0.35).coerceAtMost(0.85)
                    com.meshwhisper.app.data.model.LastKnownLocationEntity(
                        nodeId = p.nodeId,
                        alias = p.alias,
                        latitude = dist * cos(angle),
                        longitude = dist * sin(angle),
                        timestamp = p.lastSeen
                    )
                }
            }

            for (loc in activePeers) {
                if (loc.nodeId == myNodeId) continue
                // Map coordinates relative to center
                val angle = (loc.nodeId.hashCode() % 360) * (Math.PI / 180.0)
                val distanceRatio = ((loc.nodeId.hashCode() and 0x7FFFFFFF) % 65 + 25) / 100f * maxR
                val px = cx + (cos(angle) * distanceRatio).toFloat()
                val py = cy + (sin(angle) * distanceRatio).toFloat()

                // Glow ring
                drawCircle(
                    color = WarmGreen.copy(alpha = 0.25f * pulse),
                    radius = 12.dp.toPx(),
                    center = Offset(px, py)
                )
                // Core Pin
                drawCircle(
                    color = WarmGreen,
                    radius = 5.dp.toPx(),
                    center = Offset(px, py)
                )

                // Label
                val labelPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.DKGRAY
                    textSize = 24f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText("📍 ${loc.alias}", px + 14f, py + 8f, labelPaint)
            }
        }

        // Overlay Map Legend & Mode Badge
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(WarmSurfaceContainer.copy(alpha = 0.9f))
                .border(0.8.dp, WarmCardBorder, RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = Icons.Default.Explore, contentDescription = null, tint = BurntSienna, modifier = Modifier.size(12.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "100% Offline Grid • ${locations.size} GPS fixes",
                color = TextPrimary,
                fontSize = 10.sp,
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * RSSI-Based Proximity Homing View ("Find My Peer").
 * Search-and-Rescue beacon locator with live dBm signal strength gauge & pulsing distance rings.
 */
@Composable
fun RssiProximityHomingView(
    peers: List<PeerEntity>,
    selectedPeerId: Long?,
    onSelectPeer: (Long) -> Unit,
    onOpenChat: (Long) -> Unit,
    viewModel: MeshViewModel
) {
    val targetPeer = peers.find { it.nodeId == selectedPeerId } ?: peers.firstOrNull()
    val rawRssi = targetPeer?.rssi ?: -75

    // Exponential Moving Average (EMA) smoothing (alpha = 0.25) to prevent physical multipath flicker
    var smoothedRssi by remember(targetPeer?.nodeId) { mutableFloatStateOf(rawRssi.toFloat()) }
    LaunchedEffect(rawRssi) {
        smoothedRssi = (smoothedRssi * 0.75f) + (rawRssi.toFloat() * 0.25f)
    }

    // Relative RF Signal Proximity Tiering (Honest RF propagation without false meter claims)
    val (statusTitle, statusColor, pulseSpeed) = when {
        smoothedRssi >= -62f -> Triple("STRONG SIGNAL (Immediate Proximity)", SaharaOnline, 400)
        smoothedRssi >= -78f -> Triple("MODERATE SIGNAL (Close Range)", SaharaWarning, 800)
        else -> Triple("WEAK SIGNAL (Distant / Obstacles)", SaharaPrimary, 1400)
    }

    val infiniteTransition = rememberInfiniteTransition(label = "HomingPulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseSpeed, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "HomingPulseVal"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(WarmSurface)
            .border(1.dp, WarmCardBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Target Selector & Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TARGET: ${targetPeer?.alias ?: "No Peer Selected"}",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = statusTitle,
                    color = statusColor,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Bold
                )
            }

            if (targetPeer != null) {
                Button(
                    onClick = { onOpenChat(targetPeer.nodeId) },
                    colors = ButtonDefaults.buttonColors(containerColor = BurntSienna),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text("Hail Peer", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Animated Concentric Homing Gauge
        Box(
            modifier = Modifier
                .size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = size.width / 2f
                val cy = size.height / 2f
                val maxR = size.width / 2f

                // Outer pulsing wave
                drawCircle(
                    color = statusColor.copy(alpha = 0.2f * pulseAlpha),
                    radius = maxR * 0.95f
                )
                drawCircle(
                    color = statusColor.copy(alpha = 0.4f * pulseAlpha),
                    radius = maxR * 0.70f
                )
                drawCircle(
                    color = statusColor.copy(alpha = 0.7f * pulseAlpha),
                    radius = maxR * 0.45f
                )
                // Center node pin
                drawCircle(
                    color = statusColor,
                    radius = maxR * 0.20f
                )
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (targetPeer?.isDirect == true) "${smoothedRssi.toInt()}" else "Mesh",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = if (targetPeer?.isDirect == true) "dBm" else "${targetPeer?.hopCount ?: 1} hops",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    fontFamily = ManropeFamily
                )
            }
        }

        // Relative Last Seen Info
        Text(
            text = "Last signal: ${targetPeer?.let { viewModel.formatLastSeen(it.lastSeen) } ?: "N/A"}",
            color = TextSecondary,
            fontSize = 11.sp,
            fontFamily = ManropeFamily
        )
    }
}

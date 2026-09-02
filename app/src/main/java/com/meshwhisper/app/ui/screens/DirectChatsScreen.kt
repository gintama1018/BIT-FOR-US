package com.meshwhisper.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.data.model.MediaType
import com.meshwhisper.app.data.model.MessageEntity
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.ui.components.NodeAvatar
import com.meshwhisper.app.ui.theme.*
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DirectChatsScreen(
    viewModel: MeshViewModel,
    onOpenChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by viewModel.peers.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Map latest messages by peerId
    val recentMap = remember(recentConversations) {
        val map = mutableMapOf<Long, MessageEntity>()
        for (msg in recentConversations) {
            val peerId = if (msg.isOutgoing) msg.recipientId else msg.senderId
            if (!map.containsKey(peerId) || (map[peerId]?.timestamp ?: 0L) < msg.timestamp) {
                map[peerId] = msg
            }
        }
        map
    }

    // Sort peers: active conversations first, then online status, then alias, filtered by search query
    val sortedPeers = remember(peers, recentMap, connectedNodeIds, searchQuery) {
        peers.map { peer ->
            peer.copy(isDirect = connectedNodeIds.contains(peer.nodeId))
        }.filter { peer ->
            if (searchQuery.isBlank()) true
            else {
                val hex = String.format("%016X", peer.nodeId)
                peer.alias.contains(searchQuery, ignoreCase = true) || hex.contains(searchQuery, ignoreCase = true)
            }
        }.sortedWith(
            compareByDescending<PeerEntity> { recentMap[it.nodeId]?.timestamp ?: 0L }
                .thenByDescending { it.isDirect }
                .thenBy { it.alias }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SaharaBackground)
    ) {
        // Top Header matching 3._direct_messages/code.html
        Surface(
            color = SaharaBackground,
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { viewModel.announcePresence() },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SignalCellularAlt,
                            contentDescription = "Signal Status",
                            tint = SaharaPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            isSearchActive = !isSearchActive
                            if (!isSearchActive) searchQuery = ""
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                            contentDescription = "Search Contacts",
                            tint = if (isSearchActive) SaharaPrimary else SaharaOnSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

                // Collapsible Search Input Field
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search alias or node hex...", color = SaharaOnSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = SaharaPrimary,
                            unfocusedBorderColor = SaharaOutlineVariant,
                            focusedTextColor = SaharaOnSurface,
                            unfocusedTextColor = SaharaOnSurface,
                            focusedContainerColor = SaharaSurfaceContainerLowest,
                            unfocusedContainerColor = SaharaSurfaceContainerLowest
                        ),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = SaharaPrimary, modifier = Modifier.size(18.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear", tint = SaharaOnSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp)
                    )
                }

                if (!isSearchActive) {
                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Direct",
                        style = MaterialTheme.typography.displayMedium,
                        color = SaharaPrimary,
                        fontWeight = FontWeight.Medium
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "E2EE",
                            tint = SaharaOnSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "END-TO-END ENCRYPTED",
                            style = MaterialTheme.typography.labelMedium,
                            color = SaharaOnSurfaceVariant.copy(alpha = 0.8f),
                            fontSize = 11.sp,
                            letterSpacing = 0.6.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }

        if (sortedPeers.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = SaharaPrimary.copy(alpha = 0.35f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "No matching contacts found" else "No mesh contacts yet",
                        color = SaharaOnSurface,
                        fontSize = 20.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (searchQuery.isNotBlank()) "Try searching for a different alias or 4-digit hex ID." else "When other MeshWhisper nodes come into Wi-Fi / BLE range, they will appear here automatically.",
                        color = SaharaOnSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp)
            ) {
                items(sortedPeers, key = { it.nodeId }) { peer ->
                    val latestMessage = recentMap[peer.nodeId]
                    SaharaInboxPeerRow(
                        peer = peer,
                        latestMessage = latestMessage,
                        onClick = { onOpenChat(peer.nodeId) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp, end = 8.dp),
                        thickness = 0.6.dp,
                        color = SaharaSurfaceContainerHigh
                    )
                }
            }
        }
    }
}

/**
 * Editorial Inbox Peer Row matching 3._direct_messages/code.html
 */
@Composable
private fun SaharaInboxPeerRow(
    peer: PeerEntity,
    latestMessage: MessageEntity?,
    onClick: () -> Unit
) {
    val formattedTime = remember(latestMessage?.timestamp, peer.lastSeen) {
        val ts = latestMessage?.timestamp ?: peer.lastSeen
        formatInboxTime(ts)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar with Direct Online Pill Dot
        Box(modifier = Modifier.size(48.dp)) {
            NodeAvatar(
                nodeId = peer.nodeId,
                alias = peer.alias,
                size = 48.dp,
                isDirect = peer.isDirect
            )
            if (peer.isDirect) {
                Box(
                    modifier = Modifier
                        .size(13.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(SaharaOnline)
                        .border(2.dp, SaharaBackground, CircleShape)
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Peer Details & Snippet
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = peer.alias.ifBlank { "Peer 0x${String.format("%016X", peer.nodeId).takeLast(4)}" },
                    style = MaterialTheme.typography.titleMedium,
                    color = SaharaOnSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (peer.isDirect) SaharaPrimary else SaharaOnSurfaceVariant,
                    fontSize = 11.sp
                )
            }

            // Message Snippet
            val snippet = when {
                latestMessage == null -> "Encrypted session ready"
                latestMessage.mediaType == MediaType.IMAGE -> "📷 Photo"
                latestMessage.mediaType == MediaType.VOICE -> "🎙️ Voice Note"
                latestMessage.mediaType == MediaType.FILE -> "📁 File Attachment"
                else -> latestMessage.text
            }

            Text(
                text = snippet,
                style = MaterialTheme.typography.bodyMedium,
                color = SaharaOnSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // Hop Pill with clean AltRoute icon
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AltRoute,
                    contentDescription = null,
                    tint = SaharaOnSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = if (peer.isDirect) "Direct Link (0 hops)" else "${peer.hopCount} hop(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = SaharaOnSurfaceVariant.copy(alpha = 0.6f),
                    fontSize = 10.sp
                )
            }
        }
    }
}

private fun formatInboxTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val now = Calendar.getInstance()
    val msgCal = Calendar.getInstance().apply { timeInMillis = timestamp }

    return if (now.get(Calendar.YEAR) == msgCal.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgCal.get(Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date(timestamp))
    } else {
        SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

package com.meshwhisper.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.app.ui.components.NodeAvatar
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
import com.meshwhisper.app.ui.theme.WarmDivider
import com.meshwhisper.app.ui.theme.WarmGreen
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.theme.WarmRed
import com.meshwhisper.app.ui.theme.WarmSurface
import com.meshwhisper.app.ui.theme.WarmSurfaceContainer
import com.meshwhisper.app.ui.viewmodel.MeshViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun DirectChatsScreen(
    viewModel: MeshViewModel,
    onOpenChat: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val peers by viewModel.peers.collectAsState()
    val connectedNodeIds by viewModel.connectedNodeIds.collectAsState()
    val recentConversations by viewModel.recentConversations.collectAsState()

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

    // Sort peers: active conversations first, then online status, then alias
    val sortedPeers = remember(peers, recentMap, connectedNodeIds) {
        peers.map { peer ->
            peer.copy(isDirect = connectedNodeIds.contains(peer.nodeId))
        }.sortedWith(
            compareByDescending<PeerEntity> { recentMap[it.nodeId]?.timestamp ?: 0L }
                .thenByDescending { it.isDirect }
                .thenBy { it.alias }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(WarmLinen)
    ) {
        // Direct Header
        Card(
            colors = CardDefaults.cardColors(containerColor = WarmSurface),
            shape = RoundedCornerShape(0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Direct Encrypted",
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontFamily = EBGaramondFamily,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = "E2EE",
                                tint = BurntSienna,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = "End-to-end encrypted with X25519 & AES-256-GCM",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(WarmSurfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "${peers.size} contact${if (peers.size != 1) "s" else ""}",
                            color = TextPrimary,
                            fontSize = 11.sp,
                            fontFamily = ManropeFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
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
                        tint = BurntSienna.copy(alpha = 0.5f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No mesh contacts yet",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "When other MeshWhisper nodes come into BLE range, they will appear here automatically.",
                        color = TextSecondary,
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
                    .fillMaxWidth()
            ) {
                items(sortedPeers, key = { it.nodeId }) { peer ->
                    val latestMessage = recentMap[peer.nodeId]
                    InboxPeerRow(
                        peer = peer,
                        latestMessage = latestMessage,
                        onClick = { onOpenChat(peer.nodeId) }
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp),
                        thickness = 0.6.dp,
                        color = WarmDivider
                    )
                }
            }
        }
    }
}

@Composable
private fun InboxPeerRow(
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Deterministic Initials Avatar with online direct badge
        NodeAvatar(
            nodeId = peer.nodeId,
            alias = peer.alias,
            size = 48.dp,
            isDirect = peer.isDirect,
            showOnlineBadge = true
        )

        Spacer(modifier = Modifier.width(14.dp))

        // Contact details & message preview
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = peer.alias,
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formattedTime,
                    color = if (latestMessage != null) TextSecondary else TextMuted,
                    fontSize = 11.sp,
                    fontFamily = ManropeFamily
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (peer.hasKeyChanged) {
                    Text(
                        text = "⚠️ Safety number changed",
                        color = WarmAmber,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                } else if (peer.isBlocked) {
                    Text(
                        text = "🚫 Blocked contact",
                        color = WarmRed,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        maxLines = 1
                    )
                } else if (latestMessage != null) {
                    if (latestMessage.isOutgoing) {
                        if (latestMessage.status == MessageStatus.DELIVERED) {
                            Icon(
                                imageVector = Icons.Default.DoneAll,
                                contentDescription = "Delivered",
                                tint = WarmGreen,
                                modifier = Modifier.size(14.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Sent",
                                tint = TextMuted,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    val previewText = when (latestMessage.mediaType) {
                        MediaType.IMAGE -> if (latestMessage.text.isNotBlank() && latestMessage.text != "📷 Photo") "📷 ${latestMessage.text}" else "📷 Photo"
                        MediaType.VOICE -> "🎤 Voice message"
                        else -> latestMessage.text
                    }
                    Text(
                        text = previewText,
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val statusText = if (peer.isDirect) "Direct link • Tap to chat" else "${peer.hopCount} hops away (Relayed)"
                    Text(
                        text = statusText,
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun formatInboxTime(timestamp: Long): String {
    val now = Calendar.getInstance()
    val msgTime = Calendar.getInstance().apply { timeInMillis = timestamp }

    return if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == msgTime.get(Calendar.DAY_OF_YEAR)
    ) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
    } else if (now.get(Calendar.YEAR) == msgTime.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) - msgTime.get(Calendar.DAY_OF_YEAR) == 1
    ) {
        "Yesterday"
    } else {
        SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
    }
}

package com.meshwhisper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.DarkBackground
import com.meshwhisper.app.ui.theme.WhatsAppGreen
import kotlin.math.abs

private val AVATAR_PALETTE = listOf(
    Color(0xFF007A60), // WhatsApp Teal
    Color(0xFF1E88E5), // Blue
    Color(0xFF8E24AA), // Purple
    Color(0xFF00897B), // Mint Teal
    Color(0xFFD81B60), // Rose Pink
    Color(0xFFFB8C00), // Amber Orange
    Color(0xFF5C6BC0), // Indigo
    Color(0xFF00ACC1), // Cyan Teal
    Color(0xFF43A047), // Green
    Color(0xFFE53935)  // Crimson
)

@Composable
fun NodeAvatar(
    nodeId: Long,
    alias: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    isDirect: Boolean = false,
    showOnlineBadge: Boolean = false
) {
    val bgColor = remember(nodeId) {
        val index = abs((nodeId xor (nodeId ushr 32)).toInt()) % AVATAR_PALETTE.size
        AVATAR_PALETTE[index]
    }

    val initials = remember(alias, nodeId) {
        val clean = alias.trim()
        if (clean.startsWith("Node-", ignoreCase = true) && clean.length > 5) {
            clean.substring(5).take(2).uppercase()
        } else if (clean.isNotBlank()) {
            val parts = clean.split(" ").filter { it.isNotBlank() }
            if (parts.size >= 2) {
                "${parts[0].first()}${parts[1].first()}".uppercase()
            } else {
                clean.take(2).uppercase()
            }
        } else {
            String.format("%02X", abs(nodeId.toInt()) % 256)
        }
    }

    val fontSize = (size.value * 0.38f).sp

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bgColor),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.Bold
            )
        }

        if (showOnlineBadge && isDirect) {
            val badgeSize = size * 0.3f
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .clip(CircleShape)
                    .background(WhatsAppGreen)
                    .border(1.5.dp, DarkBackground, CircleShape)
            )
        }
    }
}

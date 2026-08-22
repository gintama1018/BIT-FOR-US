package com.meshwhisper.app.ui.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.WarmGreen
import com.meshwhisper.app.ui.theme.WarmLinen
import java.io.File
import kotlin.math.abs

// Sahara Warm-Shifted Avatar Palette (Burnt Sienna, Terracotta, Dusty Rose, Ochre, Raw Umber)
private val AVATAR_PALETTE = listOf(
    Color(0xFFC2652A), // Burnt Sienna
    Color(0xFF8C3C3C), // Dusty Rose
    Color(0xFFD97746), // Terracotta
    Color(0xFFC98A2C), // Warm Ochre
    Color(0xFFA85C3B), // Deep Clay
    Color(0xFF8D7362), // Sandstone Taupe
    Color(0xFFD48A37), // Warm Amber
    Color(0xFF9C4A2F), // Russet
    Color(0xFF7D5334), // Raw Umber
    Color(0xFF8A421B)  // Dark Sienna
)

@Composable
fun NodeAvatar(
    nodeId: Long,
    alias: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    avatarUri: String? = null,
    isDirect: Boolean = false,
    showOnlineBadge: Boolean = false
) {
    val bitmap = remember(avatarUri) {
        if (!avatarUri.isNullOrBlank()) {
            val file = File(avatarUri)
            if (file.exists() && file.length() > 0) {
                BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
            } else null
        } else null
    }

    val bgColor = remember(nodeId) {
        val hash = (nodeId xor (nodeId ushr 32)) and 0x7FFFFFFF
        val index = (hash % AVATAR_PALETTE.size).toInt()
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
            String.format("%02X", (nodeId and 0xFFL))
        }
    }

    val fontSize = (size.value * 0.38f).sp

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "$alias avatar",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
            )
        } else {
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
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (showOnlineBadge && isDirect) {
            val badgeSize = size * 0.3f
            Box(
                modifier = Modifier
                    .size(badgeSize)
                    .align(Alignment.BottomEnd)
                    .offset(x = 1.dp, y = 1.dp)
                    .clip(CircleShape)
                    .background(WarmGreen)
                    .border(1.5.dp, WarmLinen, CircleShape)
            )
        }
    }
}

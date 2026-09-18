package com.meshwhisper.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.ManropeFamily

data class TrustBadgeStyle(
    val text: String,
    val icon: ImageVector,
    val textColor: Color,
    val backgroundColor: Color,
    val borderColor: Color
)

fun getTrustBadgeStyle(trustState: String): TrustBadgeStyle {
    return when (trustState.uppercase()) {
        "VERIFIED" -> TrustBadgeStyle(
            text = "VERIFIED",
            icon = Icons.Default.Verified,
            textColor = Color(0xFF2E7D32),
            backgroundColor = Color(0xFFE8F5E9),
            borderColor = Color(0xFFA5D6A7)
        )
        "LINKED" -> TrustBadgeStyle(
            text = "LINKED",
            icon = Icons.Default.Link,
            textColor = Color(0xFF1565C0),
            backgroundColor = Color(0xFFE3F2FD),
            borderColor = Color(0xFF90CAF9)
        )
        "IMPORTED" -> TrustBadgeStyle(
            text = "IMPORTED",
            icon = Icons.Default.Download,
            textColor = Color(0xFFE65100),
            backgroundColor = Color(0xFFFFF3E0),
            borderColor = Color(0xFFFFCC80)
        )
        "CONFLICTED" -> TrustBadgeStyle(
            text = "COLLISION",
            icon = Icons.Default.Warning,
            textColor = Color(0xFFC62828),
            backgroundColor = Color(0xFFFFEBEE),
            borderColor = Color(0xFFEF9A9A)
        )
        "BLOCKED" -> TrustBadgeStyle(
            text = "BLOCKED",
            icon = Icons.Default.Block,
            textColor = Color(0xFF424242),
            backgroundColor = Color(0xFFEEEEEE),
            borderColor = Color(0xFFBDBDBD)
        )
        "SEEN" -> TrustBadgeStyle(
            text = "SEEN",
            icon = Icons.Default.Visibility,
            textColor = Color(0xFF546E7A),
            backgroundColor = Color(0xFFECEFF1),
            borderColor = Color(0xFFCFD8DC)
        )
        else -> TrustBadgeStyle(
            text = "LEGACY",
            icon = Icons.Default.HourglassEmpty,
            textColor = Color(0xFF78909C),
            backgroundColor = Color(0xFFF5F5F5),
            borderColor = Color(0xFFE0E0E0)
        )
    }
}

@Composable
fun TrustBadge(
    trustState: String,
    modifier: Modifier = Modifier
) {
    val style = getTrustBadgeStyle(trustState)
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(style.backgroundColor)
            .border(0.8.dp, style.borderColor, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = style.icon,
            contentDescription = style.text,
            tint = style.textColor,
            modifier = Modifier.size(11.dp)
        )
        Text(
            text = style.text,
            color = style.textColor,
            fontSize = 9.sp,
            fontFamily = ManropeFamily,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp
        )
    }
}

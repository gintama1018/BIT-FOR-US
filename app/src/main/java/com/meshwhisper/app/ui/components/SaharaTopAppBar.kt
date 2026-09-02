package com.meshwhisper.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.theme.*

/**
 * Editorial Sahara Top App Bar matching the stitch_meshwhisper_sahara_redesign system.
 */
@Composable
fun SaharaTopAppBar(
    title: String,
    subtitle: String? = null,
    peerCount: Int = 0,
    navigationIcon: ImageVector = Icons.Default.SignalCellularAlt,
    onNavigationClick: (() -> Unit)? = null,
    actionIcon: ImageVector? = Icons.Default.Emergency,
    actionIconTint: Color = SaharaError,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Pulse animation for active mesh indicator dot
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Surface(
        color = SaharaBackground,
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left Navigation / Status Button
            IconButton(
                onClick = { onNavigationClick?.invoke() },
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    imageVector = navigationIcon,
                    contentDescription = "Signal Status",
                    tint = SaharaPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Center Editorial Title & Subtitle
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = SaharaPrimary,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = SaharaOnSurfaceVariant,
                        fontSize = 11.sp,
                        letterSpacing = 0.5.sp
                    )
                } else {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(SaharaPrimary.copy(alpha = dotAlpha))
                        )
                        Text(
                            text = if (peerCount > 0) "MESH ACTIVE • $peerCount NEARBY NODE${if (peerCount > 1) "S" else ""}" else "MESH ACTIVE • STANDBY",
                            style = MaterialTheme.typography.labelMedium,
                            color = SaharaOnSurfaceVariant,
                            fontSize = 10.sp,
                            letterSpacing = 0.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Right Action / Emergency Button
            if (actionIcon != null) {
                IconButton(
                    onClick = { onActionClick?.invoke() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                ) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = "Action",
                        tint = actionIconTint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(40.dp))
            }
        }
    }
}

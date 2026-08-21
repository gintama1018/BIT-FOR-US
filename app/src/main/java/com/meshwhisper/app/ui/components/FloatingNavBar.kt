package com.meshwhisper.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.NavTab
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaContainer
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmSurface

/**
 * Reusable Floating Capsule / Pill Bottom Navigation Component.
 * Inspired by Sahara Warm Minimalism — compact, elevated, centered pill.
 */
@Composable
fun FloatingBottomNavigationPill(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 280.dp, max = 380.dp)
            .shadow(
                elevation = 8.dp,
                shape = CircleShape,
                ambientColor = Color(0x1F3A302A),
                spotColor = Color(0x143A302A)
            ),
        shape = CircleShape,
        color = WarmSurface,
        border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavTab.entries.forEach { tab ->
                val isSelected = selectedTab == tab
                FloatingNavItem(
                    tab = tab,
                    isSelected = isSelected,
                    onClick = { onTabSelected(tab) }
                )
            }
        }
    }
}

@Composable
private fun FloatingNavItem(
    tab: NavTab,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    val iconTint by animateColorAsState(
        targetValue = if (isSelected) Color.White else TextMuted,
        animationSpec = tween(200),
        label = "navIconTint"
    )

    Column(
        modifier = Modifier
            .clip(CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(bounded = true, radius = 24.dp, color = BurntSienna),
                onClick = onClick
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (isSelected) {
            // Active State: Burnt Sienna circular badge with white icon and bold micro-label
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(BurntSienna),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.title,
                    tint = iconTint,
                    modifier = Modifier.size(17.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = tab.label.uppercase(),
                color = BurntSienna,
                fontSize = 9.sp,
                fontFamily = ManropeFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
        } else {
            // Inactive State: Clean muted icon, icon-first minimalism
            Box(
                modifier = Modifier
                    .size(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.title,
                    tint = iconTint,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            // Transparent placeholder to preserve exact height alignment across items
            Text(
                text = "",
                fontSize = 9.sp,
                fontFamily = ManropeFamily
            )
        }
    }
}

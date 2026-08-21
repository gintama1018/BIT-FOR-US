package com.meshwhisper.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshwhisper.app.ui.screens.DirectChatDetailScreen
import com.meshwhisper.app.ui.screens.DirectChatsScreen
import com.meshwhisper.app.ui.screens.IdentitySettingsScreen
import com.meshwhisper.app.ui.screens.MeshRadarScreen
import com.meshwhisper.app.ui.screens.PacketInspectorScreen
import com.meshwhisper.app.ui.screens.PublicMeshScreen
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.BurntSiennaContainer
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.theme.WarmSurfaceContainer
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

enum class NavTab(val title: String, val icon: ImageVector) {
    PUBLIC("Public", Icons.Default.CellTower),
    DIRECT("Direct", Icons.Default.Lock),
    RADAR("Radar", Icons.Default.NetworkCheck),
    INSPECTOR("Logs", Icons.Default.DataObject),
    SETTINGS("Identity", Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: MeshViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableStateOf(NavTab.PUBLIC) }
    var activeDirectChatPeerId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        bottomBar = {
            if (activeDirectChatPeerId == null) {
                androidx.compose.foundation.layout.Column {
                    HorizontalDivider(color = WarmCardBorder, thickness = 0.8.dp)
                    NavigationBar(
                        containerColor = WarmSurfaceContainer,
                        tonalElevation = 0.dp
                    ) {
                        NavTab.entries.forEach { tab ->
                            val isSelected = selectedTab == tab
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { selectedTab = tab },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title,
                                        tint = if (isSelected) BurntSienna else TextMuted
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.title,
                                        color = if (isSelected) BurntSienna else TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = ManropeFamily,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = BurntSiennaContainer,
                                    selectedIconColor = BurntSienna,
                                    unselectedIconColor = TextMuted,
                                    selectedTextColor = BurntSienna,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        },
        containerColor = WarmLinen,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(WarmLinen)
        ) {
            if (activeDirectChatPeerId != null) {
                DirectChatDetailScreen(
                    peerNodeId = activeDirectChatPeerId!!,
                    viewModel = viewModel,
                    onBack = { activeDirectChatPeerId = null }
                )
            } else {
                when (selectedTab) {
                    NavTab.PUBLIC -> PublicMeshScreen(viewModel = viewModel)
                    NavTab.DIRECT -> DirectChatsScreen(
                        viewModel = viewModel,
                        onOpenChat = { peerId -> activeDirectChatPeerId = peerId }
                    )
                    NavTab.RADAR -> MeshRadarScreen(
                        viewModel = viewModel,
                        onOpenChat = { peerId -> activeDirectChatPeerId = peerId }
                    )
                    NavTab.INSPECTOR -> PacketInspectorScreen(viewModel = viewModel)
                    NavTab.SETTINGS -> IdentitySettingsScreen(viewModel = viewModel)
                }
            }
        }
    }
}

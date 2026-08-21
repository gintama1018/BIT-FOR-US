package com.meshwhisper.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.meshwhisper.app.ui.components.FloatingBottomNavigationPill
import com.meshwhisper.app.ui.screens.DirectChatDetailScreen
import com.meshwhisper.app.ui.screens.DirectChatsScreen
import com.meshwhisper.app.ui.screens.IdentitySettingsScreen
import com.meshwhisper.app.ui.screens.MeshRadarScreen
import com.meshwhisper.app.ui.screens.PacketInspectorScreen
import com.meshwhisper.app.ui.screens.PublicMeshScreen
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

enum class NavTab(val title: String, val label: String, val icon: ImageVector) {
    PUBLIC("Public", "Mesh", Icons.Default.CellTower),
    DIRECT("Direct", "Direct", Icons.Default.Lock),
    RADAR("Radar", "Radar", Icons.Default.NetworkCheck),
    INSPECTOR("Logs", "Logs", Icons.Default.DataObject),
    SETTINGS("Identity", "Identity", Icons.Default.Settings)
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
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Transparent)
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    FloatingBottomNavigationPill(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
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

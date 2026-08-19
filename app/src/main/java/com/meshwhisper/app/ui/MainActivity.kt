package com.meshwhisper.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.meshwhisper.app.ui.components.PermissionHandler
import com.meshwhisper.app.ui.theme.DarkBackground
import com.meshwhisper.app.ui.theme.MeshWhisperTheme
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MeshViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MeshWhisperTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    PermissionHandler(
                        onPermissionsGranted = {
                            viewModel.startService()
                        }
                    ) {
                        MainScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

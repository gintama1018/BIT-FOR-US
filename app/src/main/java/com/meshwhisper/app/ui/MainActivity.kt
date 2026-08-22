package com.meshwhisper.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.meshwhisper.app.security.BiometricAuthManager
import com.meshwhisper.app.ui.components.PermissionHandler
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.EBGaramondFamily
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.MeshWhisperTheme
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

class MainActivity : FragmentActivity() {

    private val viewModel: MeshViewModel by viewModels()
    private val isPermissionsGrantedState = mutableStateOf(false)

    fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
            list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        list.add(Manifest.permission.RECORD_AUDIO)
        return list.toTypedArray()
    }

    fun checkHasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scan && adv && conn
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun requestAppPermissions() {
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), PERMISSION_REQUEST_CODE)
    }

    override fun onStart() {
        super.onStart()
        val app = application as com.meshwhisper.app.MeshApplication
        if (checkHasPermissions()) {
            app.bleEngine.setLowLatencyMode(true) // Upshift to high-speed scan in foreground
            app.bleEngine.start(app.cryptoEngine.nodeId)
            if (viewModel.isBackgroundRelayEnabled.value) {
                viewModel.startService()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        val app = application as com.meshwhisper.app.MeshApplication
        if (viewModel.isBackgroundRelayEnabled.value) {
            // Downshift to balanced duty-cycle mode in background service
            app.bleEngine.setLowLatencyMode(false)
        } else {
            // User disabled background relay -> fully stop radio when app is minimized/closed
            app.bleEngine.stop()
            app.stopMeshService()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = checkHasPermissions()
        isPermissionsGrantedState.value = granted
        if (granted) {
            val app = application as com.meshwhisper.app.MeshApplication
            app.bleEngine.setLowLatencyMode(true)
            app.bleEngine.start(app.cryptoEngine.nodeId)
            if (viewModel.isBackgroundRelayEnabled.value) {
                viewModel.startService()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = checkHasPermissions()
        isPermissionsGrantedState.value = granted
        if (granted) {
            val app = application as com.meshwhisper.app.MeshApplication
            app.bleEngine.setLowLatencyMode(true)
            app.bleEngine.start(app.cryptoEngine.nodeId)
            if (viewModel.isBackgroundRelayEnabled.value) {
                viewModel.startService()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isPermissionsGrantedState.value = checkHasPermissions()
        if (isPermissionsGrantedState.value) {
            val app = application as com.meshwhisper.app.MeshApplication
            app.bleEngine.setLowLatencyMode(true)
            app.bleEngine.start(app.cryptoEngine.nodeId)
            if (viewModel.isBackgroundRelayEnabled.value) {
                viewModel.startService()
            }
        }

        setContent {
            MeshWhisperTheme {
                val isAppLockEnabled by viewModel.isAppLockEnabled.collectAsState()
                var isUnlocked by remember { mutableStateOf(!isAppLockEnabled) }
                val hasPermissions by remember { isPermissionsGrantedState }

                LaunchedEffect(isAppLockEnabled) {
                    if (isAppLockEnabled && !isUnlocked) {
                        BiometricAuthManager.promptAuthenticate(
                            activity = this@MainActivity,
                            onSuccess = { isUnlocked = true }
                        )
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = WarmLinen
                ) {
                    if (isAppLockEnabled && !isUnlocked) {
                        AppLockScreen(
                            onUnlockClick = {
                                BiometricAuthManager.promptAuthenticate(
                                    activity = this@MainActivity,
                                    onSuccess = { isUnlocked = true }
                                )
                            }
                        )
                    } else {
                        PermissionHandler(
                            hasCorePermissions = hasPermissions,
                            onRequestPermissions = { requestAppPermissions() },
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

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
    }
}

@Composable
fun AppLockScreen(onUnlockClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WarmLinen)
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = BurntSienna,
                modifier = Modifier.size(56.dp)
            )
            Spacer(modifier = Modifier.height(20.dp))
            Text(
                text = "MeshWhisper Locked",
                color = TextPrimary,
                fontSize = 24.sp,
                fontFamily = EBGaramondFamily,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Biometric or Device PIN authentication required",
                color = TextSecondary,
                fontFamily = ManropeFamily,
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(28.dp))
            Button(
                onClick = onUnlockClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = BurntSienna,
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp) // 8px radius per DESIGN.md
            ) {
                Icon(
                    imageVector = Icons.Default.Fingerprint,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = "Unlock App",
                    color = Color.White,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

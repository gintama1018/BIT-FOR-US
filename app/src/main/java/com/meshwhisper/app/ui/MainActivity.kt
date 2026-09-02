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
import android.app.Activity
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.meshwhisper.app.ui.components.PermissionHandler
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.EBGaramondFamily
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.MeshWhisperTheme
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.viewmodel.MeshViewModel

class MainActivity : ComponentActivity(), ActivityCompat.OnRequestPermissionsResultCallback {

    private val viewModel: MeshViewModel by viewModels()
    private val isPermissionsGrantedState = mutableStateOf(false)

    fun getRequiredPermissions(): Array<String> {
        val list = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list.add(Manifest.permission.BLUETOOTH_SCAN)
            list.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            list.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        list.add(Manifest.permission.ACCESS_COARSE_LOCATION)
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
        com.meshwhisper.app.service.MeshForegroundService.isActivityInForeground = true
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
        com.meshwhisper.app.service.MeshForegroundService.isActivityInForeground = false
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
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

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: android.content.Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == "meshwhisper" && uri.host == "node") {
            val idHex = uri.getQueryParameter("id")
            val alias = uri.getQueryParameter("alias") ?: "Unknown Node"
            val pubHex = uri.getQueryParameter("pub")
            if (!idHex.isNullOrBlank() && !pubHex.isNullOrBlank()) {
                val nodeId = try {
                    java.lang.Long.parseUnsignedLong(idHex, 16)
                } catch (e: NumberFormatException) {
                    android.util.Log.w("MainActivity", "handleDeepLink: unparseable nodeId hex '$idHex'")
                    android.widget.Toast.makeText(this, "Invalid contact link", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }

                // Security Confirmation Dialog for Deep Links (Fix P1-3: Prevent Remote Trust Injection)
                android.app.AlertDialog.Builder(this)
                    .setTitle("Trust New Peer?")
                    .setMessage("Received contact link for '$alias' (Node ID: 0x${idHex.takeLast(8)}).\n\nDo you want to verify and add this peer to your trusted contacts?")
                    .setPositiveButton("Trust & Add") { _, _ ->
                        viewModel.registerScannedPeer(nodeId, alias, pubHex)
                        android.widget.Toast.makeText(this, "Added Peer: $alias", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
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

                val biometricLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == Activity.RESULT_OK) {
                        isUnlocked = true
                    }
                }

                LaunchedEffect(isAppLockEnabled) {
                    if (isAppLockEnabled && !isUnlocked) {
                        biometricLauncher.launch(Intent(this@MainActivity, BiometricUnlockActivity::class.java))
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = WarmLinen
                ) {
                    if (isAppLockEnabled && !isUnlocked) {
                        var authError by remember { mutableStateOf<String?>(null) }
                        AppLockScreen(
                            errorMessage = authError,
                            onUnlockClick = {
                                authError = null
                                biometricLauncher.launch(Intent(this@MainActivity, BiometricUnlockActivity::class.java))
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
fun AppLockScreen(
    errorMessage: String? = null,
    onUnlockClick: () -> Unit
) {
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
                text = errorMessage ?: "Biometric or Device PIN authentication required",
                color = if (errorMessage != null) com.meshwhisper.app.ui.theme.WarmRed else TextSecondary,
                fontFamily = ManropeFamily,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                    text = if (errorMessage != null) "Try Again" else "Unlock App",
                    color = Color.White,
                    fontFamily = ManropeFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}

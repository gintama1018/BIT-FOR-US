package com.meshwhisper.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

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
        val uriString = uri.toString().trim()
        if (uri.scheme == "meshwhisper" && uri.host == "node") {
            if (uriString.startsWith("meshwhisper://node/v2")) {
                val qrData = com.meshwhisper.core.identity.NodeQrCodec.decode(uriString)
                if (qrData == null) {
                    android.widget.Toast.makeText(this, "Invalid vNext contact link", android.widget.Toast.LENGTH_SHORT).show()
                    return
                }

                val alias = qrData.alias.ifBlank { "Node-${String.format("%016X", qrData.nodeId64).takeLast(4)}" }

                // Invariant I-8 / Finding S-17: Deep link import MUST ONLY produce IMPORTED state. NEVER VERIFIED.
                android.app.AlertDialog.Builder(this)
                    .setTitle("Import Contact (Unverified)")
                    .setMessage("Received contact link for '$alias' (Node ID: 0x${String.format("%016X", qrData.nodeId64).takeLast(6)}).\n\nDo you want to import this contact? Note: Deep link import does not verify end-to-end encryption integrity. In-person safety number comparison is required to verify.")
                    .setPositiveButton("Import Contact") { _, _ ->
                        lifecycleScope.launch {
                            val res = viewModel.importPeerUri(uriString)
                            if (res.isSuccess) {
                                android.widget.Toast.makeText(this@MainActivity, "Imported '$alias' as unverified contact (IMPORTED)", android.widget.Toast.LENGTH_LONG).show()
                            } else {
                                android.widget.Toast.makeText(this@MainActivity, "Failed to import contact: ${res.exceptionOrNull()?.message}", android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                android.widget.Toast.makeText(this, "Security Warning: Legacy contact link rejected (vNext §2.1)", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen and display incoming call even if device is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

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

                val activeCallInfo by viewModel.activeCallInfo.collectAsState()
                val callState by viewModel.callState.collectAsState()
                val isCallMuted by viewModel.isCallMuted.collectAsState()
                val isCallSpeakerOn by viewModel.isCallSpeakerOn.collectAsState()
                val callDurationSeconds by viewModel.callDurationSeconds.collectAsState()
                val peers by viewModel.peers.collectAsState()

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

                    // Global Call Overlay Dialog: Displays across ALL screens and lock screen for incoming / active calls
                    if (activeCallInfo != null && callState != com.meshwhisper.app.voice.CallState.IDLE) {
                        val callerPeer = peers.find { it.nodeId == activeCallInfo?.peerNodeId }
                        val callerAlias = callerPeer?.alias ?: "Node-${String.format("%016X", activeCallInfo?.peerNodeId ?: 0L).takeLast(4)}"
                        val callerAvatar = callerPeer?.avatarUri

                        com.meshwhisper.app.ui.components.CallOverlayDialog(
                            callInfo = activeCallInfo!!,
                            peerAlias = callerAlias,
                            avatarUri = callerAvatar,
                            durationSeconds = callDurationSeconds,
                            isMuted = isCallMuted,
                            isSpeakerOn = isCallSpeakerOn,
                            onAccept = {
                                com.meshwhisper.app.service.MessageNotifier.clearCallNotification(this@MainActivity)
                                viewModel.acceptVoiceCall()
                            },
                            onDecline = {
                                com.meshwhisper.app.service.MessageNotifier.clearCallNotification(this@MainActivity)
                                viewModel.declineVoiceCall()
                            },
                            onEndCall = {
                                com.meshwhisper.app.service.MessageNotifier.clearCallNotification(this@MainActivity)
                                viewModel.endVoiceCall()
                            },
                            onToggleMute = { viewModel.toggleCallMute() },
                            onToggleSpeaker = { viewModel.toggleCallSpeaker() },
                            onDismiss = {
                                com.meshwhisper.app.service.MessageNotifier.clearCallNotification(this@MainActivity)
                                viewModel.dismissEndedCall()
                            }
                        )
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

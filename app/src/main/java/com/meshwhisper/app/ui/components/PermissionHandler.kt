package com.meshwhisper.app.ui.components

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothDisabled
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meshwhisper.app.ui.theme.BurntSienna
import com.meshwhisper.app.ui.theme.EBGaramondFamily
import com.meshwhisper.app.ui.theme.ManropeFamily
import com.meshwhisper.app.ui.theme.TextMuted
import com.meshwhisper.app.ui.theme.TextPrimary
import com.meshwhisper.app.ui.theme.TextSecondary
import com.meshwhisper.app.ui.theme.WarmAmber
import com.meshwhisper.app.ui.theme.WarmCardBorder
import com.meshwhisper.app.ui.theme.WarmLinen
import com.meshwhisper.app.ui.theme.WarmSurface

@Composable
fun PermissionHandler(
    onPermissionsGranted: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val bluetoothManager = remember {
        try {
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        } catch (e: Exception) {
            null
        }
    }
    val bluetoothAdapter = remember {
        try {
            bluetoothManager?.adapter
        } catch (e: Exception) {
            null
        }
    }

    val requiredPermissions = remember {
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
        list.toTypedArray()
    }

    fun checkCorePermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val adv = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
            val conn = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scan && adv && conn
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    var allPermissionsGranted by remember {
        mutableStateOf(checkCorePermissions())
    }

    var isBluetoothEnabled by remember {
        mutableStateOf(
            try {
                bluetoothAdapter?.isEnabled == true
            } catch (e: Exception) {
                true // Default to true if unable to check
            }
        )
    }

    var isBypassed by remember { mutableStateOf(false) }

    // Re-check permissions and Bluetooth state on every App Resume (e.g. returning from Settings)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allPermissionsGranted = checkCorePermissions()
                isBluetoothEnabled = try {
                    bluetoothAdapter?.isEnabled == true
                } catch (e: Exception) {
                    true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Broadcast receiver for Bluetooth ON/OFF toggles
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    isBluetoothEnabled = (state == BluetoothAdapter.STATE_ON)
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w("PermissionHandler", "Failed to register bluetooth receiver: ${e.message}")
        }

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignored
            }
        }
    }

    // Permission request launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        allPermissionsGranted = checkCorePermissions()
        isBluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            true
        }
    }

    // Bluetooth enable dialog launcher
    val enableBtLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isBluetoothEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            true
        }
    }

    LaunchedEffect(allPermissionsGranted, isBluetoothEnabled, isBypassed) {
        if ((allPermissionsGranted && isBluetoothEnabled) || isBypassed) {
            try {
                onPermissionsGranted()
            } catch (e: Exception) {
                Log.e("PermissionHandler", "Error during onPermissionsGranted: ${e.message}", e)
            }
        }
    }

    when {
        // 1. Both Permissions and Bluetooth radio are active, or user bypassed -> Show Main App
        (allPermissionsGranted && isBluetoothEnabled) || isBypassed -> {
            content()
        }

        // 2. Permissions granted, but Bluetooth radio is OFF -> Prompt to Enable Bluetooth
        allPermissionsGranted && !isBluetoothEnabled -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmLinen)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = "Bluetooth Disabled",
                            tint = WarmAmber,
                            modifier = Modifier.size(52.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Bluetooth is Turned Off",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontFamily = EBGaramondFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "MeshWhisper operates strictly peer-to-peer over Bluetooth Low Energy. Please turn on Bluetooth on this device to connect to the mesh.",
                            color = TextSecondary,
                            fontFamily = ManropeFamily,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                try {
                                    @Suppress("DEPRECATION")
                                    enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                                } catch (e: Exception) {
                                    // Direct to system Bluetooth settings if request intent fails
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                    } catch (ex: Exception) {
                                        Log.e("PermissionHandler", "Failed to open bluetooth settings: ${ex.message}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BurntSienna,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "Turn On Bluetooth",
                                color = Color.White,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                                } catch (e: Exception) {
                                    Log.e("PermissionHandler", "Failed to open settings: ${e.message}")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = BurntSienna,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Open Bluetooth Settings",
                                color = BurntSienna,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        TextButton(
                            onClick = { isBypassed = true }
                        ) {
                            Text(
                                text = "Continue to App Anyway",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = ManropeFamily
                            )
                        }
                    }
                }
            }
        }

        // 3. Permissions not yet granted -> Prompt for Permissions
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(WarmLinen)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = WarmSurface),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(0.8.dp, WarmCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = "Bluetooth",
                            tint = BurntSienna,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Bluetooth Mesh Permissions",
                            color = TextPrimary,
                            fontSize = 20.sp,
                            fontFamily = EBGaramondFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "MeshWhisper requires Bluetooth and Nearby Device permissions to discover and route peer-to-peer messages without internet or servers.",
                            color = TextSecondary,
                            fontFamily = ManropeFamily,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                        Spacer(modifier = Modifier.height(24.dp))
                        Button(
                            onClick = {
                                try {
                                    permissionLauncher.launch(requiredPermissions)
                                } catch (e: Exception) {
                                    Log.e("PermissionHandler", "Failed to launch permission request: ${e.message}")
                                    try {
                                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                            data = Uri.fromParts("package", context.packageName, null)
                                        }
                                        context.startActivity(intent)
                                    } catch (ex: Exception) {
                                        Log.e("PermissionHandler", "Failed to open app settings: ${ex.message}")
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = BurntSienna,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Grant Permissions",
                                color = Color.White,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Log.e("PermissionHandler", "Failed to open settings: ${e.message}")
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = BurntSienna,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Open App Settings",
                                color = BurntSienna,
                                fontFamily = ManropeFamily,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        TextButton(
                            onClick = { isBypassed = true }
                        ) {
                            Text(
                                text = "Skip & Continue to App",
                                color = TextMuted,
                                fontSize = 12.sp,
                                fontFamily = ManropeFamily
                            )
                        }
                    }
                }
            }
        }
    }
}

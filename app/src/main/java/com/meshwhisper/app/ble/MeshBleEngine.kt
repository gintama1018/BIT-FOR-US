package com.meshwhisper.app.ble

import com.meshwhisper.core.transport.LinkAuthProof
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.meshwhisper.app.telemetry.GattRole
import com.meshwhisper.app.telemetry.RssiSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MeshBleEngine(private val context: Context) {

    private val tag = "MeshBleEngine"
    private val framer = BleFrameFramer()
    private val exceptionHandler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
        Log.e(tag, "Uncaught coroutine exception in MeshBleEngine: ${throwable.message}", throwable)
    }
    private val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO + exceptionHandler)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    // Guard against duplicate start/stop cycles
    private var isEngineRunning = false

    // Connected centrals on our GATT server (Peripheral role)
    private val connectedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val centralMtus = ConcurrentHashMap<String, Int>()

    // Rate limiting for inbound GATT writes (200 writes/sec headroom for 50Hz real-time voice + bursts)
    private val rateLimiter = GattWriteRateLimiter(maxWritesPerSecond = 200)

    private fun isWriteRateAllowed(address: String): Boolean {
        return rateLimiter.isWriteRateAllowed(address)
    }

    // Connected peripheral GATT clients (Central role)
    data class ClientConnection(
        val gatt: BluetoothGatt,
        var writeChar: BluetoothGattCharacteristic? = null,
        var notifyChar: BluetoothGattCharacteristic? = null,
        var mtu: Int = BleConstants.DEFAULT_MTU,
        var isReady: Boolean = false,
        var rssi: Int = 0,
        val connectedAtMs: Long = System.currentTimeMillis()
    )

    private val activeGattClients = ConcurrentHashMap<String, ClientConnection>()

    // State flows for UI & Service
    private val _isBluetoothEnabled = MutableStateFlow(bluetoothAdapter?.isEnabled == true)
    val isBluetoothEnabled: StateFlow<Boolean> = _isBluetoothEnabled.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _connectedPeersCount = MutableStateFlow(0)
    val connectedPeersCount: StateFlow<Int> = _connectedPeersCount.asStateFlow()

    private val authenticatedLinks = ConcurrentHashMap<String, LinkAuthProof>()
    private val _connectedNodeIds = MutableStateFlow<Set<Long>>(emptySet())
    val connectedNodeIds: StateFlow<Set<Long>> = _connectedNodeIds.asStateFlow()

    private val _supportsPeripheral = MutableStateFlow(true)
    val supportsPeripheral: StateFlow<Boolean> = _supportsPeripheral.asStateFlow()

    // Scanned device RSSI cache (essential for Server/Peripheral role peers where Android cannot poll RSSI)
    private val scannedDeviceRssi = ConcurrentHashMap<String, Int>()
    private val powerManager: PowerManager? = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private var rssiPollerJob: Job? = null

    // Event listeners
    var onPacketReceivedListener: ((packetBytes: ByteArray, ingressAddress: String) -> Unit)? = null
    var onPeerDiscoveredListener: ((address: String, rssi: Int) -> Unit)? = null
    var onPeerReadyListener: ((address: String) -> Unit)? = null
    var onPeerDisconnectedListener: ((address: String) -> Unit)? = null
    var onRssiUpdatedListener: ((nodeId: Long, rssi: Int) -> Unit)? = null
    var onBackgroundScanMatchListener: ((address: String, rssi: Int) -> Unit)? = null
    var onChunkSessionDroppedListener: ((deviceAddress: String, sessionId: Short, receivedChunks: Int, totalChunks: Int, reason: String) -> Unit)? = null

    init {
        framer.onChunkSessionDroppedListener = { addr, sessId, recv, total, reason ->
            onChunkSessionDroppedListener?.invoke(addr, sessId, recv, total, reason)
        }
    }

    fun getPeerRole(address: String): GattRole {
        return when {
            activeGattClients.containsKey(address) -> GattRole.CENTRAL_CLIENT
            connectedCentrals.containsKey(address) -> GattRole.PERIPHERAL_SERVER
            else -> GattRole.UNKNOWN
        }
    }

    fun getPeerMtu(address: String): Int {
        return activeGattClients[address]?.mtu
            ?: centralMtus[address]
            ?: BleConstants.DEFAULT_MTU
    }

    fun getPeerRssi(address: String): Pair<Int, RssiSource> {
        val client = activeGattClients[address]
        if (client != null && client.rssi != 0) {
            return Pair(client.rssi, RssiSource.LIVE_POLL)
        }
        val scanned = scannedDeviceRssi[address]
        if (scanned != null) {
            return Pair(scanned, RssiSource.AT_CONNECT_SCAN)
        }
        if (client != null) {
            return Pair(client.rssi, RssiSource.LIVE_POLL)
        }
        return Pair(0, RssiSource.UNKNOWN)
    }

    fun getAllConnectedAddresses(): Set<String> {
        val addresses = HashSet<String>()
        addresses.addAll(activeGattClients.keys)
        addresses.addAll(connectedCentrals.keys)
        return addresses
    }

    @SuppressLint("MissingPermission")
    private fun startRssiPoller() {
        rssiPollerJob?.cancel()
        rssiPollerJob = scope.launch {
            while (isActive) {
                delay(2500L)
                if (!isEngineRunning) break
                for ((addr, conn) in activeGattClients) {
                    try {
                        conn.gatt.readRemoteRssi()
                    } catch (e: Exception) {
                        Log.w(tag, "readRemoteRssi failed for $addr: ${e.message}")
                    }
                }
            }
        }
    }

    private fun stopRssiPoller() {
        rssiPollerJob?.cancel()
        rssiPollerJob = null
    }

    fun onLinkAuthenticated(proof: LinkAuthProof) {
        authenticatedLinks[proof.linkHandle] = proof
        updateConnectedNodeIds()
    }

    fun onLinkDisconnected(linkHandle: String) {
        authenticatedLinks.remove(linkHandle)
        updateConnectedNodeIds()
    }

    fun getDirectNodeId(address: String): Long? = authenticatedLinks[address]?.peerNodeId64

    fun isDirectlyConnected(nodeId: Long): Boolean = _connectedNodeIds.value.contains(nodeId)

    private fun updateConnectedNodeIds() {
        val activeAddresses = HashSet<String>()
        activeAddresses.addAll(connectedCentrals.keys)
        activeAddresses.addAll(activeGattClients.keys)

        authenticatedLinks.keys.retainAll(activeAddresses)
        _connectedNodeIds.value = authenticatedLinks.values.map { it.peerNodeId64 }.toSet()
    }

    private var myNodeId: Long = 0L

    // Receiver to automatically restart / stop engine when user toggles Bluetooth
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(tag, "Bluetooth radio turned ON -> restarting mesh engine")
                        _isBluetoothEnabled.value = true
                        val targetId = if (myNodeId != 0L) myNodeId else ((context.applicationContext as? com.meshwhisper.app.MeshApplication)?.cryptoEngine?.nodeId ?: 0L)
                        if (targetId != 0L) {
                            start(targetId)
                        }
                    }
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        Log.d(tag, "Bluetooth radio turned OFF -> stopping mesh engine")
                        _isBluetoothEnabled.value = false
                        stop()
                    }
                }
            }
        }
    }

    init {
        // Check advertiser capability directly from BluetoothAdapter (more reliable than packageManager feature flag)
        val canAdvertise = try {
            (bluetoothAdapter?.isMultipleAdvertisementSupported == true) ||
                    (bluetoothAdapter?.bluetoothLeAdvertiser != null)
        } catch (e: Exception) {
            false
        }
        _supportsPeripheral.value = canAdvertise

        // Register receiver for Bluetooth state changes (Android 14+ safe with RECEIVER_NOT_EXPORTED)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        try {
            ContextCompat.registerReceiver(
                context,
                bluetoothStateReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            Log.w(tag, "Failed to register bluetoothStateReceiver: ${e.message}")
        }
    }

    fun hasPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    fun start(nodeId: Long) {
        this.myNodeId = nodeId

        if (!hasPermissions()) {
            Log.w(tag, "Cannot start Mesh BLE Engine: Nearby Devices / Bluetooth permissions not granted yet")
            return
        }

        val isEnabled = try {
            bluetoothAdapter?.isEnabled == true
        } catch (e: Exception) {
            false
        }
        _isBluetoothEnabled.value = isEnabled

        if (!isEnabled) {
            Log.w(tag, "Bluetooth is disabled or unavailable. Waiting for Bluetooth radio to be enabled...")
            return
        }

        if (isEngineRunning) {
            Log.d(tag, "Mesh engine is already active, ensuring scanner and advertiser are running")
            if (!_isScanning.value) {
                Log.i(tag, "Scanner is inactive while engine is running, restarting scanner...")
                startScanning()
            }
            if (!_isAdvertising.value && _supportsPeripheral.value) {
                Log.i(tag, "Advertiser is inactive while engine is running, restarting advertiser...")
                startAdvertising()
            }
            startWatchdog()
            return
        }

        isEngineRunning = true
        Log.i(tag, "Starting Mesh BLE Engine for Node ID: $nodeId")

        try {
            startGattServer()
        } catch (e: Exception) {
            Log.e(tag, "Failed starting GATT server", e)
        }

        try {
            startAdvertising()
        } catch (e: Exception) {
            Log.e(tag, "Failed starting advertising", e)
        }

        try {
            startScanning()
        } catch (e: Exception) {
            Log.e(tag, "Failed starting scanning", e)
        }

        startRssiPoller()
        startWatchdog()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isEngineRunning && !isAdvertising.value && !isScanning.value) {
            return
        }

        Log.i(tag, "Stopping Mesh BLE Engine...")
        isEngineRunning = false
        stopWatchdog()
        stopRssiPoller()
        stopAdvertising()
        stopScanning()
        closeAllGattClients()
        stopGattServer()
        _connectedPeersCount.value = 0
    }

    private var watchdogJob: Job? = null

    @SuppressLint("MissingPermission")
    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive) {
                delay(8000L)
                if (!isEngineRunning) break

                val adapter = bluetoothAdapter
                val isBtOn = try {
                    adapter?.isEnabled == true
                } catch (_: Exception) { false }
                _isBluetoothEnabled.value = isBtOn

                if (isBtOn && hasPermissions()) {
                    if (!_isScanning.value) {
                        Log.w(tag, "Watchdog detected scanner inactive. Restarting scan...")
                        try {
                            scanner?.stopScan(scanCallback)
                        } catch (_: Exception) {}
                        startScanning()
                    }

                    if (!_isAdvertising.value && _supportsPeripheral.value) {
                        Log.w(tag, "Watchdog detected advertiser inactive. Restarting advertising...")
                        try {
                            advertiser?.stopAdvertising(advertiseCallback)
                        } catch (_: Exception) {}
                        startAdvertising()
                    }

                    // Clean up any stale unready GATT client connections that never completed handshake
                    val now = System.currentTimeMillis()
                    for ((addr, conn) in activeGattClients) {
                        if (!conn.isReady && (now - conn.connectedAtMs > 12_000L)) {
                            Log.w(tag, "Watchdog cleaning up stalled GATT connection to $addr")
                            try {
                                conn.gatt.close()
                            } catch (_: Exception) {}
                            activeGattClients.remove(addr)
                            updatePeerCount()
                        }
                    }
                }
            }
        }
    }

    private fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    @SuppressLint("MissingPermission")
    fun restartDiscovery() {
        Log.i(tag, "Manual / automatic restart of BLE discovery requested")
        stopScanning()
        stopAdvertising()
        for ((addr, conn) in activeGattClients) {
            if (!conn.isReady) {
                try {
                    conn.gatt.close()
                } catch (_: Exception) {}
                activeGattClients.remove(addr)
            }
        }
        startGattServer()
        startAdvertising()
        startScanning()
    }

    fun destroy() {
        stop()
        try {
            context.unregisterReceiver(bluetoothStateReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    // =========================================================================
    // 1. PERIPHERAL ROLE (GATT Server + Advertiser)
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null || bluetoothManager == null) return

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            if (gattServer == null) {
                Log.e(tag, "Unable to create GATT server")
                return
            }

            val service = BluetoothGattService(
                BleConstants.MESH_SERVICE_UUID,
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // Write Characteristic (Centrals write packets here)
            val writeChar = BluetoothGattCharacteristic(
                BleConstants.WRITE_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )

            // Notify Characteristic (Peripheral notifies Centrals)
            val notifyChar = BluetoothGattCharacteristic(
                BleConstants.NOTIFY_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_READ,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            val cccd = BluetoothGattDescriptor(
                BleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
            )
            notifyChar.addDescriptor(cccd)

            service.addCharacteristic(writeChar)
            service.addCharacteristic(notifyChar)

            gattServer?.addService(service)
            Log.d(tag, "GATT Server started successfully with Mesh Service")
        } catch (e: Exception) {
            Log.e(tag, "Error starting GATT server", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        try {
            gattServer?.close()
            gattServer = null
            connectedCentrals.clear()
            centralMtus.clear()
            rateLimiter.clear()
        } catch (e: Exception) {
            Log.e(tag, "Error closing GATT server", e)
        }
    }

    private var isLowLatencyMode: Boolean = true

    /**
     * Dynamically switches BLE radio power mode between Foreground (LOW_LATENCY)
     * and Background (BALANCED duty-cycled). Active GATT connections remain untouched.
     */
    @SuppressLint("MissingPermission")
    fun setLowLatencyMode(isForeground: Boolean) {
        if (isLowLatencyMode == isForeground) return
        isLowLatencyMode = isForeground
        Log.i(tag, "Switching BLE radio duty-cycle: isForeground=$isForeground (Mode: ${if (isForeground) "LOW_LATENCY (High Responsiveness)" else "LOW_POWER (Battery Preserving Duty-Cycle)"})")

        if (isScanning.value && isEngineRunning) {
            stopScanning()
            startScanning()
        }
        if (isAdvertising.value && isEngineRunning) {
            stopAdvertising()
            startAdvertising()
        }

        val priority = if (isForeground) {
            BluetoothGatt.CONNECTION_PRIORITY_HIGH
        } else {
            BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        }
        for (conn in activeGattClients.values) {
            try {
                conn.gatt.requestConnectionPriority(priority)
            } catch (_: Exception) {}
        }
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        advertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(tag, "BluetoothLeAdvertiser not available on this device")
            _supportsPeripheral.value = false
            return
        }

        val advMode = if (isLowLatencyMode) {
            AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY
        } else {
            AdvertiseSettings.ADVERTISE_MODE_LOW_POWER // Low-power battery-preserving interval in background
        }
        val txPower = AdvertiseSettings.ADVERTISE_TX_POWER_HIGH // Always maximum RF power (+4 to +8 dBm) for maximum mesh range

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(advMode)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(txPower)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        val scanResponse = if (myNodeId != 0L) {
            val nodeIdBytes = java.nio.ByteBuffer.allocate(8).putLong(myNodeId).array()
            AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceData(ParcelUuid(BleConstants.MESH_SERVICE_UUID), nodeIdBytes)
                .build()
        } else {
            null
        }

        try {
            advertiser?.stopAdvertising(advertiseCallback)
        } catch (_: Exception) {}

        try {
            if (scanResponse != null) {
                advertiser?.startAdvertising(settings, data, scanResponse, advertiseCallback)
            } else {
                advertiser?.startAdvertising(settings, data, advertiseCallback)
            }
            _isAdvertising.value = true
            Log.d(tag, "Initiated BLE Advertising for Mesh Service UUID (Node ID: $myNodeId)")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start BLE advertising", e)
            _supportsPeripheral.value = false
            _isAdvertising.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            _isAdvertising.value = false
        } catch (e: Exception) {
            Log.e(tag, "Error stopping BLE advertising", e)
        }
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(tag, "BLE Advertising active and broadcasting Mesh Service")
            _isAdvertising.value = true
            _supportsPeripheral.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "BLE Advertising failed with error code: $errorCode")
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) {
                _isAdvertising.value = true
                _supportsPeripheral.value = true
                return
            }
            _isAdvertising.value = false
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE) {
                Log.w(tag, "Advertising data too large; retrying with basic service UUID only...")
                try {
                    val basicData = AdvertiseData.Builder()
                        .setIncludeDeviceName(false)
                        .setIncludeTxPowerLevel(false)
                        .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
                        .build()
                    val fallbackSettings = AdvertiseSettings.Builder()
                        .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(true)
                        .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                        .build()
                    advertiser?.startAdvertising(fallbackSettings, basicData, this)
                } catch (e: Exception) {
                    Log.e(tag, "Fallback advertising also failed", e)
                }
            } else if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                _supportsPeripheral.value = false
            } else {
                // Transient error: Retry advertising after 1.5s backoff
                if (isEngineRunning && hasPermissions()) {
                    scope.launch {
                        delay(1500L)
                        if (isEngineRunning && !_isAdvertising.value && _supportsPeripheral.value) {
                            Log.i(tag, "Auto-recovering BLE advertiser after error $errorCode...")
                            try {
                                advertiser?.stopAdvertising(advertiseCallback)
                            } catch (_: Exception) {}
                            startAdvertising()
                        }
                    }
                }
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (connectedCentrals.size >= MAX_CONCURRENT_GATT_CONNECTIONS) {
                    Log.w(tag, "GATT server connection limit ($MAX_CONCURRENT_GATT_CONNECTIONS) reached. Rejecting central: $address")
                    try {
                        gattServer?.cancelConnection(device)
                    } catch (_: Exception) {}
                    return
                }
                Log.d(tag, "Central connected to our GATT server: $address")
                connectedCentrals[address] = device
                updatePeerCount()

                // Trigger announcement from Peripheral to Central once incoming link is established
                scope.launch {
                    delay(800L)
                    onPeerReadyListener?.invoke(address)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Central disconnected from GATT server: $address")
                connectedCentrals.remove(address)
                centralMtus.remove(address)
                rateLimiter.remove(address)
                framer.clearDevice(address)
                onLinkDisconnected(address)
                updatePeerCount()
                onPeerDisconnectedListener?.invoke(address)
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            val address = device?.address ?: return
            Log.d(tag, "Central negotiated MTU on GATT server: $address -> $mtu")
            centralMtus[address] = mtu
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (responseNeeded && device != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            val address = device?.address ?: return
            val rawBytes = value ?: return

            // Flood / DoS write rate limiter check
            if (!isWriteRateAllowed(address)) {
                Log.w(tag, "Dropping rate-limited write request from spamming device: $address")
                return
            }

            val fullPacket = framer.receiveFrame(address, rawBytes)
            if (fullPacket != null) {
                scope.launch {
                    onPacketReceivedListener?.invoke(fullPacket, address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            descriptor?.value = value
            if (responseNeeded && device != null) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }

            val address = device?.address
            if (descriptor?.uuid == BleConstants.CCCD_UUID && address != null) {
                Log.d(tag, "Central subscribed to notifications on server: $address -> trigger announce")
                scope.launch {
                    delay(300L)
                    onPeerReadyListener?.invoke(address)
                }
            }
        }
    }

    // =========================================================================
    // 2. CENTRAL ROLE (Scanner + GATT Client)
    // =========================================================================

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        scanner = bluetoothAdapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(tag, "BluetoothLeScanner not available")
            return
        }

        val scanFilters = if (isLowLatencyMode) {
            // In foreground, an empty filter list ensures that OEM hardware filters (e.g. Samsung/MediaTek/Xiaomi
            // 128-bit UUID truncation bugs) do not silently discard mesh advertisements.
            // Strict software filtering is performed in onScanResult with hasMeshService.
            emptyList()
        } else {
            // Android 8.1+ enforces non-empty filter list for background scanning.
            listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
                    .build()
            )
        }

        val scanMode = if (isLowLatencyMode) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER // Hardware duty-cycled (~0.5-1s active per 5s window) to save battery
        }

        val settings = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setReportDelay(0)
            .build()

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {}

        try {
            scanner?.startScan(scanFilters, settings, scanCallback)
            _isScanning.value = true
            Log.d(tag, "BLE Scan started for Mesh Service (lowLatency=$isLowLatencyMode, filters=${scanFilters.size})")
        } catch (e: Exception) {
            Log.e(tag, "Error starting BLE scan", e)
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScanning() {
        try {
            scanner?.stopScan(scanCallback)
            _isScanning.value = false
        } catch (e: Exception) {
            Log.e(tag, "Error stopping BLE scan", e)
        }
    }

    private val scanCallback: ScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = device.address
            val rssi = result.rssi
            scannedDeviceRssi[address] = rssi

            val serviceUuids = result.scanRecord?.serviceUuids
            val serviceData = result.scanRecord?.serviceData
            val hasMeshService = (serviceUuids?.any { it.uuid == BleConstants.MESH_SERVICE_UUID } == true) ||
                (serviceData?.keys?.any { it.uuid == BleConstants.MESH_SERVICE_UUID } == true)

            if (hasMeshService) {
                val isInteractive = powerManager?.isInteractive ?: true
                if (!isInteractive) {
                    onBackgroundScanMatchListener?.invoke(address, rssi)
                }
                // Pre-auth advertisement scan match must NOT establish direct node trust/identity binding before P4 LINK_AUTH.
                onPeerDiscoveredListener?.invoke(address, rssi)

                // Auto-connect: Establish outbound GATT Client connection if not already connected as client
                if (!activeGattClients.containsKey(address)) {
                    val currentConnections = activeGattClients.size
                    if (currentConnections < MAX_CONCURRENT_GATT_CONNECTIONS) {
                        connectToPeer(device, rssi)
                    } else {
                        Log.d(tag, "GATT client connection limit ($MAX_CONCURRENT_GATT_CONNECTIONS) reached. Peer $address will communicate via mesh flood relay.")
                    }
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed with error: $errorCode")
            if (errorCode == ScanCallback.SCAN_FAILED_ALREADY_STARTED) {
                _isScanning.value = true
                return
            }
            _isScanning.value = false
            if (isEngineRunning && hasPermissions()) {
                scope.launch {
                    delay(1500L)
                    if (isEngineRunning && !_isScanning.value) {
                        Log.i(tag, "Auto-recovering BLE scan after error $errorCode...")
                        try {
                            scanner?.stopScan(scanCallback)
                        } catch (_: Exception) {}
                        startScanning()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice, rssi: Int) {
        val address = device.address
        Log.d(tag, "Initiating GATT connection to peer: $address (RSSI: $rssi)")

        val gatt = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    context,
                    false,
                    createGattCallback(address),
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_2M_MASK or BluetoothDevice.PHY_LE_CODED_MASK
                )
            } else {
                device.connectGatt(
                    context,
                    false,
                    createGattCallback(address),
                    BluetoothDevice.TRANSPORT_LE
                )
            }
        } catch (e: Exception) {
            Log.w(tag, "connectGatt with PHY flags failed, falling back to basic LE transport: ${e.message}")
            device.connectGatt(
                context,
                false,
                createGattCallback(address),
                BluetoothDevice.TRANSPORT_LE
            )
        }

        if (gatt == null) {
            Log.e(tag, "connectGatt returned null for peer: $address")
            return
        }

        val conn = ClientConnection(
            gatt = gatt,
            rssi = rssi
        )
        activeGattClients[address] = conn

        // Connection timeout: If connection cannot complete handshake in 12 seconds, clean up so next scan retries
        scope.launch {
            delay(12000L)
            if (activeGattClients[address] === conn && !conn.isReady) {
                Log.w(tag, "GATT connection timeout to $address; aborting and releasing client")
                try {
                    conn.gatt.close()
                } catch (_: Exception) {}
                activeGattClients.remove(address)
                updatePeerCount()
            }
        }
    }

    private fun createGattCallback(deviceAddress: String) = object : BluetoothGattCallback() {
        private val hasDiscoveredServices = java.util.concurrent.atomic.AtomicBoolean(false)

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return

            // Handle connection failure / error status (such as status 133, status 8, timeout)
            if (status != BluetoothGatt.GATT_SUCCESS || newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "GATT client connection dropped or failed for $deviceAddress (status=$status, newState=$newState)")
                try {
                    gatt.close()
                } catch (_: Exception) {}
                activeGattClients.remove(deviceAddress)
                rateLimiter.remove(deviceAddress)
                framer.clearDevice(deviceAddress)
                onLinkDisconnected(deviceAddress)
                updatePeerCount()
                onPeerDisconnectedListener?.invoke(deviceAddress)
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Connected as Central to $deviceAddress, requesting HIGH priority and MTU 512...")
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.requestMtu(BleConstants.REQUESTED_MTU)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    try {
                        gatt.setPreferredPhy(
                            BluetoothDevice.PHY_LE_2M_MASK or BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_LE_2M_MASK or BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
                            BluetoothDevice.PHY_OPTION_NO_PREFERRED
                        )
                    } catch (_: Exception) {}
                }
                updatePeerCount()

                // Fallback: If MTU negotiation hangs or does not trigger onMtuChanged on certain OEM devices,
                // trigger service discovery automatically after 600ms.
                scope.launch {
                    delay(600L)
                    if (hasDiscoveredServices.compareAndSet(false, true)) {
                        Log.d(tag, "MTU negotiation timeout fallback for $deviceAddress; discovering services...")
                        gatt.discoverServices()
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                Log.d(tag, "MTU negotiated with $deviceAddress: $mtu")
                activeGattClients[deviceAddress]?.mtu = mtu
            }
            if (hasDiscoveredServices.compareAndSet(false, true)) {
                gatt?.discoverServices()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

            val service = gatt.getService(BleConstants.MESH_SERVICE_UUID)
            if (service == null) {
                Log.w(tag, "Mesh Service not found on $deviceAddress")
                return
            }

            val writeChar = service.getCharacteristic(BleConstants.WRITE_CHAR_UUID)
            val notifyChar = service.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID)

            val conn = activeGattClients[deviceAddress]
            if (conn != null) {
                conn.writeChar = writeChar
                conn.notifyChar = notifyChar

                if (notifyChar != null) {
                    // Enable notifications on Notify characteristic as return path first before opening outgoing writes
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        scope.launch {
                            var attempts = 0
                            var submitted = false
                            while (attempts < 5 && !submitted) {
                                attempts++
                                gatt.setCharacteristicNotification(notifyChar, true)
                                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val res = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                                    Log.d(tag, "writeDescriptor CCCD to $deviceAddress attempt $attempts returned: $res")
                                    res == BluetoothGatt.GATT_SUCCESS
                                } else {
                                    @Suppress("DEPRECATION")
                                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    @Suppress("DEPRECATION")
                                    val okOld = gatt.writeDescriptor(descriptor)
                                    Log.d(tag, "writeDescriptor CCCD to $deviceAddress attempt $attempts returned: $okOld")
                                    okOld
                                }
                                if (ok) {
                                    submitted = true
                                    break
                                }
                                delay(100L)
                            }
                        }
                    }

                    // Safety fallback: if OEM BLE driver drops onDescriptorWrite callback, mark ready after timeout
                    scope.launch {
                        delay(1200L)
                        if (!conn.isReady && writeChar != null) {
                            Log.w(tag, "Fallback: onDescriptorWrite timed out for $deviceAddress; opening write channel")
                            conn.isReady = true
                            onPeerReadyListener?.invoke(deviceAddress)
                        }
                    }
                } else if (writeChar != null) {
                    // Peripheral does not expose notifyChar; ready immediately for writes
                    conn.isReady = true
                    Log.i(tag, "GATT Client write channel ready immediately for $deviceAddress (no notifyChar)")
                    scope.launch {
                        delay(150L)
                        onPeerReadyListener?.invoke(deviceAddress)
                    }
                }
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt?,
            descriptor: BluetoothGattDescriptor?,
            status: Int
        ) {
            Log.i(tag, "onDescriptorWrite callback for $deviceAddress (status=$status)")
            val conn = activeGattClients[deviceAddress]
            if (conn != null && !conn.isReady) {
                conn.isReady = true
                scope.launch {
                    delay(150L)
                    onPeerReadyListener?.invoke(deviceAddress)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            // Client-role ingestion rate limiter check (P5)
            if (!isWriteRateAllowed(deviceAddress)) {
                Log.w(tag, "Dropping rate-limited characteristic notification from peripheral: $deviceAddress")
                return
            }

            val fullPacket = framer.receiveFrame(deviceAddress, value)
            if (fullPacket != null) {
                scope.launch {
                    onPacketReceivedListener?.invoke(fullPacket, deviceAddress)
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt?,
            characteristic: BluetoothGattCharacteristic?
        ) {
            if (characteristic == null) return
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return

            // Client-role ingestion rate limiter check (P5)
            if (!isWriteRateAllowed(deviceAddress)) {
                Log.w(tag, "Dropping rate-limited characteristic notification from peripheral: $deviceAddress")
                return
            }

            val fullPacket = framer.receiveFrame(deviceAddress, value)
            if (fullPacket != null) {
                scope.launch {
                    onPacketReceivedListener?.invoke(fullPacket, deviceAddress)
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt?, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                val conn = activeGattClients[deviceAddress]
                if (conn != null) {
                    conn.rssi = rssi
                }
                val nodeId = authenticatedLinks[deviceAddress]?.peerNodeId64
                if (nodeId != null) {
                    onRssiUpdatedListener?.invoke(nodeId, rssi)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeAllGattClients() {
        for ((_, conn) in activeGattClients) {
            try {
                conn.gatt.disconnect()
                conn.gatt.close()
            } catch (e: Exception) {
                Log.e(tag, "Error closing client GATT", e)
            }
        }
        activeGattClients.clear()
    }

    private fun updatePeerCount() {
        val totalUnique = (activeGattClients.keys + connectedCentrals.keys).size
        _connectedPeersCount.value = totalUnique
        updateConnectedNodeIds()
    }

    // =========================================================================
    // 3. PACKET TRANSMISSION (Broadcast & Direct)
    // =========================================================================

    /**
     * Sends packet bytes to all directly-connected peers (both Centrals and Peripherals),
     * optionally excluding the ingress peer address to prevent immediate echo back.
     * Uses negotiated MTU per central/peripheral connection for optimal throughput and low latency.
     */
    @SuppressLint("MissingPermission")
    suspend fun broadcastPacket(packetBytes: ByteArray, excludeAddress: String? = null) {
        // Transmit to Centrals connected to our GATT server
        val server = gattServer
        val service = server?.getService(BleConstants.MESH_SERVICE_UUID)
        val notifyChar = service?.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID)

        if (server != null && notifyChar != null) {
            for ((addr, device) in connectedCentrals) {
                if (addr == excludeAddress) continue
                val centralMtu = centralMtus[addr] ?: BleConstants.DEFAULT_MTU
                val frames = framer.fragment(packetBytes, centralMtu)
                for (frame in frames) {
                    var attempts = 0
                    var sentOk = false
                    while (attempts < 3 && !sentOk) {
                        attempts++
                        try {
                            @Suppress("DEPRECATION")
                            notifyChar.value = frame
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val status = server.notifyCharacteristicChanged(device, notifyChar, false, frame)
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    sentOk = true
                                } else {
                                    Log.w(tag, "notifyCharacteristicChanged rejected for central $addr (status=$status, attempt=$attempts)")
                                    delay(25L)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                val ok = server.notifyCharacteristicChanged(device, notifyChar, false)
                                if (ok) {
                                    sentOk = true
                                } else {
                                    Log.w(tag, "notifyCharacteristicChanged returned false for central $addr (attempt=$attempts)")
                                    delay(25L)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to notify central $addr", e)
                            break
                        }
                    }
                    delay(35L) // Pace raw BLE frame writes to prevent write-queue saturation
                }
            }
        }

        // Transmit to Peripherals where we are connected as GATT Client
        for ((addr, conn) in activeGattClients) {
            if (addr == excludeAddress || !conn.isReady) continue
            val writeChar = conn.writeChar ?: continue
            val frames = framer.fragment(packetBytes, conn.mtu)

            for (frame in frames) {
                var attempts = 0
                var sentOk = false
                while (attempts < 3 && !sentOk) {
                    attempts++
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val status = conn.gatt.writeCharacteristic(
                                writeChar,
                                frame,
                                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            )
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                sentOk = true
                            } else {
                                Log.w(tag, "writeCharacteristic rejected for peripheral $addr (status=$status, attempt=$attempts)")
                                delay(25L)
                            }
                        } else {
                            @Suppress("DEPRECATION")
                            writeChar.value = frame
                            @Suppress("DEPRECATION")
                            writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                            @Suppress("DEPRECATION")
                            val ok = conn.gatt.writeCharacteristic(writeChar)
                            if (ok) {
                                sentOk = true
                            } else {
                                Log.w(tag, "writeCharacteristic returned false for peripheral $addr (attempt=$attempts)")
                                delay(25L)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to write to client $addr", e)
                        break
                    }
                }
                delay(35L) // Pace raw BLE frame writes to prevent write-queue saturation
            }
        }
    }

    /**
     * Sends packet bytes directly to a specific target peer over BLE GATT (Central or Peripheral role),
     * completely bypassing broadcast to other connected peers.
     * Only transmits if the target peer has an authenticated link (P4).
     * Returns true if peer was found directly connected and transmission completed.
     */
    @SuppressLint("MissingPermission")
    suspend fun attemptSend(peerNodeId: Long, packetBytes: ByteArray): Boolean {
        var sent = false

        // 1. Prioritize direct GATT Client write (most reliable across all Android OEMs)
        for ((addr, conn) in activeGattClients) {
            val isTarget = authenticatedLinks[addr]?.peerNodeId64 == peerNodeId
            if (isTarget && conn.isReady) {
                val writeChar = conn.writeChar ?: continue
                val frames = framer.fragment(packetBytes, conn.mtu)

                var allChunksSent = true
                for (frame in frames) {
                    var attempts = 0
                    var sentOk = false
                    while (attempts < 3 && !sentOk) {
                        attempts++
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val status = conn.gatt.writeCharacteristic(
                                    writeChar,
                                    frame,
                                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                )
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    sentOk = true
                                } else {
                                    delay(25L)
                                }
                            } else {
                                @Suppress("DEPRECATION")
                                writeChar.value = frame
                                @Suppress("DEPRECATION")
                                writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                                @Suppress("DEPRECATION")
                                val ok = conn.gatt.writeCharacteristic(writeChar)
                                if (ok) {
                                    sentOk = true
                                } else {
                                    delay(25L)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to write to direct peripheral $addr for node $peerNodeId", e)
                            break
                        }
                    }
                    if (!sentOk) allChunksSent = false
                    if (frames.size > 1) {
                        delay(15L) // 15 ms write pacing
                    }
                }
                if (allChunksSent) {
                    sent = true
                    break
                }
            }
        }

        if (sent) return true

        // 2. Fallback: Check if peer is a connected Central on our GATT server
        val server = gattServer
        val service = server?.getService(BleConstants.MESH_SERVICE_UUID)
        val notifyChar = service?.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID)

        if (server != null && notifyChar != null) {
            for ((addr, device) in connectedCentrals) {
                val isTarget = authenticatedLinks[addr]?.peerNodeId64 == peerNodeId
                if (isTarget) {
                    val centralMtu = centralMtus[addr] ?: BleConstants.DEFAULT_MTU
                    val frames = framer.fragment(packetBytes, centralMtu)
                    var allChunksSent = true
                    for (frame in frames) {
                        var attempts = 0
                        var sentOk = false
                        while (attempts < 5 && !sentOk) {
                            attempts++
                            try {
                                @Suppress("DEPRECATION")
                                notifyChar.value = frame
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val status = server.notifyCharacteristicChanged(device, notifyChar, false, frame)
                                    if (status == BluetoothGatt.GATT_SUCCESS) {
                                        sentOk = true
                                    } else {
                                        Log.w(tag, "notifyCharacteristicChanged direct central $addr returned status $status (attempt $attempts)")
                                        delay(30L)
                                    }
                                } else {
                                    @Suppress("DEPRECATION")
                                    val ok = server.notifyCharacteristicChanged(device, notifyChar, false)
                                    if (ok) {
                                        sentOk = true
                                    } else {
                                        Log.w(tag, "notifyCharacteristicChanged direct central $addr returned false (attempt $attempts)")
                                        delay(30L)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(tag, "Failed to notify direct central $addr for node $peerNodeId", e)
                                break
                            }
                        }
                        if (!sentOk) allChunksSent = false
                        if (frames.size > 1) {
                            delay(15L) // 15 ms write pacing
                        }
                    }
                    if (allChunksSent) {
                        sent = true
                        break
                    }
                }
            }
        }

        return sent
    }

    suspend fun sendDirectPacket(peerNodeId: Long, packetBytes: ByteArray): Boolean = attemptSend(peerNodeId, packetBytes)

    /**
     * Sends packet bytes directly to a specific connected device address (Central or Peripheral),
     * used for transport-level LINK_AUTH HELLO/CONFIRM exchange before authentication.
     */
    @SuppressLint("MissingPermission")
    suspend fun sendDirectToDevice(deviceAddress: String, packetBytes: ByteArray): Boolean {
        val client = activeGattClients[deviceAddress]
        if (client != null && client.isReady) {
            val writeChar = client.writeChar ?: return false
            val frames = framer.fragment(packetBytes, client.mtu)
            for (frame in frames) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    client.gatt.writeCharacteristic(writeChar, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    @Suppress("DEPRECATION")
                    writeChar.value = frame
                    @Suppress("DEPRECATION")
                    writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    @Suppress("DEPRECATION")
                    client.gatt.writeCharacteristic(writeChar)
                }
                if (frames.size > 1) delay(15L)
            }
            return true
        }

        val centralDevice = connectedCentrals[deviceAddress]
        val server = gattServer
        if (centralDevice != null && server != null) {
            val service = server.getService(BleConstants.MESH_SERVICE_UUID)
            val notifyChar = service?.getCharacteristic(BleConstants.NOTIFY_CHAR_UUID) ?: return false
            val centralMtu = centralMtus[deviceAddress] ?: BleConstants.DEFAULT_MTU
            val frames = framer.fragment(packetBytes, centralMtu)
            for (frame in frames) {
                @Suppress("DEPRECATION")
                notifyChar.value = frame
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(centralDevice, notifyChar, false, frame)
                } else {
                    @Suppress("DEPRECATION")
                    server.notifyCharacteristicChanged(centralDevice, notifyChar, false)
                }
                if (frames.size > 1) delay(15L)
            }
            return true
        }

        return false
    }

    @SuppressLint("MissingPermission")
    fun disconnectDevice(address: String) {
        val clientConn = activeGattClients.remove(address)
        if (clientConn != null) {
            try {
                clientConn.gatt.disconnect()
                clientConn.gatt.close()
            } catch (_: Exception) {}
        }
        val serverDev = connectedCentrals.remove(address)
        if (serverDev != null) {
            try {
                gattServer?.cancelConnection(serverDev)
            } catch (_: Exception) {}
        }
        centralMtus.remove(address)
        rateLimiter.remove(address)
        framer.clearDevice(address)
        onLinkDisconnected(address)
        updatePeerCount()
        onPeerDisconnectedListener?.invoke(address)
    }

    companion object {
        const val MAX_CONCURRENT_GATT_CONNECTIONS = 5
    }
}

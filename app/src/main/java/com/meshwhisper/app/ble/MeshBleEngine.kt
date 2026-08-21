package com.meshwhisper.app.ble

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
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class MeshBleEngine(private val context: Context) {

    private val tag = "MeshBleEngine"
    private val framer = BleFrameFramer()
    private val scope = CoroutineScope(Dispatchers.IO)

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null
    private var gattServer: BluetoothGattServer? = null

    // Guard against duplicate start/stop cycles
    private var isEngineRunning = false

    // Connected centrals on our GATT server (Peripheral role)
    private val connectedCentrals = ConcurrentHashMap<String, BluetoothDevice>()
    private val centralMtus = ConcurrentHashMap<String, Int>()

    // Rate limiting for inbound GATT writes (Max 50 writes per second per remote device address)
    private val writeRateTracker = ConcurrentHashMap<String, MutableList<Long>>()
    private val maxWritesPerSecond = 50

    private fun isWriteRateAllowed(address: String): Boolean {
        val now = System.currentTimeMillis()
        val timestamps = writeRateTracker.getOrPut(address) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > 1000L }
            if (timestamps.size >= maxWritesPerSecond) {
                return false
            }
            timestamps.add(now)
            return true
        }
    }

    // Connected peripheral GATT clients (Central role)
    data class ClientConnection(
        val gatt: BluetoothGatt,
        var writeChar: BluetoothGattCharacteristic? = null,
        var notifyChar: BluetoothGattCharacteristic? = null,
        var mtu: Int = BleConstants.DEFAULT_MTU,
        var isReady: Boolean = false,
        var rssi: Int = 0
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

    private val directAddressToNodeId = ConcurrentHashMap<String, Long>()
    private val _connectedNodeIds = MutableStateFlow<Set<Long>>(emptySet())
    val connectedNodeIds: StateFlow<Set<Long>> = _connectedNodeIds.asStateFlow()

    private val _supportsPeripheral = MutableStateFlow(true)
    val supportsPeripheral: StateFlow<Boolean> = _supportsPeripheral.asStateFlow()

    // Event listeners
    var onPacketReceivedListener: ((packetBytes: ByteArray, ingressAddress: String) -> Unit)? = null
    var onPeerDiscoveredListener: ((address: String, rssi: Int) -> Unit)? = null
    var onPeerReadyListener: ((address: String) -> Unit)? = null
    var onPeerDisconnectedListener: ((address: String) -> Unit)? = null

    fun registerDirectNode(address: String, nodeId: Long) {
        directAddressToNodeId[address] = nodeId
        updateConnectedNodeIds()
    }

    private fun updateConnectedNodeIds() {
        val activeAddresses = HashSet<String>()
        activeAddresses.addAll(connectedCentrals.keys)
        activeAddresses.addAll(activeGattClients.keys)

        directAddressToNodeId.keys.retainAll(activeAddresses)
        _connectedNodeIds.value = directAddressToNodeId.values.toSet()
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
                        if (myNodeId != 0L && !isEngineRunning) {
                            start(myNodeId)
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
        val canAdvertise = (bluetoothAdapter?.isMultipleAdvertisementSupported == true) ||
                (bluetoothAdapter?.bluetoothLeAdvertiser != null)
        _supportsPeripheral.value = canAdvertise

        // Register receiver for Bluetooth state changes (Android 14+ safe with RECEIVER_NOT_EXPORTED)
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        ContextCompat.registerReceiver(
            context,
            bluetoothStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    @SuppressLint("MissingPermission")
    fun start(nodeId: Long) {
        this.myNodeId = nodeId
        val isEnabled = bluetoothAdapter?.isEnabled == true
        _isBluetoothEnabled.value = isEnabled

        if (!isEnabled) {
            Log.w(tag, "Bluetooth is disabled or unavailable. Waiting for Bluetooth radio to be enabled...")
            return
        }

        if (isEngineRunning) {
            Log.d(tag, "Mesh engine is already active, skipping redundant start()")
            return
        }

        isEngineRunning = true
        Log.i(tag, "Starting Mesh BLE Engine for Node ID: $nodeId")

        startGattServer()
        startAdvertising()
        startScanning()
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isEngineRunning && !isAdvertising.value && !isScanning.value) {
            return
        }

        Log.i(tag, "Stopping Mesh BLE Engine...")
        isEngineRunning = false
        stopAdvertising()
        stopScanning()
        closeAllGattClients()
        stopGattServer()
        _connectedPeersCount.value = 0
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
            writeRateTracker.clear()
        } catch (e: Exception) {
            Log.e(tag, "Error closing GATT server", e)
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

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
            .build()

        try {
            advertiser?.startAdvertising(settings, data, advertiseCallback)
            Log.d(tag, "Initiated BLE Advertising for Mesh Service UUID")
        } catch (e: Exception) {
            Log.e(tag, "Failed to start BLE advertising", e)
            _supportsPeripheral.value = false
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

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(tag, "BLE Advertising active and broadcasting Mesh Service")
            _isAdvertising.value = true
            _supportsPeripheral.value = true
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(tag, "BLE Advertising failed with error code: $errorCode")
            _isAdvertising.value = false
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED) {
                _supportsPeripheral.value = false
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            val address = device?.address ?: return
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Central connected to our GATT server: $address")
                connectedCentrals[address] = device
                updatePeerCount()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Central disconnected from GATT server: $address")
                connectedCentrals.remove(address)
                centralMtus.remove(address)
                writeRateTracker.remove(address)
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

        val scanFilters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(BleConstants.MESH_SERVICE_UUID))
                .build(),
            ScanFilter.Builder().build() // Catch all for devices whose OEM drops UUID filters
        )

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        try {
            scanner?.startScan(scanFilters, settings, scanCallback)
            _isScanning.value = true
            Log.d(tag, "BLE Scan started for Mesh Service")
        } catch (e: Exception) {
            Log.e(tag, "Error starting BLE scan", e)
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

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val address = device.address
            val rssi = result.rssi

            val serviceUuids = result.scanRecord?.serviceUuids
            val hasMeshService = serviceUuids?.any { it.uuid == BleConstants.MESH_SERVICE_UUID } == true

            if (hasMeshService || result.scanRecord?.serviceData?.containsKey(ParcelUuid(BleConstants.MESH_SERVICE_UUID)) == true) {
                onPeerDiscoveredListener?.invoke(address, rssi)

                // Auto-connect if not already connected or connecting
                if (!activeGattClients.containsKey(address) && !connectedCentrals.containsKey(address)) {
                    connectToPeer(device, rssi)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "Scan failed with error: $errorCode")
            _isScanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToPeer(device: BluetoothDevice, rssi: Int) {
        val address = device.address
        Log.d(tag, "Initiating GATT connection to peer: $address (RSSI: $rssi)")

        val gatt = device.connectGatt(
            context,
            false,
            createGattCallback(address),
            BluetoothDevice.TRANSPORT_LE
        )

        activeGattClients[address] = ClientConnection(
            gatt = gatt,
            rssi = rssi
        )
    }

    private fun createGattCallback(deviceAddress: String) = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (gatt == null) return

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Connected as Central to $deviceAddress, requesting MTU 512...")
                gatt.requestMtu(BleConstants.REQUESTED_MTU)
                updatePeerCount()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d(tag, "Disconnected from $deviceAddress")
                gatt.close()
                activeGattClients.remove(deviceAddress)
                updatePeerCount()
                onPeerDisconnectedListener?.invoke(deviceAddress)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && gatt != null) {
                Log.d(tag, "MTU negotiated with $deviceAddress: $mtu")
                activeGattClients[deviceAddress]?.mtu = mtu
            }
            gatt?.discoverServices()
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
                conn.isReady = true

                // Enable notifications on Notify characteristic
                if (notifyChar != null) {
                    gatt.setCharacteristicNotification(notifyChar, true)
                    val descriptor = notifyChar.getDescriptor(BleConstants.CCCD_UUID)
                    if (descriptor != null) {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }

                Log.i(tag, "GATT Client ready for mesh traffic to $deviceAddress -> trigger announce")
                scope.launch {
                    delay(300L)
                    onPeerReadyListener?.invoke(deviceAddress)
                }
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
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
            val fullPacket = framer.receiveFrame(deviceAddress, value)
            if (fullPacket != null) {
                scope.launch {
                    onPacketReceivedListener?.invoke(fullPacket, deviceAddress)
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
    fun broadcastPacket(packetBytes: ByteArray, excludeAddress: String? = null) {
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
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(device, notifyChar, false, frame)
                        } else {
                            @Suppress("DEPRECATION")
                            notifyChar.value = frame
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(device, notifyChar, false)
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to notify central $addr", e)
                    }
                }
            }
        }

        // Transmit to Peripherals where we are connected as GATT Client
        for ((addr, conn) in activeGattClients) {
            if (addr == excludeAddress || !conn.isReady) continue
            val writeChar = conn.writeChar ?: continue
            val frames = framer.fragment(packetBytes, conn.mtu)

            for (frame in frames) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        conn.gatt.writeCharacteristic(
                            writeChar,
                            frame,
                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        writeChar.value = frame
                        @Suppress("DEPRECATION")
                        writeChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        @Suppress("DEPRECATION")
                        conn.gatt.writeCharacteristic(writeChar)
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Failed to write to client $addr", e)
                }
            }
        }
    }
}

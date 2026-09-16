package com.meshwhisper.app.telemetry

/**
 * GATT connection role of the local device relative to the connected peer.
 */
enum class GattRole {
    CENTRAL_CLIENT,      // Local device is Central/GATT Client (connected to peer's GATT Server)
    PERIPHERAL_SERVER,   // Local device is Peripheral/GATT Server (peer connected as Central to us)
    UNKNOWN
}

/**
 * Source of the reported RSSI reading.
 *
 * readRemoteRssi() is only supported by Android when acting as Central/Client.
 * Peripheral/Server connections reflect the RSSI captured at scan time, or UNKNOWN.
 */
enum class RssiSource {
    LIVE_POLL,           // Live active polling via BluetoothGatt.readRemoteRssi()
    AT_CONNECT_SCAN,     // Captured during BLE advertisement scan prior to connection
    UNKNOWN
}

/**
 * Live empirical telemetry snapshot per connected peer.
 */
data class PeerLiveTelemetry(
    val nodeId: Long,
    val alias: String,
    val address: String,
    val isDirect: Boolean,
    val role: GattRole,
    val rssi: Int,
    val rssiSource: RssiSource,
    val mtu: Int,
    val lastHopCount: Int,
    val lastPacketType: String,
    val lastPacketTimestamp: Long,
    val packetsReceived: Int,
    val packetsSent: Int
)

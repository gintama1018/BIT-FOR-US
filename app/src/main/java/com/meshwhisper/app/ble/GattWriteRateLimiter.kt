package com.meshwhisper.app.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces per-device rate limiting on inbound GATT frames across both Peripheral (Server)
 * and Central (Client) roles to prevent buffer saturation and flood/DoS attacks.
 */
class GattWriteRateLimiter(private val maxWritesPerSecond: Int = BleConstants.MAX_BLE_WRITES_PER_SECOND) {

    private val writeRateTracker = ConcurrentHashMap<String, MutableList<Long>>()

    fun isWriteRateAllowed(linkHandle: String, now: Long = System.currentTimeMillis()): Boolean {
        val timestamps = writeRateTracker.computeIfAbsent(linkHandle) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > 1000L }
            if (timestamps.size >= maxWritesPerSecond) {
                return false
            }
            timestamps.add(now)
            return true
        }
    }

    fun remove(linkHandle: String) {
        writeRateTracker.remove(linkHandle)
    }

    fun clear() {
        writeRateTracker.clear()
    }

    fun getTrackedLinksCount(): Int = writeRateTracker.size
}

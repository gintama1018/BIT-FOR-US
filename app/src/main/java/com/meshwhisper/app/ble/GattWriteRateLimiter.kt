package com.meshwhisper.app.ble

import java.util.concurrent.ConcurrentHashMap

/**
 * Enforces per-device rate limiting on inbound GATT frames across both Peripheral (Server)
 * and Central (Client) roles to prevent buffer saturation and flood/DoS attacks.
 */
class GattWriteRateLimiter(private val maxWritesPerSecond: Int = 200) {

    private val writeRateTracker = ConcurrentHashMap<String, MutableList<Long>>()

    fun isWriteRateAllowed(address: String, now: Long = System.currentTimeMillis()): Boolean {
        val timestamps = writeRateTracker.computeIfAbsent(address) { mutableListOf() }
        synchronized(timestamps) {
            timestamps.removeAll { now - it > 1000L }
            if (timestamps.size >= maxWritesPerSecond) {
                return false
            }
            timestamps.add(now)
            return true
        }
    }

    fun remove(address: String) {
        writeRateTracker.remove(address)
    }

    fun clear() {
        writeRateTracker.clear()
    }
}

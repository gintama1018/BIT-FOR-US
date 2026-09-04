package com.meshwhisper.app.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GattWriteRateLimiterTest {

    @Test
    fun testRateLimitEnforcedAfterFiftyWrites() {
        val limiter = GattWriteRateLimiter(maxWritesPerSecond = 50)
        val now = 1000000L

        // First 50 writes in the same second must succeed
        for (i in 1..50) {
            assertThat(limiter.isWriteRateAllowed("DEVICE_A", now)).isTrue()
        }

        // 51st write in the same second must be throttled / rejected
        assertThat(limiter.isWriteRateAllowed("DEVICE_A", now)).isFalse()

        // Distinct device address is not blocked by DEVICE_A's limit
        assertThat(limiter.isWriteRateAllowed("DEVICE_B", now)).isTrue()

        // After sliding window passes (>1000ms), new writes are permitted
        assertThat(limiter.isWriteRateAllowed("DEVICE_A", now + 1001L)).isTrue()
    }
}

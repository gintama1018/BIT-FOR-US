package com.meshwhisper.app.ble

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BleFrameFramerTest {

    @Test
    fun testSingleFrameTransmission() {
        val framer = BleFrameFramer()
        val originalPayload = "Short message".toByteArray(Charsets.UTF_8)

        // With 512 MTU, fits in 1 frame
        val frames = framer.fragment(originalPayload, 512)
        assertThat(frames.size).isEqualTo(1)

        val reassembled = framer.receiveFrame("AA:BB:CC:DD:EE:FF", frames[0])
        assertThat(reassembled).isNotNull()
        assertThat(reassembled).isEqualTo(originalPayload)
    }

    @Test
    fun testChunkedFramesTransmission() {
        val framer = BleFrameFramer()
        val originalPayload = ByteArray(200) { (it % 128).toByte() }

        // With small MTU (23 bytes), fragments into multiple chunks
        val frames = framer.fragment(originalPayload, 23)
        assertThat(frames.size).isGreaterThan(1)

        var reassembled: ByteArray? = null
        for (frame in frames) {
            val res = framer.receiveFrame("AA:BB:CC:DD:EE:FF", frame)
            if (res != null) {
                reassembled = res
            }
        }

        assertThat(reassembled).isNotNull()
        assertThat(reassembled).isEqualTo(originalPayload)
    }
}

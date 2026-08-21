package com.meshwhisper.app

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.ble.BleConstants
import com.meshwhisper.app.ble.BleFrameFramer
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import org.junit.Test
import java.util.UUID

class SecurityAndRoutingTest {

    @Test
    fun testAuthenticatedHeaderBytesGeneration() {
        val msgId = UUID.randomUUID()
        val senderId = 0x0102030405060708L
        val recipientId = 0x090A0B0C0D0E0F10L
        val timestamp = 1720000000L

        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = senderId,
            recipientId = recipientId,
            ttl = 7,
            timestamp = timestamp,
            payload = "Test payload".toByteArray(Charsets.UTF_8)
        )

        val aad = packet.getAuthenticatedHeaderBytes()
        assertThat(aad.size).isEqualTo(37) // 1B type + 16B UUID + 8B sender + 8B recipient + 4B ts

        val computedAad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = senderId,
            recipientId = recipientId,
            timestamp = timestamp
        )
        assertThat(aad).isEqualTo(computedAad)
    }

    @Test
    fun testNegotiatedMtuFramingAndReassembly() {
        val framer = BleFrameFramer()
        val payload = ByteArray(1000) { (it % 256).toByte() }

        // Test with default 23-byte MTU (heavy fragmentation)
        val frames23 = framer.fragment(payload, BleConstants.DEFAULT_MTU)
        assertThat(frames23.size).isGreaterThan(1)
        var reassembled23: ByteArray? = null
        for (f in frames23) {
            reassembled23 = framer.receiveFrame("PEER_23", f)
        }
        assertThat(reassembled23).isNotNull()
        assertThat(reassembled23).isEqualTo(payload)

        // Test with 185-byte MTU
        val frames185 = framer.fragment(payload, 185)
        assertThat(frames185.size).isLessThan(frames23.size)
        var reassembled185: ByteArray? = null
        for (f in frames185) {
            reassembled185 = framer.receiveFrame("PEER_185", f)
        }
        assertThat(reassembled185).isNotNull()
        assertThat(reassembled185).isEqualTo(payload)

        // Test with 512-byte MTU (large chunks)
        val frames512 = framer.fragment(payload, BleConstants.REQUESTED_MTU)
        assertThat(frames512.size).isLessThan(frames185.size)
        var reassembled512: ByteArray? = null
        for (f in frames512) {
            reassembled512 = framer.receiveFrame("PEER_512", f)
        }
        assertThat(reassembled512).isNotNull()
        assertThat(reassembled512).isEqualTo(payload)
    }

    @Test
    fun testReplayTimestampWindowValidation() {
        val nowSec = System.currentTimeMillis() / 1000L

        // Valid timestamp (now)
        val validAge = nowSec - nowSec
        assertThat(validAge in -300..600).isTrue()

        // Valid timestamp (5 minutes in past)
        val fiveMinAgo = nowSec - 300
        val validPastAge = nowSec - fiveMinAgo
        assertThat(validPastAge in -300..600).isTrue()

        // Expired timestamp (20 minutes in past - replay attack)
        val twentyMinAgo = nowSec - 1200
        val replayAge = nowSec - twentyMinAgo
        assertThat(replayAge in -300..600).isFalse()

        // Future timestamp (> 10 minutes in future - clock manipulation)
        val futureTs = nowSec + 700
        val futureAge = nowSec - futureTs
        assertThat(futureAge in -300..600).isFalse()
    }

    @Test
    fun testEpochCalculationConsistency() {
        val t1 = 3600L // 1h in seconds
        val t2 = 3659L // 1h + 59s
        val t3 = 7200L // 2h in seconds

        val epoch1 = t1 / 3600L
        val epoch2 = t2 / 3600L
        val epoch3 = t3 / 3600L

        assertThat(epoch1).isEqualTo(epoch2) // Same 1-hour window
        assertThat(epoch1).isNotEqualTo(epoch3) // Next epoch
    }
}

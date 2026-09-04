package com.meshwhisper.core

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.protocol.MeshTrafficController
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.protocol.TrafficPriority
import com.meshwhisper.core.protocol.trafficPriority
import org.junit.Test

class TrafficControllerTest {

    @Test
    fun testTrafficPriorityMapping() {
        // Critical Tier 0
        assertThat(PacketType.SOS_MESSAGE.trafficPriority).isEqualTo(TrafficPriority.CRITICAL_EMERGENCY)

        // High Interactive Tier 1
        assertThat(PacketType.ACK.trafficPriority).isEqualTo(TrafficPriority.HIGH_INTERACTIVE)
        assertThat(PacketType.KEY_EXCHANGE.trafficPriority).isEqualTo(TrafficPriority.HIGH_INTERACTIVE)
        assertThat(PacketType.TYPING_INDICATOR.trafficPriority).isEqualTo(TrafficPriority.HIGH_INTERACTIVE)

        // Standard Messaging Tier 2
        assertThat(PacketType.DIRECT_MESSAGE.trafficPriority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)
        assertThat(PacketType.BROADCAST_MESSAGE.trafficPriority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)
        assertThat(PacketType.PEER_ANNOUNCE.trafficPriority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)

        // Bulk Data Tier 3
        assertThat(PacketType.MEDIA_INIT.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(PacketType.MEDIA_CHUNK.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(PacketType.AVATAR_REQUEST.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(PacketType.MEDIA_NACK.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(PacketType.MEDIA_ACK.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(PacketType.MEDIA_ABORT.trafficPriority).isEqualTo(TrafficPriority.BULK_TRANSFER)
    }

    @Test
    fun testPriorityQueueStrictOrdering() {
        val controller = MeshTrafficController(maxQueuePerTier = 10)

        // Enqueue in inverse order (Bulk -> Standard -> High -> Critical)
        val bulkBytes = byteArrayOf(0x01)
        val standardBytes = byteArrayOf(0x02)
        val highBytes = byteArrayOf(0x03)
        val criticalBytes = byteArrayOf(0x04)

        controller.enqueue(bulkBytes, PacketType.MEDIA_CHUNK)
        controller.enqueue(standardBytes, PacketType.DIRECT_MESSAGE)
        controller.enqueue(highBytes, PacketType.ACK)
        controller.enqueue(criticalBytes, PacketType.SOS_MESSAGE)

        assertThat(controller.totalQueued()).isEqualTo(4)

        // Dequeue order MUST strictly respect priority regardless of enqueue time
        val first = controller.pollNext()
        assertThat(first).isNotNull()
        assertThat(first!!.priority).isEqualTo(TrafficPriority.CRITICAL_EMERGENCY)
        assertThat(first.rawBytes).isEqualTo(criticalBytes)

        val second = controller.pollNext()
        assertThat(second).isNotNull()
        assertThat(second!!.priority).isEqualTo(TrafficPriority.HIGH_INTERACTIVE)
        assertThat(second.rawBytes).isEqualTo(highBytes)

        val third = controller.pollNext()
        assertThat(third).isNotNull()
        assertThat(third!!.priority).isEqualTo(TrafficPriority.STANDARD_MESSAGING)
        assertThat(third.rawBytes).isEqualTo(standardBytes)

        val fourth = controller.pollNext()
        assertThat(fourth).isNotNull()
        assertThat(fourth!!.priority).isEqualTo(TrafficPriority.BULK_TRANSFER)
        assertThat(fourth.rawBytes).isEqualTo(bulkBytes)

        assertThat(controller.pollNext()).isNull()
    }

    @Test
    fun testBoundedQueueCapacityEviction() {
        // Max capacity of 2 items per tier
        val controller = MeshTrafficController(maxQueuePerTier = 2)

        controller.enqueue(byteArrayOf(0x01), PacketType.MEDIA_CHUNK)
        controller.enqueue(byteArrayOf(0x02), PacketType.MEDIA_CHUNK)
        controller.enqueue(byteArrayOf(0x03), PacketType.MEDIA_CHUNK) // Should evict oldest (0x01)

        assertThat(controller.getCountForTier(TrafficPriority.BULK_TRANSFER)).isEqualTo(2)

        val first = controller.pollNext()
        assertThat(first?.rawBytes).isEqualTo(byteArrayOf(0x02))

        val second = controller.pollNext()
        assertThat(second?.rawBytes).isEqualTo(byteArrayOf(0x03))

        assertThat(controller.pollNext()).isNull()
    }

    @Test
    fun testExpiredPacketDiscard() {
        // Expire after 10 milliseconds
        val controller = MeshTrafficController(maxQueuePerTier = 5, maxPacketLifetimeMs = 10L)

        controller.enqueue(byteArrayOf(0xAA.toByte()), PacketType.DIRECT_MESSAGE)
        Thread.sleep(25L) // Exceed lifetime

        val polled = controller.pollNext()
        assertThat(polled).isNull() // Discarded as stale without emitting
    }
}

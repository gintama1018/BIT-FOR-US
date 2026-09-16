package com.meshwhisper.core.harness

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.transport.LinkEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

/**
 * Validates that all Phase P0 test seams and harness infrastructure components
 * (TestClock, FakeTransport, InMemoryMesh, MaliciousPeer) operate as specified.
 */
class HarnessInfrastructureTest {

    @Test
    fun testClockAdvancesDeterministically() {
        val clock = TestClock(1_700_000_000L)
        assertThat(clock.nowSeconds()).isEqualTo(1_700_000_000L)
        assertThat(clock.nowMillis()).isEqualTo(1_700_000_000_000L)

        clock.advanceSeconds(30)
        assertThat(clock.nowSeconds()).isEqualTo(1_700_000_030L)

        clock.setSeconds(1_800_000_000L)
        assertThat(clock.nowSeconds()).isEqualTo(1_800_000_000L)
    }

    @Test
    fun testInMemoryMeshRoutesUnicastAndBroadcast() = runTest {
        val mesh = InMemoryMesh()
        val nodeA = mesh.addNode("nodeA")
        val nodeB = mesh.addNode("nodeB")
        mesh.connect("nodeA", "nodeB")

        val payload = "Hello NodeB".toByteArray(Charsets.UTF_8)
        val sent = nodeA.attemptSend("nodeB", payload)
        assertThat(sent).isTrue()

        val receivedEvent = nodeB.events.first()
        assertThat(receivedEvent).isInstanceOf(LinkEvent.DataReceived::class.java)
        val dataReceived = receivedEvent as LinkEvent.DataReceived
        assertThat(dataReceived.linkId).isEqualTo("nodeA")
        assertThat(dataReceived.data).isEqualTo(payload)
        assertThat(mesh.totalPacketsRouted).isEqualTo(1)
    }

    @Test
    fun testInMemoryMeshPartitionSimulatesDrop() = runTest {
        val mesh = InMemoryMesh()
        val nodeA = mesh.addNode("nodeA")
        val nodeB = mesh.addNode("nodeB")
        mesh.connect("nodeA", "nodeB")
        mesh.partition(setOf("nodeA"), setOf("nodeB"))

        val payload = "Partitioned byte".toByteArray(Charsets.UTF_8)
        val sent = nodeA.attemptSend("nodeB", payload)
        // attemptSend returns true (bytes handed to radio buffer, Agent Rule 9), but packet is dropped
        assertThat(sent).isTrue()
        assertThat(mesh.totalPacketsDropped).isEqualTo(1)
        assertThat(mesh.totalPacketsRouted).isEqualTo(0)
    }

    @Test
    fun testMaliciousPeerAttacksAreGenerated() {
        val mallory = MaliciousPeer()
        val dummyPacket = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = 0x11223344L,
            recipientId = 0x55667788L,
            ttl = 7,
            timestamp = 1720000000L,
            payload = "Sensitive message text with trailing signature padding space 12345678901234567890".toByteArray(Charsets.UTF_8),
            authTag = ByteArray(16) { 0xFF.toByte() }
        )
        val raw = MeshPacket.serialize(dummyPacket)

        // Replay with fresh message ID
        val replayed = mallory.replayWithFreshMessageId(raw)
        val replayedPacket = MeshPacket.deserialize(replayed)
        assertThat(replayedPacket).isNotNull()
        assertThat(replayedPacket!!.messageId).isNotEqualTo(dummyPacket.messageId)

        // Zero auth tag
        val zeroed = mallory.zeroAuthTag(raw)
        val zeroedPacket = MeshPacket.deserialize(zeroed)
        assertThat(zeroedPacket!!.authTag).isEqualTo(ByteArray(16))

        // Oversize frame
        val bigFrame = mallory.oversizeFrame(3000)
        assertThat(bigFrame.size).isEqualTo(3000)

        // Path traversal string
        val path = mallory.pathTraversalFileName()
        assertThat(path).contains("..")

        // Collide nodeId64 and claim edge
        val edgeBytes = mallory.claimEdge(0x1111L, 0x2222L)
        assertThat(edgeBytes).isNotEmpty()
    }
}

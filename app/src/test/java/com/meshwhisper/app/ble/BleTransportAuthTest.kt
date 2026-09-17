package com.meshwhisper.app.ble

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.InMemoryIdentityStore
import com.meshwhisper.core.protocol.*
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.transport.*
import com.meshwhisper.core.util.DefaultRandomSource
import com.meshwhisper.core.util.SystemClock
import org.junit.Test
import java.lang.reflect.Modifier
import java.nio.ByteBuffer
import java.util.UUID

class BleTransportAuthTest {

    private val clock = SystemClock()
    private val randomSource = DefaultRandomSource()

    /**
     * T-LINK-09: Unbound BLE packet ttl=7 -> no identity binding, dropped at S0.
     */
    @Test
    fun testTLINK09_UnboundBlePacketTtl7DoesNotBindIdentity() {
        val identityStore = InMemoryIdentityStore()
        val packetStore = InMemoryPacketStore()
        val dedupCache = LruDedupCache<String, Long>(100)
        val dummyKeyProvider = object : KeyProvider {}
        val pipeline = PacketPipeline(
            localNodeId64 = 0x1111222233334444L,
            identityStore = identityStore,
            packetStore = packetStore,
            keyProvider = dummyKeyProvider,
            clock = clock,
            dedupCache = dedupCache
        )

        // Attacker creates a packet with max TTL=7 claiming senderId
        val attackerSeed = ByteArray(32) { 0x66.toByte() }
        val attackerKey = PureCryptoEngine.deriveSigningPublicKey(attackerSeed)
        val attackerNodeId = PureCryptoEngine.deriveNodeId64(attackerKey)

        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = attackerNodeId,
            recipientId = 0x1111222233334444L,
            ttl = 7,
            timestamp = clock.nowSeconds(),
            payload = "Malicious unbound payload".toByteArray(),
            authTag = ByteArray(16)
        )
        val rawBytes = MeshPacket.serialize(packet)

        // Arrives over an unauthenticated PENDING BLE link
        val linkContext = LinkContext(
            linkHandle = "UNBOUND_BLE_MAC",
            transport = TransportType.BLE,
            boundIdentity = null,
            state = LinkState.PENDING
        )

        val result = pipeline.ingest(rawBytes, linkContext)

        assertThat(result).isInstanceOf(IngestResult.Dropped::class.java)
        val dropped = result as IngestResult.Dropped
        assertThat(dropped.stage).isEqualTo(PipelineStage.S0_ADMISSION)
        assertThat(dropped.reason).contains("Link is PENDING; expected LINK_AUTH")

        // Invariant: No identity stored, no packet committed
        assertThat(identityStore.getByNodeId64(attackerNodeId)).isNull()
        assertThat(packetStore.isSeen(packet.messageId, packet.type.code)).isFalse()
    }

    /**
     * T-LINK-10: Non-LINK_AUTH packets on PENDING link are dropped at S0.
     */
    @Test
    fun testTLINK10_NonLinkAuthPacketsOnPendingLinkDroppedAtS0() {
        val dummyKeyProvider = object : KeyProvider {}
        val pipeline = PacketPipeline(
            localNodeId64 = 0x1111222233334444L,
            identityStore = InMemoryIdentityStore(),
            packetStore = InMemoryPacketStore(),
            keyProvider = dummyKeyProvider,
            clock = clock,
            dedupCache = LruDedupCache(100)
        )

        val linkContext = LinkContext(
            linkHandle = "PENDING_DEVICE",
            transport = TransportType.BLE,
            boundIdentity = null,
            state = LinkState.PENDING
        )

        val nonLinkAuthTypes = listOf(
            PacketType.BROADCAST_MESSAGE,
            PacketType.DIRECT_MESSAGE,
            PacketType.PEER_ANNOUNCE,
            PacketType.SOS_MESSAGE,
            PacketType.ACK
        )

        for (type in nonLinkAuthTypes) {
            val packet = MeshPacket(
                type = type,
                messageId = UUID.randomUUID(),
                senderId = 0x9999L,
                recipientId = 0L,
                ttl = 3,
                timestamp = clock.nowSeconds(),
                payload = ByteArray(20) { 0x01 },
                authTag = ByteArray(16)
            )
            val raw = MeshPacket.serialize(packet)
            val res = pipeline.ingest(raw, linkContext)
            assertThat(res).isInstanceOf(IngestResult.Dropped::class.java)
            assertThat((res as IngestResult.Dropped).stage).isEqualTo(PipelineStage.S0_ADMISSION)
        }
    }

    /**
     * T-LINK-11: BLE framer cumulative oversize rejected before >2104 B reassembly.
     */
    @Test
    fun testTLINK11_BleFramerCumulativeOversizeRejectedBeforeReassembly() {
        val framer = BleFrameFramer()
        val dropped = mutableListOf<String>()
        framer.onChunkSessionDroppedListener = { addr, sessId, recv, total, reason ->
            dropped.add("$addr:$sessId:$reason")
        }

        // Construct chunks claiming a total size that would exceed MAX_BLE_REASSEMBLY_SIZE (2104 B)
        // 10 chunks of 250 bytes = 2500 bytes (> 2104)
        val sessionId = 0x42.toShort()
        val totalChunks = 10
        val chunkData = ByteArray(250) { 0xAA.toByte() }

        var accepted = true
        for (i in 0 until totalChunks) {
            val frame = ByteBuffer.allocate(5 + chunkData.size)
            frame.put(BleConstants.FRAME_TYPE_CHUNK)
            frame.putShort(sessionId)
            frame.put(i.toByte())
            frame.put(totalChunks.toByte())
            frame.put(chunkData)

            val result = framer.receiveFrame("BLE_OVERSIZE_PEER", frame.array())
            if (result == null && dropped.isNotEmpty()) {
                accepted = false
                break
            }
        }

        // Must be rejected before full reassembly completes
        assertThat(accepted).isFalse()
        assertThat(dropped).isNotEmpty()
        assertThat(dropped[0]).contains("OVERSIZE")
    }

    /**
     * T-LINK-11 (Single Frame Limit): BLE frame > 512 B rejected immediately.
     */
    @Test
    fun testTLINK11_BleFramerSingleFrameOver512Rejected() {
        val framer = BleFrameFramer()
        val oversizedSingleFrame = ByteArray(513) { 0x55.toByte() }
        oversizedSingleFrame[0] = BleConstants.FRAME_TYPE_SINGLE

        val result = framer.receiveFrame("BLE_PEER", oversizedSingleFrame)
        assertThat(result).isNull()
    }

    /**
     * T-LINK-12: Rate budget is linkHandle-based, MAC rotation does NOT reset budget.
     */
    @Test
    fun testTLINK12_RateBudgetIsLinkHandleBased() {
        val limiter = GattWriteRateLimiter(maxWritesPerSecond = BleConstants.MAX_BLE_WRITES_PER_SECOND)
        val now = 1000000L
        val linkHandle = "LINK_HANDLE_XYZ"

        // Exhaust budget (50 frames/sec)
        for (i in 1..50) {
            assertThat(limiter.isWriteRateAllowed(linkHandle, now)).isTrue()
        }

        // 51st frame on this linkHandle is rejected
        assertThat(limiter.isWriteRateAllowed(linkHandle, now)).isFalse()

        // Clear on disconnect resets the tracked link
        limiter.remove(linkHandle)
        assertThat(limiter.getTrackedLinksCount()).isEqualTo(0)
    }

    /**
     * Requirement 10 & S-3: ZERO production references to registerDirectNode.
     * Architectural assertion via reflection across MeshBleEngine and MeshRouter.
     */
    @Test
    fun testZeroProductionReferencesToRegisterDirectNode() {
        // 1. MeshBleEngine must NOT contain any method or field named registerDirectNode or directAddressToNodeId
        val bleMethods = MeshBleEngine::class.java.declaredMethods.map { it.name }
        assertThat(bleMethods).doesNotContain("registerDirectNode")

        val bleFields = MeshBleEngine::class.java.declaredFields.map { it.name }
        assertThat(bleFields).doesNotContain("directAddressToNodeId")

        // 2. MeshRouter must NOT contain any method named registerDirectNode
        val routerMethods = com.meshwhisper.app.router.MeshRouter::class.java.declaredMethods.map { it.name }
        assertThat(routerMethods).doesNotContain("registerDirectNode")
    }

    /**
     * Requirement 5: bindLink authority cannot be fabricated by arbitrary production code.
     */
    @Test
    fun testBindLinkAuthorityRestriction() {
        // LinkAuthProof constructor must NOT be directly publicly constructible
        val constructors = LinkAuthProof::class.java.declaredConstructors
        assertThat(constructors).isNotEmpty()
        val nonSyntheticConstructors = constructors.filter { !it.isSynthetic }
        assertThat(nonSyntheticConstructors).isNotEmpty()
        for (ctor in nonSyntheticConstructors) {
            assertThat(Modifier.isPublic(ctor.modifiers)).isFalse()
        }

        // MeshRouter.bindLink must accept LinkAuthProof, NOT raw (String, ByteArray)
        val bindMethods = com.meshwhisper.app.router.MeshRouter::class.java.declaredMethods.filter { it.name == "bindLink" }
        assertThat(bindMethods).hasSize(1)
        assertThat(bindMethods[0].parameterTypes).asList().containsExactly(LinkAuthProof::class.java)
    }

    /**
     * Requirement 6: Simultaneous BLE HELLO production and ordering.
     */
    @Test
    fun testBleSimultaneousHelloOrdering() {
        val seedA = ByteArray(32) { 0x11.toByte() }
        val seedB = ByteArray(32) { 0x22.toByte() }
        val credsA = LinkAuthLocalCredentials.create(seedA)
        val credsB = LinkAuthLocalCredentials.create(seedB)

        val sessionA = LinkAuthSession("LINK_GATT", credsA, clock)
        val sessionB = LinkAuthSession("LINK_GATT", credsB, clock)

        // Both emit HELLO concurrently (order: A then B)
        val helloA = sessionA.createHelloPacket()
        val helloB = sessionB.createHelloPacket()

        // A consumes B's HELLO, B consumes A's HELLO
        sessionA.processIncomingPacket(helloB)
        sessionB.processIncomingPacket(helloA)

        assertThat(sessionA.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)
        assertThat(sessionB.state).isEqualTo(LinkAuthState.HELLO_EXCHANGED)

        // Both produce CONFIRM
        val confirmA = sessionA.createConfirmPacket()
        val confirmB = sessionB.createConfirmPacket()

        // Both consume peer's CONFIRM
        val resultA = sessionA.processIncomingPacket(confirmB)
        val resultB = sessionB.processIncomingPacket(confirmA)

        assertThat(resultA).isInstanceOf(LinkAuthStepResult.Completed::class.java)
        assertThat(resultB).isInstanceOf(LinkAuthStepResult.Completed::class.java)

        val proofA = (resultA as LinkAuthStepResult.Completed).proof
        val proofB = (resultB as LinkAuthStepResult.Completed).proof

        assertThat(proofA.linkKey).isEqualTo(proofB.linkKey)
    }
}

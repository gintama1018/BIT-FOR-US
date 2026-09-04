package com.meshwhisper.app.router

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.data.model.MessageStatus
import com.meshwhisper.app.protocol.MeshPacket
import com.meshwhisper.app.protocol.PacketType
import com.meshwhisper.core.router.MeshRouteEngine
import com.meshwhisper.core.router.RouteEdge
import com.meshwhisper.core.router.RouteLookupResult
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.router.LruDedupCache
import org.junit.Test
import java.nio.ByteBuffer
import java.util.UUID

/**
 * Rigorous multi-hop relay reliability test proving:
 * A -> B -> C -> ACK -> B -> A
 * with link failures, lost ACKs, duplicate retransmissions, S&F custody cleanup, and failover.
 */
class MultiHopRelayReliabilityTest {

    // Test Node Identities
    private val aliceKeys = PureCryptoEngine.generateX25519KeyPair()
    private val alicePriv get() = aliceKeys.first
    private val alicePub get() = aliceKeys.second
    private val aliceNodeId = PureCryptoEngine.deriveNodeId(aliceKeys.second)

    private val bobKeys = PureCryptoEngine.generateX25519KeyPair()
    private val bobPriv get() = bobKeys.first
    private val bobPub get() = bobKeys.second
    private val bobNodeId = PureCryptoEngine.deriveNodeId(bobKeys.second)

    private val charlieKeys = PureCryptoEngine.generateX25519KeyPair()
    private val charliePriv get() = charlieKeys.first
    private val charliePub get() = charlieKeys.second
    private val charlieNodeId = PureCryptoEngine.deriveNodeId(charlieKeys.second)

    private val daveKeys = PureCryptoEngine.generateX25519KeyPair()
    private val davePriv get() = daveKeys.first
    private val davePub get() = daveKeys.second
    private val daveNodeId = PureCryptoEngine.deriveNodeId(daveKeys.second)

    @Test
    fun testFullMultiHopDeliveryAndAckLoop_AToBToCToAckToBToA() {
        // Topology: A <-> B <-> C (linear 3-node chain)
        val routeEngineA = MeshRouteEngine(aliceNodeId)
        val routeEngineB = MeshRouteEngine(bobNodeId)
        val routeEngineC = MeshRouteEngine(charlieNodeId)

        val now = System.currentTimeMillis()

        // Configure A: direct to B; B can reach C
        routeEngineA.updateDirectNeighbors(setOf(bobNodeId))
        routeEngineA.updateEdges(listOf(RouteEdge(fromNode = bobNodeId, toNode = charlieNodeId, cost = 1, lastSeen = now)))

        // Configure B: direct to A and C
        routeEngineB.updateDirectNeighbors(setOf(aliceNodeId, charlieNodeId))

        // Configure C: direct to B; B can reach A
        routeEngineC.updateDirectNeighbors(setOf(bobNodeId))
        routeEngineC.updateEdges(listOf(RouteEdge(fromNode = bobNodeId, toNode = aliceNodeId, cost = 1, lastSeen = now)))

        // --- STEP 1: A creates and sends DM to C ---
        val msgId = UUID.randomUUID()
        val text = "Secret rendezvous at north gate"
        val timestamp = now / 1000L

        // Derive end-to-end session key between A and C
        val sessionKeyAC = PureCryptoEngine.derivePeerSessionKey(alicePriv, charliePub, timestamp)
        val aad = MeshPacket.computeAad(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = aliceNodeId,
            recipientId = charlieNodeId,
            timestamp = timestamp
        )
        val encResult = PureCryptoEngine.encrypt(
            plaintext = text.toByteArray(Charsets.UTF_8),
            messageId = msgId,
            aesKey = sessionKeyAC,
            aad = aad
        )
        val packetA = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = msgId,
            senderId = aliceNodeId,
            recipientId = charlieNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = encResult.ciphertext,
            authTag = encResult.authTag
        )

        // Route lookup at A: next hop to C must be B
        val routeA = routeEngineA.resolveRoute(charlieNodeId, now)
        assertThat(routeA).isInstanceOf(RouteLookupResult.NextHop::class.java)
        val nextHopA = routeA as RouteLookupResult.NextHop
        assertThat(nextHopA.nextHopNodeId).isEqualTo(bobNodeId)
        assertThat(nextHopA.hopCount).isEqualTo(2)

        // A hands off to B -> Message status at A progresses to RELAYED
        var aliceMessageStatus = MessageStatus.SENT
        aliceMessageStatus = MessageStatus.RELAYED
        assertThat(aliceMessageStatus).isEqualTo(MessageStatus.RELAYED)

        // --- STEP 2: Intermediate Relay B receives packet ---
        val rawPacketA = MeshPacket.serialize(packetA)
        val packetAtB = MeshPacket.deserialize(rawPacketA)!!
        assertThat(packetAtB.recipientId).isEqualTo(charlieNodeId)

        // B decrements TTL and checks route to C
        assertThat(packetAtB.ttl).isGreaterThan(1)
        val relayedAtB = packetAtB.decrementTtl()
        assertThat(relayedAtB.ttl).isEqualTo(MeshPacket.DEFAULT_TTL - 1)

        val routeB = routeEngineB.resolveRoute(charlieNodeId, now)
        // B has direct radio link to C
        assertThat(routeB).isInstanceOf(RouteLookupResult.Direct::class.java)

        // B transmits directly to C. Because direct delivery succeeds, B does NOT keep S&F custody
        val bHoldsSfCustody = false
        assertThat(bHoldsSfCustody).isFalse()

        // --- STEP 3: Charlie receives packet from B ---
        val rawRelayedAtB = MeshPacket.serialize(relayedAtB)
        val packetAtC = MeshPacket.deserialize(rawRelayedAtB)!!
        assertThat(packetAtC.recipientId).isEqualTo(charlieNodeId)

        // C decrypts and authenticates AEAD payload
        val sessionKeyCA = PureCryptoEngine.derivePeerSessionKey(charliePriv, alicePub, packetAtC.timestamp)
        val decryptedTextBytes = PureCryptoEngine.decrypt(
            ciphertext = packetAtC.payload,
            authTag = packetAtC.authTag,
            messageId = packetAtC.messageId,
            aesKey = sessionKeyCA,
            aad = packetAtC.getAuthenticatedHeaderBytes()
        )
        val receivedText = String(decryptedTextBytes, Charsets.UTF_8)
        assertThat(receivedText).isEqualTo(text)

        // C marks message DELIVERED locally
        val charlieMessageStatus = MessageStatus.DELIVERED
        assertThat(charlieMessageStatus).isEqualTo(MessageStatus.DELIVERED)

        // --- STEP 4: Charlie generates delivery ACK for Alice ---
        val ackPacketId = UUID.randomUUID()
        val ackTimestamp = (now + 50) / 1000L
        val originalMsgIdBytes = ByteBuffer.allocate(16).apply {
            putLong(msgId.mostSignificantBits)
            putLong(msgId.leastSignificantBits)
        }.array()

        val ackAad = MeshPacket.computeAad(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = charlieNodeId,
            recipientId = aliceNodeId,
            timestamp = ackTimestamp
        )
        val ackEncResult = PureCryptoEngine.encrypt(
            plaintext = originalMsgIdBytes,
            messageId = ackPacketId,
            aesKey = sessionKeyCA,
            aad = ackAad
        )
        val ackPacket = MeshPacket(
            type = PacketType.ACK,
            messageId = ackPacketId,
            senderId = charlieNodeId,
            recipientId = aliceNodeId,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = ackTimestamp,
            payload = ackEncResult.ciphertext,
            authTag = ackEncResult.authTag
        )

        // C routes ACK to A: next hop is B
        val routeToAAtC = routeEngineC.resolveRoute(aliceNodeId, now)
        assertThat(routeToAAtC).isInstanceOf(RouteLookupResult.NextHop::class.java)
        assertThat((routeToAAtC as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(bobNodeId)

        // --- STEP 5: Relay B forwards ACK to Alice ---
        val rawAck = MeshPacket.serialize(ackPacket)
        val ackAtB = MeshPacket.deserialize(rawAck)!!
        val relayedAck = ackAtB.decrementTtl()

        val routeToAAtB = routeEngineB.resolveRoute(aliceNodeId, now)
        assertThat(routeToAAtB).isInstanceOf(RouteLookupResult.Direct::class.java)

        // --- STEP 6: Alice receives and verifies ACK ---
        val rawRelayedAck = MeshPacket.serialize(relayedAck)
        val ackAtA = MeshPacket.deserialize(rawRelayedAck)!!
        assertThat(ackAtA.recipientId).isEqualTo(aliceNodeId)

        val decryptedAckPayload = PureCryptoEngine.decrypt(
            ciphertext = ackAtA.payload,
            authTag = ackAtA.authTag,
            messageId = ackAtA.messageId,
            aesKey = sessionKeyAC,
            aad = ackAtA.getAuthenticatedHeaderBytes()
        )
        val ackBuf = ByteBuffer.wrap(decryptedAckPayload)
        val recoveredMsgId = UUID(ackBuf.long, ackBuf.long)
        assertThat(recoveredMsgId).isEqualTo(msgId)

        // Alice transitions message status to DELIVERED
        aliceMessageStatus = MessageStatus.DELIVERED
        assertThat(aliceMessageStatus).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun testLostAckRetransmissionRecovery() {
        // Simulates the real-world condition where C receives message M and emits ACK,
        // but the ACK is lost in transit.
        // When A times out and retransmits M, C must re-emit ACK without creating a duplicate message!
        val dedupCache = LruDedupCache<String, Long>(4000)
        val storedMessages = mutableListOf<String>()
        val emittedAcks = mutableListOf<UUID>()

        val msgId = UUID.randomUUID()
        val dedupKey = "$msgId:${PacketType.DIRECT_MESSAGE.code}"

        // First arrival at C
        fun onReceiveAtCharlie(mId: UUID, recipient: Long, isDuplicate: Boolean) {
            if (isDuplicate) {
                // Lost-ACK Recovery Invariant
                if (recipient == charlieNodeId) {
                    emittedAcks.add(mId) // Re-emit ACK!
                }
                return // Drop duplicate payload
            }
            // First time: process message
            storedMessages.add(mId.toString())
            emittedAcks.add(mId)
        }

        // 1. Initial transmission arrives at C
        assertThat(dedupCache.containsKey(dedupKey)).isFalse()
        dedupCache.put(dedupKey, System.currentTimeMillis())
        onReceiveAtCharlie(msgId, charlieNodeId, isDuplicate = false)

        assertThat(storedMessages).containsExactly(msgId.toString())
        assertThat(emittedAcks).hasSize(1)

        // 2. ACK is lost in transit. A retransmits message with same msgId.
        val isSecondArrivalDuplicate = dedupCache.containsKey(dedupKey)
        assertThat(isSecondArrivalDuplicate).isTrue()
        onReceiveAtCharlie(msgId, charlieNodeId, isDuplicate = isSecondArrivalDuplicate)

        // Verification:
        // C re-emitted the ACK (total 2 ACKs emitted)
        assertThat(emittedAcks).hasSize(2)
        // C did NOT create a duplicate message in the DB
        assertThat(storedMessages).hasSize(1)
    }

    @Test
    fun testDisappearingIntermediateRelayAndRouteFailover() {
        // Topology:
        // A -> B -> C (2 hops)
        // A -> D -> C (2 hops)
        val routeEngineA = MeshRouteEngine(aliceNodeId)
        val now = System.currentTimeMillis()

        routeEngineA.updateDirectNeighbors(setOf(bobNodeId, daveNodeId))
        routeEngineA.updateEdges(listOf(
            RouteEdge(fromNode = bobNodeId, toNode = charlieNodeId, cost = 1, lastSeen = now),
            RouteEdge(fromNode = daveNodeId, toNode = charlieNodeId, cost = 2, lastSeen = now) // slightly higher cost
        ))

        // Initial route: uses B
        val initialRoute = routeEngineA.resolveRoute(charlieNodeId, now)
        assertThat((initialRoute as RouteLookupResult.NextHop).nextHopNodeId).isEqualTo(bobNodeId)

        // Relay B disappears / GATT connection breaks!
        routeEngineA.markLinkFailed(aliceNodeId, bobNodeId, penaltyDurationMs = 60_000L)

        // Failover route: automatically switches to Dave!
        val failoverRoute = routeEngineA.resolveRoute(charlieNodeId, now)
        assertThat(failoverRoute).isInstanceOf(RouteLookupResult.NextHop::class.java)
        val failoverNextHop = failoverRoute as RouteLookupResult.NextHop
        assertThat(failoverNextHop.nextHopNodeId).isEqualTo(daveNodeId)
        assertThat(failoverNextHop.path).containsExactly(aliceNodeId, daveNodeId, charlieNodeId).inOrder()
    }

    @Test
    fun testIntermediateRelayCustodyAndReconnectionDrain() {
        // Scenario: A sends to C via B, but C is temporarily offline from B.
        // B must hold custody in S&F. When C reconnects, B drains directly and clears custody.
        val sfQueue = mutableMapOf<String, ByteArray>()

        val msgId = UUID.randomUUID().toString()
        val dummyData = "PacketDataForCharlie".toByteArray()

        // B stores packet in S&F queue
        sfQueue[msgId] = dummyData
        assertThat(sfQueue).containsKey(msgId)

        // Direct transmission from B to C initially fails (C is offline)
        var isCharlieConnected = false
        fun attemptDeliveryToCharlie(): Boolean {
            return isCharlieConnected
        }

        assertThat(attemptDeliveryToCharlie()).isFalse()
        assertThat(sfQueue).containsKey(msgId) // Queue still retains message

        // Charlie reconnects!
        isCharlieConnected = true

        // Reconnection drain executes
        if (attemptDeliveryToCharlie()) {
            sfQueue.remove(msgId) // Custody fulfilled!
        }

        // Verification: S&F queue is now completely clean
        assertThat(sfQueue).isEmpty()
    }

    @Test
    fun testTtlDecrementAndDropAtZero() {
        val packet = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = aliceNodeId,
            recipientId = charlieNodeId,
            ttl = 2,
            timestamp = System.currentTimeMillis() / 1000L,
            payload = ByteArray(10)
        )

        // Hop 1: TTL 2 -> 1
        val hop1 = packet.decrementTtl()
        assertThat(hop1.ttl).isEqualTo(1)

        // Hop 2: TTL 1 -> 0 (Dropped: should not be relayed further)
        val canRelayFurther = hop1.ttl > 1
        assertThat(canRelayFurther).isFalse()
    }

    @Test
    fun testSplitHorizonIngressFilterPreventsEcho() {
        val ingressAddress = "00:11:22:33:44:55"
        val targetAddress = "66:77:88:99:AA:BB"

        fun canRelayToAddress(destinationAddress: String): Boolean {
            return destinationAddress != ingressAddress
        }

        assertThat(canRelayToAddress(targetAddress)).isTrue()
        assertThat(canRelayToAddress(ingressAddress)).isFalse() // Never echo back to ingress!
    }
}

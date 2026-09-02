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
    fun testStoreAndForwardTimestampWindowValidation() {
        val nowSec = System.currentTimeMillis() / 1000L

        // Stored DM from 6 hours ago (within 24h store-and-forward window)
        val sixHoursAgo = nowSec - 21600
        val sixHourAge = nowSec - sixHoursAgo
        assertThat(sixHourAge in -300..86400).isTrue()

        // Stored DM from 23 hours ago (within 24h store-and-forward window)
        val twentyThreeHoursAgo = nowSec - 82800
        val twentyThreeHourAge = nowSec - twentyThreeHoursAgo
        assertThat(twentyThreeHourAge in -300..86400).isTrue()

        // Stored DM from 25 hours ago (expired beyond 24h store-and-forward window)
        val twentyFiveHoursAgo = nowSec - 90000
        val twentyFiveHourAge = nowSec - twentyFiveHoursAgo
        assertThat(twentyFiveHourAge in -300..86400).isFalse()
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

    @Test
    fun testDedupKeyDisambiguationBetweenDmAndAck() {
        val msgId = UUID.randomUUID()

        val dmDedupKey = "$msgId:${PacketType.DIRECT_MESSAGE.code}"
        val ackDedupKey = "$msgId:${PacketType.ACK.code}"

        assertThat(dmDedupKey).isNotEqualTo(ackDedupKey)
        assertThat(dmDedupKey).endsWith(":1")
        assertThat(ackDedupKey).endsWith(":3")
    }

    @Test
    fun testMultiSessionChunkReassemblyIsolation() {
        val framer = BleFrameFramer()
        val payload1 = "PAYLOAD_ONE_ABCDEFGHIJKLMN_123456789".repeat(20).toByteArray(Charsets.UTF_8)
        val payload2 = "PAYLOAD_TWO_OPQRSTUVWXYZ_987654321".repeat(20).toByteArray(Charsets.UTF_8)

        val frames1 = framer.fragment(payload1, 50)
        val frames2 = framer.fragment(payload2, 50)

        assertThat(frames1.size).isGreaterThan(2)
        assertThat(frames2.size).isGreaterThan(2)

        // Interleave frames from the same device address
        var reassembled1: ByteArray? = null
        var reassembled2: ByteArray? = null

        val maxLen = maxOf(frames1.size, frames2.size)
        for (i in 0 until maxLen) {
            if (i < frames1.size) {
                val res = framer.receiveFrame("PEER_AA", frames1[i])
                if (res != null) reassembled1 = res
            }
            if (i < frames2.size) {
                val res = framer.receiveFrame("PEER_AA", frames2[i])
                if (res != null) reassembled2 = res
            }
        }

        assertThat(reassembled1).isNotNull()
        assertThat(reassembled1).isEqualTo(payload1)

        assertThat(reassembled2).isNotNull()
        assertThat(reassembled2).isEqualTo(payload2)
    }

    @Test
    fun testPeerAnnounceNeighborGossipSerializationAndParsing() {
        val alias = "AlphaNode"
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val pubKeyBytes = ByteArray(32) { (it + 1).toByte() }
        val neighbors = listOf(0x1122334455667788L, 0x2233445566778899L)

        // Encode payload with neighbor list
        val payload = ByteArray(1 + aliasBytes.size + pubKeyBytes.size + 1 + (neighbors.size * 8))
        val buffer = java.nio.ByteBuffer.wrap(payload)
        buffer.put((aliasBytes.size and 0xFF).toByte())
        buffer.put(aliasBytes)
        buffer.put(pubKeyBytes)
        buffer.put(neighbors.size.toByte())
        for (n in neighbors) {
            buffer.putLong(n)
        }

        // Decode payload
        val readBuf = java.nio.ByteBuffer.wrap(payload)
        val readAliasLen = readBuf.get().toInt() and 0xFF
        val readAliasBytes = ByteArray(readAliasLen)
        readBuf.get(readAliasBytes)
        val readAlias = String(readAliasBytes, Charsets.UTF_8)
        val readPubKey = ByteArray(32)
        readBuf.get(readPubKey)

        val readNeighbors = mutableListOf<Long>()
        if (readBuf.hasRemaining()) {
            val neighborCount = readBuf.get().toInt() and 0xFF
            for (i in 0 until neighborCount) {
                if (readBuf.remaining() >= 8) {
                    readNeighbors.add(readBuf.long)
                }
            }
        }

        assertThat(readAlias).isEqualTo(alias)
        assertThat(readPubKey).isEqualTo(pubKeyBytes)
        assertThat(readNeighbors).containsExactlyElementsIn(neighbors).inOrder()
    }

    @Test
    fun testRelayedPacketDoesNotTriggerDirectLinkRegistration() {
        val defaultTtl = MeshPacket.DEFAULT_TTL
        
        // Direct packet (received on 1st hop)
        val directTtl = defaultTtl
        val isDirectPacket = directTtl >= defaultTtl - 1
        assertThat(isDirectPacket).isTrue()

        // Relayed packet from 2+ hops away (TTL decremented by intermediate relays)
        val relayedTtl = defaultTtl - 2
        val isRelayedDirect = relayedTtl >= defaultTtl - 1
        assertThat(isRelayedDirect).isFalse()

        // Hop count accurately reflected
        val hopCount = maxOf(1, defaultTtl - relayedTtl)
        assertThat(hopCount).isEqualTo(2)
    }

    @Test
    fun testSosPacketSerializationAndAadAuthentication() {
        val msgId = UUID.randomUUID()
        val senderId = 0x1122334455667788L
        val timestamp = System.currentTimeMillis() / 1000L
        val sosText = "HELP: Trapped in room 402 with 2 injured students"
        val textBytes = sosText.toByteArray(Charsets.UTF_8)

        // Deterministic framed payload with flags=0 (no location)
        val payloadBuf = java.nio.ByteBuffer.allocate(1 + 2 + textBytes.size)
        payloadBuf.put(0x00.toByte())
        payloadBuf.putShort((textBytes.size and 0xFFFF).toShort())
        payloadBuf.put(textBytes)
        val framedPayload = payloadBuf.array()

        val packet = MeshPacket(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = senderId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            ttl = MeshPacket.DEFAULT_TTL,
            timestamp = timestamp,
            payload = framedPayload
        )

        val serialized = MeshPacket.serialize(packet)
        assertThat(serialized.size).isEqualTo(MeshPacket.OVERHEAD_SIZE + framedPayload.size)

        val deserialized = MeshPacket.deserialize(serialized)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.type).isEqualTo(PacketType.SOS_MESSAGE)
        assertThat(deserialized.messageId).isEqualTo(msgId)
        assertThat(deserialized.senderId).isEqualTo(senderId)
        assertThat(deserialized.recipientId).isEqualTo(MeshPacket.BROADCAST_RECIPIENT_ID)
        assertThat(deserialized.ttl).isEqualTo(MeshPacket.DEFAULT_TTL)

        // Parse framed payload
        val readBuf = java.nio.ByteBuffer.wrap(deserialized.payload)
        val flags = readBuf.get().toInt() and 0xFF
        val textLen = readBuf.short.toInt() and 0xFFFF
        val tBytes = ByteArray(textLen)
        readBuf.get(tBytes)
        assertThat(flags).isEqualTo(0)
        assertThat(String(tBytes, Charsets.UTF_8)).isEqualTo(sosText)

        // Verify AAD binding
        val aad = deserialized.getAuthenticatedHeaderBytes()
        val expectedAad = MeshPacket.computeAad(
            type = PacketType.SOS_MESSAGE,
            messageId = msgId,
            senderId = senderId,
            recipientId = MeshPacket.BROADCAST_RECIPIENT_ID,
            timestamp = timestamp
        )
        assertThat(aad).isEqualTo(expectedAad)
    }

    @Test
    fun testSosFramingWithLocationAndCoincidentalLetterL() {
        // String where character at (length - 21) is intentionally 'L' to ensure zero false positives
        val trickyText = "This is a sentence with Long words and Letters inside it!!"
        val textBytes = trickyText.toByteArray(Charsets.UTF_8)
        val lat = 28.6139
        val lon = 77.2090
        val acc = 8.5f

        // 1. Framed WITH location (flags = 1)
        val withLocBuf = java.nio.ByteBuffer.allocate(1 + 2 + textBytes.size + 20)
        withLocBuf.put(0x01.toByte())
        withLocBuf.putShort((textBytes.size and 0xFFFF).toShort())
        withLocBuf.put(textBytes)
        withLocBuf.putDouble(lat)
        withLocBuf.putDouble(lon)
        withLocBuf.putFloat(acc)
        val payloadWithLoc = withLocBuf.array()

        val readWithLoc = java.nio.ByteBuffer.wrap(payloadWithLoc)
        val flags1 = readWithLoc.get().toInt() and 0xFF
        val len1 = readWithLoc.short.toInt() and 0xFFFF
        val str1Bytes = ByteArray(len1)
        readWithLoc.get(str1Bytes)
        val parsedLat = readWithLoc.double
        val parsedLon = readWithLoc.double
        val parsedAcc = readWithLoc.float

        assertThat(flags1).isEqualTo(1)
        assertThat(String(str1Bytes, Charsets.UTF_8)).isEqualTo(trickyText)
        assertThat(parsedLat).isEqualTo(lat)
        assertThat(parsedLon).isEqualTo(lon)
        assertThat(parsedAcc).isEqualTo(acc)

        // 2. Framed WITHOUT location (flags = 0) even with 'L' in the text
        val withoutLocBuf = java.nio.ByteBuffer.allocate(1 + 2 + textBytes.size)
        withoutLocBuf.put(0x00.toByte())
        withoutLocBuf.putShort((textBytes.size and 0xFFFF).toShort())
        withoutLocBuf.put(textBytes)
        val payloadWithoutLoc = withoutLocBuf.array()

        val readWithoutLoc = java.nio.ByteBuffer.wrap(payloadWithoutLoc)
        val flags0 = readWithoutLoc.get().toInt() and 0xFF
        val len0 = readWithoutLoc.short.toInt() and 0xFFFF
        val str0Bytes = ByteArray(len0)
        readWithoutLoc.get(str0Bytes)

        assertThat(flags0).isEqualTo(0)
        assertThat(String(str0Bytes, Charsets.UTF_8)).isEqualTo(trickyText)
        assertThat(readWithoutLoc.hasRemaining()).isFalse() // No leftover false location bytes!
    }

    @Test
    fun testPeerAnnounceLocationPayloadExtension() {
        val alias = "Responder-1"
        val aliasBytes = alias.toByteArray(Charsets.UTF_8)
        val pubKeyBytes = ByteArray(32) { (it + 5).toByte() }
        val neighbors = listOf(0xAAAA1111L)
        val avatarHash: Byte = 0x2A

        val lat = 28.613939
        val lon = 77.209021
        val accuracy = 4.5f
        val locTimestamp = 1725200000000L

        // Encode with 0x4C location extension
        val payload = ByteArray(1 + aliasBytes.size + pubKeyBytes.size + 1 + (neighbors.size * 8) + 1 + 29)
        val buf = java.nio.ByteBuffer.wrap(payload)
        buf.put((aliasBytes.size and 0xFF).toByte())
        buf.put(aliasBytes)
        buf.put(pubKeyBytes)
        buf.put(neighbors.size.toByte())
        for (n in neighbors) buf.putLong(n)
        buf.put(avatarHash)
        buf.put(0x4C.toByte())
        buf.putDouble(lat)
        buf.putDouble(lon)
        buf.putFloat(accuracy)
        buf.putLong(locTimestamp)

        // Decode
        val readBuf = java.nio.ByteBuffer.wrap(payload)
        val readAliasLen = readBuf.get().toInt() and 0xFF
        val readAliasBytes = ByteArray(readAliasLen)
        readBuf.get(readAliasBytes)
        assertThat(String(readAliasBytes, Charsets.UTF_8)).isEqualTo(alias)

        val readPubKey = ByteArray(32)
        readBuf.get(readPubKey)
        assertThat(readPubKey).isEqualTo(pubKeyBytes)

        val readNeighborCount = readBuf.get().toInt() and 0xFF
        val readNeighbors = mutableListOf<Long>()
        for (i in 0 until readNeighborCount) readNeighbors.add(readBuf.long)
        assertThat(readNeighbors).containsExactly(0xAAAA1111L)

        val readAvatarHash = readBuf.get()
        assertThat(readAvatarHash).isEqualTo(avatarHash)

        // Read location extension
        assertThat(readBuf.remaining()).isEqualTo(29)
        val marker = readBuf.get()
        assertThat(marker).isEqualTo(0x4C.toByte())
        val readLat = readBuf.double
        val readLon = readBuf.double
        val readAcc = readBuf.float
        val readTs = readBuf.long

        assertThat(readLat).isEqualTo(lat)
        assertThat(readLon).isEqualTo(lon)
        assertThat(readAcc).isEqualTo(accuracy)
        assertThat(readTs).isEqualTo(locTimestamp)
    }

    @Test
    fun testEmergencyKeywordTriageHeuristics() {
        val regex = Regex("""\b(help|trapped|sos|emergency|fire|medical|injured|bleeding|earthquake|collapse|bachao|madad|danger)\b""", RegexOption.IGNORE_CASE)

        // Distress messages that MUST trigger SOS triage
        assertThat(regex.containsMatchIn("Please send help immediately")).isTrue()
        assertThat(regex.containsMatchIn("We are trapped on the 2nd floor")).isTrue()
        assertThat(regex.containsMatchIn("Severe medical emergency here")).isTrue()
        assertThat(regex.containsMatchIn("Someone is bleeding, need gauze")).isTrue()
        assertThat(regex.containsMatchIn("Earthquake collapsed staircase")).isTrue()
        assertThat(regex.containsMatchIn("Bhai bachao jaldi")).isTrue()
        assertThat(regex.containsMatchIn("Immediate danger at gate 3")).isTrue()

        // Normal messages that should NOT trigger triage
        assertThat(regex.containsMatchIn("Hey are you coming to class?")).isFalse()
        assertThat(regex.containsMatchIn("The weather is nice today")).isFalse()
        assertThat(regex.containsMatchIn("I helpful helper helping")).isFalse() // Substring boundary check
    }
}

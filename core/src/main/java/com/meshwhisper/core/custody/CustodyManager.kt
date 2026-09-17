package com.meshwhisper.core.custody

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.AuthenticatedPacket
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Role of the local node with respect to a message under custody.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.16.
 */
enum class CustodyRole {
    /**
     * Originator of the message.
     * Retains custody until end-to-end ACK or 24h expiry.
     * NEVER releases on CUSTODY_ACK. NEVER releases on attemptSend() == true.
     */
    ORIGINATOR,

    /**
     * Intermediate relay node that accepted custody.
     * Releases custody on: end-to-end ACK, valid CUSTODY_ACK from next hop, or 24h expiry.
     */
    RELAY
}

/**
 * State of custody for a message.
 */
enum class CustodyState {
    /**
     * Custody is held locally. Retried per retry ladder until handoff or ACK.
     */
    HELD,

    /**
     * Successfully confirmed delivered end-to-end via signed ACK.
     */
    DELIVERED,

    /**
     * Lifetime expired without delivery proof (24 hours).
     */
    EXPIRED,

    /**
     * Relay custody released upon receipt of valid CUSTODY_ACK from next hop.
     */
    RELEASED
}

/**
 * Record tracking custody of a stored message.
 */
data class CustodyRecord(
    val messageId: String,
    val recipientNodeId: Long,
    val originIdentityHash: ByteArray,
    val role: CustodyRole,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    var state: CustodyState = CustodyState.HELD,
    var packetData: ByteArray? = null,
    var nextHopNodeId: Long? = null,
    var nextHopIdentityHash: ByteArray? = null,
    var retryCount: Int = 0,
    var nextRetryTimeMs: Long = 0L
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustodyRecord) return false
        return messageId == other.messageId && role == other.role
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + role.hashCode()
        return result
    }
}

/**
 * Custody Manager for Secure Protocol vNext.
 * Implements Phase P5 custody safety:
 * - Invariant I-6: Originator retains custody until end-to-end ACK or expiry.
 * - Intermediate relay retains custody until valid CUSTODY_ACK from next hop, end-to-end ACK, or expiry.
 * - attemptSend() returning true does NOT release custody.
 * - Forged CUSTODY_ACK from third party is rejected (custody retained).
 * - Retry ladder with deterministic backoff.
 * - S&F partition quotas: 300 own, 200 relayed.
 */
class CustodyManager(
    val localNodeId: Long,
    val localIdentityHash: ByteArray,
    val clock: Clock = SystemClock()
) {
    companion object {
        const val CUSTODY_LIFETIME_MS = 24 * 60 * 60 * 1000L // 24 hours
        const val MAX_OWN_SF_CAPACITY = 300
        const val MAX_RELAYED_SF_CAPACITY = 200
        const val MAX_TOTAL_SF_CAPACITY = 500

        // Retry ladder backoff schedule: 1s, 2s, 4s, 8s, 16s, 32s, capped at 60s
        val RETRY_BACKOFF_STEPS_MS = listOf(
            1_000L,
            2_000L,
            4_000L,
            8_000L,
            16_000L,
            32_000L,
            60_000L
        )

        fun computeNextRetryDelayMs(retryCount: Int): Long {
            val idx = minOf(maxOf(0, retryCount), RETRY_BACKOFF_STEPS_MS.size - 1)
            return RETRY_BACKOFF_STEPS_MS[idx]
        }
    }

    // Active custody records keyed by messageId
    private val records = ConcurrentHashMap<String, CustodyRecord>()

    /**
     * Registers a locally originated message for originator custody.
     * Enforces own-message capacity (300).
     */
    @Synchronized
    fun registerOriginatorMessage(
        messageId: String,
        recipientNodeId: Long,
        packetData: ByteArray? = null,
        nowMs: Long = clock.nowMillis(),
        lifetimeMs: Long = CUSTODY_LIFETIME_MS
    ): CustodyRecord? {
        val ownRecords = records.values.filter { it.role == CustodyRole.ORIGINATOR && it.state == CustodyState.HELD }
        if (ownRecords.size >= MAX_OWN_SF_CAPACITY) {
            // Evict oldest own record if exceeding capacity
            val oldest = ownRecords.minByOrNull { it.createdAtMs }
            if (oldest != null) {
                records.remove(oldest.messageId)
            }
        }

        val record = CustodyRecord(
            messageId = messageId,
            recipientNodeId = recipientNodeId,
            originIdentityHash = localIdentityHash.clone(),
            role = CustodyRole.ORIGINATOR,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + lifetimeMs,
            state = CustodyState.HELD,
            packetData = packetData,
            retryCount = 0,
            nextRetryTimeMs = nowMs + computeNextRetryDelayMs(0)
        )
        records[messageId] = record
        return record
    }

    /**
     * Accepts intermediate custody of a relayed message.
     * Enforces relayed-message capacity (200). Relayed flood can NEVER touch own partition (S-12).
     */
    @Synchronized
    fun acceptRelayCustody(
        messageId: String,
        recipientNodeId: Long,
        originIdentityHash: ByteArray,
        packetData: ByteArray? = null,
        nowMs: Long = clock.nowMillis(),
        lifetimeMs: Long = CUSTODY_LIFETIME_MS
    ): CustodyRecord? {
        val relayedRecords = records.values.filter { it.role == CustodyRole.RELAY && it.state == CustodyState.HELD }
        if (relayedRecords.size >= MAX_RELAYED_SF_CAPACITY) {
            // Relayed partition full: evict oldest relayed entry (never touch own entries)
            val oldestRelayed = relayedRecords.minByOrNull { it.createdAtMs }
            if (oldestRelayed != null) {
                records.remove(oldestRelayed.messageId)
            }
        }

        val record = CustodyRecord(
            messageId = messageId,
            recipientNodeId = recipientNodeId,
            originIdentityHash = originIdentityHash.clone(),
            role = CustodyRole.RELAY,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + lifetimeMs,
            state = CustodyState.HELD,
            packetData = packetData,
            retryCount = 0,
            nextRetryTimeMs = nowMs + computeNextRetryDelayMs(0)
        )
        records[messageId] = record
        return record
    }

    /**
     * Records a forwarding / handoff attempt to a next-hop custodian.
     * Advances the retry ladder. Note: attemptSend() returning true does NOT release custody.
     */
    @Synchronized
    fun recordHandoffAttempt(
        messageId: String,
        nextHopNodeId: Long,
        nextHopIdentityHash: ByteArray? = null,
        nowMs: Long = clock.nowMillis()
    ): Boolean {
        val record = records[messageId] ?: return false
        if (record.state != CustodyState.HELD) return false

        record.nextHopNodeId = nextHopNodeId
        record.nextHopIdentityHash = nextHopIdentityHash?.clone()
        record.retryCount++
        record.nextRetryTimeMs = nowMs + computeNextRetryDelayMs(record.retryCount)
        return true
    }

    /**
     * Handles an authenticated CUSTODY_ACK from the network.
     * Validates:
     * 1. custodyMessageId exists in active custody queue.
     * 2. originIdentityHash is non-zero.
     * 3. Sender must match designated next-hop custodian (rejects third-party forged CUSTODY_ACK, T-CUST-07).
     *
     * Effect:
     * - If RELAY: releases intermediate custody (returns true).
     * - If ORIGINATOR: does NOT release custody! Originator custody survives until end-to-end ACK (returns false).
     */
    @Synchronized
    fun handleCustodyAck(
        custodyMessageId: String,
        senderNodeId: Long,
        senderIdentityHash: ByteArray?,
        originIdentityHash: ByteArray
    ): Boolean {
        val record = records[custodyMessageId] ?: return false
        if (record.state != CustodyState.HELD) return false

        // Validate origin identity hash is non-zero
        if (originIdentityHash.all { it == 0.toByte() }) {
            return false
        }

        // Forged CUSTODY_ACK check (T-CUST-07):
        // Sender MUST be the next-hop node to which we handed off the packet
        if (record.nextHopNodeId != null && record.nextHopNodeId != senderNodeId) {
            return false // Third-party forged CUSTODY_ACK: DROP, custody retained
        }

        if (record.nextHopIdentityHash != null && senderIdentityHash != null) {
            if (!record.nextHopIdentityHash!!.contentEquals(senderIdentityHash)) {
                return false // Identity mismatch: DROP
            }
        }

        return if (record.role == CustodyRole.RELAY) {
            // Intermediate relay releases custody upon valid CUSTODY_ACK
            record.state = CustodyState.RELEASED
            records.remove(custodyMessageId)
            true
        } else {
            // Originator MUST NOT release custody on CUSTODY_ACK (Rule 19)
            // Remains HELD until end-to-end ACK or 24h expiry
            false
        }
    }

    /**
     * Handles an authenticated end-to-end ACK.
     * Releases custody for BOTH originator and intermediate relays.
     */
    @Synchronized
    fun handleEndToEndAck(messageId: String): Boolean {
        val record = records[messageId] ?: return false
        record.state = CustodyState.DELIVERED
        records.remove(messageId)
        return true
    }

    /**
     * Checks for expired custody records (reaching 24h lifetime without delivery proof).
     * Marks state as EXPIRED (T-CUST-06). Never silently dropped.
     */
    @Synchronized
    fun checkTimeouts(nowMs: Long = clock.nowMillis()): List<CustodyRecord> {
        val expired = mutableListOf<CustodyRecord>()
        val it = records.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            val record = entry.value
            if (record.state == CustodyState.HELD && nowMs >= record.expiresAtMs) {
                record.state = CustodyState.EXPIRED
                expired.add(record)
                it.remove()
            }
        }
        return expired
    }

    /**
     * Returns pending retries whose backoff has elapsed and are still within lifetime.
     */
    fun getPendingRetries(nowMs: Long = clock.nowMillis()): List<CustodyRecord> {
        return records.values.filter {
            it.state == CustodyState.HELD && nowMs >= it.nextRetryTimeMs && nowMs < it.expiresAtMs
        }
    }

    /**
     * Builds a wire-compliant CUSTODY_ACK (0x32) packet according to §3.5.
     * Fixed wire size: 180 bytes.
     */
    fun buildCustodyAckPacket(
        custodyMessageId: UUID,
        originIdentityHash: ByteArray,
        recipientNodeId64: Long,
        senderNodeId64: Long,
        publicChannelKey: ByteArray,
        signingPrivateKey: ByteArray,
        timestampSec: Long = clock.nowSeconds()
    ): ByteArray {
        require(originIdentityHash.size == 32) { "originIdentityHash must be 32 bytes" }

        // 48 bytes plaintext: custodyMessageId (16 B) || originIdentityHash (32 B)
        val plaintext = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN)
            .putLong(custodyMessageId.mostSignificantBits)
            .putLong(custodyMessageId.leastSignificantBits)
            .put(originIdentityHash)
            .array()

        val ackPacketId = UUID.randomUUID()
        val aad = MeshPacket.computeAad(
            type = PacketType.CUSTODY_ACK,
            messageId = ackPacketId,
            senderId = senderNodeId64,
            recipientId = recipientNodeId64,
            timestamp = timestampSec
        )

        val encResult = PureCryptoEngine.encrypt(
            plaintext = plaintext,
            messageId = ackPacketId,
            aesKey = publicChannelKey,
            aad = aad
        )
        val ciphertext = encResult.ciphertext // 12 IV + 48 ciphertext = 60 bytes
        val authTag = encResult.authTag       // 16 bytes

        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(ciphertext)
        md.update(authTag)
        val cipherHash = md.digest()

        val sigTranscript = MeshPacket.buildSigTranscript(
            purposeTag = com.meshwhisper.core.protocol.ResourceLimits.PURPOSE_CONTENT,
            protocolVersion = com.meshwhisper.core.protocol.ResourceLimits.PROTOCOL_VERSION.toByte(),
            packetTypeByte = PacketType.CUSTODY_ACK.wireByte,
            messageId = ackPacketId,
            senderIdentityHash = localIdentityHash,
            senderNodeId64 = senderNodeId64,
            recipientNodeId64 = recipientNodeId64,
            timestamp = timestampSec,
            payloadLenExcludingSig = ciphertext.size,
            ciphertextAndTagHash = cipherHash
        )
        val hopSig = PureCryptoEngine.sign(signingPrivateKey, sigTranscript) // 64 bytes

        // Payload: ciphertext (60 B) || hopSig (64 B) = 124 bytes
        val fullPayload = ByteArray(ciphertext.size + hopSig.size)
        System.arraycopy(ciphertext, 0, fullPayload, 0, ciphertext.size)
        System.arraycopy(hopSig, 0, fullPayload, ciphertext.size, hopSig.size)

        val packet = MeshPacket(
            type = PacketType.CUSTODY_ACK,
            messageId = ackPacketId,
            senderId = senderNodeId64,
            recipientId = recipientNodeId64,
            ttl = 1,
            timestamp = timestampSec,
            payload = fullPayload,
            authTag = authTag
        )

        return MeshPacket.serialize(packet)
    }

    fun getRecord(messageId: String): CustodyRecord? = records[messageId]

    fun getOwnRecordsCount(): Int = records.values.count { it.role == CustodyRole.ORIGINATOR && it.state == CustodyState.HELD }

    fun getRelayedRecordsCount(): Int = records.values.count { it.role == CustodyRole.RELAY && it.state == CustodyState.HELD }

    fun getTotalRecordsCount(): Int = records.size

    fun clear() {
        records.clear()
    }
}

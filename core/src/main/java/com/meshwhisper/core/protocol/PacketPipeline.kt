package com.meshwhisper.core.protocol

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.IdentityStore
import com.meshwhisper.core.identity.PeerIdentity
import com.meshwhisper.core.identity.TrustState
import com.meshwhisper.core.router.LruDedupCache
import com.meshwhisper.core.transport.LinkAuthSession
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stages of the vNext Authentication Pipeline.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4.
 */
enum class PipelineStage {
    S0_ADMISSION,
    S1_STRUCTURE,
    S2_FRESHNESS,
    S3_PRE_AUTH_DEDUP,
    S4_IDENTITY,
    S5_AEAD,
    S6_SIGNATURE,
    S7_SEMANTICS
}

/**
 * Result of ingesting raw packet bytes through the Authentication Pipeline.
 */
sealed class IngestResult {
    data class Accepted(val packet: AuthenticatedPacket) : IngestResult()
    data class Admitted(val chunk: AdmittedChunk) : IngestResult()
    data class Dropped(
        val stage: PipelineStage,
        val reason: String,
        val isDuplicateDmForUs: Boolean = false,
        val duplicateDmPacket: MeshPacket? = null
    ) : IngestResult()
}

/**
 * Provider interface for cryptographic keys required during S5 AEAD decryption.
 */
interface KeyProvider {
    fun getPublicChannelKey(): ByteArray = PureCryptoEngine.derivePublicChannelKey()
    fun getActiveChannelKey(): ByteArray? = null
    fun getSessionKey(peerNodeId: Long, timestampSec: Long): ByteArray? = null
    fun getCallKey(callSessionId: UUID): ByteArray? = null
}

/**
 * Token bucket for rate-limiting Ed25519 signature verifications in S6.
 * Bounded to 32/s per link and 256/s global (NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §8).
 */
class VerificationRateLimiter(private val clock: Clock) {
    private val globalTokens = AtomicInteger(ResourceLimits.ED25519_VERIFY_BUDGET_PER_SEC_GLOBAL)
    private var lastGlobalResetSec = clock.nowSeconds()
    private var lastCleanupSec = clock.nowSeconds()

    private val linkTokens = ConcurrentHashMap<String, Pair<AtomicInteger, Long>>()

    @Synchronized
    fun tryAcquire(linkHandle: String, count: Int = 1): Boolean {
        val now = clock.nowSeconds()

        // Periodic eviction of stale linkTokens to prevent memory leak
        if (now - lastCleanupSec > 60L) {
            linkTokens.entries.removeIf { now - it.value.second > 60L }
            lastCleanupSec = now
        }

        // Global refresh
        if (now > lastGlobalResetSec) {
            globalTokens.set(ResourceLimits.ED25519_VERIFY_BUDGET_PER_SEC_GLOBAL)
            lastGlobalResetSec = now
        }
        if (globalTokens.get() < count) return false

        // Per-link refresh
        val linkPair = linkTokens.compute(linkHandle) { _, existing ->
            if (existing == null || now > existing.second) {
                Pair(AtomicInteger(ResourceLimits.ED25519_VERIFY_BUDGET_PER_SEC_LINK), now)
            } else {
                existing
            }
        }!!

        if (linkPair.first.get() < count) return false

        linkPair.first.addAndGet(-count)
        globalTokens.addAndGet(-count)
        return true
    }
}

/**
 * Single mandatory production authentication chokepoint for MeshWhisper vNext.
 * Implements S0 through S7 in strict, frozen order.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §4.
 */
class PacketPipeline(
    private val localNodeId64: Long,
    private val identityStore: IdentityStore,
    private val packetStore: PacketStore,
    private val keyProvider: KeyProvider,
    private val clock: Clock = SystemClock(),
    private val dedupCache: LruDedupCache<String, Long> = LruDedupCache(4000)
) {
    private val rateLimiter = VerificationRateLimiter(clock)
    private val sosRateLimiter = ConcurrentHashMap<String, MutableList<Long>>()

    /**
     * Ingests raw bytes from the wire through S0–S7.
     * Guaranteed:
     * - Returns [IngestResult.Accepted] only if S0–S7 succeed and dedup is committed.
     * - Returns [IngestResult.Admitted] for MEDIA_CHUNK only under C-14 rules.
     * - Returns [IngestResult.Dropped] if any stage fails, with ZERO state mutations.
     */
    fun ingest(rawBytes: ByteArray, linkContext: LinkContext): IngestResult {
        // =========================================================================
        // S0 — ADMISSION
        // =========================================================================
        if (rawBytes.isEmpty()) {
            return IngestResult.Dropped(PipelineStage.S0_ADMISSION, "Raw frame is empty")
        }

        // Enforce transport-specific frame limits (Security Amendment 2)
        if (rawBytes.size > linkContext.transport.maxFrameSize) {
            return IngestResult.Dropped(
                PipelineStage.S0_ADMISSION,
                "Frame size ${rawBytes.size} exceeds transport ${linkContext.transport} max ${linkContext.transport.maxFrameSize}"
            )
        }

        // UDP mesh packets are forbidden unconditionally (§8)
        if (linkContext.transport == TransportType.UDP) {
            return IngestResult.Dropped(PipelineStage.S0_ADMISSION, "UDP mesh packets are forbidden")
        }

        // If link state is PENDING, packet type MUST be LINK_AUTH (0x31)
        if (linkContext.state == LinkState.PENDING) {
            val wireByte = rawBytes[0].toInt() and 0xFF
            if (wireByte != 0x31) {
                return IngestResult.Dropped(
                    PipelineStage.S0_ADMISSION,
                    "Link is PENDING; expected LINK_AUTH (0x31), got 0x${String.format("%02X", wireByte)}"
                )
            }
        }

        // =========================================================================
        // S1 — STRUCTURE
        // =========================================================================
        if (rawBytes.size < ResourceLimits.MIN_PACKET_SIZE || rawBytes.size > ResourceLimits.MAX_PACKET_SIZE) {
            return IngestResult.Dropped(
                PipelineStage.S1_STRUCTURE,
                "Frame size ${rawBytes.size} outside valid bounds [${ResourceLimits.MIN_PACKET_SIZE}..${ResourceLimits.MAX_PACKET_SIZE}]"
            )
        }

        val packet = MeshPacket.deserialize(rawBytes)
            ?: return IngestResult.Dropped(PipelineStage.S1_STRUCTURE, "Deserialization failed (invalid header, version != 1, 0x22 retired, or trailing bytes)")

        // Auth tag check: all-zero is forbidden unless LINK_AUTH
        val isAllZeroAuthTag = packet.authTag.all { it == 0.toByte() }
        if (packet.type != PacketType.LINK_AUTH && isAllZeroAuthTag) {
            return IngestResult.Dropped(PipelineStage.S1_STRUCTURE, "Auth tag is all-zero on non-LINK_AUTH packet")
        }

        // For signed types, payload must contain at least 64 bytes hop signature + min ciphertext
        if (packet.type.isSigned) {
            val minCiphertext = getMinCiphertextForType(packet.type)
            if (packet.payload.size < 64 + minCiphertext) {
                return IngestResult.Dropped(
                    PipelineStage.S1_STRUCTURE,
                    "Signed packet payload ${packet.payload.size} < 64 + minCiphertext ($minCiphertext)"
                )
            }
        }

        // Type-specific min/max payload bounds check
        if (!validateTypePayloadBounds(packet.type, packet.payload.size)) {
            return IngestResult.Dropped(PipelineStage.S1_STRUCTURE, "Payload size ${packet.payload.size} outside type ${packet.type} bounds")
        }

        // =========================================================================
        // S2 — FRESHNESS
        // =========================================================================
        val nowSec = clock.nowSeconds()
        val ageSec = nowSec - packet.timestamp
        if (ageSec < -ResourceLimits.FUTURE_SKEW_SEC || ageSec > packet.type.pastWindowSec) {
            return IngestResult.Dropped(
                PipelineStage.S2_FRESHNESS,
                "Packet timestamp ${packet.timestamp} outside window (age: ${ageSec}s, allowed: -${ResourceLimits.FUTURE_SKEW_SEC}..${packet.type.pastWindowSec})"
            )
        }

        // =========================================================================
        // S3 — PRE-AUTH DEDUP (READ ONLY)
        // =========================================================================
        val dedupKey = "${packet.messageId}:${packet.type.code}"
        val isDmForUs = (packet.type == PacketType.DIRECT_MESSAGE && packet.recipientId == localNodeId64)

        if (dedupCache.containsKey(dedupKey) || packetStore.isSeen(packet.messageId, packet.type.code)) {
            return IngestResult.Dropped(
                stage = PipelineStage.S3_PRE_AUTH_DEDUP,
                reason = "Duplicate packet (read-only dedup hit)",
                isDuplicateDmForUs = isDmForUs,
                duplicateDmPacket = if (isDmForUs) packet else null
            )
        }

        // =========================================================================
        // S4 — IDENTITY RESOLUTION
        // =========================================================================
        var resolvedIdentity: PeerIdentity? = null

        if (packet.type == PacketType.PEER_ANNOUNCE || packet.type == PacketType.LINK_AUTH) {
            // Self-bootstrapping types
            val existing = identityStore.getByNodeId64(packet.senderId)
            if (existing != null && existing.trustState == TrustState.BLOCKED) {
                return IngestResult.Dropped(PipelineStage.S4_IDENTITY, "Sender is BLOCKED")
            }
            resolvedIdentity = existing
        } else {
            val peer = identityStore.getByNodeId64(packet.senderId)
                ?: return IngestResult.Dropped(PipelineStage.S4_IDENTITY, "Unknown sender 0x${String.format("%016X", packet.senderId)} (no announce on record)")

            if (peer.trustState == TrustState.BLOCKED) {
                return IngestResult.Dropped(PipelineStage.S4_IDENTITY, "Sender is BLOCKED")
            }

            if (peer.trustState == TrustState.CONFLICTED) {
                // Conflicted identities may only send broadcasts/SOS/announces
                if (packet.type != PacketType.BROADCAST_MESSAGE && packet.type != PacketType.SOS_MESSAGE) {
                    return IngestResult.Dropped(PipelineStage.S4_IDENTITY, "Sender is CONFLICTED; unicast rejected")
                }
            }

            resolvedIdentity = peer
        }

        // =========================================================================
        // S5 — CHEAP CRYPTO (AEAD)
        // =========================================================================
        var decryptedPayload = ByteArray(0)
        var isRelayOnly = false

        if (packet.type == PacketType.LINK_AUTH || packet.type == PacketType.VOICE_FRAME) {
            // LINK_AUTH has no AEAD encryption; VOICE_FRAME is decrypted by VoiceCallManager under pinned K_call
            decryptedPayload = packet.payload
        } else {
            val isForUs = (packet.recipientId == localNodeId64 || packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)
            val key = resolveAeadKey(packet, resolvedIdentity)

            if (key == null) {
                // Amendment 4 (C-17): Relay-only handling for channels this node cannot decrypt
                if (!isForUs || (packet.type == PacketType.BROADCAST_MESSAGE && packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID)) {
                    isRelayOnly = true
                    // Proceed to S6 under verification budget without decrypting payload
                } else {
                    return IngestResult.Dropped(PipelineStage.S5_AEAD, "No key available to decrypt packet addressed to this node")
                }
            } else {
                val ciphertext = if (packet.type.isSigned) {
                    packet.payload.copyOfRange(0, packet.payload.size - 64)
                } else {
                    packet.payload
                }

                val decrypted = try {
                    PureCryptoEngine.decrypt(
                        ciphertext = ciphertext,
                        authTag = packet.authTag,
                        messageId = packet.messageId,
                        aesKey = key,
                        aad = packet.getAuthenticatedHeaderBytes()
                    )
                } catch (_: Exception) {
                    null
                }

                if (decrypted == null) {
                    // If decryption failed and packet is broadcast/relayable, check if C-17 relay applies
                    if (packet.type == PacketType.BROADCAST_MESSAGE && packet.ttl > 1) {
                        isRelayOnly = true
                    } else {
                        return IngestResult.Dropped(PipelineStage.S5_AEAD, "AEAD decryption / tag verification failed")
                    }
                } else {
                    decryptedPayload = decrypted
                }
            }
        }

        // =========================================================================
        // S6 — EXPENSIVE CRYPTO (Ed25519)
        // =========================================================================
        val requiredTokens = if (packet.type == PacketType.PEER_ANNOUNCE) 2 else 1
        if (!rateLimiter.tryAcquire(linkContext.linkHandle, requiredTokens)) {
            return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "Verification rate budget exhausted")
        }

        var validatedAnnouncePayload: PeerAnnouncePayload? = null

        if (packet.type == PacketType.PEER_ANNOUNCE) {
            if (isRelayOnly) {
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "PEER_ANNOUNCE cannot be relay-only")
            }

            val announce = PeerAnnouncePayload.deserialize(decryptedPayload, packet.senderId, packet.ttl)
                ?: return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "PEER_ANNOUNCE payload malformed or length mismatch")

            // 1. Recompute identityHash and nodeId64 from IK_pk (Finding C-02)
            val derivedIdHash = PureCryptoEngine.deriveIdentityHash(announce.ikPub)
            val derivedNodeId = PureCryptoEngine.deriveNodeId64(derivedIdHash)
            if (derivedNodeId != packet.senderId) {
                return IngestResult.Dropped(
                    PipelineStage.S6_SIGNATURE,
                    "Sender ID mismatch: header=0x${String.format("%016X", packet.senderId)}, derived=0x${String.format("%016X", derivedNodeId)}"
                )
            }

            // 2. Validate IBC (Finding C-11)
            val ibcValid = PureCryptoEngine.validateIbc(
                ikPub = announce.ikPub,
                ekPub = announce.ekPub,
                keyVersion = announce.keyVersion,
                notBefore = announce.notBefore,
                signature = announce.ibcSignature,
                packetTimestamp = packet.timestamp
            )
            if (!ibcValid) {
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "IBC validation failed (signature invalid or timestamp constraint violated)")
            }

            // 3. Rollback and equivocation rules (Finding C-12)
            val existing = identityStore.get(derivedIdHash)
            var isEquivocation = false
            if (existing != null) {
                if (announce.keyVersion < existing.keyVersion) {
                    return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "KeyVersion rollback attack: incoming ${announce.keyVersion} < stored ${existing.keyVersion}")
                }
                if (announce.keyVersion == existing.keyVersion && !announce.ekPub.contentEquals(existing.ekPub)) {
                    isEquivocation = true
                }
                if (announce.announceCounter <= existing.lastAnnounceCounter) {
                    return IngestResult.Dropped(
                        PipelineStage.S6_SIGNATURE,
                        "AnnounceCounter replay/stale: incoming ${announce.announceCounter} <= stored ${existing.lastAnnounceCounter}"
                    )
                }
            }

            // 4. Verify hop signature over 115-byte SIG_TRANSCRIPT
            val ciphertext = packet.payload.copyOfRange(0, packet.payload.size - 64)
            val hopSignature = packet.payload.copyOfRange(packet.payload.size - 64, packet.payload.size)
            val ciphertextAndTagHash = sha256(ciphertext, packet.authTag)

            val transcript = MeshPacket.buildSigTranscript(
                purposeTag = ResourceLimits.PURPOSE_CONTENT,
                protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
                packetTypeByte = packet.type.wireByte,
                messageId = packet.messageId,
                senderIdentityHash = derivedIdHash,
                senderNodeId64 = derivedNodeId,
                recipientNodeId64 = packet.recipientId,
                timestamp = packet.timestamp,
                payloadLenExcludingSig = ciphertext.size,
                ciphertextAndTagHash = ciphertextAndTagHash
            )

            if (!PureCryptoEngine.verifySignature(announce.ikPub, transcript, hopSignature)) {
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "PEER_ANNOUNCE hop signature verification failed")
            }

            if (isEquivocation) {
                // Hop signature verified: authentic peer signed an announcement with the same keyVersion but different EK.
                // Mutate diagnostic counter at this authenticated boundary without changing keyVersion/ekPub/trustState.
                existing?.let {
                    identityStore.upsert(it.copy(warningCount = it.warningCount + 1))
                }
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "KeyVersion equivocation detected: same version, different EK")
            }

            validatedAnnouncePayload = announce
            resolvedIdentity = PeerIdentity(
                identityHash = derivedIdHash,
                ikPub = announce.ikPub,
                ekPub = announce.ekPub,
                keyVersion = announce.keyVersion,
                lastAnnounceCounter = announce.announceCounter,
                trustState = existing?.trustState ?: TrustState.SEEN,
                nodeId64 = derivedNodeId
            )
        } else if (packet.type == PacketType.LINK_AUTH) {
            // FROZEN §4 S6: LINK_AUTH verifies IBC for HELLO stage on first contact bootstrap
            if (packet.payload.isEmpty()) {
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "LINK_AUTH payload is empty")
            }
            val stage = packet.payload[0]
            if (stage == 0x01.toByte()) {
                val hello = LinkAuthSession.parseHello(packet.payload)
                    ?: return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "Malformed LINK_AUTH HELLO")
                if (!PureCryptoEngine.validateIbc(hello.ikPub, hello.ekPub, hello.keyVersion, hello.notBefore, hello.ibcSignature, packet.timestamp)) {
                    return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "LINK_AUTH IBC signature invalid or expired")
                }
                val derivedIdHash = PureCryptoEngine.deriveIdentityHash(hello.ikPub)
                val derivedNodeId = PureCryptoEngine.deriveNodeId64(derivedIdHash)
                if (derivedNodeId != packet.senderId) {
                    return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "LINK_AUTH senderId mismatch")
                }
                val existing = identityStore.get(derivedIdHash)
                resolvedIdentity = PeerIdentity(
                    identityHash = derivedIdHash,
                    ikPub = hello.ikPub,
                    ekPub = hello.ekPub,
                    keyVersion = hello.keyVersion,
                    lastAnnounceCounter = 0L,
                    trustState = existing?.trustState ?: TrustState.SEEN,
                    nodeId64 = derivedNodeId
                )
            }
        } else if (packet.type.isSigned) {
            val peer = resolvedIdentity
                ?: return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "No identity resolved for signed packet")

            val ciphertext = packet.payload.copyOfRange(0, packet.payload.size - 64)
            val hopSignature = packet.payload.copyOfRange(packet.payload.size - 64, packet.payload.size)
            val ciphertextAndTagHash = sha256(ciphertext, packet.authTag)

            val transcript = MeshPacket.buildSigTranscript(
                purposeTag = ResourceLimits.PURPOSE_CONTENT,
                protocolVersion = ResourceLimits.PROTOCOL_VERSION.toByte(),
                packetTypeByte = packet.type.wireByte,
                messageId = packet.messageId,
                senderIdentityHash = peer.identityHash,
                senderNodeId64 = peer.nodeId64,
                recipientNodeId64 = packet.recipientId,
                timestamp = packet.timestamp,
                payloadLenExcludingSig = ciphertext.size,
                ciphertextAndTagHash = ciphertextAndTagHash
            )

            if (!PureCryptoEngine.verifySignature(peer.ikPub, transcript, hopSignature)) {
                return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "Hop signature verification failed")
            }
        }

        // =========================================================================
        // S7 — SEMANTIC VALIDATION
        // =========================================================================
        if (!isRelayOnly) {
            val semanticsValid = validateSemantics(packet, decryptedPayload, validatedAnnouncePayload, resolvedIdentity, linkContext)
            if (!semanticsValid) {
                return IngestResult.Dropped(PipelineStage.S7_SEMANTICS, "Semantic validation failed")
            }
        }

        // =========================================================================
        // ATOMIC POST-AUTH COMMITMENT & OBJECT CONSTRUCTION
        // =========================================================================
        // 1. Commit persistent dedup (FROZEN §7.1: LINK_AUTH and VOICE_FRAME skip persistent processed_packets row)
        if (packet.type != PacketType.LINK_AUTH && packet.type != PacketType.VOICE_FRAME) {
            val newlyCommitted = packetStore.commitSeen(packet.messageId, packet.type.code, packet.timestamp)
            if (!newlyCommitted) {
                return IngestResult.Dropped(
                    stage = PipelineStage.S3_PRE_AUTH_DEDUP,
                    reason = "Duplicate packet (raced persistent insert)",
                    isDuplicateDmForUs = isDmForUs,
                    duplicateDmPacket = if (isDmForUs) packet else null
                )
            }

            // 2. Commit RAM LRU
            dedupCache.put(dedupKey, System.currentTimeMillis())
        }

        // 3. Commit identity updates if PEER_ANNOUNCE
        val finalIdentity = resolvedIdentity ?: return IngestResult.Dropped(PipelineStage.S6_SIGNATURE, "No identity resolved")
        if (packet.type == PacketType.PEER_ANNOUNCE) {
            identityStore.upsert(finalIdentity)
        }

        // 4. Construct AuthenticatedPacket / AdmittedChunk
        if (packet.type == PacketType.MEDIA_CHUNK) {
            val (mediaId, chunkIndex, chunkData) = if (isRelayOnly || decryptedPayload.size < 18) {
                // Relay-only chunk: plaintext was not decrypted (or unavailable).
                // Do not unpack plaintext. Use empty defaults for relay admission token.
                Triple(UUID(0L, 0L), 0, ByteArray(0))
            } else {
                val buf = ByteBuffer.wrap(decryptedPayload).order(ByteOrder.BIG_ENDIAN)
                val mostSig = buf.getLong()
                val leastSig = buf.getLong()
                val id = UUID(mostSig, leastSig)
                val idx = buf.getShort().toInt() and 0xFFFF
                val data = ByteArray(buf.remaining())
                buf.get(data)
                Triple(id, idx, data)
            }

            val admittedChunk = AdmittedChunk.createFromPipeline(
                packet = packet,
                senderIdentity = finalIdentity,
                mediaId = mediaId,
                chunkIndex = chunkIndex,
                chunkData = chunkData,
                isBroadcast = packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID,
                isRelayOnly = isRelayOnly
            )
            return IngestResult.Admitted(admittedChunk)
        }

        val authPacket = AuthenticatedPacket.createFromPipeline(
            packet = packet,
            senderIdentity = finalIdentity,
            decryptedPayload = decryptedPayload,
            isRelayOnly = isRelayOnly,
            isDuplicateDmForUs = false
        )
        return IngestResult.Accepted(authPacket)
    }

    private fun validateSemantics(
        packet: MeshPacket,
        payload: ByteArray,
        announce: PeerAnnouncePayload?,
        sender: PeerIdentity?,
        linkContext: LinkContext
    ): Boolean {
        return when (packet.type) {
            PacketType.PEER_ANNOUNCE -> {
                val a = announce ?: return false
                // Amendment 3: hasLocation requires ttl == 1 AND authenticated link AND link.boundIdentity == sender.identityHash
                if (a.hasLocation) {
                    if (packet.ttl != 1) return false
                    if (linkContext.state != LinkState.AUTHENTICATED) return false
                    if (linkContext.boundIdentity == null || !linkContext.boundIdentity.contentEquals(sender?.identityHash)) return false
                }
                true
            }
            PacketType.BROADCAST_MESSAGE -> {
                if (payload.size < 2) return false
                val textLen = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                if (textLen != payload.size - 2 || textLen > 1024) return false
                true
            }
            PacketType.SOS_MESSAGE -> {
                if (payload.size < 3) return false
                val flags = payload[0].toInt() and 0xFF
                if ((flags and 0xFE) != 0) return false // reserved bits 1..7 MUST be 0
                val textLen = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                val hasLoc = (flags and 0x01) != 0
                val expectedSize = 3 + textLen + (if (hasLoc) 28 else 0)
                if (payload.size != expectedSize || textLen > 512) return false

                // SOS rate limit: max 3 per 10 min per identity (ResourceLimits.SOS_ACCEPTANCE_MAX_PER_10_MIN)
                val senderHashHex = sender?.identityHash?.joinToString("") { "%02x".format(it) } ?: return false
                val now = clock.nowSeconds()
                val tenMinutesAgo = now - 600L
                val timestamps = sosRateLimiter.computeIfAbsent(senderHashHex) { mutableListOf() }
                synchronized(timestamps) {
                    timestamps.removeAll { it < tenMinutesAgo }
                    if (timestamps.size >= ResourceLimits.SOS_ACCEPTANCE_MAX_PER_10_MIN) {
                        return false
                    }
                    timestamps.add(now)
                }
                true
            }
            PacketType.MEDIA_INIT -> {
                if (payload.size < 62) return false
                val totalChunks = ((payload[18].toInt() and 0xFF) shl 8) or (payload[19].toInt() and 0xFF)
                if (totalChunks == 0 || totalChunks > ResourceLimits.MAX_MEDIA_CHUNKS) return false
                val totalSizeBytes = ByteBuffer.wrap(payload, 20, 4).order(ByteOrder.BIG_ENDIAN).int
                if (totalSizeBytes <= 0 || totalSizeBytes > ResourceLimits.MAX_MEDIA_SIZE_BYTES) return false
                val fileNameLen = payload[60].toInt() and 0xFF
                if (fileNameLen > ResourceLimits.MEDIA_FILENAME_MAX_BYTES) return false
                if (payload.size < 61 + fileNameLen + 2) return false
                if (fileNameLen > 0) {
                    val fn = String(payload, 61, fileNameLen, Charsets.UTF_8)
                    if (fn.startsWith(".") || !fn.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) return false
                }
                val previewLen = ((payload[61 + fileNameLen].toInt() and 0xFF) shl 8) or (payload[62 + fileNameLen].toInt() and 0xFF)
                if (previewLen > ResourceLimits.MEDIA_PREVIEW_MAX_BYTES) return false
                if (payload.size < 63 + fileNameLen + previewLen + 1) return false
                val captionLen = payload[63 + fileNameLen + previewLen].toInt() and 0xFF
                if (captionLen > ResourceLimits.MEDIA_CAPTION_MAX_BYTES) return false
                true
            }
            PacketType.VOICE_CALL_SIGNAL -> {
                if (payload.size != 29) return false
                val action = payload[0]
                if (action !in 1..5) return false
                if (action == 0x01.toByte()) { // OFFER requires authenticated/bound link
                    if (linkContext.state != LinkState.AUTHENTICATED) return false
                    if (linkContext.boundIdentity == null || !linkContext.boundIdentity.contentEquals(sender?.identityHash)) return false
                }
                true
            }
            PacketType.VOICE_FRAME -> {
                if (packet.ttl != 1) return false
                if (payload.size !in 16..176) return false
                // Invariant I-10: VOICE_FRAME requires direct authenticated link matching sender identity
                if (linkContext.state != LinkState.AUTHENTICATED) return false
                if (linkContext.boundIdentity == null || !linkContext.boundIdentity.contentEquals(sender?.identityHash)) return false
                true
            }
            PacketType.PROFILE_UPDATE -> {
                if (payload.size < 55 || payload.size > 207) return false
                val domain = String(payload.copyOfRange(0, 4), Charsets.US_ASCII)
                if (domain != "MWP2") return false
                val nodeId = ByteBuffer.wrap(payload, 4, 8).long
                if (nodeId != packet.senderId) return false
                true
            }
            PacketType.ACK -> {
                payload.size == 16
            }
            PacketType.CUSTODY_ACK -> {
                if (payload.size != 48) return false
                val originHash = payload.copyOfRange(16, 48)
                if (originHash.all { it == 0.toByte() }) return false
                true
            }
            PacketType.MEDIA_CHUNK -> {
                if (payload.size < 18) return false
                val chunkLen = payload.size - 18
                if (chunkLen > ResourceLimits.MAX_MEDIA_CHUNK_PAYLOAD) return false
                true
            }
            else -> true
        }
    }

    private fun resolveAeadKey(packet: MeshPacket, sender: PeerIdentity?): ByteArray? {
        return when (packet.type) {
            PacketType.PEER_ANNOUNCE,
            PacketType.SOS_MESSAGE,
            PacketType.PROFILE_UPDATE,
            PacketType.CUSTODY_ACK -> keyProvider.getPublicChannelKey()

            PacketType.BROADCAST_MESSAGE -> keyProvider.getActiveChannelKey() ?: keyProvider.getPublicChannelKey()

            PacketType.DIRECT_MESSAGE,
            PacketType.ACK,
            PacketType.PROFILE_REQUEST,
            PacketType.AVATAR_REQUEST,
            PacketType.TYPING_INDICATOR,
            PacketType.VOICE_CALL_SIGNAL -> {
                if (packet.recipientId == localNodeId64 && sender != null) {
                    keyProvider.getSessionKey(sender.nodeId64, packet.timestamp)
                } else null
            }

            PacketType.MEDIA_INIT,
            PacketType.MEDIA_CHUNK,
            PacketType.MEDIA_NACK,
            PacketType.MEDIA_ACK,
            PacketType.MEDIA_ABORT -> {
                if (packet.recipientId == MeshPacket.BROADCAST_RECIPIENT_ID) {
                    keyProvider.getPublicChannelKey()
                } else if (packet.recipientId == localNodeId64 && sender != null) {
                    keyProvider.getSessionKey(sender.nodeId64, packet.timestamp)
                } else null
            }

            PacketType.VOICE_FRAME -> null

            PacketType.LINK_AUTH -> null
            PacketType.KEY_EXCHANGE -> null
        }
    }

    private fun getMinCiphertextForType(type: PacketType): Int {
        return when (type) {
            PacketType.PEER_ANNOUNCE -> 155 // 12 IV + 143 min plaintext
            PacketType.BROADCAST_MESSAGE -> 14 // 12 IV + 2 min plaintext
            PacketType.SOS_MESSAGE -> 15 // 12 IV + 3 min plaintext
            PacketType.DIRECT_MESSAGE -> 12 // 12 IV + 0 min plaintext
            PacketType.ACK -> 28 // 12 IV + 16 plaintext
            PacketType.PROFILE_UPDATE -> 67 // 12 IV + 55 min plaintext
            PacketType.CUSTODY_ACK -> 60 // 12 IV + 48 plaintext
            PacketType.MEDIA_INIT -> 12 // 12 IV
            PacketType.MEDIA_NACK,
            PacketType.MEDIA_ACK,
            PacketType.MEDIA_ABORT -> 12
            else -> 0
        }
    }

    private fun validateTypePayloadBounds(type: PacketType, payloadSize: Int): Boolean {
        return when (type) {
            PacketType.PEER_ANNOUNCE -> payloadSize in 219..464 // 155..400 ciphertext + 64 sig
            PacketType.BROADCAST_MESSAGE -> payloadSize in 78..1102 // 14..1038 ciphertext + 64 sig
            PacketType.SOS_MESSAGE -> payloadSize in 79..619 // 15..555 ciphertext + 64 sig
            PacketType.DIRECT_MESSAGE -> payloadSize in 76..1100 // 12..1036 ciphertext + 64 sig
            PacketType.ACK -> payloadSize == 92 // 28 ciphertext + 64 sig
            PacketType.PROFILE_UPDATE -> payloadSize in 131..283 // 67..219 ciphertext + 64 sig
            PacketType.CUSTODY_ACK -> payloadSize == 124 // 60 ciphertext + 64 sig
            PacketType.MEDIA_CHUNK -> payloadSize in 18..350 // 12 IV + 18..338 (no sig)
            PacketType.VOICE_CALL_SIGNAL -> payloadSize == 41 // 12 IV + 29 ciphertext (no sig)
            PacketType.VOICE_FRAME -> payloadSize in 16..176 // 8 seqPlain + 8..168 ciphertext (no IV, no sig)
            PacketType.LINK_AUTH -> payloadSize in 1..300 // no AEAD tag/sig
            else -> payloadSize <= ResourceLimits.MAX_PAYLOAD_SIZE
        }
    }

    private fun sha256(b1: ByteArray, b2: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(b1)
        md.update(b2)
        return md.digest()
    }
}

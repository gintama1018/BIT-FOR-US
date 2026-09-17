package com.meshwhisper.core.transport

import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import com.meshwhisper.core.protocol.ResourceLimits
import com.meshwhisper.core.util.Clock
import com.meshwhisper.core.util.SystemClock
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Arrays
import java.util.UUID

/**
 * LINK_AUTH Stage 0x01 HELLO payload (168 bytes excluding stage byte).
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4:
 *   nonce          32
 *   IK_pk          32
 *   EK_pk          32
 *   keyVersion      4
 *   notBefore       4
 *   ibcSignature   64
 */
data class HelloPayload(
    val nonce: ByteArray,
    val ikPub: ByteArray,
    val ekPub: ByteArray,
    val keyVersion: Long,
    val notBefore: Long,
    val ibcSignature: ByteArray
) {
    init {
        require(nonce.size == 32) { "nonce must be 32 bytes" }
        require(ikPub.size == 32) { "ikPub must be 32 bytes" }
        require(ekPub.size == 32) { "ekPub must be 32 bytes" }
        require(keyVersion in 1L..0xFFFFFFFFL) { "keyVersion must be in 1..0xFFFFFFFF, got $keyVersion" }
        require(notBefore in 0L..0xFFFFFFFFL) { "notBefore must be in 0..0xFFFFFFFF, got $notBefore" }
        require(ibcSignature.size == 64) { "ibcSignature must be 64 bytes" }
        require(!nonce.all { it == 0.toByte() }) { "nonce MUST NOT be all-zero (FROZEN §3.4)" }
    }

    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(168)
        buf.put(nonce)
        buf.put(ikPub)
        buf.put(ekPub)
        buf.putInt(keyVersion.toInt())
        buf.putInt(notBefore.toInt())
        buf.put(ibcSignature)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as HelloPayload
        return nonce.contentEquals(other.nonce) &&
                ikPub.contentEquals(other.ikPub) &&
                ekPub.contentEquals(other.ekPub) &&
                keyVersion == other.keyVersion &&
                notBefore == other.notBefore &&
                ibcSignature.contentEquals(other.ibcSignature)
    }

    override fun hashCode(): Int {
        var result = nonce.contentHashCode()
        result = 31 * result + ikPub.contentHashCode()
        result = 31 * result + ekPub.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + notBefore.hashCode()
        result = 31 * result + ibcSignature.contentHashCode()
        return result
    }
}

/**
 * LINK_AUTH Stage 0x02 CONFIRM payload (64 bytes excluding stage byte).
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4:
 *   confirmSig     64
 */
data class ConfirmPayload(
    val confirmSig: ByteArray
) {
    init {
        require(confirmSig.size == 64) { "confirmSig must be 64 bytes" }
    }

    fun toByteArray(): ByteArray = confirmSig.clone()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as ConfirmPayload
        return confirmSig.contentEquals(other.confirmSig)
    }

    override fun hashCode(): Int = confirmSig.contentHashCode()
}

/**
 * Local cryptographic credentials needed for LINK_AUTH handshakes.
 */
data class LinkAuthLocalCredentials(
    val identitySeed: ByteArray, // master seed S (32 bytes)
    val ikPub: ByteArray,        // Ed25519 public key (32 bytes)
    val ekPub: ByteArray,        // X25519 public key (32 bytes)
    val ekPriv: ByteArray,       // X25519 private key EK_sk == S (32 bytes)
    val keyVersion: Long,
    val notBefore: Long,
    val ibcSignature: ByteArray
) {
    val identityHash: ByteArray = PureCryptoEngine.deriveIdentityHash(ikPub)
    val nodeId64: Long = PureCryptoEngine.deriveNodeId64(identityHash)

    init {
        require(identitySeed.size == 32)
        require(ikPub.size == 32)
        require(ekPub.size == 32)
        require(ekPriv.size == 32)
        require(ibcSignature.size == 64)
        require(keyVersion in 1L..0xFFFFFFFFL)
        require(notBefore in 0L..0xFFFFFFFFL)
    }

    companion object {
        fun create(
            identitySeed: ByteArray,
            keyVersion: Long = 1L,
            notBefore: Long = 0L
        ): LinkAuthLocalCredentials {
            val ekPub = PureCryptoEngine.derivePublicKey(identitySeed)
            return create(identitySeed, ekPub, keyVersion, notBefore)
        }

        fun create(
            identitySeed: ByteArray,
            ekPub: ByteArray,
            keyVersion: Long = 1L,
            notBefore: Long = 0L
        ): LinkAuthLocalCredentials {
            val ikPub = PureCryptoEngine.deriveSigningPublicKey(identitySeed)
            val ibcSig = PureCryptoEngine.signIbc(identitySeed, ekPub, keyVersion, notBefore)
            return LinkAuthLocalCredentials(
                identitySeed = identitySeed,
                ikPub = ikPub,
                ekPub = ekPub,
                ekPriv = identitySeed,
                keyVersion = keyVersion,
                notBefore = notBefore,
                ibcSignature = ibcSig
            )
        }
    }
}

/**
 * An unforgeable token proving successful LINK_AUTH completion.
 * Can only be instantiated internally by LinkAuthSession when AUTHENTICATED state is reached.
 */
class LinkAuthProof private constructor(
    val linkHandle: String,
    val peerIdentityHash: ByteArray,
    val peerNodeId64: Long,
    val linkKey: ByteArray
) {
    companion object {
        internal fun create(
            linkHandle: String,
            peerIdentityHash: ByteArray,
            peerNodeId64: Long,
            linkKey: ByteArray
        ): LinkAuthProof {
            return LinkAuthProof(linkHandle, peerIdentityHash, peerNodeId64, linkKey)
        }
    }
}

/**
 * Result of ingesting a LINK_AUTH packet into LinkAuthSession.
 */
sealed class LinkAuthStepResult {
    data class Completed(val proof: LinkAuthProof) : LinkAuthStepResult()
    object InProgress : LinkAuthStepResult()
    data class Failed(val reason: String) : LinkAuthStepResult()
}

/**
 * Explicit state machine states for LINK_AUTH.
 */
enum class LinkAuthState {
    IDLE,
    HELLO_SENT,
    HELLO_RECEIVED,
    HELLO_EXCHANGED,
    CONFIRM_SENT,
    CONFIRM_RECEIVED,
    AUTHENTICATED,
    FAILED
}

/**
 * State machine and codec orchestrator for LINK_AUTH transport handshakes.
 * Defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4 and C-09/C-10.
 */
class LinkAuthSession(
    val linkHandle: String,
    val localCredentials: LinkAuthLocalCredentials,
    val clock: Clock = SystemClock(),
    private val secureRandom: SecureRandom = SecureRandom()
) {
    var state: LinkAuthState = LinkAuthState.IDLE
        private set

    var localNonce: ByteArray? = null
        private set

    var remoteHello: HelloPayload? = null
        private set

    var remoteIdentityHash: ByteArray? = null
        private set

    var remoteNodeId64: Long? = null
        private set

    var transcriptT: ByteArray? = null
        private set

    var linkKey: ByteArray? = null
        private set

    private var localHelloPayloadBytes: ByteArray? = null

    /**
     * Builds the local stage 0x01 HELLO packet.
     */
    @Synchronized
    fun createHelloPacket(): MeshPacket {
        check(state == LinkAuthState.IDLE || state == LinkAuthState.HELLO_RECEIVED) {
            "Cannot create HELLO in state $state"
        }

        val nonce = ByteArray(32)
        do {
            secureRandom.nextBytes(nonce)
        } while (nonce.all { it == 0.toByte() })
        localNonce = nonce

        val hello = HelloPayload(
            nonce = nonce,
            ikPub = localCredentials.ikPub,
            ekPub = localCredentials.ekPub,
            keyVersion = localCredentials.keyVersion,
            notBefore = localCredentials.notBefore,
            ibcSignature = localCredentials.ibcSignature
        )
        val payload168 = hello.toByteArray()
        localHelloPayloadBytes = payload168

        val payload = ByteBuffer.allocate(ResourceLimits.WIFI_HELLO_SIZE)
        payload.put(0x01.toByte()) // stage 0x01 HELLO
        payload.put(payload168)

        val packet = MeshPacket(
            type = PacketType.LINK_AUTH,
            messageId = UUID.randomUUID(),
            senderId = localCredentials.nodeId64,
            recipientId = 0L, // FROZEN §3.4: 0 for HELLO
            ttl = 1,
            timestamp = clock.nowSeconds(),
            payload = payload.array(),
            authTag = ByteArray(16) // FROZEN §3.4: 16 zero bytes
        )

        if (state == LinkAuthState.IDLE) {
            state = LinkAuthState.HELLO_SENT
        } else if (state == LinkAuthState.HELLO_RECEIVED) {
            advanceToHelloExchanged()
        }

        return packet
    }

    /**
     * Processes an incoming MeshPacket on this pending link.
     */
    @Synchronized
    fun processIncomingPacket(packet: MeshPacket): LinkAuthStepResult {
        if (state == LinkAuthState.AUTHENTICATED || state == LinkAuthState.FAILED) {
            return LinkAuthStepResult.Failed("Session is already $state")
        }

        if (packet.type != PacketType.LINK_AUTH) {
            fail("Packet type must be LINK_AUTH (0x31), got ${packet.type}")
            return LinkAuthStepResult.Failed("Expected LINK_AUTH (0x31)")
        }

        if (packet.ttl != 1) {
            fail("LINK_AUTH ttl must be 1, got ${packet.ttl}")
            return LinkAuthStepResult.Failed("LINK_AUTH ttl must be 1")
        }

        if (!packet.authTag.all { it == 0.toByte() }) {
            fail("LINK_AUTH authTag must be all-zero (FROZEN §3.4)")
            return LinkAuthStepResult.Failed("Non-zero authTag on LINK_AUTH")
        }

        val payload = packet.payload
        if (payload.isEmpty()) {
            fail("LINK_AUTH payload is empty")
            return LinkAuthStepResult.Failed("Empty payload")
        }

        // Frozen freshness requirement (§2.10, §3.4):
        // age = now - packet.timestamp, accept iff -120 <= age <= 60 (PAST_WINDOW[LINK_AUTH] = 60s, FUTURE_SKEW = 120s).
        // Stale or skewed packets are rejected without mutating handshake state, binding identity, or deriving K_link.
        val nowSec = clock.nowSeconds()
        val ageSec = nowSec - packet.timestamp
        if (ageSec < -ResourceLimits.FUTURE_SKEW_SEC || ageSec > packet.type.pastWindowSec) {
            return LinkAuthStepResult.Failed(
                "LINK_AUTH timestamp ${packet.timestamp} outside freshness window (age: ${ageSec}s, allowed: -${ResourceLimits.FUTURE_SKEW_SEC}..${packet.type.pastWindowSec})"
            )
        }

        val stage = payload[0]
        return when (stage) {
            0x01.toByte() -> processHello(payload, packet)
            0x02.toByte() -> processConfirm(payload, packet)
            else -> {
                fail("Unknown LINK_AUTH stage: $stage")
                LinkAuthStepResult.Failed("Unknown stage: $stage")
            }
        }
    }

    private fun processHello(payload: ByteArray, packet: MeshPacket): LinkAuthStepResult {
        // FROZEN §3.4: reject a second HELLO on a link already in CONFIRM state
        if (state == LinkAuthState.HELLO_EXCHANGED ||
            state == LinkAuthState.CONFIRM_SENT ||
            state == LinkAuthState.CONFIRM_RECEIVED
        ) {
            fail("Second HELLO rejected on link already in CONFIRM/EXCHANGED state")
            return LinkAuthStepResult.Failed("Duplicate HELLO rejected")
        }

        if (payload.size != ResourceLimits.WIFI_HELLO_SIZE) {
            fail("HELLO payload size must be 169, got ${payload.size}")
            return LinkAuthStepResult.Failed("Malformed HELLO length")
        }

        val hello = parseHello(payload)
        if (hello == null) {
            fail("Failed to parse HELLO payload")
            return LinkAuthStepResult.Failed("Malformed HELLO payload")
        }

        // Validate IBC (FROZEN §3.4 & C-11: notBefore <= packet.timestamp + 120)
        val ibcValid = PureCryptoEngine.validateIbc(
            ikPub = hello.ikPub,
            ekPub = hello.ekPub,
            keyVersion = hello.keyVersion,
            notBefore = hello.notBefore,
            signature = hello.ibcSignature,
            packetTimestamp = packet.timestamp
        )
        if (!ibcValid) {
            fail("IBC signature invalid or expired")
            return LinkAuthStepResult.Failed("Invalid IBC")
        }

        val peerIdHash = PureCryptoEngine.deriveIdentityHash(hello.ikPub)
        val peerNodeId = PureCryptoEngine.deriveNodeId64(peerIdHash)

        // FROZEN C-02: validate nodeId64 matches senderId
        if (peerNodeId != packet.senderId) {
            fail("Claimed senderId does not match derived nodeId64")
            return LinkAuthStepResult.Failed("SenderId mismatch")
        }

        // Reject reflection attack / connecting to self
        if (peerIdHash.contentEquals(localCredentials.identityHash)) {
            fail("Peer identity matches local identity (reflection rejected)")
            return LinkAuthStepResult.Failed("Reflection rejected")
        }

        remoteHello = hello
        remoteIdentityHash = peerIdHash
        remoteNodeId64 = peerNodeId

        if (state == LinkAuthState.HELLO_SENT) {
            advanceToHelloExchanged()
        } else if (state == LinkAuthState.IDLE) {
            state = LinkAuthState.HELLO_RECEIVED
        }

        return LinkAuthStepResult.InProgress
    }

    private fun advanceToHelloExchanged() {
        val localHello = localHelloPayloadBytes
        val remoteHello = remoteHello?.toByteArray()
        val peerIdHash = remoteIdentityHash

        if (localHello == null || remoteHello == null || peerIdHash == null) {
            fail("Incomplete HELLO payloads during transcript derivation")
            return
        }

        val transcript = PureCryptoEngine.buildHandshakeTranscriptT(
            helloPayloadA = localHello,
            helloPayloadB = remoteHello,
            identityHashA = localCredentials.identityHash,
            identityHashB = peerIdHash
        )
        transcriptT = transcript

        val derivedKey = PureCryptoEngine.deriveLinkKey(
            myEkPrivateKey = localCredentials.ekPriv,
            peerEkPublicKey = this.remoteHello!!.ekPub,
            transcriptT = transcript
        )
        linkKey = derivedKey
        state = LinkAuthState.HELLO_EXCHANGED
    }

    private fun processConfirm(payload: ByteArray, packet: MeshPacket): LinkAuthStepResult {
        if (state != LinkAuthState.CONFIRM_SENT && state != LinkAuthState.HELLO_EXCHANGED) {
            fail("CONFIRM received out of order in state $state")
            return LinkAuthStepResult.Failed("CONFIRM received out of order")
        }

        if (payload.size != 65) {
            fail("CONFIRM payload size must be 65, got ${payload.size}")
            return LinkAuthStepResult.Failed("Malformed CONFIRM length")
        }

        val confirm = parseConfirm(payload)
        if (confirm == null) {
            fail("Failed to parse CONFIRM payload")
            return LinkAuthStepResult.Failed("Malformed CONFIRM payload")
        }

        val transcript = transcriptT
        val remoteHello = remoteHello
        val remoteIdHash = remoteIdentityHash

        if (transcript == null || remoteHello == null || remoteIdHash == null) {
            fail("Missing handshake context during CONFIRM validation")
            return LinkAuthStepResult.Failed("Missing handshake context")
        }

        // Validate confirmSig over: "MW/SIG/v2" ‖ 0x00 ‖ 0x04 ‖ T ‖ identityHash_peer ‖ identityHash_self
        // For the remote node: its self is remoteIdHash, its peer is localCredentials.identityHash.
        val preimage = PureCryptoEngine.buildConfirmSigPreimage(
            transcriptT = transcript,
            identityHashSelf = remoteIdHash,
            identityHashPeer = localCredentials.identityHash
        )

        val sigValid = PureCryptoEngine.verifySignature(
            signingPublicKey = remoteHello.ikPub,
            data = preimage,
            signature = confirm.confirmSig
        )

        if (!sigValid) {
            fail("CONFIRM signature verification failed")
            return LinkAuthStepResult.Failed("Invalid confirmSig")
        }

        if (state == LinkAuthState.CONFIRM_SENT) {
            state = LinkAuthState.AUTHENTICATED
            return LinkAuthStepResult.Completed(
                LinkAuthProof.create(
                    linkHandle = linkHandle,
                    peerIdentityHash = remoteIdHash,
                    peerNodeId64 = remoteNodeId64!!,
                    linkKey = linkKey!!
                )
            )
        } else {
            // State was HELLO_EXCHANGED: Remote CONFIRM verified, but we haven't sent our CONFIRM yet.
            state = LinkAuthState.CONFIRM_RECEIVED
            return LinkAuthStepResult.InProgress
        }
    }

    /**
     * Builds the local stage 0x02 CONFIRM packet.
     */
    @Synchronized
    fun createConfirmPacket(): MeshPacket {
        check(state == LinkAuthState.HELLO_EXCHANGED || state == LinkAuthState.CONFIRM_RECEIVED) {
            "Cannot create CONFIRM in state $state"
        }

        val transcript = transcriptT
        val remoteIdHash = remoteIdentityHash
        val remoteNodeId = remoteNodeId64

        check(transcript != null && remoteIdHash != null && remoteNodeId != null) {
            "Missing handshake state to build CONFIRM"
        }

        val preimage = PureCryptoEngine.buildConfirmSigPreimage(
            transcriptT = transcript,
            identityHashSelf = localCredentials.identityHash,
            identityHashPeer = remoteIdHash
        )

        val confirmSig = PureCryptoEngine.sign(
            identitySeed = localCredentials.identitySeed,
            data = preimage
        )

        val payload = ByteBuffer.allocate(65)
        payload.put(0x02.toByte()) // stage 0x02 CONFIRM
        payload.put(confirmSig)

        val packet = MeshPacket(
            type = PacketType.LINK_AUTH,
            messageId = UUID.randomUUID(),
            senderId = localCredentials.nodeId64,
            recipientId = remoteNodeId, // FROZEN §3.4: peer's claimed nodeId64 for CONFIRM
            ttl = 1,
            timestamp = clock.nowSeconds(),
            payload = payload.array(),
            authTag = ByteArray(16)
        )

        if (state == LinkAuthState.HELLO_EXCHANGED) {
            state = LinkAuthState.CONFIRM_SENT
        } else if (state == LinkAuthState.CONFIRM_RECEIVED) {
            state = LinkAuthState.AUTHENTICATED
        }

        return packet
    }

    @Synchronized
    fun getSuccessProof(): LinkAuthProof? {
        return if (state == LinkAuthState.AUTHENTICATED && remoteIdentityHash != null && linkKey != null) {
            LinkAuthProof.create(
                linkHandle = linkHandle,
                peerIdentityHash = remoteIdentityHash!!,
                peerNodeId64 = remoteNodeId64!!,
                linkKey = linkKey!!
            )
        } else null
    }

    @Synchronized
    fun close() {
        fail("Closed by local node")
    }

    private fun fail(reason: String) {
        state = LinkAuthState.FAILED
        linkKey?.let { Arrays.fill(it, 0.toByte()) }
        linkKey = null
    }

    companion object {
        fun parseHello(payload: ByteArray): HelloPayload? {
            if (payload.size != ResourceLimits.WIFI_HELLO_SIZE || payload[0] != 0x01.toByte()) return null
            val buf = ByteBuffer.wrap(payload, 1, 168)

            val nonce = ByteArray(32)
            buf.get(nonce)
            if (nonce.all { it == 0.toByte() }) return null

            val ikPub = ByteArray(32)
            buf.get(ikPub)

            val ekPub = ByteArray(32)
            buf.get(ekPub)

            val keyVersion = buf.int.toLong() and 0xFFFFFFFFL
            if (keyVersion < 1L) return null

            val notBefore = buf.int.toLong() and 0xFFFFFFFFL

            val ibcSig = ByteArray(64)
            buf.get(ibcSig)

            return try {
                HelloPayload(nonce, ikPub, ekPub, keyVersion, notBefore, ibcSig)
            } catch (_: Exception) {
                null
            }
        }

        fun parseConfirm(payload: ByteArray): ConfirmPayload? {
            if (payload.size != 65 || payload[0] != 0x02.toByte()) return null
            val sig = payload.copyOfRange(1, 65)
            return try {
                ConfirmPayload(sig)
            } catch (_: Exception) {
                null
            }
        }
    }
}

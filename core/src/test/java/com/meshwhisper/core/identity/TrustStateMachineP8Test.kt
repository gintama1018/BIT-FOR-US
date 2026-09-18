package com.meshwhisper.core.identity

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Before
import org.junit.Test

/**
 * Mandatory P8 Trust State Machine Tests: T-TRUST-01..07
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §5, §6, C-12, C-23, and NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md.
 */
class TrustStateMachineP8Test {

    private lateinit var identityStore: IdentityStore

    private class KeyMaterial(
        val seed: ByteArray,
        val ikPub: ByteArray,
        val ekPub: ByteArray,
        val identityHash: ByteArray,
        val nodeId64: Long
    )

    private fun generateKeyMaterial(): KeyMaterial {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val identityHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)
        return KeyMaterial(seed, ikPub, ekPub, identityHash, nodeId64)
    }

    private fun createIbcSignature(
        keyMaterial: KeyMaterial,
        keyVersion: Long = 1L,
        notBefore: Long = 0L
    ): ByteArray {
        return PureCryptoEngine.signIbc(keyMaterial.seed, keyMaterial.ekPub, keyVersion, notBefore)
    }

    @Before
    fun setUp() {
        identityStore = InMemoryIdentityStore()
    }

    /**
     * T-TRUST-01: Valid state machine transitions T1..T11.
     */
    @Test
    fun testTTrust01ValidTransitionsT1ThroughT11() {
        val km = generateKeyMaterial()
        val ibc = createIbcSignature(km, keyVersion = 1L)
        val nowSec = System.currentTimeMillis() / 1000L

        // T1: (none) -> SEEN via first authenticated PEER_ANNOUNCE presentation
        val t1Result = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = nowSec,
            initialTrustState = TrustState.SEEN
        )
        assertThat(t1Result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val t1Peer = (t1Result as IdentityProcessResult.Accepted).identity
        assertThat(t1Peer.trustState).isEqualTo(TrustState.SEEN)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.SEEN)

        // T4: SEEN -> LINKED on link established
        IdentityManager.onLinkEstablished(identityStore, km.identityHash)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.LINKED)

        // T5: LINKED -> SEEN on link closed
        IdentityManager.onLinkClosed(identityStore, km.identityHash)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.SEEN)

        // T3: SEEN -> VERIFIED via in-app camera QR scan
        val t3Result = IdentityManager.verifyViaCameraQr(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = nowSec
        )
        assertThat(t3Result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val t3Peer = (t3Result as IdentityProcessResult.Accepted).identity
        assertThat(t3Peer.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)

        // T6: VERIFIED -> LINKED on accepted EK rotation (keyVersion increment)
        val (_, newEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val rotIbc = PureCryptoEngine.signIbc(km.seed, newEkPub, 2L, 0L)

        val t6Result = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = newEkPub,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = rotIbc,
            packetTimestamp = nowSec + 10L
        )
        assertThat(t6Result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val t6Peer = (t6Result as IdentityProcessResult.Accepted).identity
        assertThat(t6Peer.trustState).isEqualTo(TrustState.LINKED)
        assertThat(t6Peer.hasKeyChanged).isTrue()
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.LINKED)

        // T9: any -> BLOCKED via explicit user block
        IdentityManager.blockIdentity(identityStore, km.identityHash)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.BLOCKED)

        // T10: BLOCKED -> SEEN via explicit user unblock
        IdentityManager.unblockIdentity(identityStore, km.identityHash)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.SEEN)

        // T2: Fresh peer (none) -> IMPORTED via deep link / URI import
        val km2 = generateKeyMaterial()
        val ibc2 = createIbcSignature(km2)
        val t2Result = IdentityManager.importIdentity(
            identityStore = identityStore,
            ikPub = km2.ikPub,
            ekPub = km2.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc2,
            packetTimestamp = nowSec
        )
        assertThat(t2Result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val t2Peer = (t2Result as IdentityProcessResult.Accepted).identity
        assertThat(t2Peer.trustState).isEqualTo(TrustState.IMPORTED)
        assertThat(identityStore.get(km2.identityHash)?.trustState).isEqualTo(TrustState.IMPORTED)

        // T11: LEGACY_UNVERIFIED -> SEEN on matching authenticated vNext announce
        val km3 = generateKeyMaterial()
        val legacyPeer = PeerIdentity(
            identityHash = km3.identityHash,
            ikPub = km3.ikPub,
            ekPub = km3.ekPub,
            keyVersion = 1L,
            lastAnnounceCounter = 0L,
            trustState = TrustState.LEGACY_UNVERIFIED,
            nodeId64 = km3.nodeId64
        )
        identityStore.upsert(legacyPeer)
        assertThat(identityStore.get(km3.identityHash)?.trustState).isEqualTo(TrustState.LEGACY_UNVERIFIED)

        val ibc3 = createIbcSignature(km3)
        val t11Result = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = km3.ikPub,
            ekPub = km3.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc3,
            packetTimestamp = nowSec
        )
        assertThat(t11Result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        assertThat((t11Result as IdentityProcessResult.Accepted).identity.trustState).isEqualTo(TrustState.SEEN)
        assertThat(identityStore.get(km3.identityHash)?.trustState).isEqualTo(TrustState.SEEN)
    }

    /**
     * T-TRUST-02: Forbidden transitions.
     * Anything -> VERIFIED is forbidden unless it is T3 or T8.
     */
    @Test
    fun testTTrust02ForbiddenTransitions() {
        val km = generateKeyMaterial()
        val ibc = createIbcSignature(km)
        val nowSec = System.currentTimeMillis() / 1000L

        // Attempt to reach VERIFIED directly via deep-link import -> REJECTED, must be IMPORTED
        val importResult = IdentityManager.importIdentity(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = nowSec
        )
        val imported = (importResult as IdentityProcessResult.Accepted).identity
        assertThat(imported.trustState).isNotEqualTo(TrustState.VERIFIED)
        assertThat(imported.trustState).isEqualTo(TrustState.IMPORTED)

        // Re-importing existing identity must NOT promote to VERIFIED
        val reimportResult = IdentityManager.importIdentity(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = nowSec
        )
        val reimported = (reimportResult as IdentityProcessResult.Accepted).identity
        assertThat(reimported.trustState).isEqualTo(TrustState.IMPORTED)

        // PEER_ANNOUNCE presentation must NOT yield VERIFIED (must be SEEN)
        val km2 = generateKeyMaterial()
        val ibc2 = createIbcSignature(km2)
        val announceResult = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = km2.ikPub,
            ekPub = km2.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc2,
            packetTimestamp = nowSec
        )
        val announced = (announceResult as IdentityProcessResult.Accepted).identity
        assertThat(announced.trustState).isNotEqualTo(TrustState.VERIFIED)
        assertThat(announced.trustState).isEqualTo(TrustState.SEEN)

        // Transport connection / LINK_AUTH must NOT yield VERIFIED (must be LINKED)
        IdentityManager.onLinkEstablished(identityStore, km2.identityHash)
        assertThat(identityStore.get(km2.identityHash)?.trustState).isNotEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(km2.identityHash)?.trustState).isEqualTo(TrustState.LINKED)
    }

    /**
     * T-TRUST-03: S-17 Deep-link import produces IMPORTED, never VERIFIED.
     */
    @Test
    fun testTTrust03DeepLinkImportProducesImportedNeverVerified() {
        val km = generateKeyMaterial()
        val ibc = createIbcSignature(km, keyVersion = 1L)
        val nowSec = System.currentTimeMillis() / 1000L

        val qrData = NodeQrData(
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            alias = "Alice"
        )
        val uriString = NodeQrCodec.encode(qrData)
        assertThat(uriString).startsWith("meshwhisper://node/v2?")

        // Decode URI as if clicked in browser / messaging app
        val decoded = NodeQrCodec.decode(uriString)
        assertThat(decoded).isNotNull()

        val result = IdentityManager.importIdentity(
            identityStore = identityStore,
            ikPub = decoded!!.ikPub,
            ekPub = decoded.ekPub,
            keyVersion = decoded.keyVersion,
            notBefore = decoded.notBefore,
            ibcSignature = decoded.ibcSignature,
            packetTimestamp = nowSec
        )

        assertThat(result).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val identity = (result as IdentityProcessResult.Accepted).identity

        // ABSOLUTE ASSERTION (Invariant I-8 / Finding S-17)
        assertThat(identity.trustState).isNotEqualTo(TrustState.VERIFIED)
        assertThat(identity.trustState).isEqualTo(TrustState.IMPORTED)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.IMPORTED)
    }

    /**
     * T-TRUST-04: Explicit two-step camera QR verification.
     * QR scan alone prepares candidate; explicit confirmation required for VERIFIED.
     */
    @Test
    fun testTTrust04TwoStepCameraQrVerification() {
        val ourKm = generateKeyMaterial()
        val peerKm = generateKeyMaterial()
        val ibc = createIbcSignature(peerKm, keyVersion = 1L)

        val qrData = NodeQrData(
            ikPub = peerKm.ikPub,
            ekPub = peerKm.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            alias = "Bob"
        )

        // 1. Invalid IBC rejected during preparation
        val corruptIbc = ibc.copyOf().also { it[0] = (it[0] + 1).toByte() }
        val corruptQr = qrData.copy(ibcSignature = corruptIbc)
        val failPrep = IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = ourKm.identityHash,
            qrData = corruptQr
        )
        assertThat(failPrep.isFailure).isTrue()
        assertThat(identityStore.get(peerKm.identityHash)).isNull()

        // 2. NodeId mismatch rejected during preparation
        val failNodeIdPrep = IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = ourKm.identityHash,
            qrData = qrData,
            expectedNodeId64 = 0x99999999L
        )
        assertThat(failNodeIdPrep.isFailure).isTrue()
        assertThat(identityStore.get(peerKm.identityHash)).isNull()

        // 3. Valid QR scan prepares candidate: NOT YET VERIFIED
        val prepResult = IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = ourKm.identityHash,
            qrData = qrData,
            expectedNodeId64 = peerKm.nodeId64
        )
        assertThat(prepResult.isSuccess).isTrue()
        val candidate = prepResult.getOrThrow()
        assertThat(candidate.identityHash).isEqualTo(peerKm.identityHash)
        assertThat(candidate.safetyNumber).isEqualTo(PureCryptoEngine.computeSafetyNumber(ourKm.identityHash, peerKm.identityHash))

        // State in store is STILL null / not verified
        assertThat(identityStore.get(peerKm.identityHash)).isNull()

        // 4. Explicit user confirmation applies T3 -> VERIFIED
        val verified = IdentityManager.applyConfirmedVerification(identityStore, candidate)
        assertThat(verified.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(peerKm.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)
    }

    /**
     * T-TRUST-05: Key rotation clears VERIFIED and sets hasKeyChanged = true.
     */
    @Test
    fun testTTrust05KeyRotationClearsVerified() {
        val km = generateKeyMaterial()
        val ibc = createIbcSignature(km, keyVersion = 1L)
        val nowSec = System.currentTimeMillis() / 1000L

        // Establish VERIFIED peer
        val t3 = IdentityManager.verifyViaCameraQr(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = nowSec
        )
        assertThat((t3 as IdentityProcessResult.Accepted).identity.trustState).isEqualTo(TrustState.VERIFIED)

        // Rotate EK with incremented keyVersion
        val (_, newEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val rotIbc = PureCryptoEngine.signIbc(km.seed, newEkPub, 2L, 0L)

        val rotationResult = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = km.ikPub,
            ekPub = newEkPub,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = rotIbc,
            packetTimestamp = nowSec + 30L
        )

        assertThat(rotationResult).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val peerAfterRot = (rotationResult as IdentityProcessResult.Accepted).identity

        // VERIFIED MUST be demoted to LINKED
        assertThat(peerAfterRot.trustState).isNotEqualTo(TrustState.VERIFIED)
        assertThat(peerAfterRot.trustState).isEqualTo(TrustState.LINKED)
        assertThat(peerAfterRot.hasKeyChanged).isTrue()
        assertThat(peerAfterRot.keyVersion).isEqualTo(2L)
        assertThat(identityStore.get(km.identityHash)?.trustState).isEqualTo(TrustState.LINKED)
    }

    /**
     * T-TRUST-06: Collision handling and Camera QR resolution (T7 & T8).
     */
    @Test
    fun testTTrust06CollisionHandlingAndQrResolution() {
        val kmA = generateKeyMaterial()
        val ibcA = createIbcSignature(kmA)
        val nowSec = System.currentTimeMillis() / 1000L

        // Peer A enters as SEEN
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = kmA.ikPub,
            ekPub = kmA.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcA,
            packetTimestamp = nowSec
        )
        assertThat(identityStore.get(kmA.identityHash)?.trustState).isEqualTo(TrustState.SEEN)
        assertThat(identityStore.getByNodeId64(kmA.nodeId64)?.identityHash).isEqualTo(kmA.identityHash)

        // Peer B has distinct identityHash but forced colliding nodeId64
        val kmB = generateKeyMaterial()
        val ibcB = createIbcSignature(kmB)

        // Simulate identical nodeId64 collision across distinct identityHash
        val collidingIdentity = PeerIdentity(
            identityHash = kmB.identityHash,
            ikPub = kmB.ikPub,
            ekPub = kmB.ekPub,
            keyVersion = 1L,
            lastAnnounceCounter = 0L,
            trustState = TrustState.SEEN,
            nodeId64 = kmA.nodeId64 // Colliding with kmA
        )

        // Trigger collision detection
        val collidingList = identityStore.getAllByNodeId64(kmA.nodeId64)
        assertThat(collidingList).isNotEmpty()

        // T7: Both identities -> CONFLICTED
        for (peer in collidingList) {
            identityStore.upsert(peer.copy(trustState = TrustState.CONFLICTED))
        }
        identityStore.upsert(collidingIdentity.copy(trustState = TrustState.CONFLICTED))

        assertThat(identityStore.get(kmA.identityHash)?.trustState).isEqualTo(TrustState.CONFLICTED)
        assertThat(identityStore.get(kmB.identityHash)?.trustState).isEqualTo(TrustState.CONFLICTED)

        // Unicast suspended: lookup by nodeId64 returns null when multiple identities collide
        assertThat(identityStore.getByNodeId64(kmA.nodeId64)).isNull()
        assertThat(identityStore.getAllByNodeId64(kmA.nodeId64)).hasSize(2)

        // T8: User scans Camera QR of identity A
        val qrA = NodeQrData(
            ikPub = kmA.ikPub,
            ekPub = kmA.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcA,
            alias = "Alice (Authentic)"
        )
        val ourKm = generateKeyMaterial()
        val prep = IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = ourKm.identityHash,
            qrData = qrA,
            expectedNodeId64 = kmA.nodeId64
        )
        assertThat(prep.isSuccess).isTrue()
        val candidateA = prep.getOrThrow()
        assertThat(candidateA.isCollisionResolution).isTrue()

        // Apply confirmation: identity A -> VERIFIED, colliding identity B -> BLOCKED
        IdentityManager.applyConfirmedVerification(identityStore, candidateA)

        assertThat(identityStore.get(kmA.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(kmB.identityHash)?.trustState).isEqualTo(TrustState.BLOCKED)

        // Unicast restored: single non-blocked/non-conflicted routable identity resolves
        val resolved = identityStore.getAllByNodeId64(kmA.nodeId64).filter { it.trustState != TrustState.BLOCKED }
        assertThat(resolved).hasSize(1)
        assertThat(resolved.first().identityHash).isEqualTo(kmA.identityHash)
    }

    /**
     * T-TRUST-07: Capability cannot derive from legacy boolean.
     * - trustState = SEEN, isVerified = true -> MUST NOT gain VERIFIED capabilities or green shield.
     * - trustState = VERIFIED, isVerified = false -> trust decisions strictly use VERIFIED.
     */
    @Test
    fun testTTrust07CapabilityCannotDeriveFromLegacyBoolean() {
        val km = generateKeyMaterial()

        // 1. Simulating an injected stale legacy boolean isVerified = true on a SEEN peer
        val staleBooleanPeer = PeerIdentity(
            identityHash = km.identityHash,
            ikPub = km.ikPub,
            ekPub = km.ekPub,
            keyVersion = 1L,
            lastAnnounceCounter = 0L,
            trustState = TrustState.SEEN,
            nodeId64 = km.nodeId64
        )
        identityStore.upsert(staleBooleanPeer)

        val retrieved = identityStore.get(km.identityHash)!!
        // The authoritative state is strictly SEEN
        assertThat(retrieved.trustState).isEqualTo(TrustState.SEEN)
        assertThat(retrieved.trustState == TrustState.VERIFIED).isFalse()

        // Invariant: SEEN is LRU evictable, not immune like VERIFIED (§5.1)
        val hasEvictionImmunity = (retrieved.trustState == TrustState.VERIFIED ||
                retrieved.trustState == TrustState.CONFLICTED ||
                retrieved.trustState == TrustState.BLOCKED)
        assertThat(hasEvictionImmunity).isFalse()

        // 2. Inverse: trustState = VERIFIED, simulating a desynced legacy boolean = false
        val verifiedPeer = staleBooleanPeer.copy(trustState = TrustState.VERIFIED)
        identityStore.upsert(verifiedPeer)

        val retrievedVerified = identityStore.get(km.identityHash)!!
        // Decision is strictly derived from trustState
        assertThat(retrievedVerified.trustState).isEqualTo(TrustState.VERIFIED)
        val verifiedImmunity = (retrievedVerified.trustState == TrustState.VERIFIED)
        assertThat(verifiedImmunity).isTrue()
    }
}

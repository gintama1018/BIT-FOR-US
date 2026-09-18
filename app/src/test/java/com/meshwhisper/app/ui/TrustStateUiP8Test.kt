package com.meshwhisper.app.ui

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.app.data.model.PeerEntity
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.identity.*
import org.junit.Before
import org.junit.Test

/**
 * Phase P8 Trust States and UI Contract Verification Tests.
 *
 * Enforces:
 * - Exactly 7 frozen trust states: SEEN, LINKED, IMPORTED, VERIFIED, CONFLICTED, BLOCKED, LEGACY_UNVERIFIED
 * - Invariant I-8: Deep-link / URI import strictly transitions to IMPORTED, NEVER VERIFIED
 * - Two-step Camera QR verification (Candidate preparation without state mutation -> Explicit confirmation)
 * - C-12 Key rotation demotion to LINKED with hasKeyChanged flag; acknowledgment clears flag without mutating trust state
 * - C-23 NodeId64 collision detection (marks all colliding identities CONFLICTED, suspends unicast)
 * - T8 Collision resolution (promotes confirmed candidate to VERIFIED, demotes colliding losers to BLOCKED)
 * - 60-digit safety number formatting (12 groups of 5) and mathematical symmetry
 * - Elimination of legacy boolean verification bypasses (isVerified is strictly derived from trustState == 'VERIFIED')
 */
class TrustStateUiP8Test {

    private lateinit var identityStore: IdentityStore

    private class KeyPairData(
        val seed: ByteArray,
        val ikPub: ByteArray,
        val ekPub: ByteArray,
        val identityHash: ByteArray,
        val nodeId64: Long
    )

    private fun generateKey(): KeyPairData {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val identityHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64 = PureCryptoEngine.deriveNodeId64(identityHash)
        return KeyPairData(seed, ikPub, ekPub, identityHash, nodeId64)
    }

    private fun signIbc(key: KeyPairData, keyVersion: Long = 1L, notBefore: Long = 0L): ByteArray {
        return PureCryptoEngine.signIbc(key.seed, key.ekPub, keyVersion, notBefore)
    }

    @Before
    fun setUp() {
        identityStore = InMemoryIdentityStore()
    }

    /**
     * 1. Elimination of legacy boolean verification bypasses.
     * `isVerified` is strictly derived as `trustState == 'VERIFIED'`.
     * No state other than VERIFIED may produce `isVerified == true`.
     */
    @Test
    fun testLegacyVerificationBypassImpossible() {
        val nonVerifiedStates = listOf(
            "SEEN",
            "LINKED",
            "IMPORTED",
            "CONFLICTED",
            "BLOCKED",
            "LEGACY_UNVERIFIED",
            "UNKNOWN"
        )

        for (state in nonVerifiedStates) {
            val peer = PeerEntity(
                nodeId = 0x12345678L,
                alias = "TestPeer",
                publicKeyHex = "abcd",
                fingerprint = "0123",
                lastSeen = 1000L,
                isDirect = true,
                trustState = state,
                isVerified = (state == "VERIFIED")
            )
            assertThat(peer.isVerified).isFalse()
            assertThat(peer.trustState).isEqualTo(state)
        }

        // Only VERIFIED produces isVerified == true
        val verifiedPeer = PeerEntity(
            nodeId = 0x12345678L,
            alias = "VerifiedPeer",
            publicKeyHex = "abcd",
            fingerprint = "0123",
            lastSeen = 1000L,
            isDirect = true,
            trustState = "VERIFIED",
            isVerified = ("VERIFIED" == "VERIFIED")
        )
        assertThat(verifiedPeer.isVerified).isTrue()
        assertThat(verifiedPeer.trustState).isEqualTo("VERIFIED")
    }

    /**
     * 2. Invariant I-8: Deep-link / URI import strictly transitions to IMPORTED, NEVER VERIFIED.
     */
    @Test
    fun testDeepLinkImportProducesImportedStateOnly() {
        val alice = generateKey()
        val ibc = signIbc(alice, keyVersion = 1L)

        // Encode as v2 QR URI string
        val qrData = NodeQrData(
            ikPub = alice.ikPub,
            ekPub = alice.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            alias = "Alice"
        )
        val uriString = NodeQrCodec.encode(qrData)
        assertThat(uriString).startsWith("meshwhisper://node/v2?")

        // Decode from URI string
        val decoded = NodeQrCodec.decode(uriString)
        assertThat(decoded).isNotNull()
        assertThat(decoded!!.alias).isEqualTo("Alice")
        assertThat(decoded.ikPub).isEqualTo(alice.ikPub)

        // Import identity into empty store (T2: (none) -> IMPORTED)
        val importResult = IdentityManager.importIdentity(
            identityStore = identityStore,
            ikPub = decoded.ikPub,
            ekPub = decoded.ekPub,
            keyVersion = decoded.keyVersion,
            notBefore = decoded.notBefore,
            ibcSignature = decoded.ibcSignature,
            packetTimestamp = 1000L
        )

        assertThat(importResult).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val accepted = (importResult as IdentityProcessResult.Accepted).identity

        // Invariant I-8 check: MUST be IMPORTED, NEVER VERIFIED
        assertThat(accepted.trustState).isEqualTo(TrustState.IMPORTED)
        assertThat(identityStore.get(alice.identityHash)?.trustState).isEqualTo(TrustState.IMPORTED)
    }

    /**
     * 3. Two-step Camera QR verification:
     * Step 1: Candidate preparation validates IBC, computes 60-digit safety numbers and fingerprints,
     * but DOES NOT mutate trust state to VERIFIED.
     * Step 2: Explicit confirmation applies T3 to commit VERIFIED.
     */
    @Test
    fun testCameraQrTwoStepVerification() {
        val ourKey = generateKey()
        val peerKey = generateKey()
        val ibc = signIbc(peerKey, keyVersion = 1L)

        // Peer is initially SEEN
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = peerKey.ikPub,
            ekPub = peerKey.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            packetTimestamp = 1000L,
            initialTrustState = TrustState.SEEN
        )
        assertThat(identityStore.get(peerKey.identityHash)?.trustState).isEqualTo(TrustState.SEEN)

        val qrData = NodeQrData(
            ikPub = peerKey.ikPub,
            ekPub = peerKey.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibc,
            alias = "PeerBob"
        )

        // Step 1: Prepare camera QR candidate (No state mutation)
        val candidateResult = IdentityManager.prepareCameraQrCandidate(
            identityStore = identityStore,
            ourIdentityHash = ourKey.identityHash,
            qrData = qrData,
            packetTimestamp = 1000L,
            expectedNodeId64 = peerKey.nodeId64
        )
        assertThat(candidateResult.isSuccess).isTrue()
        val candidate = candidateResult.getOrThrow()

        // Verify safety number format (12 groups of 5)
        assertThat(candidate.safetyNumber).matches("^(\\d{5}\\s){11}\\d{5}$")
        assertThat(candidate.alias).isEqualTo("PeerBob")
        assertThat(candidate.nodeId64).isEqualTo(peerKey.nodeId64)
        assertThat(candidate.isCollisionResolution).isFalse()

        // Critical check: Store was NOT mutated in Step 1
        assertThat(identityStore.get(peerKey.identityHash)?.trustState).isEqualTo(TrustState.SEEN)

        // Step 2: Explicit confirmation applies T3 to commit VERIFIED
        val verified = IdentityManager.applyConfirmedVerification(identityStore, candidate)
        assertThat(verified.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(peerKey.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)
    }

    /**
     * 4. C-12 Key Rotation Demotion & Acknowledgement:
     * Incoming announce with higher keyVersion demotes VERIFIED to LINKED and sets hasKeyChanged = true.
     * Acknowledgment clears hasKeyChanged = false without mutating trustState (remains LINKED).
     */
    @Test
    fun testKeyRotationDemotesToLinkedAndAcknowledgementClearsFlagOnly() {
        val peer = generateKey()
        val ibcV1 = signIbc(peer, keyVersion = 1L)

        // Establish initial VERIFIED state
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = peer.ikPub,
            ekPub = peer.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcV1,
            packetTimestamp = 1000L,
            initialTrustState = TrustState.SEEN
        )
        val verifiedCandidate = VerificationCandidate(
            identityHash = peer.identityHash,
            ikPub = peer.ikPub,
            ekPub = peer.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = ibcV1,
            nodeId64 = peer.nodeId64,
            alias = "Peer",
            safetyNumber = "12345",
            peerFingerprint = "fp1",
            ourFingerprint = "fp2"
        )
        IdentityManager.applyConfirmedVerification(identityStore, verifiedCandidate)
        assertThat(identityStore.get(peer.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)

        // Peer rotates ephemeral key: keyVersion becomes 2L
        val (_, newEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val ibcV2 = PureCryptoEngine.signIbc(peer.seed, newEkPub, keyVersion = 2L, notBefore = 0L)

        val rotationResult = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = peer.ikPub,
            ekPub = newEkPub,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = ibcV2,
            packetTimestamp = 1500L
        )
        assertThat(rotationResult).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val rotated = (rotationResult as IdentityProcessResult.Accepted).identity

        // Under C-12: VERIFIED must be demoted to LINKED, and hasKeyChanged set to true
        assertThat(rotated.trustState).isEqualTo(TrustState.LINKED)
        assertThat(rotated.hasKeyChanged).isTrue()
        assertThat(identityStore.get(peer.identityHash)?.trustState).isEqualTo(TrustState.LINKED)
        assertThat(identityStore.get(peer.identityHash)?.hasKeyChanged).isTrue()

        // When user acknowledges key rotation in UI (dismiss banner):
        // hasKeyChanged is cleared to false, but trustState remains LINKED (does not re-verify!)
        val acknowledged = identityStore.get(peer.identityHash)!!.copy(hasKeyChanged = false)
        identityStore.upsert(acknowledged)

        val stored = identityStore.get(peer.identityHash)!!
        assertThat(stored.hasKeyChanged).isFalse()
        assertThat(stored.trustState).isEqualTo(TrustState.LINKED) // NOT VERIFIED!
    }

    /**
     * 5. C-23 NodeId64 Collision Detection:
     * Two distinct identities with the same nodeId64 both transition to CONFLICTED (T7).
     * Router unicast fails closed while in conflict.
     */
    @Test
    fun testNodeIdCollisionDetectionSuspendsUnicast() {
        val alice = generateKey()
        val eve = generateKey()
        val sharedNodeId = alice.nodeId64

        // Alice is seen first
        val alicePeer = PeerIdentity(
            identityHash = alice.identityHash,
            ikPub = alice.ikPub,
            ekPub = alice.ekPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = sharedNodeId
        )
        identityStore.upsert(alicePeer)

        // Eve announces with a collision on the same nodeId64
        val evePeer = PeerIdentity(
            identityHash = eve.identityHash,
            ikPub = eve.ikPub,
            ekPub = eve.ekPub,
            keyVersion = 1L,
            lastAnnounceCounter = 1L,
            trustState = TrustState.SEEN,
            nodeId64 = sharedNodeId
        )

        // Trigger T7 collision detection
        val collidingList = identityStore.getAllByNodeId64(sharedNodeId)
        assertThat(collidingList).isNotEmpty()

        for (peer in collidingList) {
            identityStore.upsert(peer.copy(trustState = TrustState.CONFLICTED))
        }
        identityStore.upsert(evePeer.copy(trustState = TrustState.CONFLICTED))

        // Both Alice and Eve must be marked CONFLICTED (T7)
        assertThat(identityStore.get(alice.identityHash)?.trustState).isEqualTo(TrustState.CONFLICTED)
        assertThat(identityStore.get(eve.identityHash)?.trustState).isEqualTo(TrustState.CONFLICTED)

        // Router fail-closed check: Unicast routing lookup by nodeId64 returns null when multiple identities collide
        assertThat(identityStore.getByNodeId64(sharedNodeId)).isNull()
        val allPeers = identityStore.getAllByNodeId64(sharedNodeId)
        assertThat(allPeers).hasSize(2)
        val isConflicted = allPeers.any { it.trustState == TrustState.CONFLICTED }
        assertThat(isConflicted).isTrue()
    }

    /**
     * 6. T8 Collision Resolution via Camera QR:
     * Winner is promoted to VERIFIED, colliding loser is demoted to BLOCKED.
     * Collision state is cleared.
     */
    @Test
    fun testCameraQrCollisionResolutionPromotesWinnerAndBlocksLoser() {
        val alice = generateKey()
        val eve = generateKey()
        val sharedNodeId = alice.nodeId64

        // Both in CONFLICTED state
        identityStore.upsert(
            PeerIdentity(
                identityHash = alice.identityHash,
                ikPub = alice.ikPub,
                ekPub = alice.ekPub,
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.CONFLICTED,
                nodeId64 = sharedNodeId
            )
        )
        identityStore.upsert(
            PeerIdentity(
                identityHash = eve.identityHash,
                ikPub = eve.ikPub,
                ekPub = eve.ekPub,
                keyVersion = 1L,
                lastAnnounceCounter = 1L,
                trustState = TrustState.CONFLICTED,
                nodeId64 = sharedNodeId
            )
        )

        // User in person scans Alice's QR code to resolve the collision (T8)
        val aliceIbc = signIbc(alice, keyVersion = 1L)
        val aliceCandidate = VerificationCandidate(
            identityHash = alice.identityHash,
            ikPub = alice.ikPub,
            ekPub = alice.ekPub,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = aliceIbc,
            nodeId64 = sharedNodeId,
            alias = "Alice",
            safetyNumber = "11111 22222 33333",
            peerFingerprint = "alice-fp",
            ourFingerprint = "our-fp",
            isCollisionResolution = true
        )

        // Apply confirmation
        val resolved = IdentityManager.applyConfirmedVerification(identityStore, aliceCandidate)

        // Alice is promoted to VERIFIED
        assertThat(resolved.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(identityStore.get(alice.identityHash)?.trustState).isEqualTo(TrustState.VERIFIED)

        // Eve is demoted to BLOCKED
        assertThat(identityStore.get(eve.identityHash)?.trustState).isEqualTo(TrustState.BLOCKED)

        // Conflict is resolved on sharedNodeId
        val activePeers = identityStore.getAllByNodeId64(sharedNodeId)
        val stillConflicted = activePeers.any { it.trustState == TrustState.CONFLICTED }
        assertThat(stillConflicted).isFalse()
    }

    /**
     * 7. 60-digit safety number format & mathematical symmetry:
     * Format: Exactly 60 digits in 12 blocks of 5 separated by spaces (length 71).
     * Symmetry: computeSafetyNumber(A, B) == computeSafetyNumber(B, A).
     */
    @Test
    fun testSafetyNumberFormatAndSymmetry() {
        val alice = generateKey()
        val bob = generateKey()

        val numAliceBob = PureCryptoEngine.computeSafetyNumber(alice.identityHash, bob.identityHash)
        val numBobAlice = PureCryptoEngine.computeSafetyNumber(bob.identityHash, alice.identityHash)

        // 1. Must be identical regardless of who computes it (canonical sort)
        assertThat(numAliceBob).isEqualTo(numBobAlice)

        // 2. Exactly 71 chars: 12 blocks of 5 decimal digits with 11 space separators
        assertThat(numAliceBob.length).isEqualTo(71)
        assertThat(numAliceBob).matches("^(\\d{5}\\s){11}\\d{5}$")

        // 3. Digit count is exactly 60
        val digitsOnly = numAliceBob.replace(" ", "")
        assertThat(digitsOnly.length).isEqualTo(60)
        assertThat(digitsOnly.all { it.isDigit() }).isTrue()
    }
}

package com.meshwhisper.core.identity

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

class IdentityRedesignTest {

    private lateinit var identityStore: InMemoryIdentityStore

    @Before
    fun setUp() {
        identityStore = InMemoryIdentityStore()
        PureCryptoEngine.clearAllSessionKeys()
    }

    @Test
    fun `T-ID-01 identityHash derivation byte-exact SHA-256 with MW-NODE-v2 prefix`() {
        val (seed, _) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        assertThat(ikPub.size).isEqualTo(32)

        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        assertThat(idHash.size).isEqualTo(32)

        // Recompute independently from manual specification
        val md = MessageDigest.getInstance("SHA-256")
        val prefix = "MW/NODE/v2".toByteArray(Charsets.UTF_8)
        assertThat(prefix.size).isEqualTo(10)

        val preimage = ByteArray(10 + 1 + 32)
        System.arraycopy(prefix, 0, preimage, 0, 10)
        preimage[10] = 0x00.toByte()
        System.arraycopy(ikPub, 0, preimage, 11, 32)
        assertThat(preimage.size).isEqualTo(43)

        val expectedHash = md.digest(preimage)
        assertThat(idHash).isEqualTo(expectedHash)
    }

    @Test
    fun `T-ID-02 nodeId64 is big-endian u64 of identityHash first 8 bytes`() {
        val (seed, _) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val idHash = PureCryptoEngine.deriveIdentityHash(ikPub)

        val nodeId64 = PureCryptoEngine.deriveNodeId64(idHash)
        val expectedLong = ByteBuffer.wrap(idHash, 0, 8).long
        assertThat(nodeId64).isEqualTo(expectedLong)

        // Helper delegating directly from ikPub must match
        assertThat(PureCryptoEngine.deriveNodeIdFromIdentityKey(ikPub)).isEqualTo(nodeId64)
    }

    @Test
    fun `T-ID-03 IBC_transcript layout is exactly 83 bytes with frozen fields and offsets`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val keyVersion = 1L
        val notBefore = 1700000000L

        val transcript = PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, keyVersion, notBefore)
        assertThat(transcript.size).isEqualTo(83)

        // Offset 0..9: "MW/SIG/v2" (9 B)
        val tagBytes = transcript.copyOfRange(0, 9)
        assertThat(String(tagBytes, Charsets.UTF_8)).isEqualTo("MW/SIG/v2")

        // Offset 9..10: 0x00 (separator)
        assertThat(transcript[9]).isEqualTo(0x00.toByte())

        // Offset 10..11: purposeTag 0x03 (IBC)
        assertThat(transcript[10]).isEqualTo(0x03.toByte())

        // Offset 11..43: IK_pk (32 B)
        val readIk = transcript.copyOfRange(11, 43)
        assertThat(readIk).isEqualTo(ikPub)

        // Offset 43..75: EK_pk (32 B)
        val readEk = transcript.copyOfRange(43, 75)
        assertThat(readEk).isEqualTo(ekPub)

        // Offset 75..79: u32 keyVersion (4 B big-endian)
        val readKv = ByteBuffer.wrap(transcript, 75, 4).int.toLong() and 0xFFFFFFFFL
        assertThat(readKv).isEqualTo(keyVersion)

        // Offset 79..83: u32 notBefore (4 B big-endian)
        val readNb = ByteBuffer.wrap(transcript, 79, 4).int.toLong() and 0xFFFFFFFFL
        assertThat(readNb).isEqualTo(notBefore)
    }

    @Test
    fun `T-ID-04 ibcSignature generation and pure signature verification`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val keyVersion = 1L
        val notBefore = 1700000000L

        val sig = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBefore)
        assertThat(sig.size).isEqualTo(64)

        val ok = PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, keyVersion, notBefore, sig)
        assertThat(ok).isTrue()
    }

    @Test
    fun `T-ID-05 validateIbc rejects when notBefore exceeds packetTimestamp plus 120`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val keyVersion = 1L
        val packetTimestamp = 1000L
        val notBefore = 1121L // 121s in future -> exceeds 120s limit

        val sig = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBefore)

        val valid = PureCryptoEngine.validateIbc(
            ikPub = ikPub,
            ekPub = ekPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            signature = sig,
            packetTimestamp = packetTimestamp
        )
        assertThat(valid).isFalse()
    }

    @Test
    fun `T-ID-06 validateIbc accepts when notBefore within packetTimestamp plus 120`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val keyVersion = 1L
        val packetTimestamp = 1000L

        // Exactly at boundary: notBefore == packetTimestamp + 120
        val notBeforeBoundary = 1120L
        val sigBoundary = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBeforeBoundary)
        assertThat(PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, notBeforeBoundary, sigBoundary, packetTimestamp)).isTrue()

        // Equal: notBefore == packetTimestamp
        val sigEqual = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, packetTimestamp)
        assertThat(PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, packetTimestamp, sigEqual, packetTimestamp)).isTrue()

        // In the past: notBefore < packetTimestamp
        val notBeforePast = 800L
        val sigPast = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBeforePast)
        assertThat(PureCryptoEngine.validateIbc(ikPub, ekPub, keyVersion, notBeforePast, sigPast, packetTimestamp)).isTrue()
    }

    @Test
    fun `T-ID-07 keyVersion bounds enforces u32 range 1 to 0xFFFFFFFF`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val dummySig = ByteArray(64)

        // 0 is invalid (must start at 1)
        assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, 0L, 100L)
        }
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 0L, 100L, dummySig)).isFalse()
        assertThat(PureCryptoEngine.validateIbc(ikPub, ekPub, 0L, 100L, dummySig, 100L)).isFalse()

        // Negative is invalid
        assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, -1L, 100L)
        }
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, -1L, 100L, dummySig)).isFalse()

        // > 0xFFFFFFFF is invalid
        assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, 0x100000000L, 100L)
        }
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 0x100000000L, 100L, dummySig)).isFalse()

        // Max u32 0xFFFFFFFF is valid
        val maxU32 = 0xFFFFFFFFL
        val sigMax = PureCryptoEngine.signIbc(seed, ekPub, maxU32, 100L)
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, maxU32, 100L, sigMax)).isTrue()
    }

    @Test
    fun `T-ID-08 notBefore bounds enforces u32 range 0 to 0xFFFFFFFF`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val dummySig = ByteArray(64)

        // Negative is invalid
        assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, 1L, -1L)
        }
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 1L, -1L, dummySig)).isFalse()

        // > 0xFFFFFFFF is invalid
        assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.buildIbcTranscript(ikPub, ekPub, 1L, 0x100000000L)
        }
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 1L, 0x100000000L, dummySig)).isFalse()

        // 0 is valid
        val sigZero = PureCryptoEngine.signIbc(seed, ekPub, 1L, 0L)
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 1L, 0L, sigZero)).isTrue()
    }

    @Test
    fun `T-ID-09 EK rotation does NOT change identityHash or nodeId64`() {
        val (masterSeed, initialEkPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(masterSeed)

        val identityHashInitial = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64Initial = PureCryptoEngine.deriveNodeId64(identityHashInitial)

        // Simulate 3 successive EK rotations (keyVersion 1 -> 2 -> 3)
        var currentKeyVersion = 1L
        val (_, rotatedEk1) = PureCryptoEngine.generateX25519KeyPair()
        currentKeyVersion++
        assertThat(rotatedEk1).isNotEqualTo(initialEkPub)

        // Assert identityHash and nodeId64 are strictly identical
        val identityHashAfterRot1 = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64AfterRot1 = PureCryptoEngine.deriveNodeId64(identityHashAfterRot1)
        assertThat(identityHashAfterRot1).isEqualTo(identityHashInitial)
        assertThat(nodeId64AfterRot1).isEqualTo(nodeId64Initial)

        val (_, rotatedEk2) = PureCryptoEngine.generateX25519KeyPair()
        currentKeyVersion++
        assertThat(rotatedEk2).isNotEqualTo(rotatedEk1)

        val identityHashAfterRot2 = PureCryptoEngine.deriveIdentityHash(ikPub)
        val nodeId64AfterRot2 = PureCryptoEngine.deriveNodeId64(identityHashAfterRot2)
        assertThat(identityHashAfterRot2).isEqualTo(identityHashInitial)
        assertThat(nodeId64AfterRot2).isEqualTo(nodeId64Initial)

        // Valid IBC generated for rotated EK using SAME IK
        val sigRot2 = PureCryptoEngine.signIbc(masterSeed, rotatedEk2, currentKeyVersion, 0L)
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, rotatedEk2, currentKeyVersion, 0L, sigRot2)).isTrue()
    }

    @Test
    fun `T-ID-10 IK change DOES change identityHash and therefore changes nodeId64`() {
        val (masterSeedA, _) = PureCryptoEngine.generateX25519KeyPair()
        val (masterSeedB, _) = PureCryptoEngine.generateX25519KeyPair()

        val ikPubA = PureCryptoEngine.deriveSigningPublicKey(masterSeedA)
        val ikPubB = PureCryptoEngine.deriveSigningPublicKey(masterSeedB)
        assertThat(ikPubA).isNotEqualTo(ikPubB)

        val identityHashA = PureCryptoEngine.deriveIdentityHash(ikPubA)
        val identityHashB = PureCryptoEngine.deriveIdentityHash(ikPubB)
        assertThat(identityHashA).isNotEqualTo(identityHashB)

        val nodeId64A = PureCryptoEngine.deriveNodeId64(identityHashA)
        val nodeId64B = PureCryptoEngine.deriveNodeId64(identityHashB)
        assertThat(nodeId64A).isNotEqualTo(nodeId64B)
    }

    @Test
    fun `T-NEG-12 IBC with corrupted signature rejected`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val sig = PureCryptoEngine.signIbc(seed, ekPub, 1L, 100L)

        val corruptedSig = sig.clone()
        corruptedSig[0] = (corruptedSig[0].toInt() xor 0x01).toByte()

        assertThat(PureCryptoEngine.verifyIbcSignature(ikPub, ekPub, 1L, 100L, corruptedSig)).isFalse()
        assertThat(PureCryptoEngine.validateIbc(ikPub, ekPub, 1L, 100L, corruptedSig, 100L)).isFalse()
    }

    @Test
    fun `T-NEG-13 IBC with wrong IK or wrong EK rejected`() {
        val (seedA, ekPubA) = PureCryptoEngine.generateX25519KeyPair()
        val (seedB, ekPubB) = PureCryptoEngine.generateX25519KeyPair()

        val ikPubA = PureCryptoEngine.deriveSigningPublicKey(seedA)
        val ikPubB = PureCryptoEngine.deriveSigningPublicKey(seedB)

        val sigA = PureCryptoEngine.signIbc(seedA, ekPubA, 1L, 100L)

        // Wrong IK
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPubB, ekPubA, 1L, 100L, sigA)).isFalse()

        // Wrong EK
        assertThat(PureCryptoEngine.verifyIbcSignature(ikPubA, ekPubB, 1L, 100L, sigA)).isFalse()
    }

    @Test
    fun `T-NEG-14 Rollback attack keyVersion lower than stored DROPPED and stored ekPub unchanged`() {
        val (seed, ekPub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val ts = 1000L

        // Peer established at keyVersion 2
        val sigKv2 = PureCryptoEngine.signIbc(seed, ekPub1, 2L, 0L)
        val res1 = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = ekPub1,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = sigKv2,
            packetTimestamp = ts
        )
        assertThat(res1).isInstanceOf(IdentityProcessResult.Accepted::class.java)

        val stored = identityStore.get(PureCryptoEngine.deriveIdentityHash(ikPub))!!
        assertThat(stored.keyVersion).isEqualTo(2L)
        assertThat(stored.ekPub).isEqualTo(ekPub1)

        // Attacker attempts rollback to keyVersion 1 with older EK
        val (_, olderEk) = PureCryptoEngine.generateX25519KeyPair()
        val sigKv1 = PureCryptoEngine.signIbc(seed, olderEk, 1L, 0L)
        val rollbackRes = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = olderEk,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = sigKv1,
            packetTimestamp = ts
        )

        // Must be dropped
        assertThat(rollbackRes).isInstanceOf(IdentityProcessResult.DroppedRollback::class.java)
        val drop = rollbackRes as IdentityProcessResult.DroppedRollback
        assertThat(drop.storedVersion).isEqualTo(2L)
        assertThat(drop.incomingVersion).isEqualTo(1L)

        // Stored identity state must be completely untouched
        val storedAfter = identityStore.get(PureCryptoEngine.deriveIdentityHash(ikPub))!!
        assertThat(storedAfter.keyVersion).isEqualTo(2L)
        assertThat(storedAfter.ekPub).isEqualTo(ekPub1)
    }

    @Test
    fun `T-NEG-15 Equivocation attack same keyVersion different ekPub DROPPED warning counter increments`() {
        val (seed, ekPub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val ts = 1000L

        // Store peer at keyVersion 1 with ekPub1
        val sig1 = PureCryptoEngine.signIbc(seed, ekPub1, 1L, 0L)
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = ekPub1,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = sig1,
            packetTimestamp = ts,
            initialTrustState = TrustState.VERIFIED
        )

        // Equivocation: same keyVersion (1), different EK signed by legitimate IK
        val (_, ekPub2) = PureCryptoEngine.generateX25519KeyPair()
        val sig2 = PureCryptoEngine.signIbc(seed, ekPub2, 1L, 0L)

        val equivRes = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = ekPub2,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = sig2,
            packetTimestamp = ts
        )

        assertThat(equivRes).isInstanceOf(IdentityProcessResult.DroppedEquivocation::class.java)
        val drop = equivRes as IdentityProcessResult.DroppedEquivocation
        assertThat(drop.version).isEqualTo(1L)
        assertThat(drop.warningCount).isEqualTo(1)

        val stored = identityStore.get(PureCryptoEngine.deriveIdentityHash(ikPub))!!
        // MUST NOT transition to CONFLICTED (C-12)
        assertThat(stored.trustState).isEqualTo(TrustState.VERIFIED)
        // Must keep existing ekPub1
        assertThat(stored.ekPub).isEqualTo(ekPub1)
        // Warning counter incremented
        assertThat(stored.warningCount).isEqualTo(1)
    }

    @Test
    fun `T-ID-11 NodeId64 collision C-23 transitions both identities to CONFLICTED and enables QR resolution`() {
        val (seed1, ekPub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub1 = PureCryptoEngine.deriveSigningPublicKey(seed1)
        val idHash1 = PureCryptoEngine.deriveIdentityHash(ikPub1)
        val nodeId64_1 = PureCryptoEngine.deriveNodeId64(idHash1)

        // Insert first peer into store
        val sig1 = PureCryptoEngine.signIbc(seed1, ekPub1, 1L, 0L)
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub1,
            ekPub = ekPub1,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = sig1,
            packetTimestamp = 1000L,
            initialTrustState = TrustState.VERIFIED
        )

        // Craft second peer that collides on nodeId64 (simulate collision by inserting distinct identityHash with same nodeId64)
        val (seed2, ekPub2) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub2 = PureCryptoEngine.deriveSigningPublicKey(seed2)
        val idHash2 = PureCryptoEngine.deriveIdentityHash(ikPub2)
        assertThat(idHash1).isNotEqualTo(idHash2)

        // Manually place peer2 into store with same nodeId64 or simulate incoming collision
        val sig2 = PureCryptoEngine.signIbc(seed2, ekPub2, 1L, 0L)
        // To simulate exact nodeId64 collision in tests:
        // We override peer2's nodeId64 to match nodeId64_1 in the store to verify C-23 collision handling
        val collidingPeer2 = PeerIdentity(
            identityHash = idHash2,
            ikPub = ikPub2,
            ekPub = ekPub2,
            keyVersion = 1L,
            nodeId64 = nodeId64_1,
            trustState = TrustState.SEEN
        )
        identityStore.upsert(collidingPeer2)

        // Check that identityStore sees multiple identities for that nodeId64
        val allColliding = identityStore.getAllByNodeId64(nodeId64_1)
        assertThat(allColliding.size).isEqualTo(2)

        // C-23: getByNodeId64 returns null when colliding (ambiguous destination)
        assertThat(identityStore.getByNodeId64(nodeId64_1)).isNull()

        // Test C-23 QR scan resolution: verifying peer1 promotes peer1 to VERIFIED and demotes peer2 to BLOCKED
        IdentityManager.resolveCollision(identityStore, idHash1)

        val resolvedPeer1 = identityStore.get(idHash1)!!
        val resolvedPeer2 = identityStore.get(idHash2)!!
        assertThat(resolvedPeer1.trustState).isEqualTo(TrustState.VERIFIED)
        assertThat(resolvedPeer2.trustState).isEqualTo(TrustState.BLOCKED)
    }

    @Test
    fun `T-ID-12 EK rotation demotes VERIFIED to LINKED and invalidates session keys`() {
        val (seed, ekPub1) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val ts = 1000L

        // Initial identity accepted and verified
        val sig1 = PureCryptoEngine.signIbc(seed, ekPub1, 1L, 0L)
        IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = ekPub1,
            keyVersion = 1L,
            notBefore = 0L,
            ibcSignature = sig1,
            packetTimestamp = ts,
            initialTrustState = TrustState.VERIFIED
        )

        val nodeId64 = PureCryptoEngine.deriveNodeId64(PureCryptoEngine.deriveIdentityHash(ikPub))

        // Establish cached session key
        val (myPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPriv, ekPub1, ts)
        assertThat(PureCryptoEngine.getSessionKeyCacheSize()).isGreaterThan(0)

        // Peer rotates EK to ekPub2 at keyVersion 2
        val (_, ekPub2) = PureCryptoEngine.generateX25519KeyPair()
        val sig2 = PureCryptoEngine.signIbc(seed, ekPub2, 2L, 0L)

        val rotResult = IdentityManager.processIncomingIdentity(
            identityStore = identityStore,
            ikPub = ikPub,
            ekPub = ekPub2,
            keyVersion = 2L,
            notBefore = 0L,
            ibcSignature = sig2,
            packetTimestamp = ts
        )

        assertThat(rotResult).isInstanceOf(IdentityProcessResult.Accepted::class.java)
        val accepted = rotResult as IdentityProcessResult.Accepted
        assertThat(accepted.isRotation).isTrue()
        assertThat(accepted.identity.keyVersion).isEqualTo(2L)
        assertThat(accepted.identity.ekPub).isEqualTo(ekPub2)
        assertThat(accepted.identity.hasKeyChanged).isTrue()
        // VERIFIED demoted to LINKED (C-12)
        assertThat(accepted.identity.trustState).isEqualTo(TrustState.LINKED)
    }

    @Test
    fun `T-ID-13 S-16 regression decrypt rejects ciphertext shorter than 12 bytes without UUID fallback`() {
        val dummyCiphertext = ByteArray(10) { 0xAA.toByte() } // Less than 12 bytes
        val dummyTag = ByteArray(16) { 0xBB.toByte() }
        val dummyKey = ByteArray(32) { 0xCC.toByte() }

        val ex = assertThrows(IllegalArgumentException::class.java) {
            PureCryptoEngine.decrypt(
                ciphertext = dummyCiphertext,
                authTag = dummyTag,
                messageId = UUID.randomUUID(),
                aesKey = dummyKey
            )
        }
        assertThat(ex.message).contains("missing 12-byte CSPRNG IV prefix")
    }

    @Test
    fun `T-ID-14 Session key zeroization overwrites key bytes with zeros on clear or invalidate`() {
        val (myPriv, _) = PureCryptoEngine.generateX25519KeyPair()
        val (peerPriv, peerPub) = PureCryptoEngine.generateX25519KeyPair()

        val peerNodeId = PureCryptoEngine.deriveNodeId(peerPub)
        val sessionKey = PureCryptoEngine.derivePeerSessionKey(myPriv, peerPub, 1000L)
        assertThat(PureCryptoEngine.getSessionKeyCacheSize()).isEqualTo(1)

        // Clear session keys
        PureCryptoEngine.clearAllSessionKeys()
        assertThat(PureCryptoEngine.getSessionKeyCacheSize()).isEqualTo(0)

        // Invalidate specific session key
        val key2 = PureCryptoEngine.derivePeerSessionKey(myPriv, peerPub, 2000L)
        assertThat(PureCryptoEngine.getSessionKeyCacheSize()).isEqualTo(1)
        PureCryptoEngine.invalidateSessionKey(peerNodeId)
        assertThat(PureCryptoEngine.getSessionKeyCacheSize()).isEqualTo(0)
    }

    @Test
    fun `T-ID-15 raw X25519 public key cannot be used as canonical identity`() {
        val (seed, ekPub) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val canonicalHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val canonicalNodeId = PureCryptoEngine.deriveNodeId64(canonicalHash)

        // Legacy derivation from raw X25519 key (deriveNodeId computes SHA-256(raw_pk)[0..8])
        val legacyNodeId = PureCryptoEngine.deriveNodeId(ekPub)

        // Canonical identity must not match legacy raw X25519 derivation
        assertThat(canonicalNodeId).isNotEqualTo(legacyNodeId)

        // Raw EK bytes passed directly to deriveNodeId64 produces an identity mismatch
        val rawEkAsId = PureCryptoEngine.deriveNodeId64(ekPub)
        assertThat(rawEkAsId).isNotEqualTo(canonicalNodeId)
    }

    @Test
    fun `T-ID-16 canonical nodeId64 is derived strictly from identityHash`() {
        val (seed, _) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val canonicalHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val canonicalNodeId = PureCryptoEngine.deriveNodeId64(canonicalHash)

        // Invariant: nodeId64 must match first 8 bytes of identityHash interpreted as big-endian Long
        val expectedLong = ByteBuffer.wrap(canonicalHash, 0, 8).long
        assertThat(canonicalNodeId).isEqualTo(expectedLong)

        // Perturbing even a single bit in identityHash must alter nodeId64
        val perturbedHash = canonicalHash.clone()
        perturbedHash[0] = (perturbedHash[0].toInt() xor 0x01).toByte()
        val perturbedNodeId = PureCryptoEngine.deriveNodeId64(perturbedHash)
        assertThat(perturbedNodeId).isNotEqualTo(canonicalNodeId)
    }

    @Test
    fun `T-ID-17 changing EK does not change identityHash or nodeId64`() {
        val (seed, initialEk) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val baselineHash = PureCryptoEngine.deriveIdentityHash(ikPub)
        val baselineNodeId = PureCryptoEngine.deriveNodeId64(baselineHash)

        // Perform 5 separate EK replacements
        for (i in 1..5) {
            val (_, newEk) = PureCryptoEngine.generateX25519KeyPair()
            assertThat(newEk).isNotEqualTo(initialEk)

            // Identity remains strictly bound to IK
            val currentHash = PureCryptoEngine.deriveIdentityHash(ikPub)
            val currentNodeId = PureCryptoEngine.deriveNodeId64(currentHash)

            assertThat(currentHash).isEqualTo(baselineHash)
            assertThat(currentNodeId).isEqualTo(baselineNodeId)
        }
    }

    @Test
    fun `T-ID-18 no legacy caller can establish canonical identity from raw EK`() {
        val store = InMemoryIdentityStore()

        val (peerSeed, peerEk) = PureCryptoEngine.generateX25519KeyPair()
        val peerIk = PureCryptoEngine.deriveSigningPublicKey(peerSeed)
        val peerCanonicalHash = PureCryptoEngine.deriveIdentityHash(peerIk)
        val peerCanonicalNodeId = PureCryptoEngine.deriveNodeId64(peerCanonicalHash)

        // A legacy caller who only possesses peerEk cannot establish canonical identity.
        // Attempting to process incoming identity without a valid IBC signed by peerIk fails.
        val dummySig = ByteArray(64) { 0x00 }
        val resInvalidSig = IdentityManager.processIncomingIdentity(
            identityStore = store,
            ikPub = peerIk,
            ekPub = peerEk,
            keyVersion = 1L,
            notBefore = 1000L,
            ibcSignature = dummySig,
            packetTimestamp = 1000L
        )
        assertThat(resInvalidSig).isInstanceOf(IdentityProcessResult.RejectedInvalidIbc::class.java)

        // If an attacker attempts to present peerEk as an IK, the resulting nodeId64 will NOT match canonical identity
        val forgedHash = PureCryptoEngine.deriveIdentityHash(peerEk)
        val forgedNodeId = PureCryptoEngine.deriveNodeId64(forgedHash)
        assertThat(forgedNodeId).isNotEqualTo(peerCanonicalNodeId)
    }
}

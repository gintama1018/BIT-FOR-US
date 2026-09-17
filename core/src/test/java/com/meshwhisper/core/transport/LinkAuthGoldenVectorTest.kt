package com.meshwhisper.core.transport

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Deterministic Golden Test Vectors for Phase P4 LINK_AUTH.
 * Specified in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4.
 *
 * Verifies byte-for-byte protocol compliance against fixed hex constants:
 * - Two fixed HELLO payloads (168 B excluding stage byte)
 * - Lexicographical HELLO ordering by identityHash
 * - Exact Handshake Transcript T = SHA-256("MW/TCP/v2" || 0x00 || HELLO_lo || HELLO_hi)
 * - Exact Confirm signature preimage (107 B)
 * - Exact Confirm Ed25519 signature (64 B)
 * - Exact X25519 shared secret (32 B)
 * - Exact K_link HKDF-SHA256 derivation (32 B)
 */
class LinkAuthGoldenVectorTest {

    companion object {
        const val EXPECTED_ALICE_ID_HASH =
            "81292988b7545686b8a2a8c9d74d3fe0f9cbf473fb49a5c066946f5dcaca57b4"
        const val EXPECTED_BOB_ID_HASH =
            "4fb8b6b1153654637d92dcbee56361fe44745586a8763726a10cfbf05dbe53ff"

        const val EXPECTED_HELLO_ALICE_HEX =
            "5555555555555555555555555555555555555555555555555555555555555555" +
            "1a63e5d0e1c18e99031f2bf8b74f1d129fe4f42205bbcb6615512b8cd30fcd5e" +
            "7b0d47d93427f8311160781c7c733fd89f88970aef490d8aa0ee19a4cb8a1b14" +
            "00000001000003e8" +
            "a42b8cb0c3c9131eaa5cf5c63d0b1d8668b2d719f93b83e2a231d7ec081dac03" +
            "7b70f8111b4034efee20709b53cacec95fbd523a62f9016270dae93738bde20a"

        const val EXPECTED_HELLO_BOB_HEX =
            "6666666666666666666666666666666666666666666666666666666666666666" +
            "396401b8ae6db6fa0a5c9ece7796f2cbc0960d3ce8f361f5c13b7e39b07945e3" +
            "ff2ee45601ec1b67310c7790404585ae697331eee1c1f8cf2419731c1fff3e6b" +
            "00000001000003e8" +
            "6f78de89df67b001602e93497aadc36e831c75d5df5e07418a7c0606a96860c7" +
            "85a39d352b94d360236a8fa9205ea1adbd8c30d4299a558ff24ae6208f561708"

        const val EXPECTED_TRANSCRIPT_T_HEX =
            "7476758568e4675a09fdd60d122144257e578afe16a854934a05930c015f25c9"

        const val EXPECTED_PREIMAGE_ALICE_HEX =
            "4d572f5349472f76320004" +
            "7476758568e4675a09fdd60d122144257e578afe16a854934a05930c015f25c9" +
            "81292988b7545686b8a2a8c9d74d3fe0f9cbf473fb49a5c066946f5dcaca57b4" +
            "4fb8b6b1153654637d92dcbee56361fe44745586a8763726a10cfbf05dbe53ff"

        const val EXPECTED_PREIMAGE_BOB_HEX =
            "4d572f5349472f76320004" +
            "7476758568e4675a09fdd60d122144257e578afe16a854934a05930c015f25c9" +
            "4fb8b6b1153654637d92dcbee56361fe44745586a8763726a10cfbf05dbe53ff" +
            "81292988b7545686b8a2a8c9d74d3fe0f9cbf473fb49a5c066946f5dcaca57b4"

        const val EXPECTED_CONFIRM_SIG_ALICE_HEX =
            "430f4b55249a2e4092de77fba6f4756253aa9323e943807430080a6583b07307" +
            "b31ad16a23a43601486a1d69542271d3103b30f0b2e175d8a83430ff81451703"

        const val EXPECTED_CONFIRM_SIG_BOB_HEX =
            "94a991c2951da051d5eed22e8b936f796a3aaeda4581d22783bbe5af40892ea3" +
            "e871800e7f7033880d8575bd3192c23f6774e3d30bdeb7f6e204c9001df65401"

        const val EXPECTED_SHARED_SECRET_HEX =
            "3c528e9fd39731b15d10de8feb5f71d3f65b73c993581dedb03315a9ed177730"

        const val EXPECTED_K_LINK_HEX =
            "f3865812ce35777ef9251d859a91bc3eb35c21fe8e689ce0beff8dc101e78f31"
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    @Test
    fun testComputeAndVerifyGoldenVectors() {
        val aliceSeed = ByteArray(32) { 0x11.toByte() }
        val bobSeed = ByteArray(32) { 0x22.toByte() }
        val aliceEkPriv = ByteArray(32) { 0x33.toByte() }
        val bobEkPriv = ByteArray(32) { 0x44.toByte() }
        val aliceNonce = ByteArray(32) { 0x55.toByte() }
        val bobNonce = ByteArray(32) { 0x66.toByte() }
        val keyVersion = 1L
        val notBefore = 1000L

        // Alice Keys
        val aliceIkPub = PureCryptoEngine.deriveSigningPublicKey(aliceSeed)
        val aliceEkPub = PureCryptoEngine.derivePublicKey(aliceEkPriv)
        val aliceIbcSig = PureCryptoEngine.signIbc(aliceSeed, aliceEkPub, keyVersion, notBefore)
        val aliceIdentityHash = PureCryptoEngine.deriveIdentityHash(aliceIkPub)
        assertThat(bytesToHex(aliceIdentityHash)).isEqualTo(EXPECTED_ALICE_ID_HASH)

        // Bob Keys
        val bobIkPub = PureCryptoEngine.deriveSigningPublicKey(bobSeed)
        val bobEkPub = PureCryptoEngine.derivePublicKey(bobEkPriv)
        val bobIbcSig = PureCryptoEngine.signIbc(bobSeed, bobEkPub, keyVersion, notBefore)
        val bobIdentityHash = PureCryptoEngine.deriveIdentityHash(bobIkPub)
        assertThat(bytesToHex(bobIdentityHash)).isEqualTo(EXPECTED_BOB_ID_HASH)

        // 1. Build HELLO payloads (168 B)
        val helloAlicePayload = HelloPayload(
            nonce = aliceNonce,
            ikPub = aliceIkPub,
            ekPub = aliceEkPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            ibcSignature = aliceIbcSig
        ).toByteArray()
        assertThat(helloAlicePayload.size).isEqualTo(168)
        assertThat(bytesToHex(helloAlicePayload)).isEqualTo(EXPECTED_HELLO_ALICE_HEX)

        val helloBobPayload = HelloPayload(
            nonce = bobNonce,
            ikPub = bobIkPub,
            ekPub = bobEkPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            ibcSignature = bobIbcSig
        ).toByteArray()
        assertThat(helloBobPayload.size).isEqualTo(168)
        assertThat(bytesToHex(helloBobPayload)).isEqualTo(EXPECTED_HELLO_BOB_HEX)

        // 2. Lexicographical comparison of identityHash: Bob (4fb8...) < Alice (8129...)
        val cmp = PureCryptoEngine.compareLexicographically(aliceIdentityHash, bobIdentityHash)
        assertThat(cmp).isGreaterThan(0) // Alice > Bob

        // 3. Transcript T: Must place Bob (lo) first, Alice (hi) second
        val transcriptT = PureCryptoEngine.buildHandshakeTranscriptT(
            helloPayloadA = helloAlicePayload,
            helloPayloadB = helloBobPayload,
            identityHashA = aliceIdentityHash,
            identityHashB = bobIdentityHash
        )
        assertThat(transcriptT.size).isEqualTo(32)
        assertThat(bytesToHex(transcriptT)).isEqualTo(EXPECTED_TRANSCRIPT_T_HEX)

        // Symmetry: Swapping input positions must yield identical T
        val transcriptTReversed = PureCryptoEngine.buildHandshakeTranscriptT(
            helloPayloadA = helloBobPayload,
            helloPayloadB = helloAlicePayload,
            identityHashA = bobIdentityHash,
            identityHashB = aliceIdentityHash
        )
        assertThat(transcriptTReversed).isEqualTo(transcriptT)

        // 4. Confirm Preimage: 9 B "MW/SIG/v2" + 1 B 0x00 + 1 B 0x04 + 32 B T + 32 B self + 32 B peer = 107 B
        val preimageAlice = PureCryptoEngine.buildConfirmSigPreimage(
            transcriptT = transcriptT,
            identityHashSelf = aliceIdentityHash,
            identityHashPeer = bobIdentityHash
        )
        assertThat(preimageAlice.size).isEqualTo(107)
        assertThat(bytesToHex(preimageAlice)).isEqualTo(EXPECTED_PREIMAGE_ALICE_HEX)

        val preimageBob = PureCryptoEngine.buildConfirmSigPreimage(
            transcriptT = transcriptT,
            identityHashSelf = bobIdentityHash,
            identityHashPeer = aliceIdentityHash
        )
        assertThat(preimageBob.size).isEqualTo(107)
        assertThat(bytesToHex(preimageBob)).isEqualTo(EXPECTED_PREIMAGE_BOB_HEX)

        // 5. Confirm Signatures
        val confirmSigAlice = PureCryptoEngine.sign(aliceSeed, preimageAlice)
        assertThat(confirmSigAlice.size).isEqualTo(64)
        assertThat(bytesToHex(confirmSigAlice)).isEqualTo(EXPECTED_CONFIRM_SIG_ALICE_HEX)
        assertThat(PureCryptoEngine.verifySignature(aliceIkPub, preimageAlice, confirmSigAlice)).isTrue()

        val confirmSigBob = PureCryptoEngine.sign(bobSeed, preimageBob)
        assertThat(confirmSigBob.size).isEqualTo(64)
        assertThat(bytesToHex(confirmSigBob)).isEqualTo(EXPECTED_CONFIRM_SIG_BOB_HEX)
        assertThat(PureCryptoEngine.verifySignature(bobIkPub, preimageBob, confirmSigBob)).isTrue()

        // 6. Shared Secret (X25519)
        val agreement = X25519Agreement()
        agreement.init(X25519PrivateKeyParameters(aliceEkPriv, 0))
        val sharedSecret = ByteArray(32)
        agreement.calculateAgreement(X25519PublicKeyParameters(bobEkPub, 0), sharedSecret, 0)
        assertThat(sharedSecret.size).isEqualTo(32)
        assertThat(bytesToHex(sharedSecret)).isEqualTo(EXPECTED_SHARED_SECRET_HEX)

        // 7. K_link: HKDF-SHA256(sharedSecret, salt="MW/LINK/SALT/v2", info="link"||T, 32)
        val kLinkAlice = PureCryptoEngine.deriveLinkKey(aliceEkPriv, bobEkPub, transcriptT)
        val kLinkBob = PureCryptoEngine.deriveLinkKey(bobEkPriv, aliceEkPub, transcriptT)
        assertThat(kLinkAlice).isEqualTo(kLinkBob)
        assertThat(kLinkAlice.size).isEqualTo(32)
        assertThat(bytesToHex(kLinkAlice)).isEqualTo(EXPECTED_K_LINK_HEX)
    }
}

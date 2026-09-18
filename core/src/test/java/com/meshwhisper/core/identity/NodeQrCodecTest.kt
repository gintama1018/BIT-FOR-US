package com.meshwhisper.core.identity

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.crypto.PureCryptoEngine
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NodeQrCodecTest {

    @Test
    fun `T-QR-01 valid encode and decode roundtrip matches all fields byte-exact`() {
        val (seed, _) = PureCryptoEngine.generateX25519KeyPair()
        val ikPub = PureCryptoEngine.deriveSigningPublicKey(seed)
        val (_, ekPub) = PureCryptoEngine.generateX25519KeyPair()

        val keyVersion = 1L
        val notBefore = 1000L
        val sig = PureCryptoEngine.signIbc(seed, ekPub, keyVersion, notBefore)

        val original = NodeQrData(
            ikPub = ikPub,
            ekPub = ekPub,
            keyVersion = keyVersion,
            notBefore = notBefore,
            ibcSignature = sig,
            alias = "Alice Explorer"
        )

        val encoded = NodeQrCodec.encode(original)
        assertThat(encoded).startsWith("meshwhisper://node/v2?")

        val decoded = NodeQrCodec.decode(encoded)
        assertNotNull(decoded)
        decoded!!

        assertThat(decoded.ikPub).isEqualTo(original.ikPub)
        assertThat(decoded.ekPub).isEqualTo(original.ekPub)
        assertThat(decoded.keyVersion).isEqualTo(original.keyVersion)
        assertThat(decoded.notBefore).isEqualTo(original.notBefore)
        assertThat(decoded.ibcSignature).isEqualTo(original.ibcSignature)
        assertThat(decoded.alias).isEqualTo("Alice Explorer")
        assertThat(decoded.identityHash).isEqualTo(original.identityHash)
        assertThat(decoded.nodeId64).isEqualTo(original.nodeId64)

        // IBC validation
        val valid = PureCryptoEngine.validateIbc(
            ikPub = decoded.ikPub,
            ekPub = decoded.ekPub,
            keyVersion = decoded.keyVersion,
            notBefore = decoded.notBefore,
            signature = decoded.ibcSignature,
            packetTimestamp = 1050L
        )
        assertThat(valid).isTrue()
    }

    @Test
    fun `T-QR-02 reject missing URI prefix or wrong protocol scheme`() {
        val original = NodeQrData(
            ikPub = ByteArray(32) { 2 },
            ekPub = ByteArray(32) { 3 },
            keyVersion = 1L,
            notBefore = 100L,
            ibcSignature = ByteArray(64) { 4 },
            alias = "Bob"
        )
        val encoded = NodeQrCodec.encode(original)

        // Missing prefix
        val withoutPrefix = encoded.removePrefix("meshwhisper://node/v2?")
        assertNull(NodeQrCodec.decode(withoutPrefix))

        // Wrong scheme
        val wrongScheme = "otherproto://node/v2?" + withoutPrefix
        assertNull(NodeQrCodec.decode(wrongScheme))

        // Unsupported version
        val wrongVersion = encoded.replace("/node/v2?", "/node/v1?")
        assertNull(NodeQrCodec.decode(wrongVersion))
    }

    @Test
    fun `T-QR-03 reject truncated or malformed payload`() {
        // Missing parameters
        assertNull(NodeQrCodec.decode("meshwhisper://node/v2?ik=0102&ek=0304"))

        // Invalid hex lengths
        assertNull(NodeQrCodec.decode("meshwhisper://node/v2?ik=deadbeef&ek=deadbeef&kv=1&nb=0&ibc=deadbeef&alias=test"))

        // Empty string
        assertNull(NodeQrCodec.decode(""))

        // Out of bounds keyVersion
        val validOriginal = NodeQrData(
            ikPub = ByteArray(32) { 1 },
            ekPub = ByteArray(32) { 2 },
            keyVersion = 1L,
            notBefore = 100L,
            ibcSignature = ByteArray(64) { 3 },
            alias = "test"
        )
        val encoded = NodeQrCodec.encode(validOriginal)
        val invalidKv = encoded.replace("kv=1", "kv=-5")
        assertNull(NodeQrCodec.decode(invalidKv))
    }
}

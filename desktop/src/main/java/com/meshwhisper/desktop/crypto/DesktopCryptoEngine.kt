package com.meshwhisper.desktop.crypto

import com.meshwhisper.core.crypto.EncryptedResult
import com.meshwhisper.core.crypto.PureCryptoEngine
import java.util.UUID

/**
 * Desktop cryptographic engine encapsulating cryptographic operations.
 * Allows router/ and media/ to remain decoupled from direct PureCryptoEngine calls (T-ARCH-01).
 */
object DesktopCryptoEngine {
    fun derivePublicKey(privateKey: ByteArray): ByteArray =
        PureCryptoEngine.derivePublicKey(privateKey)

    fun deriveSigningPublicKey(identitySeed: ByteArray): ByteArray =
        PureCryptoEngine.deriveSigningPublicKey(identitySeed)

    fun deriveIdentityHash(ikPub: ByteArray): ByteArray =
        PureCryptoEngine.deriveIdentityHash(ikPub)

    fun deriveNodeId64(identityHash: ByteArray): Long =
        PureCryptoEngine.deriveNodeId64(identityHash)

    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> =
        PureCryptoEngine.generateX25519KeyPair()

    fun sign(identitySeed: ByteArray, data: ByteArray): ByteArray =
        PureCryptoEngine.sign(identitySeed, data)

    fun encrypt(
        plaintext: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): EncryptedResult = PureCryptoEngine.encrypt(plaintext, messageId, aesKey, aad)

    fun derivePeerSessionKey(
        myPrivateKey: ByteArray,
        peerPublicKeyBytes: ByteArray,
        timestampSec: Long
    ): ByteArray = PureCryptoEngine.derivePeerSessionKey(myPrivateKey, peerPublicKeyBytes, timestampSec)

    fun derivePublicChannelKey(): ByteArray =
        PureCryptoEngine.derivePublicChannelKey()

    fun hexToBytes(hex: String): ByteArray =
        PureCryptoEngine.hexToBytes(hex)

    fun bytesToHex(bytes: ByteArray): String =
        PureCryptoEngine.bytesToHex(bytes)

    fun signIbc(
        identitySeed: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long
    ): ByteArray = PureCryptoEngine.signIbc(identitySeed, ekPub, keyVersion, notBefore)

    fun generateFingerprint(publicKeyBytes: ByteArray): String =
        PureCryptoEngine.generateFingerprint(publicKeyBytes)

    fun hkdf(
        ikm: ByteArray,
        salt: ByteArray?,
        info: ByteArray,
        outputLength: Int
    ): ByteArray = PureCryptoEngine.hkdf(ikm, salt, info, outputLength)
}

package com.meshwhisper.core.crypto

import com.meshwhisper.core.protocol.MeshPacket
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class EncryptedResult(
    val ciphertext: ByteArray,
    val authTag: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncryptedResult
        return ciphertext.contentEquals(other.ciphertext) && authTag.contentEquals(other.authTag)
    }

    override fun hashCode(): Int {
        var result = ciphertext.contentHashCode()
        result = 31 * result + authTag.contentHashCode()
        return result
    }
}

/**
 * Pure JVM/Kotlin Cryptographic Engine for MeshWhisper.
 * Free from Android-specific SDK dependencies and shared across Android, Windows, and macOS.
 */
object PureCryptoEngine {

    val PUBLIC_EMERGENCY_CHANNEL_SALT = "MESHWHISPER_PUBLIC_SALT_9A8B7C6D5E".toByteArray(Charsets.UTF_8)
    val PUBLIC_CHANNEL_SALT = PUBLIC_EMERGENCY_CHANNEL_SALT
    val HKDF_DM_SALT = "MESHWHISPER_DM_SALT_1F2E3D4C5B6A".toByteArray(Charsets.UTF_8)
    val LINK_KEY_SALT = "MW/LINK/SALT/v2".toByteArray(Charsets.UTF_8)
    private const val PUBLIC_EMERGENCY_IKM = "MESHWHISPER_PUBLIC_EMERGENCY_DISASTER_ROOT_V1"

    private val secureRandom = SecureRandom()
    private const val MAX_SESSION_KEY_CACHE_SIZE = 256
    private val sessionKeyEpochCache = object : java.util.LinkedHashMap<String, ByteArray>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean {
            if (size > MAX_SESSION_KEY_CACHE_SIZE) {
                eldest?.value?.let { java.util.Arrays.fill(it, 0.toByte()) }
                return true
            }
            return false
        }
    }

    /**
     * Generates a new random X25519 keypair (Pair(privateKeyBytes, publicKeyBytes)).
     */
    fun generateX25519KeyPair(): Pair<ByteArray, ByteArray> {
        val keyGen = X25519KeyPairGenerator()
        keyGen.init(X25519KeyGenerationParameters(secureRandom))
        val keyPair = keyGen.generateKeyPair()

        val privParams = keyPair.private as X25519PrivateKeyParameters
        val pubParams = keyPair.public as X25519PublicKeyParameters

        return Pair(privParams.encoded, pubParams.encoded)
    }

    /**
     * Derives public key from a given 32-byte private key.
     */
    fun derivePublicKey(privateKey: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(privateKey, 0)
        return privParams.generatePublicKey().encoded
    }

    /**
     * Derives a 32-byte Ed25519 signing private key from the master identity private seed using domain-separated HKDF-SHA256.
     */
    fun deriveSigningPrivateKey(identitySeed: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(identitySeed, HKDF_DM_SALT, "MESHWHISPER_ED25519_SIGNING_KEY_V1".toByteArray(Charsets.UTF_8)))
        val out = ByteArray(32)
        hkdf.generateBytes(out, 0, 32)
        return out
    }

    /**
     * Derives a 32-byte Ed25519 signing public key from the master identity private seed.
     */
    fun deriveSigningPublicKey(identitySeed: ByteArray): ByteArray {
        val signingPriv = deriveSigningPrivateKey(identitySeed)
        val privParams = Ed25519PrivateKeyParameters(signingPriv, 0)
        return privParams.generatePublicKey().encoded
    }

    /**
     * Cryptographically signs a message/packet byte payload with the node's Ed25519 identity key.
     * Produces an unforgeable 64-byte Ed25519 digital signature.
     */
    fun sign(identitySeed: ByteArray, data: ByteArray): ByteArray {
        val signingPriv = deriveSigningPrivateKey(identitySeed)
        val privParams = Ed25519PrivateKeyParameters(signingPriv, 0)
        val signer = Ed25519Signer()
        signer.init(true, privParams)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    /**
     * Verifies an Ed25519 digital signature against the claimed sender's 32-byte signing public key.
     */
    fun verifySignature(signingPublicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 64 || signingPublicKey.size != 32) return false
        return try {
            val pubParams = Ed25519PublicKeyParameters(signingPublicKey, 0)
            val verifier = Ed25519Signer()
            verifier.init(false, pubParams)
            verifier.update(data, 0, data.size)
            verifier.verifySignature(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Derives 32-byte identityHash according to NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §2.2:
     * identityHash = SHA-256("MW/NODE/v2" || 0x00 || IK_pk)
     * Total preimage: 10 + 1 + 32 = 43 bytes.
     */
    fun deriveIdentityHash(ikPub: ByteArray): ByteArray {
        require(ikPub.size == 32) { "ikPub must be exactly 32 bytes, got ${ikPub.size}" }
        val md = MessageDigest.getInstance("SHA-256")
        md.update("MW/NODE/v2".toByteArray(Charsets.UTF_8))
        md.update(0x00.toByte())
        md.update(ikPub)
        return md.digest()
    }

    /**
     * Derives 64-bit Long Node ID from identityHash according to §2.3:
     * nodeId64 = big-endian u64 of identityHash[0..8]
     */
    fun deriveNodeId64(identityHash: ByteArray): Long {
        require(identityHash.size >= 8) { "identityHash must be at least 8 bytes, got ${identityHash.size}" }
        val buffer = ByteBuffer.wrap(identityHash, 0, 8)
        return buffer.long
    }

    fun deriveNodeIdFromIdentityKey(ikPub: ByteArray): Long {
        return deriveNodeId64(deriveIdentityHash(ikPub))
    }

    /**
     * Builds the 83-byte IBC transcript according to §2.4:
     * "MW/SIG/v2" (9 B) || 0x00 (1 B) || 0x03 (1 B) || IK_pk (32 B) || EK_pk (32 B) || u32 keyVersion (4 B) || u32 notBefore (4 B)
     */
    fun buildIbcTranscript(
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long
    ): ByteArray {
        require(ikPub.size == 32) { "ikPub must be 32 bytes, got ${ikPub.size}" }
        require(ekPub.size == 32) { "ekPub must be 32 bytes, got ${ekPub.size}" }
        require(keyVersion in 1L..0xFFFFFFFFL) { "keyVersion must be in 1..0xFFFFFFFF, got $keyVersion" }
        require(notBefore in 0L..0xFFFFFFFFL) { "notBefore must be in 0..0xFFFFFFFF, got $notBefore" }

        val buf = ByteBuffer.allocate(83)
        buf.put("MW/SIG/v2".toByteArray(Charsets.UTF_8)) // 9 B
        buf.put(0x00.toByte())                           // 1 B
        buf.put(0x03.toByte())                           // 1 B (IBC purpose tag)
        buf.put(ikPub)                                   // 32 B
        buf.put(ekPub)                                   // 32 B
        buf.putInt(keyVersion.toInt())                   // 4 B big-endian
        buf.putInt(notBefore.toInt())                    // 4 B big-endian
        return buf.array()
    }

    /**
     * Cryptographically signs an IBC transcript using the master identity seed (IK_sk).
     * Produces a 64-byte Ed25519 digital signature.
     */
    fun signIbc(
        identitySeed: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long
    ): ByteArray {
        val ikPub = deriveSigningPublicKey(identitySeed)
        val transcript = buildIbcTranscript(ikPub, ekPub, keyVersion, notBefore)
        return sign(identitySeed, transcript)
    }

    /**
     * Pure signature verification of an Identity Binding Certificate (IBC).
     * Enforces u32 bounds for keyVersion and notBefore, but does NOT validate protocol timestamp.
     */
    fun verifyIbcSignature(
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long,
        signature: ByteArray
    ): Boolean {
        if (ikPub.size != 32 || ekPub.size != 32 || signature.size != 64) return false
        if (keyVersion < 1L || keyVersion > 0xFFFFFFFFL) return false
        if (notBefore < 0L || notBefore > 0xFFFFFFFFL) return false

        return try {
            val transcript = buildIbcTranscript(ikPub, ekPub, keyVersion, notBefore)
            verifySignature(ikPub, transcript, signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Full protocol validation of an Identity Binding Certificate (IBC) according to §2.4 & C-11.
     * Always requires packetTimestamp. Enforces:
     * - u32 bounds on keyVersion (1..0xFFFFFFFF), notBefore (0..0xFFFFFFFF), and packetTimestamp (0..0xFFFFFFFF)
     * - notBefore <= packetTimestamp + 120
     * - valid Ed25519 signature over IBC_transcript
     */
    fun validateIbc(
        ikPub: ByteArray,
        ekPub: ByteArray,
        keyVersion: Long,
        notBefore: Long,
        signature: ByteArray,
        packetTimestamp: Long
    ): Boolean {
        if (keyVersion < 1L || keyVersion > 0xFFFFFFFFL) return false
        if (notBefore < 0L || notBefore > 0xFFFFFFFFL) return false
        if (packetTimestamp < 0L || packetTimestamp > 0xFFFFFFFFL) return false

        // Enforce notBefore <= packetTimestamp + 120 (C-11)
        if (notBefore > packetTimestamp + 120L) {
            return false
        }

        return verifyIbcSignature(ikPub, ekPub, keyVersion, notBefore, signature)
    }

    /**
     * Builds the Handshake Transcript T according to NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4:
     * T = SHA-256("MW/TCP/v2" ‖ 0x00 ‖ HELLO_lo ‖ HELLO_hi)
     * where HELLO_lo / HELLO_hi are the full 168-byte HELLO payloads (excluding stage byte 0x01),
     * ordered by lexicographic comparison of the two 32-byte identityHash values.
     * If identityHashA == identityHashB, throws IllegalArgumentException (reflection/self-connect rejected).
     */
    fun buildHandshakeTranscriptT(
        helloPayloadA: ByteArray,
        helloPayloadB: ByteArray,
        identityHashA: ByteArray,
        identityHashB: ByteArray
    ): ByteArray {
        require(helloPayloadA.size == 168) { "helloPayloadA must be exactly 168 bytes, got ${helloPayloadA.size}" }
        require(helloPayloadB.size == 168) { "helloPayloadB must be exactly 168 bytes, got ${helloPayloadB.size}" }
        require(identityHashA.size == 32) { "identityHashA must be 32 bytes" }
        require(identityHashB.size == 32) { "identityHashB must be 32 bytes" }

        val cmp = compareLexicographically(identityHashA, identityHashB)
        require(cmp != 0) { "identityHashA and identityHashB are identical (reflection attack rejected)" }

        val (helloLo, helloHi) = if (cmp < 0) {
            Pair(helloPayloadA, helloPayloadB)
        } else {
            Pair(helloPayloadB, helloPayloadA)
        }

        val prefix = "MW/TCP/v2".toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prefix)
        md.update(0.toByte())
        md.update(helloLo)
        md.update(helloHi)
        return md.digest()
    }

    /**
     * Compares two byte arrays lexicographically as unsigned bytes.
     */
    fun compareLexicographically(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val byteA = a[i].toInt() and 0xFF
            val byteB = b[i].toInt() and 0xFF
            if (byteA != byteB) {
                return byteA.compareTo(byteB)
            }
        }
        return a.size.compareTo(b.size)
    }

    /**
     * Builds the preimage for LINK_AUTH CONFIRM signature (confirmSig):
     * "MW/SIG/v2" ‖ 0x00 ‖ 0x04 ‖ T ‖ identityHash_self ‖ identityHash_peer
     * (9 + 1 + 1 + 32 + 32 + 32 = 107 bytes)
     */
    fun buildConfirmSigPreimage(
        transcriptT: ByteArray,
        identityHashSelf: ByteArray,
        identityHashPeer: ByteArray
    ): ByteArray {
        require(transcriptT.size == 32) { "transcriptT must be 32 bytes" }
        require(identityHashSelf.size == 32) { "identityHashSelf must be 32 bytes" }
        require(identityHashPeer.size == 32) { "identityHashPeer must be 32 bytes" }

        val buffer = ByteBuffer.allocate(107)
        buffer.put("MW/SIG/v2".toByteArray(Charsets.UTF_8)) // 9 bytes
        buffer.put(0.toByte()) // 1 byte separator
        buffer.put(0x04.toByte()) // 1 byte purpose tag PURPOSE_LINK
        buffer.put(transcriptT) // 32 bytes
        buffer.put(identityHashSelf) // 32 bytes
        buffer.put(identityHashPeer) // 32 bytes
        return buffer.array()
    }

    /**
     * Derives K_link according to NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.4 & §6.3:
     * K_link = HKDF(X25519(EK_sk_self, EK_pk_peer), salt = "MW/LINK/SALT/v2", info = "link" ‖ T, 32)
     */
    fun deriveLinkKey(
        myEkPrivateKey: ByteArray,
        peerEkPublicKey: ByteArray,
        transcriptT: ByteArray
    ): ByteArray {
        require(myEkPrivateKey.size == 32) { "myEkPrivateKey must be 32 bytes" }
        require(peerEkPublicKey.size == 32) { "peerEkPublicKey must be 32 bytes" }
        require(transcriptT.size == 32) { "transcriptT must be 32 bytes" }

        val privParams = X25519PrivateKeyParameters(myEkPrivateKey, 0)
        val pubParams = X25519PublicKeyParameters(peerEkPublicKey, 0)

        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)

        val infoPrefix = "link".toByteArray(Charsets.UTF_8)
        val info = ByteArray(infoPrefix.size + transcriptT.size)
        System.arraycopy(infoPrefix, 0, info, 0, infoPrefix.size)
        System.arraycopy(transcriptT, 0, info, infoPrefix.size, transcriptT.size)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val params = HKDFParameters(
            sharedSecret,
            LINK_KEY_SALT,
            info
        )
        hkdf.init(params)
        val linkKey = ByteArray(32)
        hkdf.generateBytes(linkKey, 0, 32)
        return linkKey
    }

    /**
     * Derives a 64-bit Long Node ID from public key bytes using first 8 bytes of SHA-256 (v1 legacy).
     */
    fun deriveNodeId(publicKeyBytes: ByteArray): Long {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKeyBytes)
        val buffer = ByteBuffer.wrap(hash)
        return buffer.long
    }

    /**
     * Formats public key fingerprint as truncated visual hex: XX:XX:XX:XX
     */
    fun generateFingerprint(publicKeyBytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val hash = md.digest(publicKeyBytes)
        val hex = bytesToHex(hash).take(16).uppercase()
        return hex.chunked(4).joinToString(":")
    }

    /**
     * Calculates time epoch (1 hour window) for session key rotation.
     */
    fun getEpochForTimestamp(timestampSec: Long): Long = timestampSec / 3600L

    /**
     * Derives a 256-bit AES symmetric key for a specific peer and epoch using X25519 ECDH + HKDF-SHA256.
     */
    fun derivePeerSessionKey(
        myPrivateKey: ByteArray,
        peerPublicKeyBytes: ByteArray,
        timestampSec: Long = System.currentTimeMillis() / 1000L
    ): ByteArray {
        val myPubKey = derivePublicKey(myPrivateKey)
        val myNodeId = deriveNodeId(myPubKey)
        val peerNodeId = deriveNodeId(peerPublicKeyBytes)
        val epoch = getEpochForTimestamp(timestampSec)
        
        val id1 = minOf(myNodeId, peerNodeId)
        val id2 = maxOf(myNodeId, peerNodeId)
        val cacheKey = "$id1:$id2:$epoch"
        synchronized(sessionKeyEpochCache) {
            val cached = sessionKeyEpochCache[cacheKey]
            if (cached != null) return cached
        }

        val privParams = X25519PrivateKeyParameters(myPrivateKey, 0)
        val pubParams = X25519PublicKeyParameters(peerPublicKeyBytes, 0)

        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)

        // HKDF-SHA256 expansion to 32 bytes with epoch-bound info string
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        val info = "MESHWHISPER_SESSION_KEY_V1_EPOCH_$epoch".toByteArray(Charsets.UTF_8)
        val params = HKDFParameters(
            sharedSecret,
            HKDF_DM_SALT,
            info
        )
        hkdf.init(params)
        val sessionKey = ByteArray(32)
        hkdf.generateBytes(sessionKey, 0, 32)

        synchronized(sessionKeyEpochCache) {
            sessionKeyEpochCache[cacheKey] = sessionKey
        }
        return sessionKey
    }

    /**
     * Derives shared Public Emergency Channel AES-256 key.
     * Open disaster broadcast channel for search & rescue, beacons, and civilian alerts.
     * Spoof prevention is cryptographically guaranteed via Ed25519 identity signatures.
     */
    fun derivePublicChannelKey(): ByteArray {
        return derivePublicEmergencyChannelKey()
    }

    fun derivePublicEmergencyChannelKey(): ByteArray {
        return deriveKeyFromMasterSalt(PUBLIC_CHANNEL_SALT, "MESHWHISPER_PUBLIC_EMERGENCY_V1".toByteArray(Charsets.UTF_8))
    }

    /**
     * Derives a confidential 256-bit AES-GCM channel key for private tactical groups,
     * first responders, and custom mesh channels using PBKDF2-HMAC-SHA256 (100,000 iterations).
     *
     * @param channelName Name of the channel/team (e.g. "TEAM_ALPHA")
     * @param passphrase Secret passphrase known only to authorized team members
     * @param salt Optional 16-byte custom salt; defaults to channel-specific SHA-256 derived salt
     */
    fun deriveTeamChannelKey(
        channelName: String,
        passphrase: String,
        salt: ByteArray? = null
    ): ByteArray {
        val actualSalt = salt ?: deriveChannelSalt(channelName)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), actualSalt, 100_000, 256)
        return factory.generateSecret(spec).encoded
    }

    fun deriveChannelSalt(channelName: String): ByteArray {
        val digest = SHA256Digest()
        val input = "MESHWHISPER_TACTICAL_CHANNEL_SALT_V1:$channelName".toByteArray(Charsets.UTF_8)
        digest.update(input, 0, input.size)
        val hash = ByteArray(32)
        digest.doFinal(hash, 0)
        val outSalt = ByteArray(16)
        System.arraycopy(hash, 0, outSalt, 0, 16)
        return outSalt
    }

    fun invalidateSessionKey(peerNodeId: Long) {
        val target = peerNodeId.toString()
        synchronized(sessionKeyEpochCache) {
            val it = sessionKeyEpochCache.entries.iterator()
            while (it.hasNext()) {
                val entry = it.next()
                val parts = entry.key.split(":")
                if (parts.size >= 2 && (parts[0] == target || parts[1] == target)) {
                    java.util.Arrays.fill(entry.value, 0.toByte())
                    it.remove()
                }
            }
        }
    }

    fun clearAllSessionKeys() {
        synchronized(sessionKeyEpochCache) {
            for (key in sessionKeyEpochCache.values) {
                java.util.Arrays.fill(key, 0.toByte())
            }
            sessionKeyEpochCache.clear()
        }
    }

    fun getSessionKeyCacheSize(): Int = synchronized(sessionKeyEpochCache) { sessionKeyEpochCache.size }

    /**
     * Encrypts plaintext using AES-256-GCM with a fresh CSPRNG 96-bit nonce (NIST SP 800-38D RBG Construction).
     * Nonce is prepended to ciphertext: [12-byte CSPRNG IV][raw ciphertext].
     * Binds Additional Authenticated Data (AAD) into authentication tag.
     */
    fun encrypt(
        plaintext: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null,
        explicitIv: ByteArray? = null
    ): EncryptedResult {
        // Generate an independent 12-byte cryptographically secure random nonce or use explicit
        val iv = explicitIv ?: ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(aesKey, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)

        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        val encryptedWithTag = cipher.doFinal(plaintext)

        val tagSize = MeshPacket.AUTH_TAG_SIZE
        val rawCiphertextSize = encryptedWithTag.size - tagSize
        
        // Output wire ciphertext = [12-byte CSPRNG IV] + [raw ciphertext]
        val outputCiphertext = ByteArray(12 + rawCiphertextSize)
        System.arraycopy(iv, 0, outputCiphertext, 0, 12)
        System.arraycopy(encryptedWithTag, 0, outputCiphertext, 12, rawCiphertextSize)

        val authTag = ByteArray(tagSize)
        System.arraycopy(encryptedWithTag, rawCiphertextSize, authTag, 0, tagSize)

        return EncryptedResult(outputCiphertext, authTag)
    }

    /**
     * Decrypts ciphertext and verifies 128-bit AEAD tag + AAD header binding.
     * Enforces NIST SP 800-38D: Parses fresh 12-byte CSPRNG IV from ciphertext prefix.
     * Legacy UUID-derived IV fallback has been removed (Finding S-16).
     */
    fun decrypt(
        ciphertext: ByteArray,
        authTag: ByteArray,
        messageId: UUID,
        aesKey: ByteArray,
        aad: ByteArray? = null
    ): ByteArray {
        if (ciphertext.size < 12) {
            throw IllegalArgumentException("Ciphertext too short: missing 12-byte CSPRNG IV prefix (size=${ciphertext.size})")
        }

        val keySpec = SecretKeySpec(aesKey, "AES")
        val iv = ciphertext.copyOfRange(0, 12)
        val rawCiphertext = ciphertext.copyOfRange(12, ciphertext.size)
        val combined = ByteArray(rawCiphertext.size + authTag.size)
        System.arraycopy(rawCiphertext, 0, combined, 0, rawCiphertext.size)
        System.arraycopy(authTag, 0, combined, rawCiphertext.size, authTag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        if (aad != null) {
            cipher.updateAAD(aad)
        }
        return cipher.doFinal(combined)
    }

    fun extractIvFromUuid(uuid: UUID): ByteArray {
        val buffer = ByteBuffer.allocate(16)
        buffer.putLong(uuid.mostSignificantBits)
        buffer.putLong(uuid.leastSignificantBits)
        val fullBytes = buffer.array()
        val iv = ByteArray(12)
        System.arraycopy(fullBytes, 0, iv, 0, 12)
        return iv
    }

    fun deriveKeyFromMasterSalt(salt: ByteArray, info: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(PUBLIC_EMERGENCY_IKM.toByteArray(Charsets.UTF_8), salt, info))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, 32)
        return key
    }

    fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) +
                    Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }

    /**
     * Post-CONFIRM K_link transport frame encryption.
     * Output frame format: [12-byte IV] + [ciphertext] + [16-byte AES-GCM tag]
     * For maximum plaintext (2104 B), total output frame size is exactly 2132 B.
     */
    fun encryptTransportFrame(plaintext: ByteArray, linkKey: ByteArray): ByteArray {
        val iv = ByteArray(12).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(linkKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        val encryptedWithTag = cipher.doFinal(plaintext)
        val frame = ByteArray(12 + encryptedWithTag.size)
        System.arraycopy(iv, 0, frame, 0, 12)
        System.arraycopy(encryptedWithTag, 0, frame, 12, encryptedWithTag.size)
        return frame
    }

    /**
     * Post-CONFIRM K_link transport frame decryption.
     * Expects: [12-byte IV] + [ciphertext] + [16-byte AES-GCM tag]
     */
    fun decryptTransportFrame(frame: ByteArray, linkKey: ByteArray): ByteArray {
        require(frame.size >= 12 + 16) { "Encrypted frame too short: ${frame.size}" }
        val iv = frame.copyOfRange(0, 12)
        val ciphertextWithTag = frame.copyOfRange(12, frame.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(linkKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, iv))
        return cipher.doFinal(ciphertextWithTag)
    }

    /**
     * Derives K_call for encrypted 1-hop voice calls.
     * K_call = HKDF(sessionKey(epoch_of_OFFER), salt = HKDF_DM_SALT, info = "MW/VOICE/v2" || callSessionId(16), 32)
     * Pinned at setup and never re-derived mid-call (C-13).
     */
    fun deriveCallKey(sessionKeyEpoch: ByteArray, callSessionId: ByteArray): ByteArray {
        require(sessionKeyEpoch.size == 32) { "sessionKeyEpoch must be 32 bytes" }
        require(callSessionId.size == 16) { "callSessionId must be 16 bytes" }
        val info = "MW/VOICE/v2".toByteArray(Charsets.UTF_8) + callSessionId
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sessionKeyEpoch, HKDF_DM_SALT, info))
        val callKey = ByteArray(32)
        hkdf.generateBytes(callKey, 0, 32)
        return callKey
    }

    /**
     * Builds deterministic 12-byte voice nonce:
     * nonce = direction(1) || 0x00 0x00 0x00 || seq(8)
     */
    fun buildVoiceNonce(direction: Byte, seq: Long): ByteArray {
        val nonce = ByteArray(12)
        nonce[0] = direction
        ByteBuffer.wrap(nonce, 4, 8).order(ByteOrder.BIG_ENDIAN).putLong(seq)
        return nonce
    }

    /**
     * Encrypts a real-time voice frame under K_call with deterministic nonce.
     * Plaintext = seq(8) || audioData(<= 160)
     * Returns Pair(rawCiphertext, authTag)
     */
    fun encryptVoiceFrame(
        audioData: ByteArray,
        seq: Long,
        direction: Byte,
        callKey: ByteArray,
        aad: ByteArray
    ): Pair<ByteArray, ByteArray> {
        require(audioData.size <= 160) { "audioData exceeds maximum 160 bytes" }
        val plaintext = ByteBuffer.allocate(8 + audioData.size).order(ByteOrder.BIG_ENDIAN)
            .putLong(seq)
            .put(audioData)
            .array()
        val nonce = buildVoiceNonce(direction, seq)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(callKey, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val encryptedWithTag = cipher.doFinal(plaintext)
        val rawCiphertext = encryptedWithTag.copyOfRange(0, encryptedWithTag.size - 16)
        val authTag = encryptedWithTag.copyOfRange(encryptedWithTag.size - 16, encryptedWithTag.size)
        return Pair(rawCiphertext, authTag)
    }

    /**
     * Decrypts a real-time voice frame under K_call with deterministic nonce built from seqPlain.
     * Verifies that decrypted seq == seqPlain.
     * Returns audioData.
     */
    fun decryptVoiceFrame(
        seqPlain: Long,
        rawCiphertext: ByteArray,
        authTag: ByteArray,
        direction: Byte,
        callKey: ByteArray,
        aad: ByteArray
    ): ByteArray {
        require(authTag.size == 16) { "authTag must be 16 bytes" }
        val nonce = buildVoiceNonce(direction, seqPlain)
        val ciphertextWithTag = ByteArray(rawCiphertext.size + authTag.size)
        System.arraycopy(rawCiphertext, 0, ciphertextWithTag, 0, rawCiphertext.size)
        System.arraycopy(authTag, 0, ciphertextWithTag, rawCiphertext.size, authTag.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(callKey, "AES")
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val plaintext = cipher.doFinal(ciphertextWithTag)

        require(plaintext.size >= 8) { "Decrypted voice plaintext too short: ${plaintext.size}" }
        val decryptedSeq = ByteBuffer.wrap(plaintext, 0, 8).order(ByteOrder.BIG_ENDIAN).long
        if (decryptedSeq != seqPlain) {
            throw java.security.GeneralSecurityException("Voice sequence mismatch: seqPlain=$seqPlain != decryptedSeq=$decryptedSeq")
        }
        return plaintext.copyOfRange(8, plaintext.size)
    }
}

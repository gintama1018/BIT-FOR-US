package com.meshwhisper.app.crypto

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CryptoTest {

    private val random = SecureRandom()

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val keyGen = X25519KeyPairGenerator()
        keyGen.init(X25519KeyGenerationParameters(random))
        val kp = keyGen.generateKeyPair()
        val priv = (kp.private as X25519PrivateKeyParameters).encoded
        val pub = (kp.public as X25519PublicKeyParameters).encoded
        return Pair(priv, pub)
    }

    private fun deriveSharedKey(myPriv: ByteArray, peerPub: ByteArray): ByteArray {
        val privParams = X25519PrivateKeyParameters(myPriv, 0)
        val pubParams = X25519PublicKeyParameters(peerPub, 0)

        val agreement = X25519Agreement()
        agreement.init(privParams)
        val sharedSecret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(pubParams, sharedSecret, 0)

        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(sharedSecret, "TEST_SALT".toByteArray(), "TEST_INFO".toByteArray()))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, 32)
        return key
    }

    @Test
    fun testX25519DiffieHellmanKeyAgreement() {
        // Node A
        val (privA, pubA) = generateKeyPair()
        // Node B
        val (privB, pubB) = generateKeyPair()

        // Derive shared session key on both sides
        val sessionKeyA = deriveSharedKey(privA, pubB)
        val sessionKeyB = deriveSharedKey(privB, pubA)

        // Both nodes must arrive at the exact same 256-bit symmetric session key!
        assertThat(sessionKeyA).isEqualTo(sessionKeyB)
        assertThat(sessionKeyA.size).isEqualTo(32)
    }

    @Test
    fun testAesGcmEncryptionAndDecryption() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()
        val sessionKey = deriveSharedKey(privA, pubB)

        val messageId = UUID.randomUUID()
        val iv = CryptoEngine.extractIvFromUuid(messageId)
        val plaintext = "Top secret mesh chat message over BLE".toByteArray(Charsets.UTF_8)

        // Encrypt on Node A
        val cipherEncrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherEncrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipherEncrypt.doFinal(plaintext)

        // Decrypt on Node B
        val cipherDecrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherDecrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        val decrypted = cipherDecrypt.doFinal(ciphertextWithTag)

        assertThat(decrypted).isEqualTo(plaintext)
        assertThat(String(decrypted, Charsets.UTF_8)).isEqualTo("Top secret mesh chat message over BLE")
    }

    @Test
    fun testAesGcmTamperDetection() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()
        val sessionKey = deriveSharedKey(privA, pubB)

        val messageId = UUID.randomUUID()
        val iv = CryptoEngine.extractIvFromUuid(messageId)
        val plaintext = "Unmodified message".toByteArray(Charsets.UTF_8)

        val cipherEncrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherEncrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        val ciphertextWithTag = cipherEncrypt.doFinal(plaintext)

        // Tamper with ciphertext by flipping 1 bit
        ciphertextWithTag[0] = (ciphertextWithTag[0].toInt() xor 0x01).toByte()

        val cipherDecrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherDecrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))

        // Must reject tampered packet with AEADBadTagException
        assertThrows(AEADBadTagException::class.java) {
            cipherDecrypt.doFinal(ciphertextWithTag)
        }
    }
}

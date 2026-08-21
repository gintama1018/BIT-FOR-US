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

    @Test
    fun testAesGcmAadHeaderBindingAndAuthentication() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()
        val sessionKey = deriveSharedKey(privA, pubB)

        val messageId = UUID.randomUUID()
        val senderId = 0x1122334455667788L
        val recipientId = 0x1234567890ABCDEFL
        val timestamp = 1720000000L

        val aad = com.meshwhisper.app.protocol.MeshPacket.computeAad(
            type = com.meshwhisper.app.protocol.PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            timestamp = timestamp
        )

        val plaintext = "Authenticated header payload".toByteArray(Charsets.UTF_8)
        val iv = CryptoEngine.extractIvFromUuid(messageId)

        // Encrypt with AAD
        val cipherEncrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherEncrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        cipherEncrypt.updateAAD(aad)
        val ciphertextWithTag = cipherEncrypt.doFinal(plaintext)

        // Decrypt with correct AAD
        val cipherDecrypt = Cipher.getInstance("AES/GCM/NoPadding")
        cipherDecrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        cipherDecrypt.updateAAD(aad)
        val decrypted = cipherDecrypt.doFinal(ciphertextWithTag)

        assertThat(decrypted).isEqualTo(plaintext)

        // Tamper with recipientId in AAD (e.g. malicious relay misrouting the DM)
        val tamperedAad = com.meshwhisper.app.protocol.MeshPacket.computeAad(
            type = com.meshwhisper.app.protocol.PacketType.DIRECT_MESSAGE,
            messageId = messageId,
            senderId = senderId,
            recipientId = 0x7EADBEEFCAFEBABEL, // Tampered recipient!
            timestamp = timestamp
        )

        val cipherDecryptTampered = Cipher.getInstance("AES/GCM/NoPadding")
        cipherDecryptTampered.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), GCMParameterSpec(128, iv))
        cipherDecryptTampered.updateAAD(tamperedAad)

        // Must reject header-tampered packet with AEADBadTagException
        assertThrows(AEADBadTagException::class.java) {
            cipherDecryptTampered.doFinal(ciphertextWithTag)
        }
    }

    @Test
    fun testEpochBasedKeyRotationForwardSecrecy() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()

        fun deriveEpochKey(myPriv: ByteArray, peerPub: ByteArray, epoch: Long): ByteArray {
            val privParams = X25519PrivateKeyParameters(myPriv, 0)
            val pubParams = X25519PublicKeyParameters(peerPub, 0)
            val agreement = X25519Agreement()
            agreement.init(privParams)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(pubParams, sharedSecret, 0)

            val hkdf = HKDFBytesGenerator(SHA256Digest())
            val info = "MESHWHISPER_SESSION_KEY_V1_EPOCH_$epoch".toByteArray(Charsets.UTF_8)
            hkdf.init(HKDFParameters(sharedSecret, "MESHWHISPER_SALT".toByteArray(), info))
            val key = ByteArray(32)
            hkdf.generateBytes(key, 0, 32)
            return key
        }

        val epoch1 = 100L
        val epoch2 = 101L

        val keyA_epoch1 = deriveEpochKey(privA, pubB, epoch1)
        val keyB_epoch1 = deriveEpochKey(privB, pubA, epoch1)
        val keyA_epoch2 = deriveEpochKey(privA, pubB, epoch2)
        val keyB_epoch2 = deriveEpochKey(privB, pubA, epoch2)

        // Same epoch => matching keys
        assertThat(keyA_epoch1).isEqualTo(keyB_epoch1)
        assertThat(keyA_epoch2).isEqualTo(keyB_epoch2)

        // Different epochs => completely distinct forward secret keys!
        assertThat(keyA_epoch1).isNotEqualTo(keyA_epoch2)
    }

    @Test
    fun testAuthenticatedAckGenerationAndForgedAckRejection() {
        val (privA, pubA) = generateKeyPair()
        val (privB, pubB) = generateKeyPair()
        val (privAttacker, _) = generateKeyPair()

        val sharedKeyAtoB = deriveSharedKey(privA, pubB)
        val sharedKeyBtoA = deriveSharedKey(privB, pubA)
        val attackerKey = deriveSharedKey(privAttacker, pubA)

        val msgId = UUID.randomUUID()
        val senderId = 0x1111222233334444L
        val recipientId = 0x5555666677778888L
        val timestamp = 1720000000L

        val ackAad = com.meshwhisper.app.protocol.MeshPacket.computeAad(
            type = com.meshwhisper.app.protocol.PacketType.ACK,
            messageId = msgId,
            senderId = recipientId,
            recipientId = senderId,
            timestamp = timestamp
        )
        val iv = CryptoEngine.extractIvFromUuid(msgId)

        // Node B creates authenticated ACK (empty payload + AEAD tag)
        val cipherB = Cipher.getInstance("AES/GCM/NoPadding")
        cipherB.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sharedKeyBtoA, "AES"), GCMParameterSpec(128, iv))
        cipherB.updateAAD(ackAad)
        val ackTagWithEmptyCipher = cipherB.doFinal(ByteArray(0)) // 16-byte tag

        // Node A verifies authenticated ACK from Node B
        val cipherA = Cipher.getInstance("AES/GCM/NoPadding")
        cipherA.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedKeyAtoB, "AES"), GCMParameterSpec(128, iv))
        cipherA.updateAAD(ackAad)
        val decrypted = cipherA.doFinal(ackTagWithEmptyCipher)
        assertThat(decrypted).isEmpty() // Authentication verified!

        // Attacker creates a forged ACK without knowing B's private key
        val cipherAttacker = Cipher.getInstance("AES/GCM/NoPadding")
        cipherAttacker.init(Cipher.ENCRYPT_MODE, SecretKeySpec(attackerKey, "AES"), GCMParameterSpec(128, iv))
        cipherAttacker.updateAAD(ackAad)
        val forgedTag = cipherAttacker.doFinal(ByteArray(0))

        // Node A attempts to verify forged ACK -> must throw AEADBadTagException
        val cipherVerifyForged = Cipher.getInstance("AES/GCM/NoPadding")
        cipherVerifyForged.init(Cipher.DECRYPT_MODE, SecretKeySpec(sharedKeyAtoB, "AES"), GCMParameterSpec(128, iv))
        cipherVerifyForged.updateAAD(ackAad)

        assertThrows(AEADBadTagException::class.java) {
            cipherVerifyForged.doFinal(forgedTag)
        }
    }
}

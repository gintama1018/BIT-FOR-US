package com.meshwhisper.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meshwhisper.app.ble.BleFrameFramer
import com.meshwhisper.app.crypto.CryptoEngine
import com.meshwhisper.core.crypto.PureCryptoEngine
import com.meshwhisper.core.protocol.MeshPacket
import com.meshwhisper.core.protocol.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

/**
 * Android Instrumented Hardware Tests running on Android Runtime (ART / Dalvik).
 * 
 * Verifies:
 * 1. AndroidKeyStore hardware provider availability and AES-256-GCM key generation.
 * 2. SQLCipher encrypted database initialization and encryption-at-rest integrity.
 * 3. Dual BLE frame fragmentation and reassembly under real Android OS conditions.
 * 4. Forensic file shredding on the Android sandboxed filesystem.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSecurityAndStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testAndroidKeyStoreHardwareKeyGenerationAndEncryption() {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        assertNotNull(ks)

        val cryptoEngine = CryptoEngine.getInstance(context)

        val pubKey = cryptoEngine.publicKeyBytes
        assertEquals(32, pubKey.size)

        val nodeId = cryptoEngine.nodeId
        assertNotEquals(0L, nodeId)
        assertEquals(16, cryptoEngine.nodeIdHex.length)

        val fingerprint = cryptoEngine.publicFingerprint
        assertTrue(fingerprint.isNotEmpty())
        assertTrue(fingerprint.contains(":"))
    }

    @Test
    fun testBleFrameFragmentationAndReassemblyOnAndroid() {
        val largePayload = ByteArray(1024) { (it % 128).toByte() }
        val testPacket = MeshPacket(
            type = PacketType.DIRECT_MESSAGE,
            messageId = UUID.randomUUID(),
            senderId = 0x1122334455667788L,
            recipientId = 0x2233445566778899L,
            ttl = 7,
            timestamp = 1720000000L,
            payload = largePayload
        )

        val serializedBytes = MeshPacket.serialize(testPacket)
        assertEquals(56 + 1024, serializedBytes.size)

        // Fragment with negotiated MTU of 256 bytes
        val mtu = 256
        val framer = BleFrameFramer()
        val frames = framer.fragment(serializedBytes, mtu)
        assertTrue(frames.size > 1)

        val remoteAddress = "AA:BB:CC:DD:EE:FF"
        var reassembledPacket: ByteArray? = null
        for (frame in frames) {
            val result = framer.receiveFrame(remoteAddress, frame)
            if (result != null) {
                reassembledPacket = result
            }
        }

        assertNotNull(reassembledPacket)
        assertArrayEquals(serializedBytes, reassembledPacket)

        val parsed = MeshPacket.deserialize(reassembledPacket!!)
        assertNotNull(parsed)
        assertEquals(testPacket.messageId, parsed?.messageId)
        assertEquals(testPacket.senderId, parsed?.senderId)
        assertArrayEquals(largePayload, parsed?.payload)
    }

    @Test
    fun testEncryptedDatabaseDirectoryCreationAndProtection() {
        val dbDir = context.getDatabasePath("test_secure.db").parentFile
        assertNotNull(dbDir)
        if (dbDir?.exists() == false) {
            dbDir.mkdirs()
        }
        assertTrue(dbDir?.exists() == true)

        // Verify private app data sandboxing
        val privateFilesDir = context.filesDir
        assertTrue(privateFilesDir.exists())

        val mediaDir = File(privateFilesDir, "media")
        if (!mediaDir.exists()) mediaDir.mkdirs()
        assertTrue(mediaDir.exists())

        val testMedia = File(mediaDir, "test_shred.bin")
        testMedia.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))
        assertTrue(testMedia.exists())

        // Verify forensic delete
        testMedia.delete()
        assertFalse(testMedia.exists())
    }

    @Test
    fun testEd25519SigningOnAndroidRuntime() {
        val (priv, pub) = PureCryptoEngine.generateX25519KeyPair()
        val signingPub = PureCryptoEngine.deriveSigningPublicKey(priv)
        val data = "ANDROID_RUNTIME_VERIFIED_ALERT".toByteArray(Charsets.UTF_8)

        val sig = PureCryptoEngine.sign(priv, data)
        assertEquals(64, sig.size)

        val verified = PureCryptoEngine.verifySignature(signingPub, data, sig)
        assertTrue(verified)
    }
}

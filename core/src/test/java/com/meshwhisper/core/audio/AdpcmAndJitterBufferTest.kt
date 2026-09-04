package com.meshwhisper.core.audio

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class AdpcmAndJitterBufferTest {

    @Test
    fun testAdpcmCompressionRatioAndRoundtrip() {
        val samplesCount = 160 // 20ms frame at 8 kHz
        val pcmOriginal = ShortArray(samplesCount)

        // Generate synthetic 440 Hz test tone (A4)
        for (i in 0 until samplesCount) {
            val angle = 2.0 * Math.PI * 440.0 * i / 8000.0
            pcmOriginal[i] = (sin(angle) * 16000.0).toInt().toShort()
        }

        val adpcmBytes = ByteArray(AdpcmCodec.BYTES_PER_FRAME_ADPCM)
        val encodeState = AdpcmCodec.State()
        val bytesWritten = AdpcmCodec.encode(pcmOriginal, samplesCount, adpcmBytes, encodeState)

        // Verify compression ratio: 160 samples (320 bytes PCM) -> exactly 80 bytes
        assertEquals(80, bytesWritten)
        assertEquals(80, adpcmBytes.size)

        val pcmDecoded = ShortArray(samplesCount)
        val decodeState = AdpcmCodec.State()
        val samplesDecoded = AdpcmCodec.decode(adpcmBytes, bytesWritten, pcmDecoded, decodeState)

        assertEquals(samplesCount, samplesDecoded)

        // Check fidelity: calculate Mean Absolute Error (MAE)
        var totalError = 0.0
        for (i in 0 until samplesCount) {
            totalError += Math.abs(pcmOriginal[i] - pcmDecoded[i])
        }
        val avgError = totalError / samplesCount

        // Standard ADPCM 4-bit SNR on a 16-bit 16000 amplitude sine wave typically has avg error < 1200
        assertTrue("ADPCM average quantization error ($avgError) exceeded tolerance", avgError < 1500.0)
    }

    @Test
    fun testAdpcmSilenceAndExtremes() {
        val samplesCount = 160
        val silence = ShortArray(samplesCount) { 0 }
        val adpcm = ByteArray(80)

        AdpcmCodec.encode(silence, samplesCount, adpcm)
        val decoded = ShortArray(samplesCount)
        AdpcmCodec.decode(adpcm, 80, decoded)

        for (sample in decoded) {
            assertTrue("Decoded silence sample ($sample) should stay close to zero", Math.abs(sample.toInt()) < 200)
        }

        // Test extreme values (clamping without crashing)
        val extremes = ShortArray(samplesCount) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        val extAdpcm = ByteArray(80)
        AdpcmCodec.encode(extremes, samplesCount, extAdpcm)
        val extDecoded = ShortArray(samplesCount)
        AdpcmCodec.decode(extAdpcm, 80, extDecoded)
        assertEquals(samplesCount, extDecoded.size)
    }

    @Test
    fun testJitterBufferPreloadAndInOrder() {
        val jb = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 5)

        val f0 = AudioFrame(0, 1000L, ByteArray(80) { 0 })
        val f1 = AudioFrame(1, 1020L, ByteArray(80) { 1 })

        // Pushing 1st frame: still buffering, pop should return null
        assertTrue(jb.push(f0))
        assertTrue(jb.isBuffering())
        assertNull(jb.pop())

        // Pushing 2nd frame: meets targetPreloadFrames (2)
        assertTrue(jb.push(f1))
        assertFalse(jb.isBuffering())

        // Now pop should return f0 then f1
        val popped0 = jb.pop()
        assertNotNull(popped0)
        assertEquals(0, popped0?.sequenceNumber)

        val popped1 = jb.pop()
        assertNotNull(popped1)
        assertEquals(1, popped1?.sequenceNumber)

        // Buffer empty -> should re-enter buffering mode
        assertNull(jb.pop())
        assertTrue(jb.isBuffering())
    }

    @Test
    fun testJitterBufferOutOfWeekReordering() {
        val jb = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 5)

        val f0 = AudioFrame(0, 1000L, ByteArray(80))
        val f1 = AudioFrame(1, 1020L, ByteArray(80))

        // Frame 1 arrives before frame 0 (out of order)
        assertTrue(jb.push(f1))
        assertTrue(jb.push(f0))

        // Popping must return f0 first, then f1
        val popped0 = jb.pop()
        assertEquals(0, popped0?.sequenceNumber)

        val popped1 = jb.pop()
        assertEquals(1, popped1?.sequenceNumber)
    }

    @Test
    fun testJitterBufferDropDuplicatesAndLate() {
        val jb = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 5)

        val f0 = AudioFrame(0, 1000L, ByteArray(80))
        val f1 = AudioFrame(1, 1020L, ByteArray(80))

        jb.push(f0)
        // Duplicate push should be rejected
        assertFalse(jb.push(f0))

        jb.push(f1)
        assertEquals(0, jb.pop()?.sequenceNumber)
        assertEquals(1, jb.pop()?.sequenceNumber)

        // Frame 0 pushed again after being popped -> should be dropped as late
        assertFalse(jb.push(f0))
    }

    @Test
    fun testJitterBufferLossConcealmentSkip() {
        val jb = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 5)

        val f0 = AudioFrame(0, 1000L, ByteArray(80))
        // Frame 1 is lost! Frame 2 arrives
        val f2 = AudioFrame(2, 1040L, ByteArray(80))

        jb.push(f0)
        jb.push(f2)

        // Frame 0 pops
        assertEquals(0, jb.pop()?.sequenceNumber)

        // Frame 1 was lost; after brief check, jitter buffer advances to frame 2
        val popped2 = jb.pop()
        assertNotNull(popped2)
        assertEquals(2, popped2?.sequenceNumber)
    }
}

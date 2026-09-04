package com.meshwhisper.core.audio

/**
 * Pure Kotlin implementation of the standard IMA/DVI ADPCM 4-bit audio codec.
 *
 * Compresses 16-bit linear PCM audio into 4-bit nibbles (4:1 compression).
 * At 8,000 Hz mono:
 *   - 20ms frame = 160 samples (320 bytes PCM) -> 80 bytes ADPCM (32 kbps).
 *   - Zero external dependencies; 100% testable on pure JVM and Android.
 *   - Negligible CPU footprint and zero GC overhead during streaming.
 */
class AdpcmCodec {

    data class State(
        var predictor: Int = 0,
        var stepIndex: Int = 0
    ) {
        fun reset() {
            predictor = 0
            stepIndex = 0
        }
    }

    companion object {
        const val SAMPLES_PER_FRAME_20MS_8KHZ = 160
        const val BYTES_PER_FRAME_ADPCM = 80 // 160 samples * 4 bits = 80 bytes

        private val INDEX_TABLE = intArrayOf(
            -1, -1, -1, -1, 2, 4, 6, 8,
            -1, -1, -1, -1, 2, 4, 6, 8
        )

        private val STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )

        /**
         * Encodes a single 16-bit PCM sample to a 4-bit ADPCM nibble.
         */
        fun encodeSample(sample: Short, state: State): Int {
            var step = STEP_TABLE[state.stepIndex]
            var diff = sample.toInt() - state.predictor
            var sign = 0
            if (diff < 0) {
                sign = 8
                diff = -diff
            }

            var delta = 0
            var vpdiff = step shr 3

            if (diff >= step) {
                delta = delta or 4
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 2
                diff -= step
                vpdiff += step
            }
            step = step shr 1
            if (diff >= step) {
                delta = delta or 1
                vpdiff += step
            }

            delta = delta or sign

            if (sign != 0) {
                state.predictor -= vpdiff
            } else {
                state.predictor += vpdiff
            }
            state.predictor = state.predictor.coerceIn(-32768, 32767)
            state.stepIndex = (state.stepIndex + INDEX_TABLE[delta]).coerceIn(0, 88)

            return delta and 0x0F
        }

        /**
         * Decodes a single 4-bit ADPCM nibble to a 16-bit PCM sample.
         */
        fun decodeSample(nibble: Int, state: State): Short {
            val code = nibble and 0x0F
            val step = STEP_TABLE[state.stepIndex]
            var vpdiff = step shr 3

            if ((code and 4) != 0) vpdiff += step
            if ((code and 2) != 0) vpdiff += (step shr 1)
            if ((code and 1) != 0) vpdiff += (step shr 2)

            if ((code and 8) != 0) {
                state.predictor -= vpdiff
            } else {
                state.predictor += vpdiff
            }
            state.predictor = state.predictor.coerceIn(-32768, 32767)
            state.stepIndex = (state.stepIndex + INDEX_TABLE[code]).coerceIn(0, 88)

            return state.predictor.toShort()
        }

        /**
         * Encodes 16-bit PCM samples into an ADPCM byte array.
         * Every byte contains two 4-bit samples: lower nibble = sample N, upper nibble = sample N+1.
         *
         * @return number of bytes written to [outAdpcm]
         */
        fun encode(
            pcmIn: ShortArray,
            samplesCount: Int,
            outAdpcm: ByteArray,
            state: State = State()
        ): Int {
            val bytesToWrite = samplesCount / 2
            require(outAdpcm.size >= bytesToWrite) { "Output buffer too small: required $bytesToWrite, got ${outAdpcm.size}" }

            for (i in 0 until bytesToWrite) {
                val sample0 = pcmIn[i * 2]
                val sample1 = pcmIn[i * 2 + 1]
                val nibble0 = encodeSample(sample0, state)
                val nibble1 = encodeSample(sample1, state)
                outAdpcm[i] = ((nibble1 shl 4) or (nibble0 and 0x0F)).toByte()
            }
            return bytesToWrite
        }

        /**
         * Decodes an ADPCM byte array into 16-bit PCM samples.
         *
         * @return number of samples written to [outPcm]
         */
        fun decode(
            adpcmIn: ByteArray,
            byteCount: Int,
            outPcm: ShortArray,
            state: State = State()
        ): Int {
            val expectedSamples = byteCount * 2
            require(outPcm.size >= expectedSamples) { "Output buffer too small: required $expectedSamples, got ${outPcm.size}" }

            for (i in 0 until byteCount) {
                val byteVal = adpcmIn[i].toInt() and 0xFF
                val nibble0 = byteVal and 0x0F
                val nibble1 = (byteVal shr 4) and 0x0F
                outPcm[i * 2] = decodeSample(nibble0, state)
                outPcm[i * 2 + 1] = decodeSample(nibble1, state)
            }
            return expectedSamples
        }
    }
}

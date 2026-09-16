package com.meshwhisper.core.util

import java.security.SecureRandom
import java.util.Random

/**
 * Deterministic CSPRNG / random source abstraction.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
interface RandomSource {
    fun bytes(n: Int): ByteArray
}

class DefaultRandomSource(
    private val secureRandom: SecureRandom = SecureRandom()
) : RandomSource {
    override fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        secureRandom.nextBytes(out)
        return out
    }
}

class SeededRandomSource(seed: Long = 42L) : RandomSource {
    private val random = Random(seed)

    override fun bytes(n: Int): ByteArray {
        val out = ByteArray(n)
        random.nextBytes(out)
        return out
    }
}

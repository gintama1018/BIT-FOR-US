package com.meshwhisper.core.util

/**
 * Deterministic clock abstraction for timing, freshness windows, and timeouts.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
interface Clock {
    fun nowSeconds(): Long
    fun nowMillis(): Long = nowSeconds() * 1000L
}

class SystemClock : Clock {
    override fun nowSeconds(): Long = System.currentTimeMillis() / 1000L
    override fun nowMillis(): Long = System.currentTimeMillis()
}

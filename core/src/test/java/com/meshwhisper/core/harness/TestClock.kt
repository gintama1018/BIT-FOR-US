package com.meshwhisper.core.harness

import com.meshwhisper.core.util.Clock

/**
 * Controllable virtual clock for deterministic protocol testing.
 * Specified in NEXTGEN/03_VNEXT_TESTS_AND_AGENT_RULES.md §12.2.
 */
class TestClock(private var currentSeconds: Long = 1_700_000_000L) : Clock {
    override fun nowSeconds(): Long = currentSeconds
    override fun nowMillis(): Long = currentSeconds * 1000L

    fun advanceSeconds(seconds: Long) {
        require(seconds >= 0) { "Clock cannot go backwards in time" }
        currentSeconds += seconds
    }

    fun setSeconds(seconds: Long) {
        currentSeconds = seconds
    }
}

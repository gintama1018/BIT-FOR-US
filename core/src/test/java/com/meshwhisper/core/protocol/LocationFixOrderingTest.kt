package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationFixOrderingTest {

    /** T-SOS-02: an older SOS must not overwrite a newer stored fix. */
    @Test
    fun olderFixIsNotNewer() {
        assertThat(LocationFixOrdering.isNewer(1_000L, 0L, 2_000L, 5L)).isFalse()
    }

    @Test
    fun newerFixWins() {
        assertThat(LocationFixOrdering.isNewer(3_000L, 0L, 2_000L, 5L)).isTrue()
    }

    @Test
    fun sameTimestampTieBrokenBySequence() {
        assertThat(LocationFixOrdering.isNewer(2_000L, 6L, 2_000L, 5L)).isTrue()
        assertThat(LocationFixOrdering.isNewer(2_000L, 5L, 2_000L, 5L)).isFalse()
        assertThat(LocationFixOrdering.isNewer(2_000L, 0L, 2_000L, 5L)).isFalse()
    }

    /** T-SOS-01: SOS (MANUAL_SOS) and dying gasp render as emergency; periodic/battery warnings do not. */
    @Test
    fun emergencyTriggerClassification() {
        assertThat(LocationFixOrdering.isEmergencyTrigger(BreadcrumbTriggerType.MANUAL_SOS.code.toInt())).isTrue()
        assertThat(LocationFixOrdering.isEmergencyTrigger(BreadcrumbTriggerType.BATTERY_CRITICAL_5.code.toInt())).isTrue()
        assertThat(LocationFixOrdering.isEmergencyTrigger(BreadcrumbTriggerType.PERIODIC.code.toInt())).isFalse()
        assertThat(LocationFixOrdering.isEmergencyTrigger(BreadcrumbTriggerType.BATTERY_15.code.toInt())).isFalse()
        // Default entity triggerType is 1 (PERIODIC): the old SOS insert path produced exactly this bug.
        assertThat(LocationFixOrdering.isEmergencyTrigger(1)).isFalse()
    }
}

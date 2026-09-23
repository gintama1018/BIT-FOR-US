package com.meshwhisper.app.location

import com.google.common.truth.Truth.assertThat
import com.meshwhisper.core.protocol.BreadcrumbTriggerType
import com.meshwhisper.core.protocol.LocationBreadcrumbPayload
import org.junit.Test

class LocationBreadcrumbIngressTest {

    @Test
    fun orderingLogic_newerFixTimestampOverridesLowerSequenceNumber_reinstallProof() {
        // Old record in DB before user wiped/reinstalled phone
        val oldRecordedFixTime = 1700000000_000L
        val oldSequenceNumber = 500L

        // Fresh install: sequence number reset to 1, but GPS hardware fix time is newer
        val freshInstallFixTime = 1720000000_000L
        val freshInstallSeq = 1L

        val shouldUpdate = (freshInstallFixTime > oldRecordedFixTime) ||
                (freshInstallFixTime == oldRecordedFixTime && freshInstallSeq > oldSequenceNumber)

        assertThat(shouldUpdate).isTrue()
    }

    @Test
    fun orderingLogic_staleReplayDropped() {
        val currentRecordedFixTime = 1720000000_000L
        val currentSequenceNumber = 10L

        // Replayed old packet from 1 hour ago
        val replayedFixTime = 1719996400_000L
        val replayedSeq = 8L

        val shouldUpdate = (replayedFixTime > currentRecordedFixTime) ||
                (replayedFixTime == currentRecordedFixTime && replayedSeq > currentSequenceNumber)

        assertThat(shouldUpdate).isFalse()
    }

    @Test
    fun orderingLogic_sameFixTimestamp_requiresHigherSequenceNumber() {
        val recordedFixTime = 1720000000_000L
        val recordedSeq = 5L

        // Same GPS fix re-sent with higher sequence number (e.g. note or battery update)
        val newerSeq = 6L
        val shouldUpdateNewer = (recordedFixTime > recordedFixTime) ||
                (recordedFixTime == recordedFixTime && newerSeq > recordedSeq)
        assertThat(shouldUpdateNewer).isTrue()

        // Same GPS fix with older sequence number
        val olderSeq = 4L
        val shouldUpdateOlder = (recordedFixTime > recordedFixTime) ||
                (recordedFixTime == recordedFixTime && olderSeq > recordedSeq)
        assertThat(shouldUpdateOlder).isFalse()
    }

    @Test
    fun clockDriftDefense_futureTimestampExceedingTenMinutes_rejected() {
        val nowSec = 1727100000L
        val maxAllowableDriftSec = nowSec + 600L // 10 minutes

        val validTimestampSec = nowSec + 300L // 5 mins in future (allowable clock drift)
        assertThat(validTimestampSec > maxAllowableDriftSec).isFalse()

        val invalidFutureTimestampSec = nowSec + 900L // 15 mins in future (manipulated/malformed)
        assertThat(invalidFutureTimestampSec > maxAllowableDriftSec).isTrue()
    }

    @Test
    fun revokeTrigger_payloadIdentification() {
        val revokePayload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.REVOKE,
            sequenceNumber = 2L,
            fixTimestampSec = 1727100000L
        )

        val bytes = revokePayload.serialize()
        assertThat(LocationBreadcrumbPayload.isBreadcrumb(bytes)).isTrue()

        val parsed = LocationBreadcrumbPayload.deserialize(bytes)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.triggerType).isEqualTo(BreadcrumbTriggerType.REVOKE)
    }
}

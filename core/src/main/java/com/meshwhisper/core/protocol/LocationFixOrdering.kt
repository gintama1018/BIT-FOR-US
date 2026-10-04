package com.meshwhisper.core.protocol

/**
 * Single source of truth for "is this location fix newer than what we already store?".
 *
 * The Room `LocationDao.updateIfNewer` query encodes the same rule in SQL:
 *   (:fixTimestamp > timestamp) OR (:fixTimestamp = timestamp AND :sequenceNumber > sequenceNumber)
 * This pure function is the JVM-testable specification of that rule so SOS and breadcrumb ingest
 * paths cannot drift apart.
 */
object LocationFixOrdering {
    fun isNewer(
        incomingFixTimestampMs: Long,
        incomingSequence: Long,
        storedFixTimestampMs: Long,
        storedSequence: Long
    ): Boolean =
        incomingFixTimestampMs > storedFixTimestampMs ||
            (incomingFixTimestampMs == storedFixTimestampMs && incomingSequence > storedSequence)

    /** Trigger types the UI must render with the red emergency style. */
    fun isEmergencyTrigger(triggerCode: Int): Boolean =
        triggerCode == BreadcrumbTriggerType.BATTERY_CRITICAL_5.code.toInt() ||
            triggerCode == BreadcrumbTriggerType.MANUAL_SOS.code.toInt()
}

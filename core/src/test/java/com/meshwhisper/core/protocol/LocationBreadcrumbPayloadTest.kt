package com.meshwhisper.core.protocol

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LocationBreadcrumbPayloadTest {

    @Test
    fun roundTripSerialization_preservesAllFieldsAndPadsTo64Bytes() {
        val original = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.BATTERY_CRITICAL_5,
            batteryPercent = 4,
            sequenceNumber = 42L,
            latitude = 12.9715987,
            longitude = 77.5945627,
            altitude = 920.0,
            accuracyMeters = 8.5f,
            fixTimestampSec = 1727100000L,
            note = "Battery dying near gate 2"
        )

        val bytes = original.serialize()
        assertThat(bytes.size).isEqualTo(LocationBreadcrumbPayload.FIXED_PAYLOAD_SIZE)
        assertThat(bytes.size).isEqualTo(64)
        assertThat(LocationBreadcrumbPayload.isBreadcrumb(bytes)).isTrue()

        val deserialized = LocationBreadcrumbPayload.deserialize(bytes)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.triggerType).isEqualTo(BreadcrumbTriggerType.BATTERY_CRITICAL_5)
        assertThat(deserialized.batteryPercent).isEqualTo(4)
        assertThat(deserialized.sequenceNumber).isEqualTo(42L)
        assertThat(deserialized.latitude).isWithin(1e-6).of(12.9715987)
        assertThat(deserialized.longitude).isWithin(1e-6).of(77.5945627)
        assertThat(deserialized.altitude).isEqualTo(920.0)
        assertThat(deserialized.accuracyMeters).isWithin(0.1f).of(8.5f)
        assertThat(deserialized.fixTimestampSec).isEqualTo(1727100000L)
        assertThat(deserialized.note).isEqualTo("Battery dying near gate 2")
    }

    @Test
    fun negativeTest_plainTextStartingWithL_neverCollidesWithBreadcrumb() {
        val testStrings = listOf(
            "Location? Hello",
            "Look at this",
            "L",
            "Last seen 5 mins ago",
            "Let's meet at building B"
        )

        for (text in testStrings) {
            val textBytes = text.toByteArray(Charsets.UTF_8)
            assertThat(LocationBreadcrumbPayload.isBreadcrumb(textBytes)).isFalse()
            assertThat(LocationBreadcrumbPayload.deserialize(textBytes)).isNull()
        }
    }

    @Test
    fun negativeTest_invalidMagicPrefixOrVersion_rejected() {
        val valid = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.PERIODIC,
            latitude = 10.0,
            longitude = 20.0,
            fixTimestampSec = 1000L
        ).serialize()

        // Corrupt magic prefix byte 0
        val corruptMagic = valid.copyOf()
        corruptMagic[0] = 0x00
        assertThat(LocationBreadcrumbPayload.isBreadcrumb(corruptMagic)).isFalse()
        assertThat(LocationBreadcrumbPayload.deserialize(corruptMagic)).isNull()

        // Corrupt version
        val corruptVersion = valid.copyOf()
        corruptVersion[3] = 0x99.toByte()
        assertThat(LocationBreadcrumbPayload.isBreadcrumb(corruptVersion)).isFalse()
        assertThat(LocationBreadcrumbPayload.deserialize(corruptVersion)).isNull()
    }

    @Test
    fun negativeTest_outOfBoundsCoordinates_rejected() {
        // Latitude > 90
        val payloadBadLat = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.PERIODIC,
            latitude = 90.0001,
            longitude = 50.0,
            fixTimestampSec = 1000L
        )
        // Even if serialized, deserialize strictly enforces -90..90
        val badBytes = payloadBadLat.serialize()
        // Manually tamper latitude to > 90
        java.nio.ByteBuffer.wrap(badBytes).putInt(10, (95.0 * 1e7).toInt())
        assertThat(LocationBreadcrumbPayload.deserialize(badBytes)).isNull()

        // Longitude > 180
        val badLonBytes = payloadBadLat.serialize()
        java.nio.ByteBuffer.wrap(badLonBytes).putInt(14, (185.0 * 1e7).toInt())
        assertThat(LocationBreadcrumbPayload.deserialize(badLonBytes)).isNull()
    }

    @Test
    fun unknownAccuracy_representedCleanly() {
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.PERIODIC,
            latitude = 12.0,
            longitude = 77.0,
            accuracyMeters = 0.0f,
            fixTimestampSec = 1000L
        )
        val deserialized = LocationBreadcrumbPayload.deserialize(payload.serialize())
        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.accuracyMeters).isEqualTo(0.0f)
    }

    @Test
    fun revokeTrigger_roundTripsCleanlyWithoutCoordinates() {
        val revokePayload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.REVOKE,
            sequenceNumber = 999L,
            fixTimestampSec = 1727105000L,
            note = "Revoked"
        )
        val bytes = revokePayload.serialize()
        val deserialized = LocationBreadcrumbPayload.deserialize(bytes)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.triggerType).isEqualTo(BreadcrumbTriggerType.REVOKE)
        assertThat(deserialized.sequenceNumber).isEqualTo(999L)
        assertThat(deserialized.latitude).isEqualTo(0.0)
        assertThat(deserialized.longitude).isEqualTo(0.0)
        assertThat(deserialized.note).isEqualTo("Revoked")
    }

    @Test
    fun noteCappedAt32Bytes() {
        val longNote = "This is a very long note that exceeds the maximum thirty-two byte limit completely"
        val payload = LocationBreadcrumbPayload(
            triggerType = BreadcrumbTriggerType.MANUAL_SOS,
            latitude = 12.0,
            longitude = 77.0,
            fixTimestampSec = 1000L,
            note = longNote
        )
        val bytes = payload.serialize()
        val deserialized = LocationBreadcrumbPayload.deserialize(bytes)
        assertThat(deserialized).isNotNull()
        assertThat(deserialized!!.note!!.toByteArray(Charsets.UTF_8).size).isAtMost(32)
    }
}

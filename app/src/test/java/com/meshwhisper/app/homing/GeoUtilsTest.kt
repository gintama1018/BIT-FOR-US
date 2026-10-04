package com.meshwhisper.app.homing

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.abs

class GeoUtilsTest {

    @Test
    fun testHaversineDistance_samePointReturnsZero() {
        val dist = GeoUtils.calculateDistanceMeters(28.6139, 77.2090, 28.6139, 77.2090)
        assertThat(dist).isWithin(0.001).of(0.0)
    }

    @Test
    fun testHaversineDistance_knownReferenceDistance() {
        // New Delhi (28.6139° N, 77.2090° E) to Agra (27.1767° N, 78.0081° E) ~ 180 km
        val dist = GeoUtils.calculateDistanceMeters(28.6139, 77.2090, 27.1767, 78.0081)
        assertThat(dist / 1000.0).isWithin(5.0).of(178.0)
    }

    @Test
    fun testBearing_cardinalDirections() {
        // Due North
        val bearingNorth = GeoUtils.calculateBearingDegrees(10.0, 10.0, 11.0, 10.0)
        assertThat(bearingNorth).isWithin(1.0f).of(0.0f)

        // Due East (at equator)
        val bearingEast = GeoUtils.calculateBearingDegrees(0.0, 10.0, 0.0, 11.0)
        assertThat(bearingEast).isWithin(1.0f).of(90.0f)

        // Due South
        val bearingSouth = GeoUtils.calculateBearingDegrees(11.0, 10.0, 10.0, 10.0)
        assertThat(bearingSouth).isWithin(1.0f).of(180.0f)

        // Due West (at equator)
        val bearingWest = GeoUtils.calculateBearingDegrees(0.0, 11.0, 0.0, 10.0)
        assertThat(bearingWest).isWithin(1.0f).of(270.0f)
    }

    @Test
    fun testBearingToCardinal() {
        assertThat(GeoUtils.bearingToCardinal(0f)).isEqualTo("N")
        assertThat(GeoUtils.bearingToCardinal(45f)).isEqualTo("NE")
        assertThat(GeoUtils.bearingToCardinal(90f)).isEqualTo("E")
        assertThat(GeoUtils.bearingToCardinal(135f)).isEqualTo("SE")
        assertThat(GeoUtils.bearingToCardinal(180f)).isEqualTo("S")
        assertThat(GeoUtils.bearingToCardinal(247f)).isEqualTo("SW")
        assertThat(GeoUtils.bearingToCardinal(270f)).isEqualTo("W")
        assertThat(GeoUtils.bearingToCardinal(315f)).isEqualTo("NW")
        assertThat(GeoUtils.bearingToCardinal(359f)).isEqualTo("N")
    }

    @Test
    fun testFormatDistance() {
        assertThat(GeoUtils.formatDistance(184.2)).isEqualTo("184 m")
        assertThat(GeoUtils.formatDistance(50.0)).isEqualTo("50 m")
        assertThat(GeoUtils.formatDistance(1420.0)).isEqualTo("1.4 km")
    }

    @Test
    fun testFormatBearing() {
        assertThat(GeoUtils.formatBearing(247.0f)).isEqualTo("247° SW")
        assertThat(GeoUtils.formatBearing(12.0f)).isEqualTo("12° N")
    }
}

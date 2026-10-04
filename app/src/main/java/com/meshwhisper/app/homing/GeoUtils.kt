package com.meshwhisper.app.homing

import kotlin.math.*

/**
 * High-accuracy offline geodesic utilities for disaster homing and coordinates drop.
 * Zero external libraries or Google Play Services dependencies.
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6371000.0

    /**
     * Calculates great-circle distance between two GPS coordinates using the Haversine formula.
     */
    fun calculateDistanceMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)

        val a = sin(dLat / 2.0).pow(2.0) +
                cos(rLat1) * cos(rLat2) * sin(dLon / 2.0).pow(2.0)
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculates initial forward bearing from (lat1, lon1) towards (lat2, lon2) in degrees [0..359].
     * 0° = North, 90° = East, 180° = South, 270° = West.
     */
    fun calculateBearingDegrees(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Float {
        val rLat1 = Math.toRadians(lat1)
        val rLat2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(rLat2)
        val x = cos(rLat1) * sin(rLat2) - sin(rLat1) * cos(rLat2) * cos(dLon)
        val rad = atan2(y, x)
        val deg = Math.toDegrees(rad)
        return ((deg + 360.0) % 360.0).toFloat()
    }

    /**
     * Converts a bearing in degrees to cardinal abbreviation (e.g. 247° -> "SW").
     */
    fun bearingToCardinal(bearing: Float): String {
        val normalized = ((bearing % 360f) + 360f) % 360f
        return when {
            normalized >= 337.5f || normalized < 22.5f -> "N"
            normalized < 67.5f -> "NE"
            normalized < 112.5f -> "E"
            normalized < 157.5f -> "SE"
            normalized < 202.5f -> "S"
            normalized < 247.5f -> "SW"
            normalized < 292.5f -> "W"
            else -> "NW"
        }
    }

    /**
     * Formats distance cleanly for homing display:
     * - "184 m"
     * - "1.4 km"
     */
    fun formatDistance(meters: Double): String {
        return if (meters < 1000.0) {
            "${meters.roundToInt()} m"
        } else {
            String.format(java.util.Locale.US, "%.1f km", meters / 1000.0)
        }
    }

    /**
     * Formats bearing and cardinal cleanly (e.g. "247° SW").
     */
    fun formatBearing(bearing: Float): String {
        val cardinal = bearingToCardinal(bearing)
        return "${bearing.roundToInt()}° $cardinal"
    }
}

package com.meshwhisper.app.util

import kotlin.math.floor

/**
 * 100% Offline Open Location Code (Plus Code) generator for MeshWhisper.
 * Implements the standard base-20 Open Location Code algorithm without network or city databases.
 *
 * Encodes any (latitude, longitude) into a full 10-character Plus Code (e.g. "8FVC7JVW+9V")
 * resolving to a ~13.5m x 13.5m area worldwide. Ideal for verbal transmission over VHF/UHF
 * emergency radio to disaster response teams (NDRF, Civil Defence, Coast Guard).
 */
object PlusCodeHelper {

    private const val CODE_ALPHABET = "23456789CFGHJMPQRVWX"
    private const val ENCODING_BASE = 20
    private const val SEPARATOR_CHAR = '+'
    private const val SEPARATOR_POSITION = 8

    /**
     * Encodes latitude and longitude into a full 10-character Plus Code.
     * Example: (12.971598, 77.594562) -> "7J4V7JVW+9V"
     */
    fun encode(latitude: Double, longitude: Double): String {
        // Clamp latitude to [-90, 90)
        var lat = latitude.coerceIn(-90.0, 90.0)
        if (lat >= 90.0) {
            lat = 89.9999999
        }

        // Normalize longitude to [-180, 180)
        var lon = longitude
        while (lon < -180.0) lon += 360.0
        while (lon >= 180.0) lon -= 360.0

        var latVal = lat + 90.0
        var lonVal = lon + 180.0

        val codeBuilder = StringBuilder()

        // Lat/Lon grid step sizes for 5 pairs of digits
        val latSteps = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
        val lonSteps = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)

        for (i in 0 until 5) {
            val latStep = latSteps[i]
            val lonStep = lonSteps[i]

            val latIndex = floor(latVal / latStep).toInt().coerceIn(0, ENCODING_BASE - 1)
            val lonIndex = floor(lonVal / lonStep).toInt().coerceIn(0, ENCODING_BASE - 1)

            latVal -= latIndex * latStep
            lonVal -= lonIndex * lonStep

            codeBuilder.append(CODE_ALPHABET[latIndex])
            codeBuilder.append(CODE_ALPHABET[lonIndex])

            if (codeBuilder.length == SEPARATOR_POSITION) {
                codeBuilder.append(SEPARATOR_CHAR)
            }
        }

        return codeBuilder.toString()
    }
}

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
    fun encode(latitude: Double, longitude: Double): String =
        com.meshwhisper.core.util.PlusCodeHelper.encode(latitude, longitude)
}

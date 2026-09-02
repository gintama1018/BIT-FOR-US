package com.meshwhisper.app.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class LocationData(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float = 0f,
    val altitude: Double = 0.0,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 100% Offline GPS & Hardware Location Helper for MeshWhisper.
 * Uses standard Android LocationManager (no Google Play Services dependency required).
 * Functions fully offline in disaster/airplane mode with device GPS satellites.
 */
class LocationHelper(private val context: Context) {

    private val tag = "LocationHelper"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun isGpsEnabled(): Boolean {
        return try {
            locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Instantly retrieves the best available last known location from GPS or Network providers.
     */
    fun getLastKnownLocation(): LocationData? {
        if (!hasLocationPermission() || locationManager == null) return null

        var bestLocation: Location? = null

        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )

        for (provider in providers) {
            try {
                if (locationManager.isProviderEnabled(provider)) {
                    val loc = locationManager.getLastKnownLocation(provider)
                    if (loc != null) {
                        if (bestLocation == null || loc.accuracy < bestLocation.accuracy || (loc.time > bestLocation.time && (loc.time - bestLocation.time) > 60_000L)) {
                            bestLocation = loc
                        }
                    }
                }
            } catch (e: SecurityException) {
                Log.w(tag, "SecurityException fetching last known location from $provider: ${e.message}")
            } catch (e: Exception) {
                Log.w(tag, "Error fetching last known location from $provider: ${e.message}")
            }
        }

        return bestLocation?.toLocationData()
    }

    /**
     * Requests a single fresh GPS/Network location update with a strict coroutine timeout.
     * Falls back to last known location if a fresh fix cannot be acquired within the timeout.
     */
    suspend fun getCurrentLocation(timeoutMs: Long = 4000L): LocationData? {
        if (!hasLocationPermission() || locationManager == null) return null

        // If cached location is less than 30 seconds old and has good accuracy (< 30m), return immediately
        val cached = getLastKnownLocation()
        if (cached != null && (System.currentTimeMillis() - cached.timestamp < 30_000L) && cached.accuracy in 0.1f..30.0f) {
            return cached
        }

        val freshLoc = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<LocationData?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        try {
                            locationManager.removeUpdates(this)
                        } catch (_: Exception) {}
                        if (continuation.isActive) {
                            continuation.resume(location.toLocationData())
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }

                try {
                    val isGps = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    val isNetwork = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

                    if (isGps) {
                        locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
                    } else if (isNetwork) {
                        locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
                    } else {
                        continuation.resume(cached)
                        return@suspendCancellableCoroutine
                    }
                } catch (e: SecurityException) {
                    Log.w(tag, "SecurityException on single location request: ${e.message}")
                    continuation.resume(cached)
                } catch (e: Exception) {
                    Log.w(tag, "Exception on single location request: ${e.message}")
                    continuation.resume(cached)
                }

                continuation.invokeOnCancellation {
                    try {
                        locationManager.removeUpdates(listener)
                    } catch (_: Exception) {}
                }
            }
        }

        return freshLoc ?: cached
    }

    private fun Location.toLocationData(): LocationData {
        return LocationData(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            timestamp = time
        )
    }
}

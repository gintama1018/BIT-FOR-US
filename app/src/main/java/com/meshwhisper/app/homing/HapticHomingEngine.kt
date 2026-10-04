package com.meshwhisper.app.homing

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Physical disaster haptic feedback engine for disaster search and rescue.
 *
 * Implements two physical operational modes:
 * 1. Rescuer Eyes-Free Haptic Geiger Counter: Tactile cadence pulses faster as the rescuer
 *    physically approaches the target node through rubble, dark, or smoke.
 * 2. Trapped Phone Seismic & Acoustic Resonator: Drives the device's vibration motor at resonant
 *    frequency to create mechanical tapping against concrete/rubble for acoustic search geophones.
 */
class HapticHomingEngine(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vibratorManager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var homingJob: Job? = null
    private var seismicBuzzerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var currentDistanceMeters: Double = Double.MAX_VALUE
    private var currentHeadingDeviation: Float = 0f
    private var isHomingActive = false

    /**
     * Updates the physical homing parameters from GPS / Compass / BLE.
     * @param distanceMeters estimated distance to target.
     * @param headingDeviation difference between phone azimuth and target bearing (-180..+180).
     */
    fun updateHomingMetrics(distanceMeters: Double, headingDeviation: Float) {
        currentDistanceMeters = distanceMeters
        currentHeadingDeviation = headingDeviation
    }

    /**
     * Starts the eyes-free tactile guidance loop for the rescuer.
     */
    fun startRescuerHaptics() {
        if (isHomingActive || vibrator == null || !vibrator.hasVibrator()) return
        isHomingActive = true

        homingJob = scope.launch {
            while (isActive && isHomingActive) {
                val dist = currentDistanceMeters
                val dev = abs(currentHeadingDeviation)

                // 1. Wrong direction alert (if pointed > 65° away from target)
                if (dev > 65f && dist < 200.0) {
                    emitDoubleBuzz()
                    delay(1200L)
                    continue
                }

                // 2. Proximity Geiger-counter cadence
                val (pulseDurationMs, intervalMs) = when {
                    dist <= 3.0 -> 70L to 150L   // Immediate contact zone: very rapid ticks
                    dist <= 8.0 -> 50L to 280L   // Within 8 meters: rapid ticks
                    dist <= 20.0 -> 40L to 550L  // Within 20 meters: moderate ticks
                    dist <= 50.0 -> 35L to 1000L // Approaching zone: steady ticks
                    dist <= 150.0 -> 30L to 1800L// Distant detection: slow ticks
                    else -> 0L to 2500L          // Out of tactile homing range
                }

                if (pulseDurationMs > 0L) {
                    emitPulse(pulseDurationMs)
                }

                delay(intervalMs)
            }
        }
    }

    fun stopRescuerHaptics() {
        isHomingActive = false
        homingJob?.cancel()
        homingJob = null
        vibrator?.cancel()
    }

    /**
     * Trapped Phone Mode: Emits high-amplitude resonant seismic pulses.
     * Physical vibration transfers through solid rubble/pipes/slabs, detectable by rescue geophones.
     */
    fun startSeismicAcousticBuzzer(burstCycles: Int = 10) {
        if (seismicBuzzerJob != null) return

        seismicBuzzerJob = scope.launch {
            try {
                for (i in 0 until burstCycles) {
                    if (!isActive) break
                    // 3 high-intensity taps (SOS rhythmic signature)
                    for (tap in 0..2) {
                        emitHighAmplitudeTap(220L)
                        delay(140L)
                    }
                    delay(300L)
                    // 1 long resonant buzz
                    emitHighAmplitudeTap(600L)
                    delay(1500L) // Rest between burst sequences
                }
            } finally {
                seismicBuzzerJob = null
            }
        }
    }

    fun stopSeismicAcousticBuzzer() {
        seismicBuzzerJob?.cancel()
        seismicBuzzerJob = null
        vibrator?.cancel()
    }

    private fun emitPulse(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun emitHighAmplitudeTap(durationMs: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val maxAmp = if (vibrator?.hasAmplitudeControl() == true) 255 else VibrationEffect.DEFAULT_AMPLITUDE
                val effect = VibrationEffect.createOneShot(durationMs, maxAmp)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        } catch (_: Exception) {}
    }

    private fun emitDoubleBuzz() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val timings = longArrayOf(0, 45, 75, 45)
                val amplitudes = intArrayOf(0, 200, 0, 200)
                val effect = VibrationEffect.createWaveform(timings, amplitudes, -1)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                val pattern = longArrayOf(0, 45, 75, 45)
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    fun destroy() {
        stopRescuerHaptics()
        stopSeismicAcousticBuzzer()
        scope.cancel()
    }
}

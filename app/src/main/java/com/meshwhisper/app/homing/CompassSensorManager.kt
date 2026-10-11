package com.meshwhisper.app.homing

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

/**
 * Manages device hardware geomagnetic & rotation vector sensors to derive real-time,
 * tilt-compensated, True-North geographic azimuth for disaster homing.
 * Operates 100% offline with zero external dependencies.
 */
class CompassSensorManager(context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accelerometer = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private val magnetometer = if (rotationSensor == null) sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD) else null

    private val _azimuthDegrees = MutableStateFlow(0f)
    val azimuthDegrees: StateFlow<Float> = _azimuthDegrees.asStateFlow()

    private val _isAccuracyUnreliable = MutableStateFlow(false)
    val isAccuracyUnreliable: StateFlow<Boolean> = _isAccuracyUnreliable.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    private val lastAccelerometer = FloatArray(3)
    private val lastMagnetometer = FloatArray(3)
    private var isAccelerometerSet = false
    private var isMagnetometerSet = false

    private var isListening = false
    private var lastEmittedAzimuth = 0f
    private var magneticDeclination = 0f

    /**
     * Injects device GPS coordinates to calculate local magnetic declination (True North compensation).
     */
    fun setDeviceLocation(latitude: Double, longitude: Double, altitudeMeters: Double = 0.0) {
        if (latitude != 0.0 || longitude != 0.0) {
            try {
                val geoField = GeomagneticField(
                    latitude.toFloat(),
                    longitude.toFloat(),
                    altitudeMeters.toFloat(),
                    System.currentTimeMillis()
                )
                magneticDeclination = geoField.declination
            } catch (_: Exception) {}
        }
    }

    fun startListening() {
        if (isListening || sensorManager == null) return
        // Use SENSOR_DELAY_GAME for high-rate, low-latency 50Hz dynamic needle tracking
        val rate = SensorManager.SENSOR_DELAY_GAME
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, rate)
            isListening = true
        } else if (accelerometer != null && magnetometer != null) {
            sensorManager.registerListener(this, accelerometer, rate)
            sensorManager.registerListener(this, magnetometer, rate)
            isListening = true
        }
    }

    fun stopListening() {
        if (!isListening || sensorManager == null) return
        sensorManager.unregisterListener(this)
        isListening = false
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return

        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val deg = calculateTiltCompensatedAzimuth(rotationMatrix)
            emitSmoothedAzimuth(deg)
        } else if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, lastAccelerometer, 0, event.values.size)
            isAccelerometerSet = true
            tryComputeFallback()
        } else if (event.sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, lastMagnetometer, 0, event.values.size)
            isMagnetometerSet = true
            tryComputeFallback()
        }
    }

    private fun tryComputeFallback() {
        if (isAccelerometerSet && isMagnetometerSet) {
            if (SensorManager.getRotationMatrix(rotationMatrix, null, lastAccelerometer, lastMagnetometer)) {
                val deg = calculateTiltCompensatedAzimuth(rotationMatrix)
                emitSmoothedAzimuth(deg)
            }
        }
    }

    private fun calculateTiltCompensatedAzimuth(matrix: FloatArray): Float {
        // matrix[8] is the cos(pitch)*cos(roll) component (screen facing sky vs vertical).
        // If held upright in hand (pitch > ~30 deg), remap coordinates to eliminate gimbal lock.
        val rawMagAzimuth = if (abs(matrix[8]) > 0.85f) {
            SensorManager.getOrientation(matrix, orientationAngles)
            val azimuthRad = orientationAngles[0]
            ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0).toFloat()
        } else {
            val remapped = FloatArray(9)
            SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            SensorManager.getOrientation(remapped, orientationAngles)
            val azimuthRad = orientationAngles[0]
            ((Math.toDegrees(azimuthRad.toDouble()) + 360.0) % 360.0).toFloat()
        }

        // Apply Geomagnetic Declination -> converts Magnetic North to True Geographic North (matching GPS)
        return ((rawMagAzimuth + magneticDeclination + 360f) % 360f)
    }

    private fun emitSmoothedAzimuth(newDegrees: Float) {
        val diff = ((newDegrees - lastEmittedAzimuth + 540f) % 360f) - 180f
        val absDiff = abs(diff)
        if (absDiff > 0.3f) {
            // Adaptive smoothing: ultra-fast response for turns, micro-filter for hand tremors
            val alpha = when {
                absDiff > 25f -> 0.90f  // Instant tracking when pivoting phone
                absDiff > 10f -> 0.75f  // Normal turning
                absDiff > 3f  -> 0.50f  // Fine orientation adjustment
                else          -> 0.30f  // Micro-tremor stabilization
            }
            val smoothed = (lastEmittedAzimuth + diff * alpha + 360f) % 360f
            lastEmittedAzimuth = smoothed
            _azimuthDegrees.value = smoothed
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (sensor?.type == Sensor.TYPE_ROTATION_VECTOR || sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) {
            _isAccuracyUnreliable.value = (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE)
        }
    }
}

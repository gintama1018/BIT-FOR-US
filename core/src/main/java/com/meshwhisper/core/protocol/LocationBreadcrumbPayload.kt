package com.meshwhisper.core.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Trigger source for emergency location beacons and breadcrumbs.
 */
enum class BreadcrumbTriggerType(val code: Byte) {
    PERIODIC(0x01),
    BATTERY_15(0x02),
    BATTERY_10(0x03),
    BATTERY_CRITICAL_5(0x04),
    MANUAL_SOS(0x05),
    REVOKE(0x06);

    companion object {
        fun fromCode(code: Byte): BreadcrumbTriggerType? = values().firstOrNull { it.code == code }
    }
}

/**
 * Compact, endian-safe binary sub-payload for Emergency Location Beacons & Ephemeral Breadcrumbs.
 * Carried inside pairwise encrypted DIRECT_MESSAGE frames (AES-256-GCM + Ed25519 signature).
 *
 * Wire format (ByteOrder.BIG_ENDIAN, padded to fixed 64 bytes):
 * - Bytes 0..2:   Magic Prefix [0xFF, 0x42, 0x43] (0xFF is invalid UTF-8, zero text collision)
 * - Byte 3:       Version (0x01)
 * - Byte 4:       Trigger Type (BreadcrumbTriggerType code)
 * - Byte 5:       Battery Percent (-1..100)
 * - Bytes 6..9:   Sequence Number (uint32 big-endian, starts at 1)
 * - Bytes 10..13: Latitude scaled by 1e7 (int32 big-endian)
 * - Bytes 14..17: Longitude scaled by 1e7 (int32 big-endian)
 * - Bytes 18..19: Altitude in meters (int16 big-endian)
 * - Bytes 20..21: Accuracy in 0.1m decimeters (uint16 big-endian, 0 = UNKNOWN)
 * - Bytes 22..25: Hardware GPS fix timestamp in epoch seconds (uint32 big-endian)
 * - Byte 26:      Note length in bytes (uint8, 0..32)
 * - Bytes 27..N:  Optional UTF-8 note (max 32 bytes)
 * - Padded to exactly FIXED_PAYLOAD_SIZE (64 bytes) with trailing zeros.
 */
data class LocationBreadcrumbPayload(
    val triggerType: BreadcrumbTriggerType,
    val batteryPercent: Int = -1,
    val sequenceNumber: Long = 1L,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val accuracyMeters: Float = 0.0f,
    val fixTimestampSec: Long = 0L,
    val note: String? = null
) {
    companion object {
        const val VERSION: Byte = 0x01
        const val FIXED_PAYLOAD_SIZE = 64
        const val MAX_NOTE_BYTES = 32

        val MAGIC_PREFIX = byteArrayOf(0xFF.toByte(), 0x42.toByte(), 0x43.toByte()) // 0xFF, 'B', 'C'

        /**
         * Quick non-destructive check to verify if a decrypted byte array is a breadcrumb payload.
         * Runs in O(1) before any UTF-8 string decoding.
         */
        fun isBreadcrumb(bytes: ByteArray): Boolean {
            if (bytes.size < 27) return false
            return bytes[0] == MAGIC_PREFIX[0] &&
                    bytes[1] == MAGIC_PREFIX[1] &&
                    bytes[2] == MAGIC_PREFIX[2] &&
                    bytes[3] == VERSION
        }

        /**
         * Deserializes and strictly validates a byte array into a LocationBreadcrumbPayload.
         * Returns null if bytes do not match magic header, version, or contain corrupted/out-of-bounds fields.
         */
        fun deserialize(bytes: ByteArray): LocationBreadcrumbPayload? {
            if (!isBreadcrumb(bytes)) return null

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buf.position(4) // Skip 3-byte magic + 1-byte version

            val triggerCode = buf.get()
            val trigger = BreadcrumbTriggerType.fromCode(triggerCode) ?: return null

            val batteryPercent = buf.get().toInt().coerceIn(-1, 100)
            val sequenceNumber = buf.int.toLong() and 0xFFFFFFFFL

            val latScaled = buf.int
            val lonScaled = buf.int
            val alt = buf.short.toDouble()
            val accRaw = buf.short.toInt() and 0xFFFF
            val fixTimestampSec = buf.int.toLong() and 0xFFFFFFFFL
            val noteLen = buf.get().toInt() and 0xFF

            if (noteLen > MAX_NOTE_BYTES || buf.remaining() < noteLen) {
                return null
            }

            val noteStr = if (noteLen > 0) {
                val noteBytes = ByteArray(noteLen)
                buf.get(noteBytes)
                String(noteBytes, Charsets.UTF_8)
            } else {
                null
            }

            // REVOKE packets do not require coordinate validation
            if (trigger == BreadcrumbTriggerType.REVOKE) {
                return LocationBreadcrumbPayload(
                    triggerType = trigger,
                    batteryPercent = batteryPercent,
                    sequenceNumber = sequenceNumber,
                    latitude = 0.0,
                    longitude = 0.0,
                    altitude = 0.0,
                    accuracyMeters = 0.0f,
                    fixTimestampSec = fixTimestampSec,
                    note = noteStr
                )
            }

            val latitude = latScaled / 1e7
            val longitude = lonScaled / 1e7

            // Strict bounds check: valid geographical coordinates
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0 || latitude.isNaN() || longitude.isNaN()) {
                return null
            }

            val accuracyMeters = accRaw / 10.0f

            return LocationBreadcrumbPayload(
                triggerType = trigger,
                batteryPercent = batteryPercent,
                sequenceNumber = sequenceNumber,
                latitude = latitude,
                longitude = longitude,
                altitude = alt,
                accuracyMeters = accuracyMeters,
                fixTimestampSec = fixTimestampSec,
                note = noteStr
            )
        }
    }

    /**
     * Serializes this payload to a fixed 64-byte binary array (ByteOrder.BIG_ENDIAN).
     */
    fun serialize(): ByteArray {
        val out = ByteArray(FIXED_PAYLOAD_SIZE)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)

        // 1. Magic prefix + version (4 bytes)
        buf.put(MAGIC_PREFIX)
        buf.put(VERSION)

        // 2. Trigger + battery (2 bytes)
        buf.put(triggerType.code)
        buf.put(batteryPercent.coerceIn(-1, 100).toByte())

        // 3. Monotonic sequence number (4 bytes)
        buf.putInt((sequenceNumber and 0xFFFFFFFFL).toInt())

        // 4. Coordinates & telemetry (16 bytes)
        if (triggerType == BreadcrumbTriggerType.REVOKE) {
            buf.putInt(0)
            buf.putInt(0)
            buf.putShort(0.toShort())
            buf.putShort(0.toShort())
        } else {
            val latScaled = (latitude.coerceIn(-90.0, 90.0) * 1e7).toInt()
            val lonScaled = (longitude.coerceIn(-180.0, 180.0) * 1e7).toInt()
            val altShort = altitude.toInt().coerceIn(-1000, 9000).toShort()
            val accShort = (accuracyMeters * 10.0f).toInt().coerceIn(0, 65535).toShort()

            buf.putInt(latScaled)
            buf.putInt(lonScaled)
            buf.putShort(altShort)
            buf.putShort(accShort)
        }

        // 5. Hardware GPS fix timestamp (4 bytes)
        buf.putInt((fixTimestampSec and 0xFFFFFFFFL).toInt())

        // 6. Note (1 byte length + max 32 bytes)
        val noteBytes = note?.toByteArray(Charsets.UTF_8)?.take(MAX_NOTE_BYTES)?.toByteArray() ?: ByteArray(0)
        buf.put(noteBytes.size.toByte())
        if (noteBytes.isNotEmpty()) {
            buf.put(noteBytes)
        }

        // Remainder of the 64 bytes is naturally zero-padded
        return out
    }
}

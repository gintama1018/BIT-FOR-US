package com.meshwhisper.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

enum class ImageQuality {
    STANDARD,
    HIGH,
    ORIGINAL
}

object MediaCompressor {
    private const val TAG = "MediaCompressor"

    fun compressImage(context: Context, imageUri: Uri): ByteArray? {
        return compressImageWithQuality(context, imageUri, ImageQuality.STANDARD)
    }

    fun compressImageWithQuality(context: Context, imageUri: Uri, quality: ImageQuality): ByteArray? {
        if (quality == ImageQuality.ORIGINAL) {
            return try {
                context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read original image bytes: $imageUri", e)
                null
            }
        }

        val maxDimension = when (quality) {
            ImageQuality.HIGH -> 1600
            ImageQuality.STANDARD -> 800
            ImageQuality.ORIGINAL -> 1600
        }
        val targetMaxBytes = when (quality) {
            ImageQuality.HIGH -> 1200 * 1024 // 1.2 MB
            ImageQuality.STANDARD -> 300 * 1024 // 300 KB
            ImageQuality.ORIGINAL -> 10 * 1024 * 1024
        }
        var jpegQuality = when (quality) {
            ImageQuality.HIGH -> 85
            ImageQuality.STANDARD -> 75
            ImageQuality.ORIGINAL -> 100
        }

        return try {
            val contentResolver = context.contentResolver

            // 1. Decode bounds only
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var inSampleSize = 1
            val maxOrigDim = max(origWidth, origHeight)
            if (maxOrigDim > maxDimension) {
                inSampleSize = maxOrigDim / maxDimension
            }

            // 2. Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Lower memory footprint
            }

            val sampledBitmap = contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            // 3. Exact scale to fit within maxDimension
            val width = sampledBitmap.width
            val height = sampledBitmap.height
            val scaleFactor = minOf(
                maxDimension.toFloat() / width,
                maxDimension.toFloat() / height,
                1.0f
            )

            val scaledBitmap = if (scaleFactor < 1.0f) {
                val newW = (width * scaleFactor).toInt()
                val newH = (height * scaleFactor).toInt()
                Bitmap.createScaledBitmap(sampledBitmap, newW, newH, true)
            } else {
                sampledBitmap
            }

            // 4. Compress to JPEG with adaptive quality targeting targetMaxBytes
            var compressedBytes: ByteArray
            do {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, outputStream)
                compressedBytes = outputStream.toByteArray()
                jpegQuality -= 10
            } while (compressedBytes.size > targetMaxBytes && jpegQuality >= 35)

            Log.d(TAG, "Compressed image ($quality) from (${origWidth}x${origHeight}) to (${scaledBitmap.width}x${scaledBitmap.height}), size: ${compressedBytes.size} bytes")
            compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image ($quality) from URI: $imageUri", e)
            null
        }
    }

    /**
     * Generates a tiny 24x24 low-res preview (<= 350 bytes) for embedding in MEDIA_INIT.
     */
    fun generateMicroPreview(context: Context, imageUri: Uri, maxBytes: Int = 350): ByteArray? {
        return try {
            val contentResolver = context.contentResolver
            val options = BitmapFactory.Options().apply { inSampleSize = 8; inPreferredConfig = Bitmap.Config.RGB_565 }
            val sampled = contentResolver.openInputStream(imageUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val microBitmap = Bitmap.createScaledBitmap(sampled, 24, 24, true)
            var quality = 35
            var bytes: ByteArray
            do {
                val stream = ByteArrayOutputStream()
                microBitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
                bytes = stream.toByteArray()
                quality -= 5
            } while (bytes.size > maxBytes && quality >= 15)

            if (bytes.size <= maxBytes) bytes else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to generate micro preview: ${e.message}")
            null
        }
    }

    /**
     * Extracts ~24 normalized amplitude sample bytes (0..100) from an audio file for waveform skeleton rendering.
     */
    fun extractAudioWaveform(file: File, sampleCount: Int = 24): ByteArray {
        val result = ByteArray(sampleCount)
        if (!file.exists() || file.length() == 0L) return result

        return try {
            val fileBytes = file.readBytes()
            val step = maxOf(1, fileBytes.size / sampleCount)
            for (i in 0 until sampleCount) {
                val start = i * step
                val end = minOf(start + step, fileBytes.size)
                var sum = 0L
                var count = 0
                for (j in start until end step 4) {
                    sum += abs(fileBytes[j].toInt())
                    count++
                }
                val avg = if (count > 0) (sum / count) else 0
                val normalized = ((avg.toDouble() / 128.0) * 100.0).toInt().coerceIn(10, 100)
                result[i] = normalized.toByte()
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract waveform amplitude: ${e.message}")
            ByteArray(sampleCount) { 30.toByte() }
        }
    }

    /**
     * Computes the 32-byte SHA-256 target hash of a media payload.
     */
    fun computeSha256(bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes)
    }

    private const val MAX_AVATAR_DIMENSION = 128
    private const val MAX_AVATAR_BYTES = 8 * 1024 // 8 KB limit

    fun compressAvatar(context: Context, imageUri: Uri): ByteArray? {
        return try {
            val contentResolver = context.contentResolver

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var inSampleSize = 1
            val maxOrigDim = max(origWidth, origHeight)
            if (maxOrigDim > MAX_AVATAR_DIMENSION) {
                inSampleSize = maxOrigDim / MAX_AVATAR_DIMENSION
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val sampledBitmap = contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            val width = sampledBitmap.width
            val height = sampledBitmap.height
            val scaleFactor = minOf(
                MAX_AVATAR_DIMENSION.toFloat() / width,
                MAX_AVATAR_DIMENSION.toFloat() / height,
                1.0f
            )

            val scaledBitmap = if (scaleFactor < 1.0f) {
                val newW = (width * scaleFactor).toInt()
                val newH = (height * scaleFactor).toInt()
                Bitmap.createScaledBitmap(sampledBitmap, newW, newH, true)
            } else {
                sampledBitmap
            }

            var quality = 65
            var compressedBytes: ByteArray
            do {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedBytes = outputStream.toByteArray()
                quality -= 10
            } while (compressedBytes.size > MAX_AVATAR_BYTES && quality >= 20)

            Log.d(TAG, "Compressed avatar to (${scaledBitmap.width}x${scaledBitmap.height}), size: ${compressedBytes.size} bytes (target <= 8KB)")
            compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress avatar from URI: $imageUri", e)
            null
        }
    }
}

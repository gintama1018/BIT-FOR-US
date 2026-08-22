package com.meshwhisper.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlin.math.max

object MediaCompressor {
    private const val TAG = "MediaCompressor"
    private const val MAX_IMAGE_DIMENSION = 640
    private const val MAX_IMAGE_BYTES = 60 * 1024 // 60 KB limit

    fun compressImage(context: Context, imageUri: Uri): ByteArray? {
        return try {
            val contentResolver = context.contentResolver

            // 1. Decode bounds only to calculate inSampleSize
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            val origWidth = options.outWidth
            val origHeight = options.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var inSampleSize = 1
            val maxOrigDim = max(origWidth, origHeight)
            if (maxOrigDim > MAX_IMAGE_DIMENSION) {
                inSampleSize = maxOrigDim / MAX_IMAGE_DIMENSION
            }

            // 2. Decode downsampled bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // Lower memory footprint
            }

            val sampledBitmap = contentResolver.openInputStream(imageUri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            } ?: return null

            // 3. Exact scale to fit within MAX_IMAGE_DIMENSION
            val width = sampledBitmap.width
            val height = sampledBitmap.height
            val scaleFactor = minOf(
                MAX_IMAGE_DIMENSION.toFloat() / width,
                MAX_IMAGE_DIMENSION.toFloat() / height,
                1.0f
            )

            val scaledBitmap = if (scaleFactor < 1.0f) {
                val newW = (width * scaleFactor).toInt()
                val newH = (height * scaleFactor).toInt()
                Bitmap.createScaledBitmap(sampledBitmap, newW, newH, true)
            } else {
                sampledBitmap
            }

            // 4. Compress to JPEG with adaptive quality targeting <= 60KB
            var quality = 55
            var compressedBytes: ByteArray
            do {
                val outputStream = ByteArrayOutputStream()
                scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                compressedBytes = outputStream.toByteArray()
                quality -= 10
            } while (compressedBytes.size > MAX_IMAGE_BYTES && quality >= 20)

            Log.d(TAG, "Compressed image from (${origWidth}x${origHeight}) to (${scaledBitmap.width}x${scaledBitmap.height}), size: ${compressedBytes.size} bytes (target <= 60KB)")
            compressedBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to compress image from URI: $imageUri", e)
            null
        }
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

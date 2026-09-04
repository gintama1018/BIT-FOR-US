package com.meshwhisper.app.ui.components

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * High-performance, offline CameraX ImageAnalysis analyzer for QR codes.
 * Extracts raw 8-bit Y-luminance planes with row-stride padding safety and rotation correction.
 */
class QrCodeAnalyzer(
    private val onQrCodeScanned: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8"
        )
        setHints(hints)
    }

    @Volatile
    var isScanningEnabled: Boolean = true

    override fun analyze(image: ImageProxy) {
        if (!isScanningEnabled) {
            image.close()
            return
        }

        try {
            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride

            // Handle row-stride padding across diverse Android camera sensors
            val bytes = if (rowStride == width) {
                ByteArray(buffer.remaining()).also { buffer.get(it) }
            } else {
                val clean = ByteArray(width * height)
                val rowBuffer = ByteArray(rowStride)
                for (row in 0 until height) {
                    val toRead = minOf(rowStride, buffer.remaining())
                    buffer.get(rowBuffer, 0, toRead)
                    System.arraycopy(rowBuffer, 0, clean, row * width, width)
                }
                clean
            }

            // Adjust for sensor rotation so scanning succeeds in both portrait and landscape
            val rotation = image.imageInfo.rotationDegrees
            val (rotatedBytes, rotWidth, rotHeight) = when (rotation) {
                90 -> rotate90(bytes, width, height)
                180 -> rotate180(bytes, width, height)
                270 -> rotate270(bytes, width, height)
                else -> Triple(bytes, width, height)
            }

            val source = PlanarYUVLuminanceSource(
                rotatedBytes,
                rotWidth,
                rotHeight,
                0,
                0,
                rotWidth,
                rotHeight,
                false
            )

            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            val result = reader.decodeWithState(binaryBitmap)

            if (result != null && !result.text.isNullOrBlank()) {
                isScanningEnabled = false
                onQrCodeScanned(result.text)
            }
        } catch (_: Exception) {
            // ZXing NotFoundException occurs naturally for frames without a QR code
        } finally {
            image.close()
        }
    }

    private fun rotate90(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[i++] = data[y * width + x]
            }
        }
        return Triple(rotated, height, width)
    }

    private fun rotate270(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val rotated = ByteArray(data.size)
        var i = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                rotated[i++] = data[y * width + x]
            }
        }
        return Triple(rotated, height, width)
    }

    private fun rotate180(data: ByteArray, width: Int, height: Int): Triple<ByteArray, Int, Int> {
        val rotated = ByteArray(data.size)
        var i = 0
        for (idx in data.size - 1 downTo 0) {
            rotated[i++] = data[idx]
        }
        return Triple(rotated, width, height)
    }
}

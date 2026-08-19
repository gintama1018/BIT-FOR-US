package com.meshwhisper.app.ui.util

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrCodeGenerator {

    fun generateQrBitmap(content: String, sizePx: Int = 512): ImageBitmap? {
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val darkColor = android.graphics.Color.rgb(0, 230, 118) // Emerald
            val lightColor = android.graphics.Color.rgb(17, 24, 39) // Dark Surface

            for (x in 0 until width) {
                for (y in 0 until height) {
                    bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) darkColor else lightColor)
                }
            }

            bitmap.asImageBitmap()
        } catch (e: Exception) {
            null
        }
    }
}

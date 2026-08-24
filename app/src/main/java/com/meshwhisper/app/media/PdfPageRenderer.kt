package com.meshwhisper.app.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Lightweight, zero-dependency native PDF Page Renderer using Android's built-in PdfRenderer API.
 */
class PdfPageRenderer(private val file: File) : AutoCloseable {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    val pageCount: Int
        get() = renderer?.pageCount ?: 0

    val isAvailable: Boolean
        get() = renderer != null && pageCount > 0

    init {
        try {
            if (file.exists() && file.length() > 0) {
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                fileDescriptor?.let {
                    renderer = PdfRenderer(it)
                }
            }
        } catch (e: Exception) {
            Log.e("PdfPageRenderer", "Failed to initialize PdfRenderer for ${file.absolutePath}: ${e.message}", e)
            close()
        }
    }

    /**
     * Renders a specific PDF page lazily into a high-quality display bitmap with crisp white background.
     */
    fun renderPage(pageIndex: Int, targetWidth: Int = 1080): Bitmap? {
        val r = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= r.pageCount) return null

        return try {
            val page = r.openPage(pageIndex)
            val origW = page.width
            val origH = page.height

            val scale = (targetWidth.toFloat() / origW).coerceIn(0.5f, 3.0f)
            val outW = (origW * scale).toInt()
            val outH = (origH * scale).toInt()

            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)

            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            bitmap
        } catch (e: Exception) {
            Log.e("PdfPageRenderer", "Failed to render PDF page $pageIndex: ${e.message}", e)
            null
        }
    }

    override fun close() {
        try {
            renderer?.close()
        } catch (_: Exception) {}
        renderer = null

        try {
            fileDescriptor?.close()
        } catch (_: Exception) {}
        fileDescriptor = null
    }
}

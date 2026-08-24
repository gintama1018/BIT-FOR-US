package com.meshwhisper.app.media

import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipInputStream

/**
 * Zero-dependency lightweight plain-text extractor for .docx (Office Open XML) documents.
 * Extracts body text runs from word/document.xml without needing heavy external libraries.
 */
object DocxTextExtractor {
    private const val TAG = "DocxTextExtractor"

    fun extractText(file: File): String? {
        if (!file.exists() || file.length() == 0L) return null

        return try {
            var extractedXml: String? = null
            ZipInputStream(FileInputStream(file)).use { zipIn ->
                var entry = zipIn.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        extractedXml = zipIn.reader(Charsets.UTF_8).readText()
                        break
                    }
                    entry = zipIn.nextEntry
                }
            }

            if (extractedXml == null) return null

            // Parse <w:p> (paragraphs) and <w:t> (text runs)
            val paragraphs = extractedXml!!.split("</w:p>")
            val sb = StringBuilder()

            for (p in paragraphs) {
                val textRuns = Regex("<w:t[^>]*>(.*?)</w:t>").findAll(p)
                val line = textRuns.map { it.groupValues[1] }.joinToString("")
                if (line.isNotBlank()) {
                    // Decode XML entities
                    val cleanLine = line
                        .replace("&amp;", "&")
                        .replace("&lt;", "<")
                        .replace("&gt;", ">")
                        .replace("&quot;", "\"")
                        .replace("&apos;", "'")
                    sb.append(cleanLine).append("\n\n")
                }
            }

            val result = sb.toString().trim()
            if (result.isNotBlank()) result else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract text from DOCX: ${e.message}")
            null
        }
    }
}

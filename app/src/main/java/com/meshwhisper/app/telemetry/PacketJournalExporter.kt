package com.meshwhisper.app.telemetry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * High-reliability persistent file journal for Phase 0 instrumentation.
 * Appends every packet, chunk timeout, background heartbeat, and scan match to a persistent CSV file.
 * Survives process restarts and allows instant export via Android Share Sheet or `adb pull`.
 */
object PacketJournalExporter {

    private const val TAG = "PacketJournal"
    private const val JOURNAL_DIR = "journals"
    private const val ACTIVE_JOURNAL_FILE = "mesh_packet_journal.csv"

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    private val lock = Any()

    private const val CSV_HEADER =
        "timestamp_iso,timestamp_ms,event_type,packet_type,msg_id,sender_hex,recipient_hex,ttl,hop_count,bytes,rssi,screen_interactive,details"

    fun getJournalFile(context: Context): File {
        val dir = File(context.filesDir, JOURNAL_DIR).apply { if (!exists()) mkdirs() }
        val file = File(dir, ACTIVE_JOURNAL_FILE)
        if (!file.exists()) {
            synchronized(lock) {
                if (!file.exists()) {
                    file.writeText("$CSV_HEADER\n")
                }
            }
        }
        return file
    }

    /**
     * Appends a generic packet event (TX, RX, RELAY, DROP) to the journal.
     */
    fun logEvent(
        context: Context,
        eventType: String,
        packetType: String,
        messageId: String,
        senderId: Long,
        recipientId: Long,
        ttl: Int,
        hopCount: Int,
        bytes: Int,
        rssi: Int?,
        isScreenInteractive: Boolean,
        details: String
    ) {
        val now = System.currentTimeMillis()
        val iso = isoFormat.format(Date(now))
        val senderHex = String.format(Locale.US, "0x%016X", senderId)
        val recipientHex = String.format(Locale.US, "0x%016X", recipientId)
        val sanitizedDetails = details.replace("\"", "\"\"").replace("\n", " ")
        val line = "$iso,$now,$eventType,$packetType,$messageId,$senderHex,$recipientHex,$ttl,$hopCount,$bytes,${rssi ?: ""},$isScreenInteractive,\"$sanitizedDetails\"\n"

        appendToFile(context, line)
    }

    /**
     * Logs chunk-level assembly failures (eviction or 30-second timeout).
     */
    fun logChunkTimeout(
        context: Context,
        deviceAddress: String,
        sessionId: Short,
        receivedChunks: Int,
        totalChunks: Int,
        reason: String,
        isScreenInteractive: Boolean
    ) {
        val now = System.currentTimeMillis()
        val iso = isoFormat.format(Date(now))
        val missing = maxOf(0, totalChunks - receivedChunks)
        val details = "ChunkSession $sessionId on $deviceAddress dropped: recv=$receivedChunks/$totalChunks missing=$missing reason=$reason"
        val line = "$iso,$now,FRAME_TIMEOUT,CHUNK_LOSS,sess_$sessionId,0x0,0x0,0,0,0,,$isScreenInteractive,\"$details\"\n"

        appendToFile(context, line)
    }

    /**
     * Logs background liveness heartbeats during screen-off operation.
     */
    fun logHeartbeat(
        context: Context,
        isScreenInteractive: Boolean,
        connectedPeers: Int,
        relayedPackets: Int,
        wakeLockHeld: Boolean
    ) {
        val now = System.currentTimeMillis()
        val iso = isoFormat.format(Date(now))
        val details = "Heartbeat: interactive=$isScreenInteractive peers=$connectedPeers relayed=$relayedPackets wakeLockHeld=$wakeLockHeld"
        val line = "$iso,$now,BG_HEARTBEAT,HEARTBEAT,,0x0,0x0,0,0,0,,$isScreenInteractive,\"$details\"\n"

        appendToFile(context, line)
    }

    /**
     * Logs BLE advertisement scan matches, particularly when screen is off.
     */
    fun logScanMatch(
        context: Context,
        deviceAddress: String,
        rssi: Int,
        isScreenInteractive: Boolean
    ) {
        val now = System.currentTimeMillis()
        val iso = isoFormat.format(Date(now))
        val details = "ScanMatch: address=$deviceAddress rssi=$rssi screenInteractive=$isScreenInteractive"
        val line = "$iso,$now,BG_SCAN_MATCH,BLE_SCAN,,0x0,0x0,0,0,0,$rssi,$isScreenInteractive,\"$details\"\n"

        appendToFile(context, line)
    }

    private fun appendToFile(context: Context, line: String) {
        try {
            val file = getJournalFile(context)
            synchronized(lock) {
                FileWriter(file, true).use { fw ->
                    PrintWriter(fw).use { pw ->
                        pw.print(line)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append to journal: ${e.message}", e)
        }
    }

    /**
     * Generates a timestamped export file in external files dir (for adb pull)
     * and returns the shareable Uri via FileProvider.
     */
    suspend fun exportJournal(context: Context): Pair<File, Uri>? = withContext(Dispatchers.IO) {
        try {
            val src = getJournalFile(context)
            val exportDir = File(context.filesDir, JOURNAL_DIR).apply { if (!exists()) mkdirs() }
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val exportFile = File(exportDir, "mesh_journal_export_$timeStamp.csv")

            synchronized(lock) {
                src.copyTo(exportFile, overwrite = true)
            }

            // Also copy to external files dir so testers can pull via `adb pull` without root
            try {
                val extDir = context.getExternalFilesDir(JOURNAL_DIR)
                if (extDir != null) {
                    val extFile = File(extDir, exportFile.name)
                    exportFile.copyTo(extFile, overwrite = true)
                    Log.i(TAG, "Journal mirrored to external storage for adb pull: ${extFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Mirror to external storage skipped: ${e.message}")
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                exportFile
            )
            Pair(exportFile, uri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export journal: ${e.message}", e)
            null
        }
    }

    /**
     * Clears existing journal and re-initializes with CSV header.
     */
    fun clearJournal(context: Context) {
        synchronized(lock) {
            try {
                val file = getJournalFile(context)
                file.writeText("$CSV_HEADER\n")
                Log.i(TAG, "Journal cleared and re-initialized")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear journal: ${e.message}", e)
            }
        }
    }
}

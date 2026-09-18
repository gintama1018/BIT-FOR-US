package com.meshwhisper.core.protocol

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Normative P6 media admission control and resource accounting.
 * Implements NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md §3.14 and C-14.
 */
class InboundMediaAdmission(
    val totalChunks: Int,
    val totalSizeBytes: Int
) {
    val chunks = ConcurrentHashMap<Int, ByteArray>()
    var cumulativeBytesAccepted: Long = 0L
    var cumulativeBytesRelayed: Long = 0L
    val forwardCountPerIndex = ConcurrentHashMap<Int, Int>()
    val admissionLock = Any()

    /**
     * Receiver-side atomic chunk admission (P6 Final Micro-Fix 1 & C-14):
     * 1. Validates chunkData.size <= MAX_MEDIA_CHUNK_PAYLOAD (320 B)
     * 2. Validates chunkIndex in 0 until totalChunks
     * 3. Write-once: if chunks contains chunkIndex, reject duplicate/race
     * 4. Cumulative check: cumulativeBytesAccepted + chunkData.size <= totalSizeBytes
     * 5. Commit chunk and increment cumulativeBytesAccepted
     */
    fun admitChunk(chunkIndex: Int, chunkData: ByteArray): Boolean {
        return synchronized(admissionLock) {
            if (chunkData.size > ResourceLimits.MAX_MEDIA_CHUNK_PAYLOAD) {
                false
            } else if (chunkIndex < 0 || chunkIndex >= totalChunks) {
                false
            } else if (chunks.containsKey(chunkIndex)) {
                false
            } else if (cumulativeBytesAccepted + chunkData.size > totalSizeBytes) {
                false
            } else {
                chunks[chunkIndex] = chunkData
                cumulativeBytesAccepted += chunkData.size
                true
            }
        }
    }

    /**
     * Concurrency-safe relay-side chunk admission accounting (P6):
     * - cumulativeBytesRelayed + chunkSize <= totalSizeBytes * 1.1
     * - each chunkIndex forwarded at most twice
     */
    fun checkAndRecordRelay(chunkIndex: Int, chunkSize: Int): Boolean {
        return synchronized(admissionLock) {
            val count = forwardCountPerIndex.getOrDefault(chunkIndex, 0)
            if (count >= 2) {
                false
            } else if (cumulativeBytesRelayed + chunkSize > (totalSizeBytes * 1.1).toLong()) {
                false
            } else {
                forwardCountPerIndex[chunkIndex] = count + 1
                cumulativeBytesRelayed += chunkSize
                true
            }
        }
    }
}

/**
 * Identity media bandwidth tracking (8 MB/hr rolling window).
 * Implements ResourceLimits.MEDIA_BANDWIDTH_PER_IDENTITY_BYTES_PER_HOUR.
 */
class IdentityMediaBudgetTracker(
    private val hourlyBudgetBytes: Long = ResourceLimits.MEDIA_BANDWIDTH_PER_IDENTITY_BYTES_PER_HOUR
) {
    private val identityHourlyBudget = ConcurrentHashMap<Long, MutableList<Pair<Long, Long>>>()

    fun checkAndTrackMediaBudget(senderId: Long, bytesToAdd: Int, nowMs: Long = System.currentTimeMillis()): Boolean {
        val oneHourAgo = nowMs - 3600_000L
        val history = identityHourlyBudget.computeIfAbsent(senderId) { mutableListOf() }
        synchronized(history) {
            history.removeAll { it.first < oneHourAgo }
            val currentUsage = history.sumOf { it.second }
            if (currentUsage + bytesToAdd > hourlyBudgetBytes) {
                return false
            }
            history.add(Pair(nowMs, bytesToAdd.toLong()))
            return true
        }
    }

    fun clear() {
        identityHourlyBudget.clear()
    }
}

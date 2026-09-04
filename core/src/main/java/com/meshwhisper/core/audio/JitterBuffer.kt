package com.meshwhisper.core.audio

import java.util.TreeMap

/**
 * Audio frame container for real-time streaming.
 */
data class AudioFrame(
    val sequenceNumber: Int,
    val timestampMs: Long,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrame
        return sequenceNumber == other.sequenceNumber &&
                timestampMs == other.timestampMs &&
                data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Thread-safe, bounded Jitter Buffer for 1-hop real-time audio streams.
 *
 * Absorbs 40-80ms of wireless timing jitter, reorders packets received slightly out of order,
 * drops duplicate/stale packets, and maintains smooth playback timing.
 *
 * @param targetPreloadFrames Number of frames to buffer before initial playback starts (default 2 = 40ms)
 * @param maxCapacityFrames Maximum frame capacity before dropping oldest frames (default 8 = 160ms)
 */
class JitterBuffer(
    val targetPreloadFrames: Int = 2,
    val maxCapacityFrames: Int = 8
) {
    private val frameMap = TreeMap<Int, AudioFrame>()
    private var nextPlaySeq: Int = -1
    private var lastPoppedSeq: Int = -1
    private var isBuffering: Boolean = true
    private var totalFramesPushed: Long = 0L
    private var totalFramesDroppedLate: Long = 0L
    private var totalFramesDroppedOverflow: Long = 0L

    @Synchronized
    fun push(frame: AudioFrame): Boolean {
        totalFramesPushed++

        if (lastPoppedSeq != -1 && frame.sequenceNumber <= lastPoppedSeq) {
            // Frame is older than what was already played; drop as late
            totalFramesDroppedLate++
            return false
        }

        // If playback hasn't started yet, allow nextPlaySeq to adapt to earlier received frames
        if (nextPlaySeq == -1 || (lastPoppedSeq == -1 && frame.sequenceNumber < nextPlaySeq)) {
            nextPlaySeq = frame.sequenceNumber
        }

        if (frameMap.containsKey(frame.sequenceNumber)) {
            // Duplicate frame; drop
            return false
        }

        frameMap[frame.sequenceNumber] = frame

        // Enforce maximum buffer capacity: if buffer exceeds capacity, drop oldest
        while (frameMap.size > maxCapacityFrames) {
            val oldestKey = frameMap.firstKey()
            frameMap.remove(oldestKey)
            totalFramesDroppedOverflow++
            if (oldestKey >= nextPlaySeq) {
                nextPlaySeq = oldestKey + 1
            }
        }

        // Check if initial pre-buffering condition is satisfied
        if (isBuffering && frameMap.size >= targetPreloadFrames) {
            isBuffering = false
        }

        return true
    }

    /**
     * Polls the next in-order audio frame for playback.
     * Returns:
     *   - [AudioFrame] if the exact next expected frame is ready.
     *   - `null` if currently buffering or the frame is missing/not yet arrived.
     */
    @Synchronized
    fun pop(): AudioFrame? {
        if (isBuffering) {
            if (frameMap.size >= targetPreloadFrames) {
                isBuffering = false
            } else {
                return null
            }
        }

        if (frameMap.isEmpty()) {
            // Underflow: re-enter buffering mode until target depth is restored
            isBuffering = true
            return null
        }

        val frame = frameMap.remove(nextPlaySeq)
        if (frame != null) {
            lastPoppedSeq = nextPlaySeq
            nextPlaySeq++
            return frame
        }

        // The expected frame is missing. If newer frames are waiting, skip the lost frame
        // after giving it a brief grace period to prevent stalling the pipeline.
        val firstAvailableKey = frameMap.firstKey()
        if (firstAvailableKey > nextPlaySeq && (firstAvailableKey - nextPlaySeq) <= 3) {
            // Advance playback head to the next available frame
            nextPlaySeq = firstAvailableKey
            val skippedFrame = frameMap.remove(nextPlaySeq)
            if (skippedFrame != null) {
                lastPoppedSeq = nextPlaySeq
                nextPlaySeq++
            }
            return skippedFrame
        }

        return null
    }

    @Synchronized
    fun size(): Int = frameMap.size

    @Synchronized
    fun isBuffering(): Boolean = isBuffering

    @Synchronized
    fun reset() {
        frameMap.clear()
        nextPlaySeq = -1
        lastPoppedSeq = -1
        isBuffering = true
    }
}

package com.meshwhisper.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import com.meshwhisper.core.audio.AdpcmCodec
import com.meshwhisper.core.audio.AudioFrame
import com.meshwhisper.core.audio.JitterBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete Android implementation of [AudioStreamer].
 * Captures 8 kHz 16-bit PCM from the microphone, compresses it via [AdpcmCodec] (80 bytes / 20ms),
 * and plays received peer frames via [JitterBuffer] and [AudioTrack].
 */
class AndroidAudioStreamer(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : AudioStreamer {

    private val tag = "AndroidAudioStreamer"

    private val isRunning = AtomicBoolean(false)
    private val isMuted = AtomicBoolean(false)

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private val jitterBuffer = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 8)
    private val encodeState = AdpcmCodec.State()
    private val decodeState = AdpcmCodec.State()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @SuppressLint("MissingPermission")
    override fun startStreaming(onOutboundFrame: (sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) -> Unit) {
        if (isRunning.getAndSet(true)) {
            Log.w(tag, "AudioStreamer already running")
            return
        }

        jitterBuffer.reset()
        encodeState.reset()
        decodeState.reset()

        val sampleRate = 8000
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val samplesPerFrame = AdpcmCodec.SAMPLES_PER_FRAME_20MS_8KHZ // 160 samples = 20ms

        // Initialize AudioRecord
        val minRecordBufSize = AudioRecord.getMinBufferSize(sampleRate, channelIn, encoding)
        val recordBufSize = maxOf(minRecordBufSize, samplesPerFrame * 2 * 4)

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                channelIn,
                encoding,
                recordBufSize
            )
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                record.startRecording()
                audioRecord = record
            } else {
                Log.e(tag, "AudioRecord failed to initialize with VOICE_COMMUNICATION; falling back to MIC")
                record.release()
                val fallbackRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    channelIn,
                    encoding,
                    recordBufSize
                )
                if (fallbackRecord.state == AudioRecord.STATE_INITIALIZED) {
                    fallbackRecord.startRecording()
                    audioRecord = fallbackRecord
                } else {
                    Log.e(tag, "Fallback AudioRecord also failed to initialize")
                    fallbackRecord.release()
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception creating AudioRecord: ${e.message}", e)
        }

        // Initialize AudioTrack
        val minTrackBufSize = AudioTrack.getMinBufferSize(sampleRate, channelOut, encoding)
        val trackBufSize = maxOf(minTrackBufSize, samplesPerFrame * 2 * 4)

        try {
            @Suppress("DEPRECATION")
            val track = AudioTrack(
                AudioManager.STREAM_VOICE_CALL,
                sampleRate,
                channelOut,
                encoding,
                trackBufSize,
                AudioTrack.MODE_STREAM
            )
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.play()
                audioTrack = track
            } else {
                Log.e(tag, "AudioTrack failed to initialize")
                track.release()
            }
        } catch (e: Exception) {
            Log.e(tag, "Exception creating AudioTrack: ${e.message}", e)
        }

        // Capture Coroutine Loop
        captureJob = scope.launch {
            val pcmIn = ShortArray(samplesPerFrame)
            val adpcmOut = ByteArray(AdpcmCodec.BYTES_PER_FRAME_ADPCM)
            var seqNum = 0

            while (isActive && isRunning.get()) {
                val record = audioRecord ?: break
                val readCount = record.read(pcmIn, 0, samplesPerFrame)
                if (readCount == samplesPerFrame) {
                    if (isMuted.get()) {
                        // Microphone is muted: zero out audio data to send silence
                        pcmIn.fill(0)
                    }

                    AdpcmCodec.encode(pcmIn, samplesPerFrame, adpcmOut, encodeState)
                    val frameCopy = adpcmOut.copyOf()
                    onOutboundFrame(seqNum++, System.currentTimeMillis(), frameCopy)
                } else if (readCount < 0) {
                    Log.w(tag, "AudioRecord read error: $readCount")
                    delay(20L)
                }
            }
        }

        // Playback Coroutine Loop
        playbackJob = scope.launch {
            val pcmOut = ShortArray(samplesPerFrame)
            val silence = ShortArray(samplesPerFrame) { 0 }

            while (isActive && isRunning.get()) {
                val track = audioTrack ?: break
                val frame = jitterBuffer.pop()

                if (frame != null && frame.data.isNotEmpty()) {
                    AdpcmCodec.decode(frame.data, frame.data.size, pcmOut, decodeState)
                    track.write(pcmOut, 0, samplesPerFrame)
                } else {
                    // Underflow or waiting for preload: write small comfort silence or brief sleep
                    track.write(silence, 0, samplesPerFrame / 2)
                    delay(10L)
                }
            }
        }
    }

    override fun onInboundFrame(sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) {
        if (!isRunning.get()) return
        jitterBuffer.push(AudioFrame(sequenceNumber, timestamp, audioBytes))
    }

    override fun stopStreaming() {
        if (!isRunning.getAndSet(false)) return

        scope.launch {
            try {
                captureJob?.cancelAndJoin()
                playbackJob?.cancelAndJoin()
            } catch (_: Exception) {
            }

            try {
                audioRecord?.stop()
                audioRecord?.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioRecord: ${e.message}")
            } finally {
                audioRecord = null
            }

            try {
                audioTrack?.stop()
                audioTrack?.release()
            } catch (e: Exception) {
                Log.w(tag, "Error releasing AudioTrack: ${e.message}")
            } finally {
                audioTrack = null
            }

            jitterBuffer.reset()
        }
    }

    override fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        try {
            audioManager?.mode = if (speakerOn) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_IN_CALL
            audioManager?.isSpeakerphoneOn = speakerOn
        } catch (e: Exception) {
            Log.w(tag, "Failed to toggle speakerphone: ${e.message}")
        }
    }
}

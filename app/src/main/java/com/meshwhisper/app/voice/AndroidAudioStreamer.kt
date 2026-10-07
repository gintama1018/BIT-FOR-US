package com.meshwhisper.app.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
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

    private val jitterBuffer = JitterBuffer(targetPreloadFrames = 2, maxCapacityFrames = 12)
    private val encodeState = AdpcmCodec.State()
    private val decodeState = AdpcmCodec.State()

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    @SuppressLint("MissingPermission")
    override fun startStreaming(onOutboundFrame: (sequenceNumber: Int, timestamp: Long, audioBytes: ByteArray) -> Unit) {
        if (isRunning.getAndSet(true)) {
            Log.w(tag, "AudioStreamer already running")
            return
        }

        try {
            wakeLock = powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MeshWhisper:VoiceCallStreamer")?.apply {
                setReferenceCounted(false)
                acquire(15 * 60 * 1000L)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to acquire wake lock: ${e.message}")
        }

        jitterBuffer.reset()
        encodeState.reset()
        decodeState.reset()

        val sampleRate = 8000
        val channelIn = AudioFormat.CHANNEL_IN_MONO
        val channelOut = AudioFormat.CHANNEL_OUT_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val samplesPerFrame = 320 // 40ms frame at 8 kHz (160 bytes ADPCM) - 25 pkts/sec for zero BLE radio drop

        // Configure system audio policy for VoIP communication & loud speakerphone
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            setSpeakerOn(true)
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) ?: 0
            if (maxVol > 0) {
                audioManager?.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxVol * 0.95).toInt(), 0)
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to initialize AudioManager routing: ${e.message}")
        }

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
                try {
                    if (AcousticEchoCanceler.isAvailable()) {
                        AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true }
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        AutomaticGainControl.create(record.audioSessionId)?.apply { enabled = true }
                    }
                } catch (fxEx: Exception) {
                    Log.w(tag, "Failed to initialize hardware audio effects: ${fxEx.message}")
                }
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
            val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelOut)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    sampleRate,
                    channelOut,
                    encoding,
                    trackBufSize,
                    AudioTrack.MODE_STREAM
                )
            }
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track.setVolume(1.0f)
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
            val adpcmOut = ByteArray(samplesPerFrame / 2)
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

            while (isActive && isRunning.get()) {
                val track = audioTrack ?: break
                val frame = jitterBuffer.pop()

                if (frame != null && frame.data.isNotEmpty()) {
                    val sampleCount = frame.data.size * 2
                    val outBuf = if (sampleCount == samplesPerFrame) pcmOut else ShortArray(sampleCount)
                    AdpcmCodec.decode(frame.data, frame.data.size, outBuf, decodeState)
                    track.write(outBuf, 0, sampleCount)
                } else {
                    // Waiting for next frame or preload: brief yield without injecting artificial silence into AudioTrack
                    delay(5L)
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

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager?.clearCommunicationDevice()
                }
                audioManager?.mode = AudioManager.MODE_NORMAL
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = false
            } catch (_: Exception) {}

            try {
                if (wakeLock?.isHeld == true) {
                    wakeLock?.release()
                }
            } catch (_: Exception) {}
            wakeLock = null

            jitterBuffer.reset()
        }
    }

    override fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    override fun setSpeakerOn(speakerOn: Boolean) {
        try {
            audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audioManager?.availableCommunicationDevices ?: emptyList()
                val targetDevice = if (speakerOn) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                }
                if (targetDevice != null) {
                    audioManager?.setCommunicationDevice(targetDevice)
                } else {
                    audioManager?.clearCommunicationDevice()
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager?.isSpeakerphoneOn = speakerOn
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to toggle speakerphone: ${e.message}")
        }
    }
}

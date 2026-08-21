package com.meshwhisper.app.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class AudioRecorder(private val context: Context) {
    private val tag = "AudioRecorder"
    private var recorder: MediaRecorder? = null
    private var currentOutputFile: File? = null
    private var startTimeMs: Long = 0L

    fun startRecording(outputFile: File): Boolean {
        return try {
            currentOutputFile = outputFile
            outputFile.parentFile?.mkdirs()

            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(24000) // 24 kbps (clean voice quality, ~3KB/sec)
            mr.setAudioSamplingRate(16000)    // 16 kHz voice sampling
            mr.setAudioChannels(1)            // Mono
            mr.setOutputFile(outputFile.absolutePath)
            mr.setMaxDuration(30000)          // Hard cap 30 seconds

            mr.prepare()
            mr.start()
            recorder = mr
            startTimeMs = System.currentTimeMillis()
            Log.d(tag, "Started voice recording to: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(tag, "Failed to start audio recording", e)
            recorder?.release()
            recorder = null
            false
        }
    }

    fun stopRecording(): Long {
        val mr = recorder ?: return 0L
        val durationMs = System.currentTimeMillis() - startTimeMs
        return try {
            mr.stop()
            mr.release()
            recorder = null
            durationMs
        } catch (e: Exception) {
            Log.e(tag, "Failed to stop audio recording cleanly", e)
            recorder?.release()
            recorder = null
            0L
        }
    }

    fun cancelRecording() {
        try {
            recorder?.stop()
        } catch (e: Exception) {
            // Ignore
        }
        recorder?.release()
        recorder = null
        currentOutputFile?.delete()
        currentOutputFile = null
    }
}

class AudioPlayer {
    private val tag = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var currentPlayingUri: String? = null
    private var progressJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _activeUri = MutableStateFlow<String?>(null)
    val activeUri: StateFlow<String?> = _activeUri.asStateFlow()

    private val _progressMs = MutableStateFlow(0L)
    val progressMs: StateFlow<Long> = _progressMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    fun play(filePath: String) {
        if (currentPlayingUri == filePath && mediaPlayer != null) {
            if (mediaPlayer?.isPlaying == true) {
                pause()
            } else {
                mediaPlayer?.start()
                _isPlaying.value = true
                startProgressTracker()
            }
            return
        }

        stop()

        try {
            val mp = MediaPlayer()
            mp.setDataSource(filePath)
            mp.prepare()
            mp.setOnCompletionListener {
                _isPlaying.value = false
                _progressMs.value = 0L
                progressJob?.cancel()
            }
            mp.start()

            mediaPlayer = mp
            currentPlayingUri = filePath
            _activeUri.value = filePath
            _durationMs.value = mp.duration.toLong()
            _isPlaying.value = true
            startProgressTracker()
        } catch (e: Exception) {
            Log.e(tag, "Error playing audio file: $filePath", e)
            stop()
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying.value = false
        progressJob?.cancel()
    }

    fun stop() {
        progressJob?.cancel()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        currentPlayingUri = null
        _activeUri.value = null
        _isPlaying.value = false
        _progressMs.value = 0L
    }

    private fun startProgressTracker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive && mediaPlayer?.isPlaying == true) {
                _progressMs.value = mediaPlayer?.currentPosition?.toLong() ?: 0L
                delay(100L)
            }
        }
    }
}

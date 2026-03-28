package com.example.walkietalkieapp.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

class AudioRecorder {
    private val sampleRate = 16000
    private val minBufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else sampleRate

    @Volatile
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var recorder: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    fun start(onData: (ByteArray) -> Unit) {
        if (isRecording) {
            return
        }

        val activeRecorder = createRecorder() ?: return
        isRecording = true
        activeRecorder.startRecording()
        recordingThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            val buffer = ByteArray(bufferSize)
            while (isRecording) {
                val read = activeRecorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    onData(buffer.copyOf(read))
                }
            }
        }.apply {
            name = "AudioRecorderThread"
            start()
        }
    }

    fun stop() {
        isRecording = false
        val activeRecorder = recorder
        activeRecorder?.runCatching { stop() }
        recordingThread?.join(300)
        recordingThread = null

        activeRecorder?.runCatching {
            release()
        }
        recorder = null
        echoCanceler?.release()
        noiseSuppressor?.release()
        automaticGainControl?.release()
        echoCanceler = null
        noiseSuppressor = null
        automaticGainControl = null
    }

    fun release() {
        stop()
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(): AudioRecord? {
        if (minBufferSize <= 0) {
            return null
        }

        return AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        ).also {
            recorder = it
            setupAudioEffects(it.audioSessionId)
        }
    }

    private fun setupAudioEffects(audioSessionId: Int) {
        echoCanceler?.release()
        noiseSuppressor?.release()
        automaticGainControl?.release()

        echoCanceler = if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }

        noiseSuppressor = if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }

        automaticGainControl = if (AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
        } else {
            null
        }
    }
}

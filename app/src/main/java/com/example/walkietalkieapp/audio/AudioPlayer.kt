package com.example.walkietalkieapp.audio

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioTrack

class AudioPlayer {
    private val sampleRate = 16000
    private val minBufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else sampleRate
    private var track: AudioTrack? = null
    @Volatile
    private var muted = false

    fun start() {
        if (track != null) {
            return
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setLegacyStreamType(AudioManager.STREAM_MUSIC)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        track = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferSize)
            .build()
            .also { it.play() }
    }

    fun play(data: ByteArray) {
        if (muted) {
            return
        }
        if (track == null) {
            start()
        }
        track?.write(data, 0, data.size)
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (!value) {
            track?.flush()
        }
    }

    fun release() {
        track?.runCatching {
            stop()
            flush()
            release()
        }
        track = null
    }
}

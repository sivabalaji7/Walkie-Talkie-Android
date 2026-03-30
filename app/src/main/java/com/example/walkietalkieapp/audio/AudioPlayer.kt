package com.example.walkietalkieapp.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class AudioPlayer {
    companion object {
        private const val DEFAULT_SOURCE = "default"
    }

    private val sampleRate = 16000
    private val minBufferSize = AudioTrack.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val bufferSize = if (minBufferSize > 0) minBufferSize * 2 else sampleRate
    private val sourceQueues = ConcurrentHashMap<String, ConcurrentLinkedQueue<ByteArray>>()

    private var track: AudioTrack? = null
    private var mixerThread: Thread? = null

    @Volatile
    private var muted = false

    @Volatile
    private var running = false

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

        running = true
        mixerThread = Thread {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running) {
                if (muted) {
                    Thread.sleep(12)
                    continue
                }

                val chunks = mutableListOf<ByteArray>()
                sourceQueues.forEach { (_, queue) ->
                    val chunk = queue.poll()
                    if (chunk != null) {
                        chunks += chunk
                    }
                }

                if (chunks.isEmpty()) {
                    Thread.sleep(8)
                    continue
                }

                val mixed = mixChunks(chunks)
                if (mixed.isNotEmpty()) {
                    track?.write(mixed, 0, mixed.size)
                }
            }
        }.apply {
            name = "AudioMixerThread"
            start()
        }
    }

    fun play(data: ByteArray) {
        play(DEFAULT_SOURCE, data)
    }

    fun play(sourceId: String, data: ByteArray) {
        if (muted || data.isEmpty()) {
            return
        }
        if (track == null) {
            start()
        }
        val queue = sourceQueues.getOrPut(sourceId) { ConcurrentLinkedQueue() }
        queue.offer(data.copyOf())
    }

    fun setMuted(value: Boolean) {
        muted = value
        clearQueues()
        track?.flush()
    }

    fun release() {
        running = false
        mixerThread?.join(300)
        mixerThread = null
        clearQueues()
        track?.runCatching {
            stop()
            flush()
            release()
        }
        track = null
    }

    private fun clearQueues() {
        sourceQueues.values.forEach { it.clear() }
    }

    private fun mixChunks(chunks: List<ByteArray>): ByteArray {
        if (chunks.isEmpty()) {
            return ByteArray(0)
        }

        val maxBytes = chunks.maxOf { if (it.size % 2 == 0) it.size else it.size - 1 }
        if (maxBytes <= 0) {
            return ByteArray(0)
        }

        val output = ByteArray(maxBytes)
        var index = 0
        while (index + 1 < maxBytes) {
            var mixedSample = 0
            chunks.forEach { chunk ->
                if (index + 1 < chunk.size) {
                    val sample = ((chunk[index + 1].toInt() shl 8) or (chunk[index].toInt() and 0xFF)).toShort()
                    mixedSample += sample.toInt()
                }
            }

            val clipped = mixedSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            output[index] = (clipped.toInt() and 0xFF).toByte()
            output[index + 1] = ((clipped.toInt() shr 8) and 0xFF).toByte()
            index += 2
        }
        return output
    }
}

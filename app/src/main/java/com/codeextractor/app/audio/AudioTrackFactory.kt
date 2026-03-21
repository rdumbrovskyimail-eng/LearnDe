package com.codeextractor.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.delay

object AudioTrackFactory {
    private const val TAG = "AudioTrackFactory"
    private const val OUTPUT_SAMPLE_RATE = 24_000

    fun build(minBuf: Int): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(OUTPUT_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(minBuf * 4)
        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        .build()

    suspend fun writeNonBlocking(track: AudioTrack, chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val written = track.write(
                chunk, offset, chunk.size - offset,
                AudioTrack.WRITE_NON_BLOCKING
            )
            when {
                written > 0 -> offset += written
                written == 0 -> delay(2)
                else -> {
                    Log.w(TAG, "AudioTrack.write() returned $written — skipping chunk")
                    break
                }
            }
        }
    }
}
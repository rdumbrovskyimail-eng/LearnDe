package com.codeextractor.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.codeextractor.app.domain.AudioEngine
import com.codeextractor.app.domain.model.SessionConfig
import com.codeextractor.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация AudioEngine — запись микрофона + воспроизведение.
 * Перенесено из оригинального MainActivity: AudioRecord, AudioTrack, jitter buffer.
 */
@Singleton
class AndroidAudioEngine @Inject constructor(
    private val logger: AppLogger
) : AudioEngine {

    companion object {
        private const val PLAYBACK_QUEUE_CAPACITY = 256
        private const val JITTER_PRE_BUFFER_CHUNKS = 3
        private const val JITTER_TIMEOUT_MS = 150L
    }

    private val _micOutput = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val micOutput: Flow<ByteArray> = _micOutput.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    @Volatile
    override var isCapturing: Boolean = false
        private set

    @Volatile
    override var isPlaying: Boolean = false
        private set

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null

    private val playbackChannel = Channel<ByteArray>(PLAYBACK_QUEUE_CAPACITY)

    @Volatile
    private var isFirstBatch = true

    @Volatile
    private var awaitingDrain = false

    // ════════════════════════════════════════════════════════════
    //  CAPTURE (микрофон)
    // ════════════════════════════════════════════════════════════

    @Suppress("MissingPermission")
    override suspend fun startCapture() {
        if (isCapturing) return

        withContext(Dispatchers.IO) {
            val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                logger.e("AudioRecord.getMinBufferSize failed: $minBuf")
                return@withContext
            }

            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    logger.e("AudioRecord init failed")
                    recorder.release()
                    return@withContext
                }

                // AEC
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                            enabled = true
                            logger.d("AEC enabled (sessionId=${recorder.audioSessionId})")
                        }
                    } catch (e: Exception) {
                        logger.e("AEC init error: ${e.message}")
                    }
                }

                recorder.startRecording()
                audioRecord = recorder
                isCapturing = true
                logger.d("🎙 Recording started (buf=$minBuf, rate=$sampleRate)")

                // Цикл чтения — coroutineScope для структурированной отмены
                coroutineScope {
                    launch {
                        val buffer = ShortArray(minBuf)
                        val byteBuffer = ByteBuffer.allocate(minBuf * 2).order(ByteOrder.LITTLE_ENDIAN)
                        val rawBytes = byteBuffer.array()

                        while (isActive && isCapturing) {
                            val read = recorder.read(buffer, 0, buffer.size)
                            if (read > 0) {
                                byteBuffer.clear()
                                byteBuffer.asShortBuffer().put(buffer, 0, read)
                                val chunk = rawBytes.copyOf(read * 2)
                                _micOutput.tryEmit(chunk)
                            }
                        }
                    }
                }
            } catch (e: SecurityException) {
                logger.e("SECURITY: ${e.message}")
            } catch (e: Exception) {
                logger.e("AUDIO CAPTURE ERROR: ${e.message}", e)
            }
        }
    }

    override suspend fun stopCapture() {
        isCapturing = false
        withContext(Dispatchers.IO) {
            echoCanceler?.let {
                runCatching { it.enabled = false; it.release() }
            }
            echoCanceler = null
            audioRecord?.let {
                runCatching { it.stop(); it.release() }
            }
            audioRecord = null
            logger.d("🎙 Recording stopped")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  PLAYBACK (динамик)
    // ════════════════════════════════════════════════════════════

    override suspend fun initPlayback() {
        withContext(Dispatchers.IO) {
            val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                logger.e("⚠ Device does not support ${sampleRate}Hz output!")
                return@withContext
            }

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            audioTrack = track
            track.play()
            isPlaying = true
            logger.d("Speaker ready (rate=$sampleRate, minBuf=$minBuf)")

            // Playback loop
            launch {
                for (chunk in playbackChannel) {
                    if (!isActive) break

                    if (isFirstBatch) {
                        val preBuffer = mutableListOf(chunk)
                        repeat(JITTER_PRE_BUFFER_CHUNKS - 1) {
                            try {
                                val next = withTimeoutOrNull(JITTER_TIMEOUT_MS) {
                                    playbackChannel.receive()
                                }
                                if (next != null) preBuffer.add(next)
                            } catch (_: ClosedReceiveChannelException) { return@repeat }
                            catch (_: Exception) { return@repeat }
                        }
                        for (buffered in preBuffer) {
                            _playbackSync.tryEmit(buffered)
                            track.write(buffered, 0, buffered.size)
                        }
                        isFirstBatch = false
                        logger.d("Jitter pre-buffer: ${preBuffer.size} chunks")
                    } else {
                        _playbackSync.tryEmit(chunk)
                        track.write(chunk, 0, chunk.size)
                    }

                    if (awaitingDrain && playbackChannel.isEmpty) {
                        logger.d("⏹ Playback drained")
                        awaitingDrain = false
                        isFirstBatch = true
                    }
                }
            }
        }
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        awaitingDrain = false
        val result = playbackChannel.trySend(pcmData)
        if (result.isFailure) {
            logger.w("Playback queue full — dropping oldest")
            playbackChannel.tryReceive()
            playbackChannel.trySend(pcmData)
        }
    }

    override suspend fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        awaitingDrain = false
        audioTrack?.apply {
            pause()
            flush()
            play()
        }
        logger.d("⚡ Playback flushed (barge-in)")
    }

    override suspend fun onTurnComplete() {
        awaitingDrain = true
    }

    override suspend fun releaseAll() {
        stopCapture()
        playbackChannel.close()
        audioTrack?.let {
            runCatching { it.stop(); it.release() }
        }
        audioTrack = null
        isPlaying = false
        logger.d("Audio engine released")
    }
}
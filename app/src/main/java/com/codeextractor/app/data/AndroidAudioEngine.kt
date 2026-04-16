// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/data/AndroidAudioEngine.kt
// Изменения: + updateJitterConfig() implementation
// ═══════════════════════════════════════════════════════════
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

@Singleton
class AndroidAudioEngine @Inject constructor(
    private val logger: AppLogger
) : AudioEngine {

    @Volatile private var playbackQueueCapacity = 256
    @Volatile private var jitterPreBufferChunks = 3
    @Volatile private var jitterTimeoutMs = 150L

    // ═══ NEW: программный gain ═══
    @Volatile private var playbackGain: Float = 0.9f   // 0..1 → setVolume
    @Volatile private var micGain: Float = 1.0f        // 0.5..2.0 → умножение сэмплов
    @Volatile private var forceSpeakerOutput: Boolean = true

    private val _micOutput = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val micOutput: Flow<ByteArray> = _micOutput.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    @Volatile override var isCapturing: Boolean = false; private set
    @Volatile override var isPlaying: Boolean = false; private set

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null
    private val playbackChannel = Channel<ByteArray>(256)
    @Volatile private var isFirstBatch = true
    @Volatile private var awaitingDrain = false

    override fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int) {
        jitterPreBufferChunks = preBufferChunks.coerceIn(1, 10)
        jitterTimeoutMs = timeoutMs.coerceIn(50L, 500L)
        playbackQueueCapacity = queueCapacity.coerceIn(64, 512)
        logger.d("Jitter config: preBuffer=$jitterPreBufferChunks, timeout=${jitterTimeoutMs}ms")
    }

    override fun setPlaybackVolume(gain: Float) {
        playbackGain = gain.coerceIn(0f, 1f)
        runCatching { audioTrack?.setVolume(playbackGain) }
    }

    override fun setMicGain(gain: Float) {
        micGain = gain.coerceIn(0.5f, 2.0f)
    }

    override fun setSpeakerRouting(forceSpeaker: Boolean) {
        forceSpeakerOutput = forceSpeaker
    }

    @Suppress("MissingPermission")
    override suspend fun startCapture() {
        if (isCapturing) return
        withContext(Dispatchers.IO) {
            val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
            val minBuf = AudioRecord.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
                logger.e("AudioRecord.getMinBufferSize failed: $minBuf"); return@withContext
            }
            try {
                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    logger.e("AudioRecord init failed"); recorder.release(); return@withContext
                }
                if (AcousticEchoCanceler.isAvailable()) {
                    try {
                        echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                            enabled = true
                        }
                    } catch (e: Exception) { logger.e("AEC init error: ${e.message}") }
                }
                recorder.startRecording()
                audioRecord = recorder
                isCapturing = true
                logger.d("Recording started (rate=$sampleRate)")
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
                                _micOutput.tryEmit(rawBytes.copyOf(read * 2))
                            }
                        }
                    }
                }
            } catch (e: SecurityException) { logger.e("SECURITY: ${e.message}")
            } catch (e: Exception) { logger.e("CAPTURE ERROR: ${e.message}", e) }
        }
    }

    override suspend fun stopCapture() {
        isCapturing = false
        withContext(Dispatchers.IO) {
            echoCanceler?.let { runCatching { it.enabled = false; it.release() } }
            echoCanceler = null
            audioRecord?.let { runCatching { it.stop(); it.release() } }
            audioRecord = null
        }
    }

    override suspend fun initPlayback() {
        withContext(Dispatchers.IO) {
            val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                logger.e("Device does not support ${sampleRate}Hz!"); return@withContext
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2).build()
            audioTrack = track
            runCatching { track.setVolume(playbackGain) }
            track.play()
            isPlaying = true
            logger.d("Speaker ready (rate=$sampleRate)")
            launch {
                for (chunk in playbackChannel) {
                    if (!isActive) break
                    if (isFirstBatch) {
                        val preBuffer = mutableListOf(chunk)
                        repeat(jitterPreBufferChunks - 1) {
                            try {
                                val next = withTimeoutOrNull(jitterTimeoutMs) { playbackChannel.receive() }
                                if (next != null) preBuffer.add(next)
                            } catch (_: ClosedReceiveChannelException) { return@repeat }
                            catch (_: Exception) { return@repeat }
                        }
                        for (buffered in preBuffer) {
                            _playbackSync.tryEmit(buffered)
                            track.write(buffered, 0, buffered.size)
                        }
                        isFirstBatch = false
                    } else {
                        _playbackSync.tryEmit(chunk)
                        track.write(chunk, 0, chunk.size)
                    }
                    if (awaitingDrain && playbackChannel.isEmpty) {
                        awaitingDrain = false; isFirstBatch = true
                    }
                }
            }
        }
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        awaitingDrain = false
        val result = playbackChannel.trySend(pcmData)
        if (result.isFailure) {
            playbackChannel.tryReceive()
            playbackChannel.trySend(pcmData)
        }
    }

    override suspend fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { }
        isFirstBatch = true; awaitingDrain = false
        audioTrack?.apply { pause(); flush(); play() }
    }

    override suspend fun onTurnComplete() { awaitingDrain = true }

    override suspend fun releaseAll() {
        stopCapture()
        playbackChannel.close()
        audioTrack?.let { runCatching { it.stop(); it.release() } }
        audioTrack = null; isPlaying = false
    }
}
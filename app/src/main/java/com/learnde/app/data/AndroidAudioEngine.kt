package com.learnde.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
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
 * Реальная реализация AudioEngine.
 *
 * Архитектура:
 *  - engineScope: SupervisorJob на IO — живёт от initPlayback до releaseAll.
 *  - captureJob: одиночная корутина чтения AudioRecord.
 *  - playbackJob: одиночная корутина чтения playbackChannel → AudioTrack.
 *  - startCapture/initPlayback возвращаются мгновенно (не блокируют).
 *
 * Фиксы vs прошлая версия:
 *   [1] startCapture не висит в coroutineScope{} — запуск через engineScope.launch.
 *   [2] initPlayback не теряет launch внутри withContext — использует engineScope.
 *   [3] playbackChannel пересоздаётся при releaseAll → повторный initPlayback работает.
 *   [4] playbackChannel capacity следует playbackQueueCapacity (не хардкод 256).
 *   [5] Отдельный SupervisorJob — падение одной корутины не валит другие.
 */
@Singleton
class AndroidAudioEngine @Inject constructor(
    private val logger: AppLogger
) : AudioEngine {

    // ═══ CONFIG ═══
    @Volatile private var playbackQueueCapacity = 256
    @Volatile private var jitterPreBufferChunks = 3
    @Volatile private var jitterTimeoutMs = 150L

    @Volatile private var playbackGain: Float = 0.9f
    @Volatile private var micGain: Float = 1.0f
    @Volatile private var forceSpeakerOutput: Boolean = true

    // ═══ FLOWS ═══
    private val _micOutput = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val micOutput: Flow<ByteArray> = _micOutput.asSharedFlow()

    private val _playbackSync = MutableSharedFlow<ByteArray>(
        replay = 0, extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val playbackSync: Flow<ByteArray> = _playbackSync.asSharedFlow()

    @Volatile override var isCapturing: Boolean = false; private set
    @Volatile override var isPlaying: Boolean = false; private set

    // ═══ STATE ═══
    private var engineScope: CoroutineScope = newEngineScope()
    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var playbackChannel: Channel<ByteArray> =
        Channel(playbackQueueCapacity, BufferOverflow.DROP_OLDEST)
    @Volatile private var isFirstBatch = true
    @Volatile private var awaitingDrain = false

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ════════════════════════════════════════════════════════════════════
    //  CONFIG SETTERS
    // ════════════════════════════════════════════════════════════════════

    override fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int) {
        jitterPreBufferChunks = preBufferChunks.coerceIn(1, 10)
        jitterTimeoutMs = timeoutMs.coerceIn(50L, 500L)
        playbackQueueCapacity = queueCapacity.coerceIn(64, 512)
        logger.d("Jitter config: preBuffer=$jitterPreBufferChunks, timeout=${jitterTimeoutMs}ms, queue=$playbackQueueCapacity")
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

    // ════════════════════════════════════════════════════════════════════
    //  CAPTURE
    // ════════════════════════════════════════════════════════════════════

    @Suppress("MissingPermission")
    override suspend fun startCapture() {
        if (isCapturing) {
            logger.d("startCapture skipped — already capturing")
            return
        }
        if (!engineScope.isActive) engineScope = newEngineScope()

        val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            logger.e("AudioRecord.getMinBufferSize failed: $minBuf")
            return
        }

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: SecurityException) {
            logger.e("SECURITY on AudioRecord ctor: ${e.message}")
            return
        } catch (e: Exception) {
            logger.e("AudioRecord ctor failed: ${e.message}", e)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            logger.e("AudioRecord init failed")
            runCatching { recorder.release() }
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                    enabled = true
                }
            }.onFailure { logger.e("AEC init error: ${it.message}") }
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            logger.e("startRecording failed: ${e.message}", e)
            runCatching { recorder.release() }
            return
        }

        audioRecord = recorder
        isCapturing = true
        logger.d("Recording started (rate=$sampleRate, minBuf=$minBuf)")

        captureJob = engineScope.launch {
            val buffer = ShortArray(minBuf)
            val byteBuffer = ByteBuffer.allocate(minBuf * 2).order(ByteOrder.LITTLE_ENDIAN)
            val rawBytes = byteBuffer.array()

            try {
                while (isActive && isCapturing) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val g = micGain
                        if (g != 1.0f) {
                            for (i in 0 until read) {
                                val amplified = (buffer[i] * g).toInt()
                                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                buffer[i] = amplified.toShort()
                            }
                        }
                        byteBuffer.clear()
                        byteBuffer.asShortBuffer().put(buffer, 0, read)
                        _micOutput.tryEmit(rawBytes.copyOf(read * 2))
                    } else if (read < 0) {
                        logger.e("AudioRecord.read err=$read — stop loop")
                        break
                    }
                }
            } catch (e: Exception) {
                logger.e("CAPTURE LOOP ERROR: ${e.message}", e)
            } finally {
                logger.d("Capture loop exited")
            }
        }
    }

    override suspend fun stopCapture() {
        if (!isCapturing && audioRecord == null) return
        isCapturing = false
        runCatching { captureJob?.cancelAndJoin() }
        captureJob = null

        withContext(Dispatchers.IO) {
            echoCanceler?.let { runCatching { it.enabled = false; it.release() } }
            echoCanceler = null
            audioRecord?.let { runCatching { it.stop(); it.release() } }
            audioRecord = null
        }
        logger.d("Capture stopped")
    }

    // ════════════════════════════════════════════════════════════════════
    //  PLAYBACK
    // ════════════════════════════════════════════════════════════════════

    override suspend fun initPlayback() {
        if (isPlaying) {
            logger.d("initPlayback skipped — already playing")
            return
        }
        if (!engineScope.isActive) engineScope = newEngineScope()
        if (playbackChannel.isClosedForSend) {
            playbackChannel = Channel(playbackQueueCapacity, BufferOverflow.DROP_OLDEST)
        }

        val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
            logger.e("Device does not support ${sampleRate}Hz!")
            return
        }

        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2).build()
        } catch (e: Exception) {
            logger.e("AudioTrack build failed: ${e.message}", e)
            return
        }

        audioTrack = track
        runCatching { track.setVolume(playbackGain) }
        track.play()
        isPlaying = true
        logger.d("Speaker ready (rate=$sampleRate)")

        playbackJob = engineScope.launch {
            try {
                for (chunk in playbackChannel) {
                    if (!isActive) break
                    if (isFirstBatch) {
                        val preBuffer = mutableListOf(chunk)
                        repeat(jitterPreBufferChunks - 1) {
                            try {
                                val next = withTimeoutOrNull(jitterTimeoutMs) {
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
                    } else {
                        _playbackSync.tryEmit(chunk)
                        track.write(chunk, 0, chunk.size)
                    }
                    if (awaitingDrain && playbackChannel.isEmpty) {
                        awaitingDrain = false
                        isFirstBatch = true
                    }
                }
            } catch (e: Exception) {
                logger.e("PLAYBACK LOOP ERROR: ${e.message}", e)
            } finally {
                logger.d("Playback loop exited")
            }
        }
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return
        awaitingDrain = false
        val result = playbackChannel.trySend(pcmData)
        if (result.isFailure) {
            playbackChannel.tryReceive()
            playbackChannel.trySend(pcmData)
        }
    }

    override suspend fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        awaitingDrain = false
        audioTrack?.apply {
            runCatching { pause(); flush(); play() }
        }
    }

    override suspend fun onTurnComplete() {
        awaitingDrain = true
    }

    override suspend fun releaseAll() {
        stopCapture()
        isPlaying = false
        runCatching { playbackChannel.close() }
        runCatching { playbackJob?.cancelAndJoin() }
        playbackJob = null
        audioTrack?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
        audioTrack = null
        runCatching { engineScope.coroutineContext[Job]?.cancelAndJoin() }
        logger.d("Engine released")
    }
}
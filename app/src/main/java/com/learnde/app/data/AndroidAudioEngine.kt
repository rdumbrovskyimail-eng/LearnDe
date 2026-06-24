// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/data/AndroidAudioEngine.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.util.AppLogger
import javax.inject.Inject
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
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidAudioEngine(
    private val logger: AppLogger
) : AudioEngine {

    // ═══ CONFIG ═══
    @Volatile private var playbackQueueCapacity = 256
    @Volatile private var jitterPreBufferChunks = 3
    @Volatile private var jitterTimeoutMs = 150L

    @Volatile private var playbackGain: Float = 1.0f
    @Volatile private var softwareGain: Float = 3.0f // Программный буст громкости (по умолчанию 300%)
    @Volatile private var micGain: Float = 1.4f
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

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var audioTrack: AudioTrack? = null

    private var playbackChannel: Channel<ByteArray> = Channel(Channel.UNLIMITED)

    @Volatile private var isFirstBatch = true
    @Volatile private var awaitingDrain = false
    @Volatile private var playbackLoopGen = 0
    @Volatile private var estimatedPlaybackEndMs = 0L

    @Volatile private var audibleUntilMs: Long = 0L
    override val playbackAudibleUntilMs: Long get() = audibleUntilMs

    private fun newEngineScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ════════════════════════════════════════════════════════════════════
    //  CONFIG SETTERS
    // ════════════════════════════════════════════════════════════════════

    override fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int) {
        jitterPreBufferChunks = preBufferChunks.coerceIn(1, 10)
        jitterTimeoutMs = timeoutMs.coerceIn(50L, 500L)
        playbackQueueCapacity = queueCapacity.coerceIn(64, 512)
    }

    override fun setPlaybackVolume(gain: Float) {
        // Если ползунок больше 1.0 (100%), используем математическое усиление PCM
        if (gain <= 1.0f) {
            playbackGain = gain
            softwareGain = 1.0f
        } else {
            playbackGain = 1.0f
            softwareGain = gain
        }
        runCatching { audioTrack?.setVolume(playbackGain) }
    }

    override fun setMicGain(gain: Float) {
        micGain = gain.coerceIn(0.5f, 1.5f)
    }

    override fun setSpeakerRouting(forceSpeaker: Boolean) {
        forceSpeakerOutput = forceSpeaker
    }

    // ════════════════════════════════════════════════════════════════════
    //  CAPTURE
    // ════════════════════════════════════════════════════════════════════

    @Suppress("MissingPermission")
    override suspend fun startCapture() {
        if (isCapturing) return
        if (!engineScope.isActive) engineScope = newEngineScope()

        val sampleRate = SessionConfig.INPUT_SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) return

        val recorder = try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf * 2)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
                )
            }
        } catch (e: Exception) {
            logger.e("AudioRecord ctor failed: ${e.message}", e)
            return
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return
        }

        if (AcousticEchoCanceler.isAvailable()) {
            runCatching {
                echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }

        if (NoiseSuppressor.isAvailable()) {
            runCatching {
                noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
            }
        }

        try {
            recorder.startRecording()
        } catch (e: Exception) {
            runCatching { recorder.release() }
            return
        }

        audioRecord = recorder
        isCapturing = true

        captureJob = engineScope.launch {
            val buffer = ShortArray(minBuf)
            val byteBuffer = ByteBuffer.allocate(minBuf * 2).order(ByteOrder.LITTLE_ENDIAN)
            val rawBytes = byteBuffer.array()

            var rollingPeak = 4000
            val targetPeak = 24000
            val agcAttack = 0.4f
            val agcRelease = 0.015f
            val agcMaxBoost = 2.5f
            val agcMinBoost = 0.6f
            val noiseFloor = 900

            try {
                while (isActive && isCapturing) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> {
                            var localPeak = 0
                            for (i in 0 until read) {
                                val v = kotlin.math.abs(buffer[i].toInt())
                                if (v > localPeak) localPeak = v
                            }

                            rollingPeak = if (localPeak > rollingPeak) {
                                (rollingPeak + (localPeak - rollingPeak) * agcAttack).toInt()
                            } else {
                                (rollingPeak - (rollingPeak - localPeak) * agcRelease).toInt()
                            }
                            if (rollingPeak < noiseFloor) rollingPeak = noiseFloor

                            val agcGain = (targetPeak.toFloat() / rollingPeak.toFloat())
                                .coerceIn(agcMinBoost, agcMaxBoost)
                            val finalGain = agcGain * micGain

                            for (i in 0 until read) {
                                val amplified = (buffer[i] * finalGain).toInt()
                                buffer[i] = when {
                                    amplified > Short.MAX_VALUE -> Short.MAX_VALUE
                                    amplified < Short.MIN_VALUE -> Short.MIN_VALUE
                                    else -> amplified.toShort()
                                }
                            }

                            byteBuffer.clear()
                            byteBuffer.asShortBuffer().put(buffer, 0, read)
                            _micOutput.tryEmit(rawBytes.copyOf(read * 2))
                        }
                        read == 0 -> yield()
                        else -> break
                    }
                }
            } catch (e: Exception) {
                logger.e("CAPTURE LOOP ERROR: ${e.message}", e)
            }
        }
    }

    override suspend fun stopCapture() {
        if (!isCapturing && audioRecord == null) return
        isCapturing = false

        val rec = audioRecord
        val aec = echoCanceler

        runCatching { rec?.stop() }
        runCatching { withTimeoutOrNull(800L) { captureJob?.cancelAndJoin() } }
        captureJob = null

        val ns = noiseSuppressor
        withContext(Dispatchers.IO) {
            runCatching { aec?.enabled = false; aec?.release() }
            echoCanceler = null
            runCatching { ns?.enabled = false; ns?.release() }
            noiseSuppressor = null
            runCatching { rec?.release() }
            audioRecord = null
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  PLAYBACK
    // ════════════════════════════════════════════════════════════════════

    override suspend fun initPlayback() {
        if (isPlaying) return
        if (!engineScope.isActive) engineScope = newEngineScope()
        if (playbackChannel.isClosedForSend) playbackChannel = Channel(Channel.UNLIMITED)

        val sampleRate = SessionConfig.OUTPUT_SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) return

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
        
        val myGen = ++playbackLoopGen
        playbackJob = engineScope.launch {
            try {
                for (chunk in playbackChannel) {
                    if (!isActive || myGen != playbackLoopGen) break
                    if (isFirstBatch) {
                        val preBuffer = mutableListOf(chunk)
                        repeat(jitterPreBufferChunks - 1) {
                            try {
                                val next = withTimeoutOrNull(jitterTimeoutMs) { playbackChannel.receive() }
                                if (next != null) preBuffer.add(next)
                            } catch (_: Exception) { return@repeat }
                        }
                        for (buffered in preBuffer) {
                            _playbackSync.tryEmit(buffered)
                            runCatching { track.write(buffered, 0, buffered.size) }
                        }
                        isFirstBatch = false
                    } else {
                        _playbackSync.tryEmit(chunk)
                        runCatching { track.write(chunk, 0, chunk.size) }
                    }
                    if (awaitingDrain && playbackChannel.isEmpty) {
                        awaitingDrain = false
                        isFirstBatch = true
                    }
                }
            } catch (e: Exception) {
                logger.e("PLAYBACK LOOP ERROR: ${e.message}", e)
            }
        }
    }

    override suspend fun enqueuePlayback(pcmData: ByteArray) {
        if (pcmData.isEmpty()) return

        // Применяем программное усиление звука (Software Gain)
        if (softwareGain > 1.01f) {
            amplifyPcm(pcmData, softwareGain)
        }

        val durationMs = pcmData.size / 48L
        val now = System.currentTimeMillis()
        val preBufferLeadMs = if (isFirstBatch) jitterPreBufferChunks * jitterTimeoutMs else 0L
        audibleUntilMs = maxOf(audibleUntilMs, now + preBufferLeadMs) + durationMs

        val durationLegacyMs = (pcmData.size / 2) * 1000L / SessionConfig.OUTPUT_SAMPLE_RATE
        estimatedPlaybackEndMs = maxOf(estimatedPlaybackEndMs, now) + durationLegacyMs

        playbackChannel.trySend(pcmData)
        awaitingDrain = false
    }

    /** Усиливает PCM 16-bit LE данные с защитой от перегруза (clipping) */
    private fun amplifyPcm(data: ByteArray, gain: Float) {
        for (i in 0 until data.size - 1 step 2) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val s = if (sample >= 0x8000) sample - 0x10000 else sample
            
            val amplified = (s * gain).toInt()
            val clipped = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            
            data[i] = clipped.toByte()
            data[i + 1] = (clipped shr 8).toByte()
        }
    }

    override suspend fun flushPlayback() {
        while (playbackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        awaitingDrain = false
        estimatedPlaybackEndMs = 0L
        audibleUntilMs = 0L
        audioTrack?.apply { runCatching { pause(); flush(); play() } }
    }

    override suspend fun onTurnComplete() {
        awaitingDrain = true
        runCatching {
            val padMs = 120
            val silence = ByteArray((SessionConfig.OUTPUT_SAMPLE_RATE * 2 * padMs) / 1000)
            enqueuePlayback(silence)
        }
    }

    override suspend fun releaseAll() {
        playbackLoopGen++
        stopCapture()
        isPlaying = false
        estimatedPlaybackEndMs = 0L
        audibleUntilMs = 0L
        runCatching { playbackChannel.close() }
        runCatching { withTimeoutOrNull(800L) { playbackJob?.cancelAndJoin() } }
        playbackJob = null
        audioTrack?.let { runCatching { it.pause(); it.flush(); it.stop(); it.release() } }
        audioTrack = null
        runCatching { withTimeoutOrNull(800L) { engineScope.coroutineContext[Job]?.cancelAndJoin() } }
    }
}
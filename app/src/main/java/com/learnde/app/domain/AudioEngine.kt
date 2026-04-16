package com.learnde.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Абстракция аудио-подсистемы — версия 2026.
 *
 * Два канала:
 *  CAPTURE  — 16kHz, mono, 16-bit LE (AudioRecord + AEC)
 *  PLAYBACK — 24kHz, mono, 16-bit LE (AudioTrack + jitter buffer)
 *
 * Конфигурируемые параметры:
 *  - jitter pre-buffer size
 *  - playback queue capacity
 *  - AEC toggle
 */
interface AudioEngine {

    val micOutput: Flow<ByteArray>
    val isCapturing: Boolean
    val isPlaying: Boolean
    val playbackSync: Flow<ByteArray>

    suspend fun startCapture()
    suspend fun stopCapture()
    suspend fun enqueuePlayback(pcmData: ByteArray)
    suspend fun flushPlayback()
    suspend fun onTurnComplete()
    suspend fun initPlayback()
    suspend fun releaseAll()

    /**
     * Обновить параметры jitter buffer.
     * Вызывать при изменении настроек (не требует перезапуска).
     */
    fun updateJitterConfig(preBufferChunks: Int, timeoutMs: Long, queueCapacity: Int)

    /** Программная громкость воспроизведения (0.0 .. 1.0). */
    fun setPlaybackVolume(gain: Float)

    /** Усиление микрофона (0.5 .. 2.0). */
    fun setMicGain(gain: Float)

    /** true — громкий динамик (SPEAKER), false — разрешить earpiece/BT. */
    fun setSpeakerRouting(forceSpeaker: Boolean)
}

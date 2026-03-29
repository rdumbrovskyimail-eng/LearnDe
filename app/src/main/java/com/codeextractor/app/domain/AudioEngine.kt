package com.codeextractor.app.domain

import kotlinx.coroutines.flow.Flow

/**
 * Абстракция аудио-подсистемы.
 *
 * Два независимых канала:
 *  1. CAPTURE (микрофон → PCM ByteArray)
 *     - AudioRecord + AcousticEchoCanceler
 *     - 16kHz, mono, 16-bit LE
 *     - Эмитит чанки через micOutput
 *
 *  2. PLAYBACK (PCM ByteArray → динамик)
 *     - AudioTrack (VOICE_COMMUNICATION)
 *     - 24kHz, mono, 16-bit LE
 *     - Jitter pre-buffer перед первым чанком
 *     - flush для barge-in
 *
 * Реализация: data/AndroidAudioEngine.kt
 * Будущее: Этап 5-6 — Oboe/C++ zero-copy, advanced jitter buffer
 */
interface AudioEngine {

    /** Поток PCM-чанков с микрофона. Активен пока идёт запись. */
    val micOutput: Flow<ByteArray>

    /** true если микрофон активно записывает */
    val isCapturing: Boolean

    /** true если идёт воспроизведение аудио */
    val isPlaying: Boolean

    /**
     * Начать запись с микрофона.
     * Требует RECORD_AUDIO permission.
     * Чанки появляются в micOutput.
     */
    suspend fun startCapture()

    /** Остановить запись, освободить AudioRecord */
    suspend fun stopCapture()

    /**
     * Поставить PCM-чанк в очередь воспроизведения.
     * @param pcmData raw PCM 16-bit LE, mono, 24kHz
     */
    suspend fun enqueuePlayback(pcmData: ByteArray)

    /**
     * Сброс очереди воспроизведения (barge-in).
     * Очищает буфер, делает flush AudioTrack.
     */
    suspend fun flushPlayback()

    /**
     * Сигнал: модель закончила генерировать аудио.
     * AudioEngine доигрывает буфер и сбрасывает jitter state.
     */
    suspend fun onTurnComplete()

    /** Инициализация AudioTrack (вызвать один раз при старте) */
    suspend fun initPlayback()

    /** Полное освобождение всех аудио-ресурсов */
    suspend fun releaseAll()
}
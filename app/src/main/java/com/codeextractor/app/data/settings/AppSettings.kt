package com.codeextractor.app.data.settings

import kotlinx.serialization.Serializable

/**
 * Полная конфигурация приложения — все параметры Gemini Live API 2026.
 *
 * Группы настроек:
 *  1. AUTH — API ключи, ротация
 *  2. MODEL — модель, generation config
 *  3. VOICE — голос, speech config
 *  4. AUDIO — capture/playback, AEC, jitter
 *  5. SESSION — compression, resumption, timeouts
 *  6. VAD — Voice Activity Detection
 *  7. TRANSCRIPTION — input/output transcription
 *  8. TOOLS — function calling, Google Search
 *  9. THINKING — thinkingLevel
 * 10. SYSTEM — system instruction, language
 * 11. DEBUG — logging, diagnostics
 */
@Serializable
data class AppSettings(

    // ═══════════════════════════════════════════════════════
    //  1. AUTH
    // ═══════════════════════════════════════════════════════

    /** Основной API ключ (зашифрован через CryptoManager) */
    val apiKey: String = "",

    /** Резервный API ключ для ротации при 429 */
    val apiKeyBackup: String = "",

    /** Автоматическая ротация ключей при rate limit */
    val autoRotateKeys: Boolean = false,

    // ═══════════════════════════════════════════════════════
    //  2. MODEL
    // ═══════════════════════════════════════════════════════

    /** Модель Gemini Live. Рекомендуется gemini-3.1-flash-live-preview */
    val model: String = "models/gemini-3.1-flash-live-preview",

    /** Температура генерации (0.0 — 2.0, default 1.0) */
    val temperature: Float = 1.0f,

    /** Top-P (nucleus sampling, 0.0 — 1.0) */
    val topP: Float = 0.95f,

    /** Top-K (0 = disabled) */
    val topK: Int = 40,

    /** Максимум output-токенов (Gemini 3.1 Live: до 65536) */
    val maxOutputTokens: Int = 8192,

    /** Presence penalty (-2.0 — 2.0) */
    val presencePenalty: Float = 0.0f,

    /** Frequency penalty (-2.0 — 2.0) */
    val frequencyPenalty: Float = 0.0f,

    /** Модальность ответа: AUDIO или TEXT */
    val responseModality: String = "AUDIO",

    // ═══════════════════════════════════════════════════════
    //  3. VOICE
    // ═══════════════════════════════════════════════════════

    /** Голос TTS: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr */
    val voiceId: String = "Aoede",

    /** Язык ответа (BCP-47). Пустая строка = автоопределение */
    val languageCode: String = "",

    // ═══════════════════════════════════════════════════════
    //  4. AUDIO
    // ═══════════════════════════════════════════════════════

    /** Acoustic Echo Canceler */
    val useAec: Boolean = true,

    /** Количество чанков в jitter pre-buffer (1-10) */
    val jitterPreBufferChunks: Int = 3,

    /** Таймаут jitter pre-buffer в мс */
    val jitterTimeoutMs: Long = 150L,

    /** Размер очереди playback (64-512) */
    val playbackQueueCapacity: Int = 256,

    /** Отправлять audioStreamEnd при паузе микрофона */
    val sendAudioStreamEnd: Boolean = true,

    // ═══════════════════════════════════════════════════════
    //  5. SESSION
    // ═══════════════════════════════════════════════════════

    /** Включить session resumption */
    val enableSessionResumption: Boolean = true,

    /** Transparent session resumption (автоматический reconnect) */
    val transparentResumption: Boolean = true,

    /** Включить context window compression */
    val enableContextCompression: Boolean = true,

    /** Порог токенов для triggering compression (0 = default сервера) */
    val compressionTriggerTokens: Int = 0,

    /** Максимум попыток reconnect (1-20) */
    val maxReconnectAttempts: Int = 5,

    /** Базовая задержка reconnect в мс */
    val reconnectBaseDelayMs: Long = 2000L,

    /** Максимальная задержка reconnect в мс (cap для exponential backoff) */
    val reconnectMaxDelayMs: Long = 30000L,

    // ═══════════════════════════════════════════════════════
    //  6. VAD
    // ═══════════════════════════════════════════════════════

    /** Серверный VAD (Voice Activity Detection) */
    val enableServerVad: Boolean = true,

    /** Порог начала речи в секундах (0.0 — 1.0) */
    val vadStartOfSpeechSensitivity: Float = 0.5f,

    /** Порог окончания речи в секундах (0.0 — 5.0) */
    val vadEndOfSpeechSensitivity: Float = 0.5f,

    /** Тишина перед определением конца хода (в мс, 0 = default) */
    val vadSilenceTimeoutMs: Int = 0,

    // ═══════════════════════════════════════════════════════
    //  7. TRANSCRIPTION
    // ═══════════════════════════════════════════════════════

    /** Транскрипция входящего аудио (речь пользователя) */
    val inputTranscription: Boolean = true,

    /** Транскрипция ответа модели */
    val outputTranscription: Boolean = true,

    // ═══════════════════════════════════════════════════════
    //  8. TOOLS
    // ═══════════════════════════════════════════════════════

    /** Включить Google Search grounding */
    val enableGoogleSearch: Boolean = false,

    // ═══════════════════════════════════════════════════════
    //  9. THINKING
    // ═══════════════════════════════════════════════════════

    /** Профиль латентности → thinkingLevel: minimal, low, medium, high */
    val latencyProfile: String = "UltraLow",

    // ═══════════════════════════════════════════════════════
    // 10. SYSTEM
    // ═══════════════════════════════════════════════════════

    /** Системная инструкция */
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ═══════════════════════════════════════════════════════
    // 11. DEBUG
    // ═══════════════════════════════════════════════════════

    /** Показывать debug log */
    val showDebugLog: Boolean = false,

    /** Логировать сырые WS-фреймы (осторожно — много данных!) */
    val logRawWebSocketFrames: Boolean = false,

    /** Показывать usage metadata (подсчёт токенов) */
    val showUsageMetadata: Boolean = false
) {
    companion object {
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты русскоязычный голосовой ассистент. " +
            "Всегда отвечай только на русском языке. " +
            "Слушай и понимай русскую речь. " +
            "Отвечай кратко и по делу, не более 2-3 предложений, " +
            "если пользователь не просит подробного ответа."
    }
}

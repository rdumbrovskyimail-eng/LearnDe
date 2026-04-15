package com.codeextractor.app.domain.model

/**
 * Конфигурация сессии Gemini Live API — полная спецификация 2026.
 *
 * Формируется из AppSettings → передаётся в LiveClient.connect().
 * Каждое поле маппится на JSON-поле в setup message.
 *
 * Документация: https://ai.google.dev/api/live
 * Модель: gemini-3.1-flash-live-preview (128k context, 64k output)
 */
data class SessionConfig(

    // ── Model ──
    val model: String = DEFAULT_MODEL,

    // ── Generation Config ──
    val responseModality: String = "AUDIO",
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxOutputTokens: Int = 8192,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,

    // ── Voice ──
    val voiceId: String = "Aoede",
    val languageCode: String = "",

    // ── Thinking ──
    val latencyProfile: LatencyProfile = LatencyProfile.UltraLow,

    // ── VAD ──
    val autoActivityDetection: Boolean = true,
    val vadStartSensitivity: Float = 0.5f,
    val vadEndSensitivity: Float = 0.5f,
    val vadSilenceTimeoutMs: Int = 0,

    // ── System Instruction ──
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ── Transcription ──
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,

    // ── Session Management ──
    val enableSessionResumption: Boolean = true,
    val transparentResumption: Boolean = true,
    val sessionHandle: String? = null,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Int = 0,

    // ── Tools ──
    val enableGoogleSearch: Boolean = false,

    // ── Audio ──
    val sendAudioStreamEnd: Boolean = true
) {
    companion object {
        const val DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview"

        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты русскоязычный голосовой ассистент. " +
            "Всегда отвечай только на русском языке. " +
            "Слушай и понимай русскую речь. " +
            "Отвечай кратко и по делу, не более 2-3 предложений, " +
            "если пользователь не просит подробного ответа."

        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000

        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

/**
 * Профиль латентности → thinkingLevel в Gemini 3.1 setup.
 *
 * minimal — минимальная задержка, подходит для голосового чата
 * low     — немного больше reasoning при умеренной латентности
 * medium  — баланс скорость/качество
 * high    — максимальный reasoning, высокая латентность
 */
enum class LatencyProfile(val thinkingLevel: String, val displayName: String) {
    UltraLow("minimal", "Ultra Low (minimal thinking)"),
    Low("low", "Low (light thinking)"),
    Balanced("medium", "Balanced (medium thinking)"),
    Reasoning("high", "Reasoning (deep thinking)")
}

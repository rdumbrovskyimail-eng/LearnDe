// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/domain/model/SessionConfig.kt
// Изменения: + FunctionDeclarationConfig, + functionDeclarations field
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.domain.model

/**
 * Декларация функции для Gemini tool calling.
 * Передаётся в setup message → tools[].functionDeclarations[].
 */
data class FunctionDeclarationConfig(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterConfig> = emptyMap()
)

data class ParameterConfig(
    val type: String = "STRING",
    val description: String = ""
)

/**
 * Конфигурация сессии Gemini Live API — полная спецификация 2026.
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
    val functionDeclarations: List<FunctionDeclarationConfig> = emptyList(),

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

enum class LatencyProfile(val thinkingLevel: String, val displayName: String) {
    UltraLow("minimal", "Ultra Low (minimal thinking)"),
    Low("low", "Low (light thinking)"),
    Balanced("medium", "Balanced (medium thinking)"),
    Reasoning("high", "Reasoning (deep thinking)")
}
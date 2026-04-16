// ═══════════════════════════════════════════════════════════════════
//  ПОЛНАЯ ЗАМЕНА
//  Путь: app/src/main/java/com/codeextractor/app/domain/model/SessionConfig.kt
//
//  Изменения:
//   + Убрано упоминание vadStartSensitivity/vadEndSensitivity из JSON setup
//     (их API принимает только как enum-строки "START_SENSITIVITY_LOW|HIGH",
//      float-пороги игнорируются. В 3.1 Flash Live дефолтов достаточно.)
//   + Уточнены дефолты: temperature=0.8, topP=0.95 (как в референсах Google)
//   + Модель: models/gemini-3.1-flash-live-preview (подтверждено офиц. доками)
//   + Language code по умолчанию пустой (native audio выбирает язык сам)
// ═══════════════════════════════════════════════════════════════════
package com.codeextractor.app.domain.model

/**
 * Декларация function calling для Gemini tool use.
 * Передаётся в setup.tools[].functionDeclarations[].
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
 * Конфигурация сессии Gemini Live API (2026, v1beta).
 */
data class SessionConfig(

    // ── Model ──
    val model: String = DEFAULT_MODEL,

    // ── Generation Config (всё идёт ВНУТРЬ generationConfig) ──
    val responseModality: String = "AUDIO",
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 0,                   // 0 = не слать (дефолт сервера)
    val maxOutputTokens: Int = 8192,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,

    // ── Speech Config (внутри generationConfig) ──
    val voiceId: String = "Aoede",
    val languageCode: String = "",       // пусто = автоопределение

    // ── Thinking Config (внутри generationConfig) ──
    val latencyProfile: LatencyProfile = LatencyProfile.UltraLow,

    // ── VAD (realtimeInputConfig верхнего уровня) ──
    val autoActivityDetection: Boolean = true,
    val vadStartSensitivity: Float = 0.5f,   // зарезервировано, не отправляется
    val vadEndSensitivity: Float = 0.5f,     // зарезервировано, не отправляется
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

    // ── Audio behaviour ──
    val sendAudioStreamEnd: Boolean = true
) {
    companion object {
        /** Подтверждено: ai.google.dev/gemini-api/docs/models/gemini-3.1-flash-live-preview */
        const val DEFAULT_MODEL = "models/gemini-3.1-flash-live-preview"

        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты русскоязычный голосовой ассистент. " +
            "Всегда отвечай только на русском языке. " +
            "Слушай и понимай русскую речь. " +
            "Отвечай кратко и по делу, не более 2-3 предложений, " +
            "если пользователь не просит подробного ответа."

        /** Gemini 3.1 native audio: вход 16 kHz */
        const val INPUT_SAMPLE_RATE = 16_000
        /** Gemini 3.1 native audio: выход 24 kHz */
        const val OUTPUT_SAMPLE_RATE = 24_000

        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

/**
 * Профиль латентности → Gemini 3.1 thinkingLevel.
 * Допустимы: minimal | low | medium | high
 */
enum class LatencyProfile(val thinkingLevel: String, val displayName: String) {
    UltraLow("minimal", "Ultra Low (minimal thinking)"),
    Low("low", "Low (light thinking)"),
    Balanced("medium", "Balanced (medium thinking)"),
    Reasoning("high", "Reasoning (deep thinking)")
}

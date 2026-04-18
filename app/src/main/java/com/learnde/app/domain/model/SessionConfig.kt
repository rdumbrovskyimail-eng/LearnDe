package com.learnde.app.domain.model

/**
 * Декларация function calling для Gemini tool use.
 * Передаётся в setup.tools[].functionDeclarations[].
 *
 * ВАЖНО: даже если функция без аргументов — API требует parameters:
 * {type:"object", properties:{}} (фикс code 1007 при >5 функций).
 *
 * @param required имена параметров верхнего уровня, обязательные для вызова.
 */
data class FunctionDeclarationConfig(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Описание параметра function declaration.
 * Совместимо с OpenAPI Schema subset, который принимает Gemini Live API.
 *
 * Типы: STRING | NUMBER | INTEGER | BOOLEAN | ARRAY | OBJECT
 * (клиент переводит в lowercase при сериализации — это требование JSON Schema).
 */
data class ParameterConfig(
    val type: String = "STRING",
    val description: String = "",
    val enumValues: List<String> = emptyList(),
    val items: ParameterConfig? = null,
    val properties: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Конфигурация сессии Gemini Live API (v1beta, 2026).
 *
 * Структура setup по официальной спецификации Gemini 3.1 Flash Live:
 *
 *   setup:
 *     model                          ← ЧИСТЫЙ ID без "models/" префикса
 *     generationConfig:
 *       temperature, topP, topK, maxOutputTokens, responseModalities,
 *       presencePenalty, frequencyPenalty,
 *       speechConfig,
 *       thinkingConfig
 *     systemInstruction
 *     tools
 *     realtimeInputConfig
 *     inputAudioTranscription
 *     outputAudioTranscription
 *     sessionResumption
 *     contextWindowCompression
 *     mediaResolution                ← НА КОРНЕВОМ УРОВНЕ setup, не в generationConfig
 */
data class SessionConfig(

    // ── Model ──
    val model: String = DEFAULT_MODEL,

    // ── Generation Config ──
    val responseModality: String = "AUDIO",
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val topK: Int = 0,                   // 0 = не слать
    val maxOutputTokens: Int = 8192,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,

    // ── Speech Config (внутри generationConfig) ──
    val voiceId: String = "Aoede",
    val languageCode: String = "",

    // ── Thinking Config (внутри generationConfig) ──
    val latencyProfile: LatencyProfile = LatencyProfile.UltraLow,
    val thinkingIncludeThoughts: Boolean = false,

    // ── Media Resolution (КОРНЕВОЙ уровень setup, не generationConfig!) ──
    val mediaResolution: String = "",    // "" | "MEDIA_RESOLUTION_LOW|MEDIUM|HIGH"

    // ── VAD (realtimeInputConfig верхнего уровня) ──
    val autoActivityDetection: Boolean = true,
    val vadStartSensitivity: String = "START_SENSITIVITY_LOW",
    val vadEndSensitivity: String = "END_SENSITIVITY_LOW",
    val vadPrefixPaddingMs: Int = 20,
    val vadSilenceDurationMs: Int = 100,

    // ── System Instruction ──
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ── Transcription (корневой уровень setup) ──
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,

    // ── Session Management ──
    val enableSessionResumption: Boolean = true,
    val transparentResumption: Boolean = true,
    val sessionHandle: String? = null,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Long = 0L,
    val compressionTargetTokens: Long = 0L,

    // ── Tools ──
    val enableGoogleSearch: Boolean = false,
    val functionDeclarations: List<FunctionDeclarationConfig> = emptyList(),

    // ── Audio behaviour ──
    val sendAudioStreamEnd: Boolean = true,

    // ── Connection timeout ──
    /** Сколько миллисекунд ждать setupComplete после onOpen. */
    val setupTimeoutMs: Long = 10_000L
) {
    companion object {

        /**
         * Model ID для WebSocket BidiGenerateContent.
         *
         * ВАЖНО: в WebSocket Live API используется ЧИСТЫЙ ID модели,
         * БЕЗ префикса "models/" (в отличие от REST API).
         * Префикс "models/" вызывает close code 1008 (Policy Violation).
         */
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

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
 * Профиль латентности → Gemini 3.1 thinkingLevel.
 *
 * ОФИЦИАЛЬНЫЕ значения thinkingLevel в Gemini 3.1 (lowercase):
 *   - "minimal"  (default, lowest latency, для voice agents)
 *   - "low"
 *   - "medium"
 *   - "high"     (deep reasoning, highest latency)
 *
 * Значение "none" НЕ СУЩЕСТВУЕТ и приведёт к close code 1007.
 * Для минимального thinking используй "minimal".
 */
enum class LatencyProfile(val thinkingLevel: String, val displayName: String) {
    UltraLow ("minimal", "Ultra Low (minimal thinking)"),
    Low      ("low",     "Low (light thinking)"),
    Balanced ("medium",  "Balanced (medium thinking)"),
    Reasoning("high",    "Reasoning (deep thinking)")
}

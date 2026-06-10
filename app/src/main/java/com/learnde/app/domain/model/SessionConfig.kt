package com.learnde.app.domain.model

/**
 * Декларация function calling для Gemini tool use.
 * Передаётся в setup.tools[].functionDeclarations[].
 */
data class FunctionDeclarationConfig(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Описание параметра function declaration.
 * Типы: STRING | NUMBER | INTEGER | BOOLEAN | ARRAY | OBJECT
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
 * Конфигурация сессии Gemini Live API v1beta.
 *
 * ДИАГНОСТИЧЕСКИЕ ФЛАГИ (sendXxx) позволяют выключать отдельные блоки
 * setup для поиска источника close code 1007 "Invalid JSON payload".
 *
 * По умолчанию все блоки включены — если 1007, используй готовые
 * профили: baselineProfile(), withoutThinkingProfile(), и т.д.
 */
data class SessionConfig(

    // ── Model ──
    val model: String = DEFAULT_MODEL,

    // ── Generation Config ──
    val responseModality: String = "AUDIO",
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 0,
    val maxOutputTokens: Int = 4096,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,

    // ── Speech Config ──
    val voiceId: String = "Aoede",
    val languageCode: String = "",

    // ── Thinking Config ──
    val latencyProfile: LatencyProfile = LatencyProfile.UltraLow,
    val thinkingIncludeThoughts: Boolean = false,

    // ── Media Resolution ──
    val mediaResolution: String = "",

    // ── VAD ──
    val autoActivityDetection: Boolean = true,
    // Оптимум для начинающего ученика (официальные enum'ы AutomaticActivityDetection):
    // START HIGH  — ловить тихую, неуверенную речь ученика;
    // END LOW     — НЕ обрывать ученика, когда он думает над словом;
    // prefix 300  — не терять первый слог;
    // silence 900 — дать ученику паузу почти в секунду до окончания хода.
    val vadStartSensitivity: String = "START_SENSITIVITY_HIGH",
    val vadEndSensitivity: String = "END_SENSITIVITY_LOW",
    val vadPrefixPaddingMs: Int = 300,
    val vadSilenceDurationMs: Int = 900,

    // ── System Instruction ──
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ── Transcription ──
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    /**
     * Список BCP-47 кодов языков для AudioTranscriptionConfig.languageCodes.
     * Подсказка для ASR — ограничивает спекуляцию языка.
     * Пустой = автоопределение (по умолчанию).
     */
    val transcriptionLanguageCodes: List<String> = emptyList(),

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
    val setupTimeoutMs: Long = 10_000L
) {
    companion object {

        /**
         * Model ID для WebSocket BidiGenerateContent.
         * БЕЗ префикса "models/"!
         */
        const val DEFAULT_MODEL = "gemini-3.1-flash-live-preview"

        const val DEFAULT_SYSTEM_INSTRUCTION = "Ты — полезный голосовой ассистент."

        const val INPUT_SAMPLE_RATE = 16_000
        const val OUTPUT_SAMPLE_RATE = 24_000

        const val WS_HOST = "generativelanguage.googleapis.com"
        const val WS_PATH = "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}

/**
 * Профиль латентности → Gemini 3.1 thinkingLevel.
 *
 * Off       — thinkingConfig вообще НЕ отправляется. Самая быстрая реакция.
 *             Идеально для перевода и командных голосовых интерфейсов.
 * UltraLow  — "minimal" thinking. Лёгкое обдумывание перед ответом.
 * Low       — "low" thinking. Стандартное.
 * Balanced  — "medium" thinking. Для разговорных AI.
 * Reasoning — "high" thinking. Для сложных задач (математика, код, анализ).
 *
 * thinkingLevel == null означает "не отправлять блок thinkingConfig".
 */
enum class LatencyProfile(val thinkingLevel: String?, val displayName: String) {
    Off      (null,      "Off — мгновенный ответ"),
    UltraLow ("minimal", "Ultra Low — minimal thinking"),
    Low      ("low",     "Low — light thinking"),
    Balanced ("medium",  "Balanced — medium thinking"),
    Reasoning("high",    "Reasoning — deep thinking")
}

// ═══════════════════════════════════════════════════════════════════
//  ПОЛНАЯ ЗАМЕНА
//  Путь: app/src/main/java/com/learnde/app/domain/model/SessionConfig.kt
//
//  Изменения v2:
//   + ParameterConfig расширен: enumValues / items / properties / required
//     (поддержка вложенных объектов и массивов для function calling)
//   + FunctionDeclarationConfig: добавлен required на уровне функции
//     (Gemini 3.1 без required иногда отдаёт 1007 при >5 объявленных функциях)
//   + VAD float-пороги заменены на строковые enum, которые реально принимает v1beta
//   + Добавлены initialHistoryInClientContent / mediaResolution /
//     thinkingIncludeThoughts
//   + Добавлен WS_PATH_EPHEMERAL (справочно, не используется)
//
//  Ранее:
//   + Модель: models/gemini-3.1-flash-live-preview (офиц. доки)
//   + temperature=0.8, topP=0.95 (как в референсах Google)
//   + languageCode пустой — native audio выбирает язык сам
// ═══════════════════════════════════════════════════════════════════
package com.learnde.app.domain.model

/**
 * Декларация function calling для Gemini tool use.
 * Передаётся в setup.tools[].functionDeclarations[].
 *
 * @param required имена параметров верхнего уровня, обязательные для вызова
 *                 (важно для Gemini 3.1 при >5 функций, иначе 1007).
 */
data class FunctionDeclarationConfig(
    val name: String,
    val description: String,
    val parameters: Map<String, ParameterConfig> = emptyMap(),
    val required: List<String> = emptyList()
)

/**
 * Описание одного параметра function declaration (совместимо с OpenAPI Schema,
 * который принимает Gemini Live API).
 *
 * @param type         STRING | NUMBER | INTEGER | BOOLEAN | ARRAY | OBJECT
 * @param description  человекочитаемое описание параметра для модели
 * @param enumValues   ограниченный набор значений (для STRING/INTEGER)
 * @param items        описание элемента массива (обязательно для type == ARRAY)
 * @param properties   вложенные поля (для type == OBJECT)
 * @param required     имена обязательных вложенных полей (для type == OBJECT)
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

    // ── VAD sensitivity (enum strings, v1beta API) ──
    val vadStartSensitivity: String = "START_SENSITIVITY_LOW",   // LOW | HIGH
    val vadEndSensitivity: String = "END_SENSITIVITY_LOW",       // LOW | HIGH
    val vadPrefixPaddingMs: Int = 20,
    val vadSilenceDurationMs: Int = 100,

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

    // ── History Config (только для 3.1 Flash Live) ──
    val initialHistoryInClientContent: Boolean = true,

    // ── Media Resolution (для будущих видео-уроков) ──
    val mediaResolution: String = "",  // "" = не слать; "MEDIA_RESOLUTION_LOW|MEDIUM|HIGH"

    // ── Thinking: показывать ли мысли модели в логах ──
    val thinkingIncludeThoughts: Boolean = false,

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

        /** Ephemeral tokens path (не используется, но пусть будет для справки) */
        const val WS_PATH_EPHEMERAL = "ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"
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
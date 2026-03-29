package com.codeextractor.app.domain.model

/**
 * Конфигурация сессии Gemini Live.
 * Передаётся в LiveClient.connect() → формирует setup JSON.
 *
 * Подготовлено под Этап 3 (экран настроек) — каждый параметр
 * будет привязан к UI-контролу.
 */
data class SessionConfig(
    /** Модель Gemini. В 2026: gemini-3.1-flash-live-preview */
    val model: String = DEFAULT_MODEL,

    /** Голос TTS. Варианты: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr */
    val voiceId: String = "Aoede",

    /** Профиль латентности → thinkingLevel в setup */
    val latencyProfile: LatencyProfile = LatencyProfile.UltraLow,

    /** Серверный VAD (Voice Activity Detection) */
    val autoActivityDetection: Boolean = true,

    /** Системная инструкция для модели */
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    /** Включить транскрипцию входящего аудио */
    val inputTranscription: Boolean = true,

    /** Включить транскрипцию ответа модели */
    val outputTranscription: Boolean = true
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
 * Профиль латентности.
 * Gemini 3.1: thinkingLevel вместо thinkingBudget.
 */
enum class LatencyProfile(val thinkingLevel: String) {
    /** Минимальная латентность — оптимально для голосового чата */
    UltraLow("minimal"),

    /** Баланс между скоростью и качеством */
    Balanced("medium"),

    /** Максимальный reasoning — высокая латентность */
    Reasoning("high")
}
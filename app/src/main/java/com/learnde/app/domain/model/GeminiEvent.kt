package com.learnde.app.domain.model

/**
 * Все события от Gemini WebSocket сервера — полная спецификация Gemini 3.1 Flash Live (2026).
 *
 * Жизненный цикл подключения:
 *   1. onOpen (TLS handshake OK)      → Connected         (UI: жёлтый "connecting")
 *   2. setupComplete пришёл от сервера → SetupComplete     (UI: зелёный "ready")
 *   3. Закрытие сессии                 → Disconnected
 *   4. Ошибка                         → ConnectionError
 *
 * Новое в Gemini 3.1 Flash Live:
 *  - ToolCallCancellation  — отмена tool call при barge-in
 *  - UsageMetadata          — подсчёт токенов
 *  - GroundingMetadata      — результаты Google Search
 *  - GoAway с timeLeft      — время до закрытия
 *  - SessionHandleUpdate    — resumable + lastConsumedIndex
 */
sealed interface GeminiEvent {

    // ── Жизненный цикл соединения ───────────────────────────────────────────

    /**
     * WebSocket подключён (TLS handshake прошёл, setup отправлен, но ещё не подтверждён).
     * UI должен показывать "connecting" (жёлтый статус).
     */
    data object Connected : GeminiEvent

    /**
     * Сервер подтвердил setup — можно записывать аудио.
     * UI должен показывать "ready" (зелёный статус).
     */
    data object SetupComplete : GeminiEvent

    /** WebSocket закрыт штатно или по таймауту */
    data class Disconnected(val code: Int, val reason: String) : GeminiEvent

    /** Ошибка соединения */
    data class ConnectionError(val message: String) : GeminiEvent

    // ── Аудио и текст ───────────────────────────────────────────────────────

    /** PCM-аудио от модели для воспроизведения (24 kHz, 16-bit, mono, little-endian) */
    data class AudioChunk(val pcmData: ByteArray) : GeminiEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioChunk && pcmData.contentEquals(other.pcmData)
        override fun hashCode(): Int = pcmData.contentHashCode()
    }

    /** Текстовый ответ модели */
    data class ModelText(val text: String) : GeminiEvent

    /** Транскрипция речи пользователя (inputAudioTranscription) */
    data class InputTranscript(val text: String) : GeminiEvent

    /** Транскрипция ответа модели (outputAudioTranscription) */
    data class OutputTranscript(val text: String) : GeminiEvent

    // ── Control events ──────────────────────────────────────────────────────

    /** Пользователь перебил модель — flush playback buffer */
    data object Interrupted : GeminiEvent

    /** Модель закончила текущий ход */
    data object TurnComplete : GeminiEvent

    /** Генерация полностью завершена */
    data object GenerationComplete : GeminiEvent

    // ── Tool calling ────────────────────────────────────────────────────────

    /** Сервер вызывает функцию (в 3.1 — только синхронный tool calling) */
    data class ToolCall(val calls: List<FunctionCall>) : GeminiEvent

    /**
     * Сервер отменяет ранее выданный tool call (при barge-in).
     * Клиент должен попытаться отменить/откатить side-effects.
     */
    data class ToolCallCancellation(val ids: List<String>) : GeminiEvent

    // ── Session management ──────────────────────────────────────────────────

    /**
     * Обновление session handle для reconnect.
     * @param handle             новый handle для возобновления
     * @param resumable          true если сессия может быть возобновлена
     * @param lastConsumedIndex  индекс последнего обработанного сообщения (transparent mode)
     */
    data class SessionHandleUpdate(
        val handle: String,
        val resumable: Boolean = true,
        val lastConsumedIndex: Long? = null
    ) : GeminiEvent

    /**
     * Сервер предупреждает о скором закрытии.
     * @param timeLeft оставшееся время (строка, e.g. "30s")
     */
    data class GoAway(val timeLeft: String? = null) : GeminiEvent

    // ── Metadata ────────────────────────────────────────────────────────────

    /**
     * Статистика использования токенов.
     * Gemini/Vertex используют разные имена полей — клиент нормализует.
     */
    data class UsageMetadata(
        val promptTokens: Int,
        val responseTokens: Int,
        val totalTokens: Int
    ) : GeminiEvent

    /**
     * Результаты Google Search grounding.
     * @param rawJson сырой JSON для отображения/обработки
     */
    data class GroundingMetadata(val rawJson: String) : GeminiEvent
}

/** Один вызов функции из toolCall.functionCalls[] */
data class FunctionCall(
    val name: String,
    val id: String,
    val args: Map<String, String>
)

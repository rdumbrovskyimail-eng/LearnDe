package com.learnde.app.domain.model

/**
 * Все события от Gemini WebSocket сервера — полная спецификация 2026.
 *
 * Новое в Gemini 3.1 Flash Live:
 *  - ToolCallCancellation  — отмена tool call при barge-in
 *  - UsageMetadata          — подсчёт токенов
 *  - GroundingMetadata      — результаты Google Search
 *  - GoAway с timeLeft      — время до закрытия
 *  - SessionHandleUpdate    — resumable + lastConsumedIndex
 */
sealed interface GeminiEvent {

    /** Сервер подтвердил setup — можно записывать аудио */
    data object SetupComplete : GeminiEvent

    /** PCM-аудио от модели для воспроизведения */
    data class AudioChunk(val pcmData: ByteArray) : GeminiEvent {
        override fun equals(other: Any?): Boolean =
            other is AudioChunk && pcmData.contentEquals(other.pcmData)
        override fun hashCode(): Int = pcmData.contentHashCode()
    }

    /** Текстовый ответ модели */
    data class ModelText(val text: String) : GeminiEvent

    /** Транскрипция речи пользователя */
    data class InputTranscript(val text: String) : GeminiEvent

    /** Транскрипция ответа модели */
    data class OutputTranscript(val text: String) : GeminiEvent

    /** Пользователь перебил модель — flush playback */
    data object Interrupted : GeminiEvent

    /** Модель закончила текущий ход */
    data object TurnComplete : GeminiEvent

    /** Генерация полностью завершена */
    data object GenerationComplete : GeminiEvent

    /** Сервер вызывает функцию (синхронный tool calling) */
    data class ToolCall(val calls: List<FunctionCall>) : GeminiEvent

    /**
     * Сервер отменяет ранее выданный tool call (при barge-in).
     * Клиент должен попытаться отменить/откатить side-effects.
     */
    data class ToolCallCancellation(val ids: List<String>) : GeminiEvent

    /**
     * Обновление session handle для reconnect.
     * @param handle     новый handle для возобновления
     * @param resumable  true если сессия может быть возобновлена
     * @param lastConsumedIndex индекс последнего обработанного сообщения (transparent mode)
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

    /** WebSocket подключён (до setup) */
    data object Connected : GeminiEvent

    /** WebSocket закрыт штатно или по таймауту */
    data class Disconnected(val code: Int, val reason: String) : GeminiEvent

    /** Ошибка соединения */
    data class ConnectionError(val message: String) : GeminiEvent
}

/** Один вызов функции из toolCall.functionCalls[] */
data class FunctionCall(
    val name: String,
    val id: String,
    val args: Map<String, String>
)

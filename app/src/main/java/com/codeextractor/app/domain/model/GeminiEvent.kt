package com.codeextractor.app.domain.model

/**
 * Все события от Gemini WebSocket сервера.
 * LiveClient парсит JSON и эмитит типизированные события.
 * ViewModel подписывается и обновляет VoiceState.
 *
 * Маппинг на протокол v1beta:
 *  - setupComplete         → SetupComplete
 *  - serverContent.modelTurn.parts[].inlineData → AudioChunk
 *  - serverContent.modelTurn.parts[].text       → ModelText
 *  - serverContent.inputTranscription           → InputTranscript
 *  - serverContent.outputTranscription          → OutputTranscript
 *  - serverContent.interrupted                  → Interrupted (barge-in)
 *  - serverContent.turnComplete                 → TurnComplete
 *  - serverContent.generationComplete           → GenerationComplete
 *  - toolCall                                   → ToolCall
 *  - sessionResumptionUpdate                    → SessionHandleUpdate
 *  - goAway                                     → GoAway
 *  - WS onFailure / onClosed                    → ConnectionError / Disconnected
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

    /** Текстовый ответ модели (если responseModalities включает TEXT) */
    data class ModelText(val text: String) : GeminiEvent

    /** Транскрипция речи пользователя (от сервера) */
    data class InputTranscript(val text: String) : GeminiEvent

    /** Транскрипция ответа модели (от сервера) */
    data class OutputTranscript(val text: String) : GeminiEvent

    /** Пользователь перебил модель — нужен flush playback */
    data object Interrupted : GeminiEvent

    /** Модель закончила текущий ход */
    data object TurnComplete : GeminiEvent

    /** Генерация полностью завершена */
    data object GenerationComplete : GeminiEvent

    /** Сервер вызывает функцию (синхронный tool calling) */
    data class ToolCall(val calls: List<FunctionCall>) : GeminiEvent

    /** Обновление session handle для reconnect */
    data class SessionHandleUpdate(val handle: String) : GeminiEvent

    /** Сервер предупреждает о скором закрытии */
    data object GoAway : GeminiEvent

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
package com.codeextractor.app.domain

import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.GeminiEvent
import com.codeextractor.app.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow

/**
 * Абстракция WebSocket-клиента Gemini Live API.
 *
 * Контракт:
 *  1. connect()    → открывает WS, шлёт setup, эмитит события
 *  2. sendAudio()  → стримит PCM-чанки (realtimeInput)
 *  3. sendText()   → текстовый ввод (realtimeInput.text)
 *  4. sendTurnComplete() → сигнал окончания хода пользователя
 *  5. sendToolResponse() → ответ на синхронный tool call
 *  6. restoreContext()   → clientContent с историей после reconnect
 *  7. disconnect() → штатное закрытие (code 1000)
 *
 * Реализация: data/GeminiLiveClient.kt
 * Вся OkHttp/JSON логика инкапсулирована — ViewModel видит только Flow<GeminiEvent>.
 */
interface LiveClient {

    /** Поток событий от сервера. Активен пока соединение живо. */
    val events: Flow<GeminiEvent>

    /** Текущий session handle для reconnect (null если нет) */
    val sessionHandle: String?

    /** true если WebSocket открыт и setup завершён */
    val isReady: Boolean

    /**
     * Подключение к Gemini Live API.
     * @param apiKey API ключ Google AI
     * @param config конфигурация сессии (модель, голос, VAD и т.д.)
     */
    suspend fun connect(apiKey: String, config: SessionConfig)

    /**
     * Отправка PCM-аудио чанка.
     * @param pcmData raw PCM 16-bit LE, mono, 16kHz
     */
    fun sendAudio(pcmData: ByteArray)

    /**
     * Отправка текстового сообщения.
     * @param text текст от пользователя
     */
    fun sendText(text: String)

    /** Сигнал серверу: пользователь закончил говорить */
    fun sendTurnComplete()

    /**
     * Ответ на синхронный tool call от модели.
     * @param responses список пар (callId → JSON result)
     */
    fun sendToolResponse(responses: List<ToolResponse>)

    /**
     * Восстановление контекста разговора после reconnect.
     * Отправляет clientContent с turns из истории.
     */
    fun restoreContext(history: List<ConversationMessage>)

    /** Штатное закрытие WebSocket */
    fun disconnect()
}

/** Ответ на один function call */
data class ToolResponse(
    val name: String,
    val id: String,
    val result: String
)
package com.codeextractor.app.domain

import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.GeminiEvent
import com.codeextractor.app.domain.model.SessionConfig
import kotlinx.coroutines.flow.Flow

/**
 * Абстракция WebSocket-клиента Gemini Live API — контракт 2026.
 *
 * Полный набор операций:
 *  1. connect()           — WS + setup с полной конфигурацией
 *  2. sendAudio()         — стрим PCM (realtimeInput.audio)
 *  3. sendText()          — текст (realtimeInput.text)
 *  4. sendAudioStreamEnd()— flush серверного audio кеша при паузе mic
 *  5. sendTurnComplete()  — сигнал окончания хода
 *  6. sendToolResponse()  — ответ на tool call
 *  7. restoreContext()    — seeding истории (только в начале сессии!)
 *  8. disconnect()        — штатное закрытие
 */
interface LiveClient {

    val events: Flow<GeminiEvent>
    val sessionHandle: String?
    val isReady: Boolean

    /**
     * Подключение к Gemini Live API.
     * @param apiKey  API ключ
     * @param config  полная конфигурация сессии
     * @param logRaw  логировать сырые WS-фреймы
     */
    suspend fun connect(apiKey: String, config: SessionConfig, logRaw: Boolean = false)

    fun sendAudio(pcmData: ByteArray)
    fun sendText(text: String)

    /**
     * Отправить audioStreamEnd для flush кеша на сервере.
     * Вызывать при паузе/остановке микрофона.
     */
    fun sendAudioStreamEnd()

    fun sendTurnComplete()
    fun sendToolResponse(responses: List<ToolResponse>)

    /**
     * Seeding начальной истории.
     * ВАЖНО: в Gemini 3.1 разрешено ТОЛЬКО в начале сессии
     * (до первого model turn). После — вызовет 1007.
     */
    fun restoreContext(history: List<ConversationMessage>)

    fun disconnect()
}

data class ToolResponse(
    val name: String,
    val id: String,
    val result: String
)

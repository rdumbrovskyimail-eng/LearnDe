// ═══════════════════════════════════════════════════════════════════
//  ПОЛНАЯ ЗАМЕНА
//  Путь: app/src/main/java/com/learnde/app/data/GeminiLiveClient.kt
//
//  КРИТИЧЕСКИЕ ФИКСЫ v3:
//
//  [1] suspend disconnect() с ожиданием реального onClosed
//      (CompletableDeferred + withTimeoutOrNull 2с).
//      Устраняет эвристический delay(400) в VoiceViewModel.
//
//  [2] sendText() — initial user message через clientContent.turns.
//      sendRealtimeText() — текст в ходе диалога через realtimeInput.text
//      (семантическое разделение двух каналов, 3.1 Flash Live).
//
//  [3] functionDeclarations: рекурсивная схема параметров через
//      buildParameterSchema() — поддержка вложенных OBJECT/ARRAY,
//      enumValues, required на уровне функции и вложенных объектов.
//      Типы переводятся в lowercase (JSON Schema требует string, а не STRING).
//
//  [4] thinkingConfig и speechConfig — на КОРНЕВОМ уровне setup
//      (вне generationConfig), как предписывает v1beta.
//
//  [5] realtimeInputConfig: полный VAD
//      (startOfSpeechSensitivity, endOfSpeechSensitivity,
//       prefixPaddingMs, silenceDurationMs) — только когда
//       автоматический VAD включён.
//
//  [6] Новые setup-поля: historyConfig, mediaResolution,
//      thinkingConfig.includeThoughts.
//
//  [7] Новые методы: sendRealtimeText, sendVideoFrame.
//
//  [8] parseToolCall: корректная сериализация JsonObject/JsonArray
//      в args (раньше generic toString() давал мусор для вложенных
//      аргументов вида {"words":["der","die"]}).
//
//  [9] Диагностика 1007/1008: трекинг последних 3 отправленных
//      фреймов + дамп в лог при protocol error (работает при
//      logRawFrames=true).
// ═══════════════════════════════════════════════════════════════════
package com.learnde.app.data

import android.util.Base64
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.ParameterConfig
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeminiLiveClient @Inject constructor(
    private val logger: AppLogger
) : LiveClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // живое соединение за NAT
        .build()

    @Volatile private var webSocket: WebSocket? = null

    private val _events = MutableSharedFlow<GeminiEvent>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val events: Flow<GeminiEvent> = _events.asSharedFlow()

    @Volatile
    override var sessionHandle: String? = null
        private set

    @Volatile
    override var isReady: Boolean = false
        private set

    @Volatile
    private var logRawFrames: Boolean = false

    private var currentConfig: SessionConfig? = null

    /** Ожидание фактического закрытия WS (onClosed / onFailure). */
    @Volatile
    private var closeCompletion: CompletableDeferred<Unit>? = null

    /**
     * Последние 3 отправленных клиентом фрейма (только при logRawFrames=true).
     * Используется для диагностики protocol errors (1007/1008): когда
     * сервер закрывает соединение из-за кривого payload'а, этот буфер
     * показывает, что именно было отправлено непосредственно перед крахом.
     */
    private val lastSentFrames = java.util.ArrayDeque<String>(3)

    private fun trackSentFrame(raw: String) {
        if (!logRawFrames) return
        synchronized(lastSentFrames) {
            if (lastSentFrames.size >= 3) lastSentFrames.pollFirst()
            lastSentFrames.offerLast(raw.take(500))
        }
    }

    // ════════════════════════════════════════════════════════════
    //  CONNECT / DISCONNECT
    // ════════════════════════════════════════════════════════════

    override suspend fun connect(apiKey: String, config: SessionConfig, logRaw: Boolean) {
        if (webSocket != null) disconnect()

        currentConfig = config
        logRawFrames = logRaw
        isReady = false
        synchronized(lastSentFrames) { lastSentFrames.clear() }
        // Свежий completion на новый connect-цикл
        closeCompletion = CompletableDeferred()

        val url = "wss://${SessionConfig.WS_HOST}/${SessionConfig.WS_PATH}?key=$apiKey"
        logger.d("Connecting to ${config.model}…")

        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                logger.d("WS opened (${response.code})")
                _events.tryEmit(GeminiEvent.Connected)
                sendSetup(config)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (logRawFrames) {
                    val preview = if (text.length > 300) text.take(300) + "…" else text
                    logger.d("RAW ← $preview")
                }
                parseServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                try {
                    parseServerMessage(bytes.utf8())
                } catch (e: Exception) {
                    logger.e("Binary frame decode error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                val desc = describeCloseCode(code)
                logger.d("WS closed: $code $desc reason='$reason'")

                // Диагностика protocol errors: дамп последних фреймов
                if (code == 1007 || code == 1008) {
                    synchronized(lastSentFrames) {
                        if (lastSentFrames.isNotEmpty()) {
                            logger.e("⚠ LAST SENT FRAMES before close $code:")
                            lastSentFrames.forEachIndexed { i, frame ->
                                logger.e("  [$i] $frame")
                            }
                        } else {
                            logger.e("⚠ No frames tracked (enable logRawFrames to capture)")
                        }
                    }
                }

                isReady = false
                closeCompletion?.complete(Unit)
                if (code != 1000 && code != 1001) {
                    _events.tryEmit(
                        GeminiEvent.ConnectionError(
                            "WS closed $code: $desc ${reason.ifBlank { "" }}"
                        )
                    )
                }
                _events.tryEmit(GeminiEvent.Disconnected(code, reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val status = response?.code?.let { " (HTTP $it)" } ?: ""
                logger.e("WS failure$status: ${t.message}")
                isReady = false
                closeCompletion?.complete(Unit)
                _events.tryEmit(GeminiEvent.ConnectionError(t.message ?: "Unknown error"))
            }
        })
    }

    /**
     * Закрывает WS и ДОЖИДАЕТСЯ реального onClosed/onFailure (до 2 секунд).
     * Если сервер уже закрыл раньше — возврат мгновенный.
     */
    override suspend fun disconnect() {
        val ws = webSocket
        if (ws == null) {
            isReady = false
            return
        }
        val completion = closeCompletion
        runCatching { ws.close(1000, "bye") }
        if (completion != null && !completion.isCompleted) {
            withTimeoutOrNull(2000L) { completion.await() }
        }
        webSocket = null
        isReady = false
        closeCompletion = null
    }

    // ════════════════════════════════════════════════════════════
    //  SETUP — строго по спецификации Gemini Live v1beta (2026)
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {

                // ── Модель ──
                put("model", config.model)

                // ── generationConfig ──
                // ВАЖНО: speechConfig и thinkingConfig ВЫНЕСЕНЫ на корневой
                // уровень setup (см. ниже). В generationConfig остаются только
                // generation-параметры.
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive(config.responseModality))
                    })
                    put("temperature", config.temperature)
                    put("topP", config.topP)
                    if (config.topK > 0) put("topK", config.topK)
                    put("maxOutputTokens", config.maxOutputTokens)
                    if (config.presencePenalty != 0f) put("presencePenalty", config.presencePenalty)
                    if (config.frequencyPenalty != 0f) put("frequencyPenalty", config.frequencyPenalty)
                })

                // thinkingConfig — корневой setup-параметр (не внутри generationConfig)
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", config.latencyProfile.thinkingLevel)
                    if (config.thinkingIncludeThoughts) {
                        put("includeThoughts", true)
                    }
                })

                // speechConfig — корневой setup-параметр
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", config.voiceId)
                        })
                    })
                    if (config.languageCode.isNotBlank()) {
                        put("languageCode", config.languageCode)
                    }
                })

                // ── System Instruction ──
                if (config.systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", config.systemInstruction)
                            })
                        })
                    })
                }

                // ── realtimeInputConfig: серверный VAD ──
                put("realtimeInputConfig", buildJsonObject {
                    put("automaticActivityDetection", buildJsonObject {
                        put("disabled", !config.autoActivityDetection)
                        if (config.autoActivityDetection) {
                            put("startOfSpeechSensitivity", config.vadStartSensitivity)
                            put("endOfSpeechSensitivity", config.vadEndSensitivity)
                            put("prefixPaddingMs", config.vadPrefixPaddingMs)
                            put("silenceDurationMs", config.vadSilenceDurationMs)
                        }
                    })
                })

                // ── Транскрипция ──
                if (config.inputTranscription) {
                    put("inputAudioTranscription", buildJsonObject {})
                }
                if (config.outputTranscription) {
                    put("outputAudioTranscription", buildJsonObject {})
                }

                // ── Session Resumption ──
                if (config.enableSessionResumption) {
                    put("sessionResumption", buildJsonObject {
                        config.sessionHandle?.let { put("handle", it) }
                    })
                }

                // ── Context Window Compression ──
                if (config.enableContextCompression) {
                    put("contextWindowCompression", buildJsonObject {
                        put("slidingWindow", buildJsonObject {
                            if (config.compressionTriggerTokens > 0) {
                                put("targetTokens", config.compressionTriggerTokens)
                            }
                        })
                    })
                }

                // ── History Config (3.1 Flash Live специфика) ──
                put("historyConfig", buildJsonObject {
                    put("initialHistoryInClientContent", config.initialHistoryInClientContent)
                })

                // ── Media Resolution (для видео) ──
                if (config.mediaResolution.isNotBlank()) {
                    put("mediaResolution", config.mediaResolution)
                }

                // ── Tools (Google Search + function declarations) ──
                val hasTools = config.enableGoogleSearch ||
                        config.functionDeclarations.isNotEmpty()
                if (hasTools) {
                    put("tools", buildJsonArray {
                        if (config.enableGoogleSearch) {
                            add(buildJsonObject {
                                put("googleSearch", buildJsonObject {})
                            })
                        }
                        if (config.functionDeclarations.isNotEmpty()) {
                            add(buildJsonObject {
                                put("functionDeclarations", buildJsonArray {
                                    for (decl in config.functionDeclarations) {
                                        add(buildJsonObject {
                                            put("name", decl.name)
                                            put("description", decl.description)
                                            // ВСЕГДА добавляем parameters (фикс 1007)
                                            // Поддержка вложенных OBJECT/ARRAY через
                                            // рекурсивный buildParameterSchema().
                                            put("parameters", buildJsonObject {
                                                put("type", "object")
                                                put("properties", buildJsonObject {
                                                    for ((pName, pConfig) in decl.parameters) {
                                                        put(pName, buildParameterSchema(pConfig))
                                                    }
                                                })
                                                if (decl.required.isNotEmpty()) {
                                                    put("required", buildJsonArray {
                                                        decl.required.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                        })
                                    }
                                })
                            })
                        }
                    })
                }

            })
        }

        val raw = msg.toString()
        logger.d("SETUP → ${config.model} (${raw.length} chars)")
        if (logRawFrames) logger.d("SETUP_RAW → ${raw.take(800)}")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Рекурсивно строит JSON Schema фрагмент для одного параметра.
     * Поддерживает STRING/NUMBER/INTEGER/BOOLEAN, ARRAY (через items)
     * и OBJECT (через properties + required).
     *
     * JSON Schema ожидает lowercase-тип ("string", "array", "object"),
     * поэтому pConfig.type переводится в нижний регистр.
     */
    private fun buildParameterSchema(param: ParameterConfig): JsonObject =
        buildJsonObject {
            put("type", param.type.lowercase())
            if (param.description.isNotBlank()) {
                put("description", param.description)
            }
            if (param.enumValues.isNotEmpty()) {
                put("enum", buildJsonArray {
                    param.enumValues.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (param.type.equals("ARRAY", true) && param.items != null) {
                put("items", buildParameterSchema(param.items))
            }
            if (param.type.equals("OBJECT", true) && param.properties.isNotEmpty()) {
                put("properties", buildJsonObject {
                    param.properties.forEach { (k, v) -> put(k, buildParameterSchema(v)) }
                })
                if (param.required.isNotEmpty()) {
                    put("required", buildJsonArray {
                        param.required.forEach { add(JsonPrimitive(it)) }
                    })
                }
            }
        }

    // ════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ════════════════════════════════════════════════════════════

    override fun sendAudio(pcmData: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Отправка ИНИЦИАЛЬНОГО user-текста через clientContent.turns.
     * Используется для первого сообщения после SetupComplete (initial
     * context / seeding). Для текста в процессе уже идущего диалога
     * (после первого model turn) применяй sendRealtimeText().
     *
     * Схема единая с restoreContext(). НЕ смешивать с realtimeInput.text
     * в initial-фазе — это вызывало 1007 на сессиях с seeded history.
     */
    override fun sendText(text: String) {
        if (!isReady) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turns", buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", text) })
                        })
                    })
                })
                put("turnComplete", true)
            })
        }
        val raw = msg.toString()
        logger.d("TEXT → (${text.length} chars, clientContent)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Отправка текста в процессе уже идущего диалога (после первого model turn).
     * Идёт через realtimeInput.text — это отдельный от clientContent канал,
     * зарезервированный за live-вводом. В Gemini 3.1 Flash Live после того
     * как модель начала отвечать, добавлять user-turn через clientContent
     * нельзя — только через realtimeInput.text.
     */
    override fun sendRealtimeText(text: String) {
        if (!isReady) return
        val raw = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }.toString()
        logger.d("REALTIME_TEXT → (${text.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Отправка одного JPEG-кадра через realtimeInput.video (≤1 FPS).
     * Заготовка для будущих немецких уроков с карточками / видео-контекстом.
     */
    override fun sendVideoFrame(jpegBytes: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val raw = """{"realtimeInput":{"video":{"data":"$b64","mimeType":"image/jpeg"}}}"""
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendAudioStreamEnd() {
        if (!isReady) return
        val raw = """{"realtimeInput":{"audioStreamEnd":true}}"""
        logger.d("AUDIO_STREAM_END →")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendTurnComplete() {
        if (!isReady) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turnComplete", true)
            })
        }
        val raw = msg.toString()
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    override fun sendToolResponse(responses: List<ToolResponse>) {
        val msg = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    for (resp in responses) {
                        add(buildJsonObject {
                            put("name", resp.name)
                            put("id", resp.id)
                            put("response", buildJsonObject {
                                put("result", resp.result)
                            })
                        })
                    }
                })
            })
        }
        val raw = msg.toString()
        logger.d("TOOL_RESPONSE → (${raw.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Seed начальной истории через clientContent.turns.
     * Вызывать ТОЛЬКО в начале сессии (до первого model turn).
     */
    override fun restoreContext(history: List<ConversationMessage>) {
        if (history.isEmpty()) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turns", buildJsonArray {
                    for (entry in history) {
                        add(buildJsonObject {
                            put("role", entry.role)
                            put("parts", buildJsonArray {
                                add(buildJsonObject { put("text", entry.text) })
                            })
                        })
                    }
                })
                put("turnComplete", true)
            })
        }
        val raw = msg.toString()
        logger.d("CONTEXT RESTORE → ${history.size} messages (${raw.length} chars)")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  SERVER → CLIENT (parse)
    // ════════════════════════════════════════════════════════════

    private fun parseServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            // setupComplete — зелёный свет
            if (root.containsKey("setupComplete")) {
                logger.d("✓ SETUP COMPLETE")
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
                return
            }

            // toolCall
            root["toolCall"]?.jsonObject?.let { toolCall ->
                parseToolCall(toolCall)
                return
            }

            // toolCallCancellation
            root["toolCallCancellation"]?.jsonObject?.let { cancellation ->
                val ids = cancellation["ids"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                logger.d("TOOL_CALL_CANCELLATION: $ids")
                _events.tryEmit(GeminiEvent.ToolCallCancellation(ids))
                return
            }

            // sessionResumptionUpdate
            root["sessionResumptionUpdate"]?.jsonObject?.let { update ->
                val resumable = update["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
                val newHandle = update["newHandle"]?.jsonPrimitive?.content
                val lastConsumed = update["lastConsumedClientMessageIndex"]
                    ?.jsonPrimitive?.longOrNull

                if (newHandle != null && resumable) {
                    sessionHandle = newHandle
                    logger.d("SESSION_RESUMPTION: handle updated (resumable=$resumable)")
                    _events.tryEmit(
                        GeminiEvent.SessionHandleUpdate(
                            handle = newHandle,
                            resumable = resumable,
                            lastConsumedIndex = lastConsumed
                        )
                    )
                }
                return
            }

            // goAway
            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.content
                logger.d("GO_AWAY — server will close soon (timeLeft=$timeLeft)")
                _events.tryEmit(GeminiEvent.GoAway(timeLeft))
                return
            }

            // usageMetadata (токены)
            root["usageMetadata"]?.jsonObject?.let { usage ->
                val prompt = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                val resp = (usage["candidatesTokenCount"]
                    ?: usage["responseTokenCount"])?.jsonPrimitive?.intOrNull ?: 0
                val total = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                _events.tryEmit(
                    GeminiEvent.UsageMetadata(
                        promptTokens = prompt,
                        responseTokens = resp,
                        totalTokens = total
                    )
                )
            }

            // serverContent: транскрипции, аудио, текст, флаги
            val sc = root["serverContent"]?.jsonObject ?: run {
                if (logRawFrames) {
                    val preview = if (raw.length > 200) raw.take(200) + "…" else raw
                    logger.d("SERVER ← $preview")
                }
                return
            }

            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("USER: $text")
                    _events.tryEmit(GeminiEvent.InputTranscript(text))
                }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("GEMINI: $text")
                    _events.tryEmit(GeminiEvent.OutputTranscript(text))
                }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⚡ INTERRUPTED — barge-in")
                _events.tryEmit(GeminiEvent.Interrupted)
            }

            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⏹ TURN COMPLETE")
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("✅ GENERATION COMPLETE")
                _events.tryEmit(GeminiEvent.GenerationComplete)
            }

            sc["groundingMetadata"]?.jsonObject?.let { grounding ->
                logger.d("GROUNDING METADATA received")
                _events.tryEmit(GeminiEvent.GroundingMetadata(grounding.toString()))
            }

            // modelTurn.parts[]: аудио, текст
            val parts = sc["modelTurn"]?.jsonObject?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val obj = part.jsonObject

                obj["text"]?.jsonPrimitive?.content?.let { text ->
                    logger.d("MODEL_TEXT: $text")
                    _events.tryEmit(GeminiEvent.ModelText(text))
                }

                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            _events.tryEmit(GeminiEvent.AudioChunk(pcm))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.e("PARSE ERROR: ${e.message}", e)
        }
    }

    private fun parseToolCall(toolCall: JsonObject) {
        val functionCalls = toolCall["functionCalls"]?.jsonArray ?: run {
            logger.w("toolCall without functionCalls")
            return
        }

        val calls = functionCalls.map { fc ->
            val fcObj = fc.jsonObject
            val name = fcObj["name"]?.jsonPrimitive?.content ?: "unknown"
            val id = fcObj["id"]?.jsonPrimitive?.content ?: ""
            val argsObj = fcObj["args"]?.jsonObject
            val args = mutableMapOf<String, String>()
            argsObj?.forEach { (key, value) ->
                // ВАЖНО: JsonObject.toString()/JsonArray.toString() из kotlinx.serialization
                // возвращают ВАЛИДНЫЙ JSON, а не generic Any.toString()-мусор.
                // Так tool-handler может повторно распарсить вложенные структуры:
                // например, {"words": ["der", "die"]} → "["der","die"]"
                args[key] = when (value) {
                    is JsonPrimitive -> value.content
                    is JsonObject   -> value.toString()
                    is JsonArray    -> value.toString()
                    else            -> value.toString()
                }
            }
            logger.d("🔧 TOOL_CALL: $name(id=$id, $args)")
            FunctionCall(name, id, args)
        }

        _events.tryEmit(GeminiEvent.ToolCall(calls))
    }

    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1006 -> "[Abnormal Closure]"
        1007 -> "[Invalid Frame Payload — невалидный JSON или смешивание realtimeInput/clientContent]"
        1008 -> "[Policy Violation — модель недоступна ключу, либо неверная структура setup]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired — enable contextWindowCompression для продления]"
        4001 -> "[Gemini: Invalid setup — проверьте структуру setup]"
        4002 -> "[Gemini: Rate limited (429)]"
        4003 -> "[Gemini: Auth failed — неверный API ключ]"
        else -> "[Code $code]"
    }
}
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

/**
 * Клиент Gemini Live API v1beta.
 *
 * Структура setup строго по спецификации:
 *   setup:
 *     model
 *     generationConfig:
 *       temperature, topP, topK, maxOutputTokens, responseModalities,
 *       presencePenalty, frequencyPenalty,
 *       speechConfig (здесь!),
 *       thinkingConfig (здесь!),
 *       mediaResolution (здесь!)
 *     systemInstruction
 *     tools
 *     realtimeInputConfig
 *     inputAudioTranscription
 *     outputAudioTranscription
 *     sessionResumption
 *     contextWindowCompression
 */
class GeminiLiveClient(
    private val logger: AppLogger
) : LiveClient {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
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

    @Volatile
    private var closeCompletion: CompletableDeferred<Unit>? = null

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
    //  SETUP — официальная структура v1beta
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {

                // ─── Модель ───
                put("model", config.model)

                // ─── generationConfig (всё здесь!) ───
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

                    // speechConfig — ВНУТРИ generationConfig
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

                    // thinkingConfig — ВНУТРИ generationConfig
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", config.latencyProfile.thinkingLevel)
                        if (config.thinkingIncludeThoughts) {
                            put("includeThoughts", true)
                        }
                    })

                    // mediaResolution — ВНУТРИ generationConfig
                    if (config.mediaResolution.isNotBlank()) {
                        put("mediaResolution", config.mediaResolution)
                    }
                })

                // ─── systemInstruction ───
                if (config.systemInstruction.isNotBlank()) {
                    put("systemInstruction", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("text", config.systemInstruction)
                            })
                        })
                    })
                }

                // ─── realtimeInputConfig ───
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

                // ─── Транскрипция (корневой уровень setup) ───
                if (config.inputTranscription) {
                    put("inputAudioTranscription", buildJsonObject {})
                }
                if (config.outputTranscription) {
                    put("outputAudioTranscription", buildJsonObject {})
                }

                // ─── Session Resumption ───
                if (config.enableSessionResumption) {
                    put("sessionResumption", buildJsonObject {
                        config.sessionHandle?.let { put("handle", it) }
                        if (config.transparentResumption) {
                            put("transparent", true)
                        }
                    })
                }

                // ─── Context Window Compression ───
                if (config.enableContextCompression) {
                    put("contextWindowCompression", buildJsonObject {
                        if (config.compressionTriggerTokens > 0L) {
                            put("triggerTokens", config.compressionTriggerTokens)
                        }
                        put("slidingWindow", buildJsonObject {
                            if (config.compressionTargetTokens > 0L) {
                                put("targetTokens", config.compressionTargetTokens)
                            }
                        })
                    })
                }

                // ─── Tools ───
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
                                        add(buildFunctionDeclaration(decl))
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
        if (logRawFrames) logger.d("SETUP_RAW → ${raw.take(1500)}")
        trackSentFrame(raw)
        webSocket?.send(raw)
    }

    /**
     * Строит одно function declaration.
     * ВАЖНО: даже для функций без параметров — всегда отправляем
     *   parameters: {type: "object", properties: {}}
     * Это требование Gemini 3.1 при >5 функциях (иначе 1007).
     */
    private fun buildFunctionDeclaration(decl: com.learnde.app.domain.model.FunctionDeclarationConfig): JsonObject =
        buildJsonObject {
            put("name", decl.name)
            put("description", decl.description)
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
        }

    /**
     * Рекурсивно строит JSON Schema для одного параметра.
     * JSON Schema требует lowercase тип: "string", "array", "object".
     */
    private fun buildParameterSchema(param: ParameterConfig): JsonObject =
        buildJsonObject {
            val lowerType = param.type.lowercase()
            put("type", lowerType)
            if (param.description.isNotBlank()) {
                put("description", param.description)
            }
            if (param.enumValues.isNotEmpty()) {
                put("enum", buildJsonArray {
                    param.enumValues.forEach { add(JsonPrimitive(it)) }
                })
            }
            if (lowerType == "array" && param.items != null) {
                put("items", buildParameterSchema(param.items))
            }
            if (lowerType == "object" && param.properties.isNotEmpty()) {
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

            if (root.containsKey("setupComplete")) {
                logger.d("✓ SETUP COMPLETE")
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
                return
            }

            root["toolCall"]?.jsonObject?.let { toolCall ->
                parseToolCall(toolCall)
                return
            }

            root["toolCallCancellation"]?.jsonObject?.let { cancellation ->
                val ids = cancellation["ids"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                logger.d("TOOL_CALL_CANCELLATION: $ids")
                _events.tryEmit(GeminiEvent.ToolCallCancellation(ids))
                return
            }

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

            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeft = goAway["timeLeft"]?.jsonPrimitive?.content
                logger.d("GO_AWAY — server will close soon (timeLeft=$timeLeft)")
                _events.tryEmit(GeminiEvent.GoAway(timeLeft))
                return
            }

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
                args[key] = when (value) {
                    is JsonPrimitive -> value.content
                    is JsonObject    -> value.toString()
                    is JsonArray     -> value.toString()
                    else             -> value.toString()
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
        1007 -> "[Invalid Frame Payload — невалидный JSON / неизвестное поле в setup]"
        1008 -> "[Policy Violation — модель недоступна ключу либо неверная структура setup]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired — enable contextWindowCompression]"
        4001 -> "[Gemini: Invalid setup — проверьте структуру setup]"
        4002 -> "[Gemini: Rate limited (429)]"
        4003 -> "[Gemini: Auth failed — неверный API ключ]"
        else -> "[Code $code]"
    }
}
package com.codeextractor.app.data

import android.util.Base64
import com.codeextractor.app.domain.LiveClient
import com.codeextractor.app.domain.ToolResponse
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.FunctionCall
import com.codeextractor.app.domain.model.GeminiEvent
import com.codeextractor.app.domain.model.SessionConfig
import com.codeextractor.app.util.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
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

/**
 * Gemini Live API WebSocket клиент — полная реализация 2026.
 *
 * Поддерживает все возможности gemini-3.1-flash-live-preview:
 *  - Session resumption с handle (прозрачный reconnect)
 *  - Context window compression (sliding window)
 *  - Google Search grounding
 *  - audioStreamEnd для flush кеша
 *  - toolCallCancellation обработка
 *  - Usage metadata tracking
 *  - Полная конфигурация VAD
 *  - Полная generationConfig (temperature, topP, topK и т.д.)
 *  - historyConfig для корректного seeding context
 */
@Singleton
class GeminiLiveClient @Inject constructor(
    private val logger: AppLogger
) : LiveClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null

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

    // ════════════════════════════════════════════════════════════
    //  CONNECT / DISCONNECT
    // ════════════════════════════════════════════════════════════

    override suspend fun connect(apiKey: String, config: SessionConfig, logRaw: Boolean) {
        if (webSocket != null) disconnect()

        currentConfig = config
        logRawFrames = logRaw
        isReady = false

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
                logger.d("WS closed: $code ${describeCloseCode(code)} reason='$reason'")
                isReady = false
                _events.tryEmit(GeminiEvent.Disconnected(code, reason))
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val status = response?.code?.let { " (HTTP $it)" } ?: ""
                logger.e("WS failure$status: ${t.message}")
                isReady = false
                _events.tryEmit(GeminiEvent.ConnectionError(t.message ?: "Unknown error"))
            }
        })
    }

    override fun disconnect() {
        webSocket?.close(1000, "bye")
        webSocket = null
        isReady = false
    }

    // ════════════════════════════════════════════════════════════
    //  SETUP — полная конфигурация Gemini 3.1 Flash Live
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", config.model)

                // ── generationConfig ──
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive(config.responseModality))
                    })

                    // Temperature / sampling
                    put("temperature", config.temperature)
                    put("topP", config.topP)
                    if (config.topK > 0) put("topK", config.topK)
                    put("maxOutputTokens", config.maxOutputTokens)
                    if (config.presencePenalty != 0.0f) put("presencePenalty", config.presencePenalty)
                    if (config.frequencyPenalty != 0.0f) put("frequencyPenalty", config.frequencyPenalty)

                    // Speech
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

                    // Thinking
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", config.latencyProfile.thinkingLevel)
                    })
                })

                // ── System instruction ──
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", config.systemInstruction)
                        })
                    })
                })

                // ── VAD / Realtime input config ──
                put("realtimeInputConfig", buildJsonObject {
                    put("automaticActivityDetection", buildJsonObject {
                        put("disabled", !config.autoActivityDetection)
                        if (config.autoActivityDetection) {
                            if (config.vadStartSensitivity != 0.5f) {
                                put("startOfSpeechSensitivity", config.vadStartSensitivity)
                            }
                            if (config.vadEndSensitivity != 0.5f) {
                                put("endOfSpeechSensitivity", config.vadEndSensitivity)
                            }
                            if (config.vadSilenceTimeoutMs > 0) {
                                put("silenceTimeout", "${config.vadSilenceTimeoutMs}ms")
                            }
                        }
                    })
                })

                // ── Transcription ──
                if (config.inputTranscription) {
                    put("inputAudioTranscription", buildJsonObject {})
                }
                if (config.outputTranscription) {
                    put("outputAudioTranscription", buildJsonObject {})
                }

                // ── Session Resumption ──
                if (config.enableSessionResumption) {
                    put("sessionResumption", buildJsonObject {
                        if (config.sessionHandle != null) {
                            put("handle", config.sessionHandle)
                        }
                        if (config.transparentResumption) {
                            put("transparent", true)
                        }
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

                // ── Tools ──
                if (config.enableGoogleSearch) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("googleSearch", buildJsonObject {})
                        })
                    })
                }

                // ── History Config (для корректного seeding через clientContent) ──
                put("historyConfig", buildJsonObject {
                    put("initialHistoryInClientContent", true)
                })
            })
        }

        val raw = msg.toString()
        logger.d("SETUP → ${config.model} (${raw.length} chars)")
        webSocket?.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ════════════════════════════════════════════════════════════

    override fun sendAudio(pcmData: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
        webSocket?.send(raw)
    }

    override fun sendText(text: String) {
        if (!isReady) return
        // Gemini 3.1: текст через realtimeInput (не clientContent!)
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }
        logger.d("TEXT → (${text.length} chars)")
        webSocket?.send(msg.toString())
    }

    override fun sendAudioStreamEnd() {
        if (!isReady) return
        val msg = """{"realtimeInput":{"audioStreamEnd":true}}"""
        logger.d("AUDIO_STREAM_END →")
        webSocket?.send(msg)
    }

    override fun sendTurnComplete() {
        if (!isReady) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turnComplete", true)
            })
        }
        webSocket?.send(msg.toString())
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
        webSocket?.send(raw)
    }

    /**
     * Восстановление контекста — ТОЛЬКО при начале сессии.
     *
     * Gemini 3.1: clientContent с turns разрешён ТОЛЬКО для seeding
     * начальной истории (при historyConfig.initialHistoryInClientContent = true).
     * После первого model turn — 1007 error.
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
        webSocket?.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  SERVER → CLIENT (parse)
    // ════════════════════════════════════════════════════════════

    private fun parseServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            // ── setupComplete ──
            if (root.containsKey("setupComplete")) {
                logger.d("✓ SETUP COMPLETE")
                isReady = true
                _events.tryEmit(GeminiEvent.SetupComplete)
                return
            }

            // ── toolCall ──
            root["toolCall"]?.jsonObject?.let { toolCall ->
                parseToolCall(toolCall)
                return
            }

            // ── toolCallCancellation ──
            root["toolCallCancellation"]?.jsonObject?.let { cancellation ->
                val ids = cancellation["ids"]?.jsonArray
                    ?.map { it.jsonPrimitive.content }
                    ?: emptyList()
                logger.d("⚠ TOOL_CALL_CANCELLATION: $ids")
                _events.tryEmit(GeminiEvent.ToolCallCancellation(ids))
                return
            }

            // ── sessionResumptionUpdate ──
            root["sessionResumptionUpdate"]?.jsonObject?.let { update ->
                val resumable = update["resumable"]?.jsonPrimitive?.booleanOrNull ?: false
                val newHandle = update["newHandle"]?.jsonPrimitive?.content
                val lastConsumedIndex = update["lastConsumedClientMessageIndex"]
                    ?.jsonPrimitive?.longOrNull

                if (newHandle != null && resumable) {
                    sessionHandle = newHandle
                    logger.d("SESSION_RESUMPTION: handle updated (resumable=$resumable)")
                    _events.tryEmit(
                        GeminiEvent.SessionHandleUpdate(
                            handle = newHandle,
                            resumable = resumable,
                            lastConsumedIndex = lastConsumedIndex
                        )
                    )
                }
                return
            }

            // ── goAway ──
            root["goAway"]?.jsonObject?.let { goAway ->
                val timeLeftMs = goAway["timeLeft"]?.jsonPrimitive?.content
                logger.d("GO_AWAY — server will close soon (timeLeft=$timeLeftMs)")
                _events.tryEmit(GeminiEvent.GoAway(timeLeftMs))
                return
            }

            // ── usageMetadata ──
            root["usageMetadata"]?.jsonObject?.let { usage ->
                val promptTokens = usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                val responseTokens = (usage["candidatesTokenCount"]
                    ?: usage["responseTokenCount"])?.jsonPrimitive?.intOrNull ?: 0
                val totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
                _events.tryEmit(
                    GeminiEvent.UsageMetadata(
                        promptTokens = promptTokens,
                        responseTokens = responseTokens,
                        totalTokens = totalTokens
                    )
                )
                return
            }

            // ── serverContent ──
            val sc = root["serverContent"]?.jsonObject ?: run {
                if (logRawFrames) {
                    val preview = if (raw.length > 200) raw.take(200) + "…" else raw
                    logger.d("SERVER ← $preview")
                }
                return
            }

            // Транскрипции
            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("🎤 USER: $text")
                    _events.tryEmit(GeminiEvent.InputTranscript(text))
                }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    logger.d("🔊 GEMINI: $text")
                    _events.tryEmit(GeminiEvent.OutputTranscript(text))
                }

            // Barge-in / Interrupted
            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⚡ INTERRUPTED — barge-in")
                _events.tryEmit(GeminiEvent.Interrupted)
            }

            // Turn complete
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("⏹ TURN COMPLETE")
                _events.tryEmit(GeminiEvent.TurnComplete)
            }

            // Generation complete
            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                logger.d("✅ GENERATION COMPLETE")
                _events.tryEmit(GeminiEvent.GenerationComplete)
            }

            // Grounding metadata (Google Search results)
            sc["groundingMetadata"]?.jsonObject?.let { grounding ->
                logger.d("🔍 GROUNDING METADATA received")
                _events.tryEmit(GeminiEvent.GroundingMetadata(grounding.toString()))
            }

            // Audio / text parts — обрабатываем ВСЕ parts в одном событии
            val parts = sc["modelTurn"]?.jsonObject
                ?.get("parts") as? JsonArray ?: return

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

    private fun parseToolCall(toolCall: kotlinx.serialization.json.JsonObject) {
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
                args[key] = value.jsonPrimitive.content
            }
            logger.d("🔧 TOOL_CALL: $name($args)")
            FunctionCall(name, id, args)
        }

        _events.tryEmit(GeminiEvent.ToolCall(calls))
    }

    // ════════════════════════════════════════════════════════════
    //  UTILS
    // ════════════════════════════════════════════════════════════

    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1006 -> "[Abnormal Closure]"
        1007 -> "[Invalid Frame Payload — clientContent after model turn?]"
        1008 -> "[Policy Violation]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired (15 min)]"
        4001 -> "[Gemini: Invalid setup]"
        4002 -> "[Gemini: Rate limited]"
        4003 -> "[Gemini: Auth failed]"
        else -> "[Code $code]"
    }
}

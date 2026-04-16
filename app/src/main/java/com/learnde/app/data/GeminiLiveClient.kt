// ═══════════════════════════════════════════════════════════════════
//  ПОЛНАЯ ЗАМЕНА
//  Путь: app/src/main/java/com/codeextractor/app/data/GeminiLiveClient.kt
//
//  КРИТИЧЕСКИЕ ИСПРАВЛЕНИЯ (почему висело в жёлтом):
//
//  ✗ БЫЛО:   speechConfig  — на верхнем уровне setup
//  ✓ СТАЛО:  speechConfig  ВНУТРИ generationConfig
//
//  ✗ БЫЛО:   thinkingConfig — на верхнем уровне setup
//  ✓ СТАЛО:  thinkingConfig ВНУТРИ generationConfig
//
//  Согласно официальной спецификации Gemini Live API v1beta
//  (ai.google.dev/api/live) структура setup такова:
//
//  setup: {
//    model,
//    generationConfig: {
//      responseModalities, temperature, topP, topK,
//      maxOutputTokens, presencePenalty, frequencyPenalty,
//      speechConfig,          ← здесь
//      thinkingConfig,        ← здесь
//      mediaResolution
//    },
//    systemInstruction,
//    realtimeInputConfig,       ← верхний уровень (VAD)
//    inputAudioTranscription,   ← верхний уровень
//    outputAudioTranscription,  ← верхний уровень
//    sessionResumption,         ← верхний уровень
//    contextWindowCompression,  ← верхний уровень
//    tools,                     ← верхний уровень
//    historyConfig              ← верхний уровень (Gemini 3.1)
//  }
//
//  Прочие улучшения:
//   + pingInterval 30s (вместо 0) — держит NAT/соединение живым
//   + replay=0 для _events (не перешлёт устаревший SetupComplete при resubscribe)
//   + AEC-контроль перенесён в AudioEngine (здесь не требуется)
//   + realtimeInput.audio использует корректный MIME 'audio/pcm;rate=16000'
//   + parseToolCall корректно обрабатывает args как JsonObject (не строки)
// ═══════════════════════════════════════════════════════════════════
package com.learnde.app.data

import android.util.Base64
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.FunctionCall
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
                val desc = describeCloseCode(code)
                logger.d("WS closed: $code $desc reason='$reason'")
                isReady = false
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
    //  SETUP — строго по спецификации Gemini Live v1beta (2026)
    // ════════════════════════════════════════════════════════════

    private fun sendSetup(config: SessionConfig) {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {

                // ── Модель ──
                put("model", config.model)

                // ── generationConfig: ВСЕ параметры генерации,
                //    ВКЛЮЧАЯ speechConfig и thinkingConfig ──
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
                    // Gemini 3.1: thinkingLevel ∈ {minimal, low, medium, high}
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", config.latencyProfile.thinkingLevel)
                    })
                })

                // ── System Instruction (верхний уровень) ──
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
                                            // ВСЕГДА передаём parameters — Gemini 3.1 требует корректную схему
                                            // даже для функций без аргументов (иначе сервер может вернуть INVALID_ARGUMENT).
                                            put("parameters", buildJsonObject {
                                                put("type", "OBJECT")
                                                put("properties", buildJsonObject {
                                                    for ((pName, pConfig) in decl.parameters) {
                                                        put(pName, buildJsonObject {
                                                            put("type", pConfig.type)
                                                            put("description", pConfig.description)
                                                        })
                                                    }
                                                })
                                            })
                                        })
                                    }
                                })
                            })
                        }
                    })
                }

                // ── historyConfig: обязателен для gemini-3.1-flash-live
                //    если собираемся seed-ить историю через clientContent ──
                put("historyConfig", buildJsonObject {
                    put("initialHistoryInClientContent", true)
                })
            })
        }

        val raw = msg.toString()
        logger.d("SETUP → ${config.model} (${raw.length} chars)")
        if (logRawFrames) logger.d("SETUP_RAW → ${raw.take(800)}")
        webSocket?.send(raw)
    }

    // ════════════════════════════════════════════════════════════
    //  CLIENT → SERVER
    // ════════════════════════════════════════════════════════════

    override fun sendAudio(pcmData: ByteArray) {
        if (!isReady) return
        val b64 = Base64.encodeToString(pcmData, Base64.NO_WRAP)
        // Минимальная JSON-строка — быстро, без парсинга
        val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=${SessionConfig.INPUT_SAMPLE_RATE}"}}}"""
        webSocket?.send(raw)
    }

    override fun sendText(text: String) {
        if (!isReady) return
        // Gemini 3.1: после первого model-turn текст идёт через realtimeInput.text
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
        val raw = """{"realtimeInput":{"audioStreamEnd":true}}"""
        logger.d("AUDIO_STREAM_END →")
        webSocket?.send(raw)
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
     * Seed начальной истории.
     * Для Gemini 3.1 допустимо ТОЛЬКО до первого model turn
     * и требует historyConfig.initialHistoryInClientContent=true в setup.
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
            // args может содержать вложенные JSON-значения; приводим к строке безопасно
            argsObj?.forEach { (key, value) ->
                args[key] = when (value) {
                    is kotlinx.serialization.json.JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }
            logger.d("🔧 TOOL_CALL: $name($args)")
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
        1007 -> "[Invalid Frame Payload — невалидный JSON или использование clientContent после model turn]"
        1008 -> "[Policy Violation — модель недоступна ключу, либо неверная структура setup]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired (15 min)]"
        4001 -> "[Gemini: Invalid setup — проверьте структуру setup]"
        4002 -> "[Gemini: Rate limited (429)]"
        4003 -> "[Gemini: Auth failed — неверный API ключ]"
        else -> "[Code $code]"
    }
}

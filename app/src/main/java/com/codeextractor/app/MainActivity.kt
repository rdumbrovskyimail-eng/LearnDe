package com.codeextractor.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.codeextractor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * ============================================================================
 *  Gemini 3.1 Flash Live — Production Voice Client (Android/Kotlin)
 * ============================================================================
 *
 *  Model:    gemini-3.1-flash-live-preview
 *  API:      Gemini Live API (WebSocket, v1beta)
 *  Audio:    16kHz PCM input → 24kHz PCM output
 *  Features: Voice chat, function calling, barge-in, context restore
 *
 *  Migration from 2.5 Flash Native Audio:
 *  - Endpoint: v1alpha → v1beta (v1alpha only for ephemeral tokens)
 *  - Model: gemini-2.5-flash-native-audio-preview → gemini-3.1-flash-live-preview
 *  - Thinking: thinkingBudget → thinkingLevel (minimal/low/medium/high)
 *  - Async function calling: NOT supported (sync only)
 *  - Proactive audio / affective dialogue: NOT supported
 *  - Session limits: audio-only 15min, audio+video 2min
 *  - Output tokens: 8K → 64K
 *  - Context window: 128K tokens
 * ============================================================================
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GeminiLive"

        // Gemini 3.1 Flash Live — production model
        private const val MODEL = "models/gemini-3.1-flash-live-preview"

        private const val HOST = "generativelanguage.googleapis.com"

        // v1beta — основной endpoint для 3.1 с API key
        // v1alpha нужен ТОЛЬКО для ephemeral tokens (BidiGenerateContentConstrained)
        private const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val AUDIO_PERMISSION_CODE = 200
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private val COLOR_READY = android.graphics.Color.parseColor("#4CAF50")

        private const val PLAYBACK_QUEUE_CAPACITY = 256
        private const val JITTER_PRE_BUFFER_CHUNKS = 3
        private const val JITTER_TIMEOUT_MS = 150L

        private const val MAX_CONTEXT_MESSAGES = 10

        private const val PREFS_FILE = "secure_prefs"
        private const val PREFS_KEY_API = "gemini_api_key"
    }

    // ====================================================================
    //  STATE
    // ====================================================================

    private sealed interface SessionState {
        data object Disconnected : SessionState
        data object Connecting : SessionState
        data object Connected : SessionState
        data object Ready : SessionState
        data object Recording : SessionState
    }

    private val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)

    private lateinit var binding: ActivityMainBinding
    private val json = Json { ignoreUnknownKeys = true }

    private var apiKey: String = ""

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS) // Keep-alive для длинных сессий
        .build()

    private var webSocket: WebSocket? = null
    private var setupComplete = CompletableDeferred<Unit>()

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var recordJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private val audioPlaybackChannel = Channel<ByteArray>(PLAYBACK_QUEUE_CAPACITY)
    private var playbackJob: Job? = null

    @Volatile
    private var awaitingPlaybackDrain = false

    @Volatile
    private var isFirstBatch = true

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val logLines = ArrayDeque<String>(500)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { saveLogToUri(it) } }

    // ====================================================================
    //  CONVERSATION HISTORY (context restore after reconnect)
    // ====================================================================

    private data class ConversationMessage(val role: String, val text: String)

    private val conversationHistory = ArrayDeque<ConversationMessage>(MAX_CONTEXT_MESSAGES + 2)

    private fun addToHistory(role: String, text: String) {
        synchronized(conversationHistory) {
            conversationHistory.addLast(ConversationMessage(role, text))
            while (conversationHistory.size > MAX_CONTEXT_MESSAGES) {
                conversationHistory.removeFirst()
            }
        }
    }

    // ====================================================================
    //  TOOL DECLARATIONS (function calling)
    // ====================================================================

    private data class ToolParameter(
        val name: String,
        val type: String,
        val description: String,
        val required: Boolean = true
    )

    private data class FunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: List<ToolParameter> = emptyList()
    )

    /**
     * Список объявленных функций.
     * Пустой список → tools не отправляются в setup.
     * ВАЖНО: В Gemini 3.1 Flash Live function calling только СИНХРОННЫЙ.
     * Модель ждёт toolResponse перед продолжением ответа.
     */
    private val toolDeclarations: List<FunctionDeclaration> = listOf(
        // Раскомментируйте для активации:
        // FunctionDeclaration(
        //     name = "get_current_time",
        //     description = "Returns the current date and time",
        //     parameters = emptyList()
        // ),
        // FunctionDeclaration(
        //     name = "get_weather",
        //     description = "Returns weather for a given city",
        //     parameters = listOf(
        //         ToolParameter("city", "string", "City name"),
        //         ToolParameter("units", "string", "celsius or fahrenheit", required = false)
        //     )
        // ),
    )

    // ====================================================================
    //  LIFECYCLE
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeInsets()
        log("=== APP STARTED (Gemini 3.1 Flash Live) ===")

        apiKey = loadApiKey()

        setupUI()
        observeState()
        startAudioPlaybackLoop()

        if (apiKey.isNotEmpty()) {
            binding.keyInputLayout.visibility = View.GONE
            binding.keyDivider.visibility = View.GONE
            connectWebSocket()
        } else {
            log("⚠ API ключ не задан — введите ключ и нажмите OK")
            binding.startButton.isEnabled = false
            binding.stopButton.isEnabled = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        releaseAudioRecord()
        audioPlaybackChannel.close()
        playbackJob?.cancel()
        releaseAudioTrack()
        disconnectWebSocket()
        client.dispatcher.cancelAll()
    }

    // ====================================================================
    //  UI
    // ====================================================================

    private fun setupEdgeToEdgeInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val density = resources.displayMetrics.density
            val pad12 = (12 * density).toInt()
            val pad16 = (16 * density).toInt()

            binding.headerLayout.setPadding(pad16, systemBars.top + pad12, pad16, pad12)
            binding.controlsLayout.setPadding(pad16, pad12, pad16, systemBars.bottom + pad12)
            insets
        }
    }

    private fun setupUI() {
        binding.startButton.setOnClickListener { requestPermissionAndRecord() }
        binding.stopButton.setOnClickListener { stopRecording() }
        binding.saveLogButton.setOnClickListener {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            saveLogLauncher.launch("gemini_log_$ts.txt")
        }

        binding.keyOkButton.setOnClickListener {
            val input = binding.apiKeyEditText.text?.toString()?.trim().orEmpty()
            if (input.length < 20) {
                Toast.makeText(this, "Ключ слишком короткий", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveApiKey(input)
            apiKey = input
            binding.keyInputLayout.visibility = View.GONE
            binding.keyDivider.visibility = View.GONE
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.apiKeyEditText.windowToken, 0)
            log("✓ API ключ сохранён")
            connectWebSocket()
        }

        binding.apiKeyEditText.setOnEditorActionListener { _, _, _ ->
            binding.keyOkButton.performClick()
            true
        }

        if (apiKey.isNotEmpty()) {
            binding.apiKeyEditText.hint = "Ключ сохранён (tap для замены)"
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            sessionState.collectLatest { state ->
                val (icon, color, label) = when (state) {
                    SessionState.Disconnected -> Triple(
                        android.R.drawable.presence_busy,
                        android.graphics.Color.RED,
                        "Disconnected"
                    )
                    SessionState.Connecting -> Triple(
                        android.R.drawable.presence_busy,
                        android.graphics.Color.RED,
                        "Connecting…"
                    )
                    SessionState.Connected -> Triple(
                        android.R.drawable.presence_online,
                        android.graphics.Color.YELLOW,
                        "Setting up…"
                    )
                    SessionState.Ready -> Triple(
                        android.R.drawable.presence_online,
                        COLOR_READY,
                        "Ready — press Start"
                    )
                    SessionState.Recording -> Triple(
                        android.R.drawable.presence_audio_online,
                        android.graphics.Color.GREEN,
                        "● Recording"
                    )
                }
                binding.statusIndicator.setImageResource(icon)
                binding.statusIndicator.setColorFilter(color)
                binding.statusText.text = label

                binding.startButton.isEnabled = state == SessionState.Ready
                binding.stopButton.isEnabled = state == SessionState.Recording
            }
        }
    }

    private fun appendLogToUI(line: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val text = synchronized(logLines) {
                logLines.addLast(line)
                while (logLines.size > 500) logLines.removeFirst()
                logLines.joinToString("\n")
            }
            binding.chatLog.text = if (text.length > 3000) text.takeLast(3000) else text
            binding.logScrollView.post {
                binding.logScrollView.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    // ====================================================================
    //  LOGGING
    // ====================================================================

    private fun log(msg: String) {
        val line = "[${timeFormat.format(Date())}] $msg"
        Log.d(TAG, line)
        appendLogToUI(line)
    }

    private fun saveLogToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val text = synchronized(logLines) { logLines.joinToString("\n") }
                    out.write(text.toByteArray())
                }
                log("Log saved")
            }.onFailure { log("Save error: ${it.message}") }
        }
    }

    // ====================================================================
    //  1. WEBSOCKET
    // ====================================================================

    private fun buildWsUrl(): String =
        "wss://$HOST/$WS_PATH?key=$apiKey"

    private fun connectWebSocket() {
        if (apiKey.isEmpty()) {
            log("⚠ connectWebSocket() — ключ не задан")
            return
        }
        if (sessionState.value != SessionState.Disconnected) return
        sessionState.value = SessionState.Connecting

        setupComplete.completeExceptionally(
            kotlinx.coroutines.CancellationException("Session reset")
        )
        setupComplete = CompletableDeferred()

        log("Connecting to $MODEL via v1beta…")
        val request = Request.Builder().url(buildWsUrl()).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: okhttp3.Response) {
                log("WS opened (${response.code})")
                sessionState.value = SessionState.Connected
                reconnectAttempt = 0
                sendSetup()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                log("BINARY frame ${bytes.size} bytes")
                try {
                    handleServerMessage(bytes.utf8())
                } catch (e: Exception) {
                    log("Binary frame decode error: ${e.message}")
                }
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                log("WS closed: $code ${describeCloseCode(code)} reason='$reason'")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                val status = response?.code?.let { " (HTTP $it)" } ?: ""
                log("WS failure$status: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away — server shutdown or session timeout]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1005 -> "[No Status Code]"
        1006 -> "[Abnormal Closure — no close frame]"
        1007 -> "[Invalid Payload]"
        1008 -> "[Policy Violation — check model name/endpoint]"
        1009 -> "[Message Too Big]"
        1011 -> "[Internal Server Error]"
        1012 -> "[Service Restart]"
        1013 -> "[Try Again Later — overloaded]"
        1014 -> "[Bad Gateway]"
        1015 -> "[TLS Handshake Failure]"
        4000 -> "[Gemini: Session expired (15 min limit)]"
        4001 -> "[Gemini: Invalid setup message]"
        4002 -> "[Gemini: Rate limited]"
        4003 -> "[Gemini: Authentication failed]"
        else -> "[Code $code]"
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "bye")
        webSocket = null
        sessionState.value = SessionState.Disconnected
    }

    private fun handleDisconnect() {
        releaseAudioRecord()
        sessionState.value = SessionState.Disconnected
        scheduleReconnect()
    }

    @Synchronized
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        echoCanceler?.let {
            runCatching { it.enabled = false; it.release() }
        }
        echoCanceler = null
        audioRecord?.let {
            runCatching { it.stop(); it.release() }
        }
        audioRecord = null
    }

    private fun scheduleReconnect() {
        if (reconnectAttempt >= MAX_RECONNECT_ATTEMPTS) {
            log("Max reconnect attempts reached ($MAX_RECONNECT_ATTEMPTS)")
            lifecycleScope.launch(Dispatchers.Main) {
                Toast.makeText(this@MainActivity, "Connection lost", Toast.LENGTH_LONG).show()
            }
            return
        }
        val delayMs = RECONNECT_BASE_DELAY_MS * (1L shl reconnectAttempt)
        reconnectAttempt++
        log("Reconnect #$reconnectAttempt in ${delayMs}ms")
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            connectWebSocket()
        }
    }

    // ====================================================================
    //  2. SETUP (BidiGenerateContentSetup) — Gemini 3.1 Flash Live
    // ====================================================================

    /**
     * Setup для Gemini 3.1 Flash Live:
     *
     *  ┌─ "setup"
     *  │   ├─ "model": "models/gemini-3.1-flash-live-preview"
     *  │   ├─ "generationConfig"
     *  │   │   ├─ "responseModalities": ["AUDIO"]
     *  │   │   ├─ "speechConfig": { voiceConfig: { prebuiltVoiceConfig: { voiceName } } }
     *  │   │   └─ "thinkingConfig": { "thinkingLevel": "minimal" }
     *  │   ├─ "systemInstruction"
     *  │   │   └─ "parts": [{ "text": "..." }]
     *  │   ├─ "realtimeInputConfig"
     *  │   │   └─ "automaticActivityDetection": { "disabled": false }
     *  │   └─ "tools" (if toolDeclarations is not empty)
     *  └─
     *
     *  Gemini 3.1 отличия от 2.5:
     *  - thinkingLevel вместо thinkingBudget
     *  - "minimal" = наименьшая латентность (по умолчанию для Live)
     *  - Нет async function calling (только синхронный)
     *  - Нет proactive audio / affective dialogue
     */
    private fun sendSetup() {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", MODEL)

                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive("AUDIO"))
                    })

                    // Голос — Aoede (женский, чистый). Варианты: Puck, Charon, Kore, Fenrir, Aoede, Leda, Orus, Zephyr
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", "Aoede")
                            })
                        })
                    })

                    // Gemini 3.1: thinkingLevel вместо thinkingBudget
                    // minimal = минимальная латентность (оптимально для голосового чата)
                    // low/medium/high = больше reasoning, но выше латентность
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", "minimal")
                    })
                })

                // Системная инструкция
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put(
                                "text",
                                "Ты русскоязычный голосовой ассистент. " +
                                "Всегда отвечай только на русском языке. " +
                                "Слушай и понимай русскую речь. " +
                                "Отвечай кратко и по делу, не более 2-3 предложений, " +
                                "если пользователь не просит подробного ответа."
                            )
                        })
                    })
                })

                // VAD: серверная детекция голосовой активности
                put("realtimeInputConfig", buildJsonObject {
                    put("automaticActivityDetection", buildJsonObject {
                        put("disabled", false)
                    })
                })

                // Транскрипция входа и выхода
                put("inputAudioTranscription", buildJsonObject {})
                put("outputAudioTranscription", buildJsonObject {})

                // Tools (если объявлены)
                if (toolDeclarations.isNotEmpty()) {
                    put("tools", buildJsonArray {
                        add(buildJsonObject {
                            put("functionDeclarations", buildJsonArray {
                                for (decl in toolDeclarations) {
                                    add(buildJsonObject {
                                        put("name", decl.name)
                                        put("description", decl.description)
                                        if (decl.parameters.isNotEmpty()) {
                                            put("parameters", buildJsonObject {
                                                put("type", "object")
                                                put("properties", buildJsonObject {
                                                    for (param in decl.parameters) {
                                                        put(param.name, buildJsonObject {
                                                            put("type", param.type)
                                                            put("description", param.description)
                                                        })
                                                    }
                                                })
                                                val required = decl.parameters
                                                    .filter { it.required }
                                                    .map { it.name }
                                                if (required.isNotEmpty()) {
                                                    put("required", buildJsonArray {
                                                        required.forEach { add(JsonPrimitive(it)) }
                                                    })
                                                }
                                            })
                                        }
                                    })
                                }
                            })
                        })
                    })
                }
            })
        }

        val raw = msg.toString()
        log("SETUP → $MODEL (${raw.length} chars)")
        webSocket?.send(raw)
    }

    // ====================================================================
    //  3. CLIENT MESSAGES — audio, text, toolResponse, context
    // ====================================================================

    private fun sendAudioChunk(b64: String) {
        if (sessionState.value != SessionState.Recording) return
        val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=$INPUT_SAMPLE_RATE"}}}"""
        webSocket?.send(raw)
    }

    private fun sendTextMessage(text: String) {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return

        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }
        val raw = msg.toString()
        log("TEXT → $raw")
        webSocket?.send(raw)
    }

    private fun sendClientContent(text: String, turnComplete: Boolean = true) {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return

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
                put("turnComplete", turnComplete)
            })
        }
        val raw = msg.toString()
        log("CLIENT_CONTENT → (${raw.length} chars)")
        webSocket?.send(raw)
    }

    private fun restoreConversationContext() {
        val history = synchronized(conversationHistory) {
            conversationHistory.toList()
        }
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
        log("CONTEXT RESTORE → ${history.size} messages (${raw.length} chars)")
        webSocket?.send(raw)
    }

    private fun sendToolResponse(functionResponses: List<ToolFunctionResponse>) {
        val msg = buildJsonObject {
            put("toolResponse", buildJsonObject {
                put("functionResponses", buildJsonArray {
                    for (resp in functionResponses) {
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
        log("TOOL_RESPONSE → (${raw.length} chars)")
        webSocket?.send(raw)
    }

    private data class ToolFunctionResponse(
        val name: String,
        val id: String,
        val result: String
    )

    private fun dispatchToolFunction(name: String, args: Map<String, String>): String {
        return when (name) {
            // "get_current_time" -> {
            //     val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            //     """{"time":"$now"}"""
            // }
            // "get_weather" -> {
            //     val city = args["city"] ?: "Unknown"
            //     """{"city":"$city","temp":"22°C","condition":"Sunny"}"""
            // }
            else -> {
                log("⚠ Unknown tool: $name")
                """{"error":"Function '$name' not implemented"}"""
            }
        }
    }

    // ====================================================================
    //  4. SERVER MESSAGES — parse & route
    // ====================================================================

    private fun handleServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            // ─── Setup complete ────────────────────────────────────
            if (root.containsKey("setupComplete")) {
                log("✓ SETUP COMPLETE ($MODEL)")
                sessionState.value = SessionState.Ready
                setupComplete.complete(Unit)

                lifecycleScope.launch(Dispatchers.IO) {
                    restoreConversationContext()
                }
                return
            }

            // ─── Tool call ─────────────────────────────────────────
            root["toolCall"]?.jsonObject?.let { toolCall ->
                handleToolCall(toolCall)
                return
            }

            // ─── Server content ────────────────────────────────────
            val sc = root["serverContent"]?.jsonObject ?: run {
                if (root.containsKey("goAway")) {
                    log("GO_AWAY — server will close soon, resetting reconnect counter")
                    reconnectAttempt = 0
                } else {
                    val preview = if (raw.length > 200) raw.take(200) + "…" else raw
                    log("SERVER ← $preview")
                }
                return
            }

            // ─── Транскрипции + история ────────────────────────────
            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    log("🎤 USER: $text")
                    addToHistory("user", text)
                }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    log("🔊 GEMINI: $text")
                    addToHistory("model", text)
                }

            // ─── Barge-in ──────────────────────────────────────────
            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⚡ INTERRUPTED — flushing playback")
                awaitingPlaybackDrain = false
                flushPlaybackQueue()
            }

            // ─── Turn complete ─────────────────────────────────────
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⏹ TURN COMPLETE")
                awaitingPlaybackDrain = true
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("✅ GENERATION COMPLETE")
            }

            // ─── Audio data ────────────────────────────────────────
            val parts = sc["modelTurn"]?.jsonObject
                ?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val obj = part.jsonObject

                obj["text"]?.jsonPrimitive?.content?.let { log("MODEL_TEXT: $it") }

                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            awaitingPlaybackDrain = false
                            val sent = audioPlaybackChannel.trySend(pcm)
                            if (sent.isFailure) {
                                log("⚠ Playback queue full — dropping oldest")
                                audioPlaybackChannel.tryReceive()
                                audioPlaybackChannel.trySend(pcm)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("PARSE ERROR: ${e.message}")
        }
    }

    private fun handleToolCall(toolCall: kotlinx.serialization.json.JsonObject) {
        val functionCalls = toolCall["functionCalls"]?.jsonArray ?: run {
            log("⚠ toolCall without functionCalls")
            return
        }

        val responses = mutableListOf<ToolFunctionResponse>()

        for (fc in functionCalls) {
            val fcObj = fc.jsonObject
            val name = fcObj["name"]?.jsonPrimitive?.content ?: "unknown"
            val id = fcObj["id"]?.jsonPrimitive?.content ?: ""
            val argsObj = fcObj["args"]?.jsonObject

            val args = mutableMapOf<String, String>()
            argsObj?.forEach { (key, value) ->
                args[key] = value.jsonPrimitive.content
            }

            log("🔧 TOOL_CALL: $name($args)")
            val result = dispatchToolFunction(name, args)
            log("🔧 TOOL_RESULT: $name → $result")

            responses.add(ToolFunctionResponse(name, id, result))
        }

        // Gemini 3.1: СИНХРОННЫЙ tool calling — модель ждёт ответ
        sendToolResponse(responses)
    }

    private fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        audioTrack?.apply {
            pause()
            flush()
            play()
        }
    }

    // ====================================================================
    //  5. AUDIO INPUT
    // ====================================================================

    private fun requestPermissionAndRecord() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERMISSION_CODE
            )
        } else {
            startRecording()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_PERMISSION_CODE) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startRecording() {
        val currentState = sessionState.value
        if (currentState == SessionState.Recording) return

        lifecycleScope.launch {
            if (currentState != SessionState.Ready) {
                log("Waiting for setupComplete…")
                runCatching { setupComplete.await() }
                    .onFailure {
                        log("Setup not completed — cannot record")
                        return@launch
                    }
            }
            if (sessionState.value != SessionState.Ready) {
                log("Session no longer ready (${sessionState.value}) — aborting")
                return@launch
            }
            launchAudioCapture()
        }
    }

    @Suppress("MissingPermission")
    private fun launchAudioCapture() {
        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf == AudioRecord.ERROR || minBuf == AudioRecord.ERROR_BAD_VALUE) {
            log("AudioRecord.getMinBufferSize failed: $minBuf")
            return
        }

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord init failed")
                recorder.release()
                return
            }

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true
                        log("AEC enabled (sessionId=${recorder.audioSessionId})")
                    }
                } catch (e: Exception) {
                    log("AEC init error: ${e.message}")
                }
            }

            try {
                recorder.startRecording()
            } catch (e: Exception) {
                log("startRecording() failed: ${e.message}")
                recorder.release()
                return
            }

            audioRecord = recorder
            sessionState.value = SessionState.Recording
            log("🎙 Recording started (buf=$minBuf, rate=$INPUT_SAMPLE_RATE)")

            recordJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(minBuf)
                val byteBuffer = ByteBuffer
                    .allocate(minBuf * 2)
                    .order(ByteOrder.LITTLE_ENDIAN)
                val rawBytes = byteBuffer.array()

                while (isActive && sessionState.value == SessionState.Recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        byteBuffer.clear()
                        byteBuffer.asShortBuffer().put(buffer, 0, read)
                        val b64 = Base64.encodeToString(rawBytes, 0, read * 2, Base64.NO_WRAP)
                        sendAudioChunk(b64)
                    }
                }
            }
        } catch (e: SecurityException) {
            log("SECURITY: ${e.message}")
        } catch (e: Exception) {
            log("AUDIO INIT ERROR: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (sessionState.value != SessionState.Recording) return
        releaseAudioRecord()
        if (sessionState.value == SessionState.Disconnected) {
            log("🎙 Recording stopped (disconnect)")
            return
        }
        sendTurnComplete()
        sessionState.value = SessionState.Ready
        log("🎙 Recording stopped")
    }

    private fun sendTurnComplete() {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return

        val msg = buildJsonObject {
            put("clientContent", buildJsonObject {
                put("turnComplete", true)
            })
        }
        webSocket?.send(msg.toString())
    }

    // ====================================================================
    //  6. AUDIO OUTPUT
    // ====================================================================

    private fun startAudioPlaybackLoop() {
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {

            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                log("⚠ Device does not support ${OUTPUT_SAMPLE_RATE}Hz output!")
                return@launch
            }

            log("AudioTrack: ${OUTPUT_SAMPLE_RATE}Hz (minBuf=$minBuf)")

            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(OUTPUT_SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()

            audioTrack = track
            track.play()
            log("Speaker ready (rate=$OUTPUT_SAMPLE_RATE)")

            for (chunk in audioPlaybackChannel) {
                if (!isActive) break

                // Jitter pre-buffer: накапливаем первые чанки перед воспроизведением
                if (isFirstBatch) {
                    val preBuffer = mutableListOf(chunk)
                    repeat(JITTER_PRE_BUFFER_CHUNKS - 1) {
                        try {
                            val next = withTimeoutOrNull(JITTER_TIMEOUT_MS) {
                                audioPlaybackChannel.receive()
                            }
                            if (next != null) {
                                preBuffer.add(next)
                            }
                        } catch (_: ClosedReceiveChannelException) {
                            return@repeat
                        } catch (_: Exception) {
                            return@repeat
                        }
                    }

                    for (buffered in preBuffer) {
                        track.write(buffered, 0, buffered.size)
                    }
                    isFirstBatch = false
                    log("Jitter pre-buffer: ${preBuffer.size} chunks")
                } else {
                    track.write(chunk, 0, chunk.size)
                }

                // Turn complete: доигрываем буфер, не flush
                if (awaitingPlaybackDrain && audioPlaybackChannel.isEmpty) {
                    log("⏹ Playback drained")
                    awaitingPlaybackDrain = false
                    isFirstBatch = true
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let {
            runCatching { it.stop(); it.release() }
        }
        audioTrack = null
    }

    // ====================================================================
    //  7. SECURE STORAGE
    // ====================================================================

    private fun getPrefs() =
        getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun loadApiKey(): String =
        getPrefs().getString(PREFS_KEY_API, "").orEmpty()

    private fun saveApiKey(key: String) {
        getPrefs().edit().putString(PREFS_KEY_API, key).apply()
    }

    // ====================================================================
    //  8. SECURITY CHECKS (optional — uncomment in onCreate if needed)
    // ====================================================================

    @Suppress("unused")
    private fun performSecurityChecks() {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            Log.e(TAG, "SECURITY: debugger detected")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su",
            "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su"
        )
        if (suPaths.any { java.io.File(it).exists() }) {
            Log.w(TAG, "SECURITY: root detected")
        }

        val tracerPid = runCatching {
            java.io.File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter("TracerPid:")?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0)

        if (tracerPid > 0) {
            Log.e(TAG, "SECURITY: TracerPid=$tracerPid")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
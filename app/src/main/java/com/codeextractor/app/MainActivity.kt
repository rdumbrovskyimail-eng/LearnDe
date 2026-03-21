package com.codeextractor.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 *  ТЕСТОВЫЙ СТЕНД v6 — 12 вариантов подключения к Gemini Live API
 * ============================================================================
 *
 *  Цель: определить какой формат setup-сообщения принимает сервер.
 *
 *  ┌──────┬────────────┬───────────┬──────────────┬──────────────┬──────────┬─────────┐
 *  │      │ Root key   │ Structure │ speechConfig │ Transcripts  │ Modality │ Ping    │
 *  ├──────┼────────────┼───────────┼──────────────┼──────────────┼──────────┼─────────┤
 *  │ V1   │ "setup"    │ nested    │ ✅ Aoede     │ ✅ {}        │ AUDIO    │ ❌      │
 *  │ V2   │ "config"   │ flat      │ ✅ Aoede     │ ✅ {}        │ AUDIO    │ ❌      │
 *  │ V3   │ "setup"    │ nested    │ ❌           │ ❌           │ AUDIO    │ ❌      │
 *  │ V4   │ "config"   │ flat      │ ❌           │ ❌           │ AUDIO    │ ❌      │
 *  │ V5   │ "setup"    │ nested    │ ✅ Aoede     │ ❌           │ AUDIO    │ ❌      │
 *  │ V6   │ "setup"    │ nested    │ ❌           │ ✅ {}        │ AUDIO    │ ❌      │
 *  │ V7   │ "config"   │ flat      │ ❌           │ ✅ {}        │ A+T      │ ❌      │
 *  │ V8   │ "setup"    │ nested    │ ❌           │ ❌           │ A+T      │ ❌      │
 *  │ V9   │ "setup"    │ nested    │ ❌           │ ❌           │ AUDIO    │ ✅ 15s  │
 *  │ V10  │ "config"   │ flat      │ ❌           │ ❌           │ AUDIO    │ ✅ 15s  │
 *  │ V11  │ "setup"    │ nested    │ ❌           │ ❌           │ AUDIO    │ ❌ BARE │
 *  │ V12  │ "setup"    │ nested    │ ❌           │ ❌           │ AUDIO    │ ✅ BARE │
 *  └──────┴────────────┴───────────┴──────────────┴──────────────┴──────────┴─────────┘
 *
 *  V1–V8: без pingInterval.  V9–V12: с pingInterval(15s).
 *  V3/V9 и V4/V10 — одинаковый JSON, разница только в ping.
 *  V11/V12 — абсолютный минимум (только model + AUDIO, без VAD).
 *
 *  Логика диагностики:
 *  ─────────────────────
 *  V1 зависает, V3 работает → speechConfig или transcriptions{} ломают native audio
 *  V5 зависает, V3 работает → speechConfig — виновник
 *  V6 зависает, V3 работает → transcriptions{} — виновник
 *  V3 зависает, V4 работает → "setup" не принимается, нужен "config"
 *  V3 зависает, V9 работает → нужен pingInterval (балансировщик рвёт)
 *  V3 зависает, V11 работает → VAD (realtimeInputConfig) — виновник
 *  V11 зависает → проблема в model/API key/сети
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GeminiLive"
        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val WS_PATH_ALPHA =
            "ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"
        private const val MODEL_2_0 = "models/gemini-2.0-flash-exp"

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val AUDIO_PERMISSION_CODE = 200
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private val COLOR_READY = android.graphics.Color.parseColor("#4CAF50")
        private const val PLAYBACK_QUEUE_CAPACITY = 256
        private const val JITTER_PRE_BUFFER_CHUNKS = 3
        private const val MAX_CONTEXT_MESSAGES = 10
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

    /** Текущий активный вариант подключения (0 = не выбран) */
    private var activeVariant = 0

    private lateinit var binding: ActivityMainBinding
    private val json = Json { ignoreUnknownKeys = true }

    // Без ping — для V1–V8
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    // С ping — для V9–V12 (Gemini рекомендация: удерживает WS через балансировщики)
    private val clientWithPing = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
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

    private data class ConversationMessage(val role: String, val text: String)
    private val conversationHistory = ArrayDeque<ConversationMessage>(MAX_CONTEXT_MESSAGES + 2)

    private fun addToHistory(role: String, text: String) {
        synchronized(conversationHistory) {
            conversationHistory.addLast(ConversationMessage(role, text))
            while (conversationHistory.size > MAX_CONTEXT_MESSAGES) conversationHistory.removeFirst()
        }
    }

    // ====================================================================
    //  TOOL CALLING — data classes + declarations
    // ====================================================================

    private data class ToolParameter(
        val name: String,
        val type: String,         // "string", "integer", "boolean", "number"
        val description: String,
        val required: Boolean = true
    )

    private data class FunctionDeclaration(
        val name: String,
        val description: String,
        val parameters: List<ToolParameter> = emptyList()
    )

    private data class ToolFunctionResponse(
        val name: String,
        val id: String,
        val result: String
    )

    /**
     * Список объявленных функций.
     * Пустой список → tools не отправляются в setup → toolCall не приходит.
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
        //         ToolParameter("city", "string", "City name, e.g. 'Berlin'"),
        //         ToolParameter("units", "string", "celsius or fahrenheit", required = false)
        //     )
        // ),
    )

    /**
     * Диспетчер вызовов инструментов.
     * Добавляйте ветки when для каждой функции из toolDeclarations.
     */
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
                log("⚠ Unknown tool function: $name")
                """{"error":"Function '$name' not implemented"}"""
            }
        }
    }

    // ====================================================================
    //  VARIANT DESCRIPTIONS (для UI)
    // ====================================================================

    private val variantDescriptions = mapOf(
        1 to "V1: setup + nested + speechConfig(Aoede) + transcriptions{} + VAD",
        2 to "V2: config + flat + speechConfig(Aoede) + transcriptions{} + VAD",
        3 to "V3: setup + nested + NO speech + NO transcriptions + VAD",
        4 to "V4: config + flat + NO speech + NO transcriptions + VAD",
        5 to "V5: setup + nested + speechConfig(Aoede) + NO transcriptions + VAD",
        6 to "V6: setup + nested + NO speech + transcriptions{} + VAD",
        7 to "V7: config + flat + NO speech + AUDIO+TEXT + transcriptions{} + VAD",
        8 to "V8: setup + nested + NO speech + AUDIO+TEXT + NO transcriptions + VAD",
        9 to "V9: =V3 + pingInterval🏓 (setup minimal + ping)",
        10 to "V10: =V4 + pingInterval🏓 (config minimal + ping)",
        11 to "V11: BARE MIN — setup + AUDIO only + nothing else",
        12 to "V12: BARE MIN + pingInterval🏓 — setup + AUDIO only + ping",
        // ── Row 2: Endpoint / Model / Casing diagnostics ──
        13 to "V13: v1ALPHA + setup + bare min (endpoint test)",
        14 to "V14: v1beta + gemini-2.0-flash-exp (model test)",
        15 to "V15: v1beta + snake_case (generation_config, response_modalities)",
        16 to "V16: v1beta + TEXT only (is AUDIO forbidden for this key?)",
        17 to "V17: 🏆 GOLDEN = v1alpha + 2.0-flash-exp + snake_case",
        18 to "V18: V17 + system_instruction (required by some models)",
        19 to "V19: v1beta + config + flat + systemInstruction (Grok rec)",
        20 to "V20: v1ALPHA + config + flat + bare min",
        21 to "V21: v1ALPHA + current native-audio model (is it endpoint?)",
        22 to "V22: v1beta + 2.0-flash-exp + config flat (model+format)",
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
        log("=== APP STARTED === (select V1–V8 to connect)")

        setupUI()
        observeState()
        startAudioPlaybackLoop()
        // НЕ подключаемся автоматически — ждём выбор варианта
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
        clientWithPing.dispatcher.cancelAll()
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
        // ─── Variant buttons ───────────────────────────────────
        binding.btnV1.setOnClickListener { startVariant(1) }
        binding.btnV2.setOnClickListener { startVariant(2) }
        binding.btnV3.setOnClickListener { startVariant(3) }
        binding.btnV4.setOnClickListener { startVariant(4) }
        binding.btnV5.setOnClickListener { startVariant(5) }
        binding.btnV6.setOnClickListener { startVariant(6) }
        binding.btnV7.setOnClickListener { startVariant(7) }
        binding.btnV8.setOnClickListener { startVariant(8) }
        binding.btnV9.setOnClickListener { startVariant(9) }
        binding.btnV10.setOnClickListener { startVariant(10) }
        binding.btnV11.setOnClickListener { startVariant(11) }
        binding.btnV12.setOnClickListener { startVariant(12) }
        binding.btnV13.setOnClickListener { startVariant(13) }
        binding.btnV14.setOnClickListener { startVariant(14) }
        binding.btnV15.setOnClickListener { startVariant(15) }
        binding.btnV16.setOnClickListener { startVariant(16) }
        binding.btnV17.setOnClickListener { startVariant(17) }
        binding.btnV18.setOnClickListener { startVariant(18) }
        binding.btnV19.setOnClickListener { startVariant(19) }
        binding.btnV20.setOnClickListener { startVariant(20) }
        binding.btnV21.setOnClickListener { startVariant(21) }
        binding.btnV22.setOnClickListener { startVariant(22) }

        // ─── Control buttons ───────────────────────────────────
        binding.startButton.setOnClickListener { requestPermissionAndRecord() }
        binding.stopButton.setOnClickListener { stopRecording() }
        binding.saveLogButton.setOnClickListener {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            saveLogLauncher.launch("gemini_log_$ts.txt")
        }
    }

    /**
     * Запуск варианта: отключиться от текущего → подключиться с новым setup.
     */
    private fun startVariant(variant: Int) {
        activeVariant = variant
        reconnectJob?.cancel()
        reconnectAttempt = 0

        val desc = variantDescriptions[variant] ?: "Unknown"
        binding.variantDescription.text = desc
        log("═══════════════════════════════════════")
        log("TESTING: $desc")
        log("═══════════════════════════════════════")

        // Отключаемся, если были подключены
        disconnectWebSocket()

        // Подключаемся с новым вариантом
        lifecycleScope.launch {
            delay(300) // Дать время на cleanup
            connectWebSocket()
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            sessionState.collectLatest { state ->
                val (icon, color, label) = when (state) {
                    SessionState.Disconnected -> Triple(
                        android.R.drawable.presence_busy,
                        android.graphics.Color.RED,
                        if (activeVariant == 0) "Select variant" else "Disconnected (V$activeVariant)"
                    )
                    SessionState.Connecting -> Triple(
                        android.R.drawable.presence_busy,
                        android.graphics.Color.RED,
                        "V$activeVariant: Connecting…"
                    )
                    SessionState.Connected -> Triple(
                        android.R.drawable.presence_online,
                        android.graphics.Color.YELLOW,
                        "V$activeVariant: Setting up…"
                    )
                    SessionState.Ready -> Triple(
                        android.R.drawable.presence_online,
                        COLOR_READY,
                        "V$activeVariant: Ready ✓ — press Start"
                    )
                    SessionState.Recording -> Triple(
                        android.R.drawable.presence_audio_online,
                        android.graphics.Color.GREEN,
                        "V$activeVariant: ● Recording"
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

    private fun buildWsUrl(): String {
        val key = BuildConfig.GEMINI_API_KEY
        // V13, V17, V18, V20, V21 используют v1alpha endpoint
        val useAlpha = activeVariant in listOf(13, 17, 18, 20, 21)
        val path = if (useAlpha) WS_PATH_ALPHA else WS_PATH
        return "wss://$HOST/$path?key=$key"
    }

    private fun connectWebSocket() {
        if (sessionState.value != SessionState.Disconnected) return
        sessionState.value = SessionState.Connecting

        setupComplete.completeExceptionally(
            kotlinx.coroutines.CancellationException("Session reset")
        )
        setupComplete = CompletableDeferred()

        val useAlpha = activeVariant in listOf(13, 17, 18, 20, 21)
        val usePing = activeVariant >= 9 && activeVariant <= 12
        log("Connecting… ${if (useAlpha) "v1alpha" else "v1beta"} ${if (usePing) "+ping" else ""}")
        val request = Request.Builder().url(buildWsUrl()).build()

        val activeClient = if (usePing) clientWithPing else client

        webSocket = activeClient.newWebSocket(request, object : WebSocketListener() {

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
        1000 -> "[Normal]"
        1001 -> "[Going Away]"
        1002 -> "[Protocol Error]"
        1006 -> "[Abnormal — no close frame]"
        1007 -> "[Invalid Payload]"
        1008 -> "[Policy Violation]"
        1011 -> "[Internal Server Error]"
        1013 -> "[Try Again Later]"
        4000 -> "[Gemini: Session expired]"
        4001 -> "[Gemini: Invalid setup]"
        4002 -> "[Gemini: Rate limited]"
        4003 -> "[Gemini: Auth failed]"
        else -> "[code $code]"
    }

    private fun disconnectWebSocket() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "bye")
        webSocket = null
        sessionState.value = SessionState.Disconnected
    }

    private fun handleDisconnect() {
        releaseAudioRecord()
        sessionState.value = SessionState.Disconnected
        // НЕ делаем auto-reconnect в тестовом режиме — пусть юзер сам выберет вариант
    }

    @Synchronized
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        echoCanceler?.let { runCatching { it.enabled = false; it.release() } }
        echoCanceler = null
        audioRecord?.let { runCatching { it.stop(); it.release() } }
        audioRecord = null
    }

    // ====================================================================
    //  2. SETUP — 8 ВАРИАНТОВ
    // ====================================================================

    /**
     * Диспетчер: вызывает нужный builder в зависимости от activeVariant.
     */
    private fun sendSetup() {
        val raw = when (activeVariant) {
            1 -> buildV1().toString()
            2 -> buildV2().toString()
            3 -> buildV3().toString()
            4 -> buildV4().toString()
            5 -> buildV5().toString()
            6 -> buildV6().toString()
            7 -> buildV7().toString()
            8 -> buildV8().toString()
            9 -> buildV3().toString()   // V9 = V3 JSON + pingInterval (client level)
            10 -> buildV4().toString()  // V10 = V4 JSON + pingInterval (client level)
            11 -> buildV11().toString() // Bare minimum
            12 -> buildV11().toString() // V12 = V11 JSON + pingInterval (client level)
            13 -> buildV11().toString() // V13 = V11 JSON but v1alpha endpoint
            14 -> buildV14().toString()
            15 -> buildV15().toString()
            16 -> buildV16().toString()
            17 -> buildV17().toString()
            18 -> buildV18().toString()
            19 -> buildV19().toString()
            20 -> buildV20().toString() // v1alpha + config flat
            21 -> buildV21().toString() // v1alpha + current model
            22 -> buildV22().toString()
            else -> {
                log("ERROR: No variant selected!")
                return
            }
        }
        log("V$activeVariant SETUP → (${raw.length} chars)")
        log("JSON: ${if (raw.length > 400) raw.take(400) + "…" else raw}")
        webSocket?.send(raw)
    }

    /**
     * Вспомогательный метод: генерирует tools JSON для вставки в setup.
     * Возвращает null если toolDeclarations пуст.
     */
    private fun buildToolsJsonArray() = if (toolDeclarations.isNotEmpty()) {
        buildJsonArray {
            add(buildJsonObject {
                put("functionDeclarations", buildJsonArray {
                    for (fn in toolDeclarations) {
                        add(buildJsonObject {
                            put("name", fn.name)
                            put("description", fn.description)
                            if (fn.parameters.isNotEmpty()) {
                                put("parameters", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        for (param in fn.parameters) {
                                            put(param.name, buildJsonObject {
                                                put("type", param.type)
                                                put("description", param.description)
                                            })
                                        }
                                    })
                                    val required = fn.parameters.filter { it.required }.map { it.name }
                                    if (required.isNotEmpty()) {
                                        put("required", buildJsonArray {
                                            for (r in required) add(JsonPrimitive(r))
                                        })
                                    }
                                })
                            }
                        })
                    }
                })
            })
        }
    } else null

    // ── V1: "setup" + nested + speechConfig + transcriptions{} + VAD ──
    private fun buildV1() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", "Aoede")
                        })
                    })
                })
            })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
            buildToolsJsonArray()?.let { put("tools", it) }
        })
    }

    // ── V2: "config" + flat + speechConfig + transcriptions{} + VAD ──
    private fun buildV2() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("speechConfig", buildJsonObject {
                put("voiceConfig", buildJsonObject {
                    put("prebuiltVoiceConfig", buildJsonObject {
                        put("voiceName", "Aoede")
                    })
                })
            })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // ── V3: "setup" + nested + NO speech + NO transcriptions + VAD ──
    // (Gemini 3.1 fix: native audio не принимает speechConfig и пустые {})
    private fun buildV3() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
            buildToolsJsonArray()?.let { put("tools", it) }
        })
    }

    // ── V4: "config" + flat + NO speech + NO transcriptions + VAD ──
    private fun buildV4() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // ── V5: "setup" + nested + speechConfig + NO transcriptions + VAD ──
    // (Изолирует: transcriptions{} ломают или нет?)
    private fun buildV5() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
                put("speechConfig", buildJsonObject {
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", "Aoede")
                        })
                    })
                })
            })
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // ── V6: "setup" + nested + NO speech + transcriptions{} + VAD ──
    // (Изолирует: speechConfig ломает или нет?)
    private fun buildV6() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // ── V7: "config" + flat + NO speech + AUDIO+TEXT + transcriptions{} + VAD ──
    // (Gemini 3.1: нужен "TEXT" для транскрипций?)
    private fun buildV7() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL)
            put("responseModalities", buildJsonArray {
                add(JsonPrimitive("AUDIO"))
                add(JsonPrimitive("TEXT"))
            })
            put("inputAudioTranscription", buildJsonObject {})
            put("outputAudioTranscription", buildJsonObject {})
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // ── V8: "setup" + nested + NO speech + AUDIO+TEXT + NO transcriptions + VAD ──
    private fun buildV8() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray {
                    add(JsonPrimitive("AUDIO"))
                    add(JsonPrimitive("TEXT"))
                })
            })
            put("realtimeInputConfig", buildJsonObject {
                put("automaticActivityDetection", buildJsonObject { put("disabled", false) })
            })
        })
    }

    // V9 = buildV3() JSON + pingInterval (разница только в OkHttpClient)
    // V10 = buildV4() JSON + pingInterval (разница только в OkHttpClient)

    // ── V11: АБСОЛЮТНЫЙ МИНИМУМ — только model + AUDIO, ничего больше ──
    // (Тест: работает ли сервер с голым setup вообще?)
    private fun buildV11() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // V12 = buildV11() JSON + pingInterval (разница только в OkHttpClient)
    // V13 = buildV11() JSON but v1alpha endpoint (разница в buildWsUrl)

    // ── V14: v1beta + другая модель gemini-2.0-flash-exp ──
    // (Тест: текущая модель недоступна для этого ключа?)
    private fun buildV14() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL_2_0)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // ── V15: snake_case вместо camelCase ──
    // (Gemini 3.1: сервер может требовать strict snake_case для raw JSON)
    private fun buildV15() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generation_config", buildJsonObject {
                put("response_modalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // ── V16: TEXT only — проверка: запрещён ли AUDIO для этого ключа? ──
    private fun buildV16() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("TEXT")) })
            })
        })
    }

    // ── V17: 🏆 GOLDEN PATH ──
    // v1alpha (через buildWsUrl) + gemini-2.0-flash-exp + snake_case
    // (Gemini 3.1: "точь-в-точь как в офф. Python SDK")
    private fun buildV17() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL_2_0)
            put("generation_config", buildJsonObject {
                put("response_modalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // ── V18: V17 + system_instruction ──
    // (Grok + Gemini 3.1: без инструкции модель может молча закрыть)
    private fun buildV18() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL_2_0)
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", "You are a helpful voice assistant.") })
                })
            })
            put("generation_config", buildJsonObject {
                put("response_modalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // ── V19: v1beta + "config" flat + systemInstruction (Grok rec) ──
    // (Grok: config + systemInstruction — рабочая комбинация в примерах 2026)
    private fun buildV19() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("systemInstruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", "You are a helpful assistant.") })
                })
            })
        })
    }

    // ── V20: v1ALPHA + "config" flat + bare min ──
    // (Изолирует: v1alpha + config вместо setup)
    private fun buildV20() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
        })
    }

    // ── V21: v1ALPHA + текущая native-audio модель + setup nested ──
    // (Изолирует: endpoint v1alpha + правильная модель = работает?)
    private fun buildV21() = buildJsonObject {
        put("setup", buildJsonObject {
            put("model", MODEL)
            put("generationConfig", buildJsonObject {
                put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            })
        })
    }

    // ── V22: v1beta + gemini-2.0-flash-exp + "config" flat ──
    // (Изолирует: модель 2.0 + config формат на beta)
    private fun buildV22() = buildJsonObject {
        put("config", buildJsonObject {
            put("model", MODEL_2_0)
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
        })
    }

    // ====================================================================
    //  3. CLIENT MESSAGES
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
            put("realtimeInput", buildJsonObject { put("text", text) })
        }
        val raw = msg.toString()
        log("TEXT → $raw")
        webSocket?.send(raw)
    }

    private fun sendTurnComplete() {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return
        val msg = buildJsonObject {
            put("clientContent", buildJsonObject { put("turnComplete", true) })
        }
        webSocket?.send(msg.toString())
        log("→ turnComplete")
    }

    /**
     * Контекст/история через clientContent (НЕ потоковая отправка).
     */
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
        log("CLIENT_CONTENT → $raw")
        webSocket?.send(raw)
    }

    /**
     * Отправка toolResponse — ответ на toolCall от сервера.
     */
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

    /**
     * Обработка toolCall от сервера: выполнить функции → отправить toolResponse.
     */
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
            argsObj?.forEach { (key, value) -> args[key] = value.jsonPrimitive.content }
            log("🔧 TOOL_CALL: $name($args)")
            val result = dispatchToolFunction(name, args)
            log("🔧 TOOL_RESULT: $name → $result")
            responses.add(ToolFunctionResponse(name, id, result))
        }
        sendToolResponse(responses)
    }

    private fun restoreConversationContext() {
        val history = synchronized(conversationHistory) { conversationHistory.toList() }
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
        log("CONTEXT RESTORE → ${history.size} msgs")
        webSocket?.send(msg.toString())
    }

    // ====================================================================
    //  4. SERVER MESSAGES
    // ====================================================================

    private fun handleServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                log("✓ SETUP COMPLETE — V$activeVariant WORKS!")
                sessionState.value = SessionState.Ready
                setupComplete.complete(Unit)

                lifecycleScope.launch(Dispatchers.IO) {
                    restoreConversationContext()
                    delay(300)
                    sendTextMessage("Hello, say something")
                }
                return
            }

            root["toolCall"]?.jsonObject?.let { toolCall ->
                handleToolCall(toolCall)
                return
            }

            val sc = root["serverContent"]?.jsonObject ?: run {
                if (root.containsKey("goAway")) {
                    log("GO_AWAY — server closing session")
                    reconnectAttempt = 0
                } else {
                    val preview = if (raw.length > 200) raw.take(200) + "…(${raw.length})" else raw
                    log("SERVER ← $preview")
                }
                return
            }

            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🎤 USER: $it"); addToHistory("user", it) }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🔊 GEMINI: $it"); addToHistory("model", it) }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⚡ INTERRUPTED")
                awaitingPlaybackDrain = false
                flushPlaybackQueue()
            }

            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⏹ TURN COMPLETE")
                awaitingPlaybackDrain = true
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("✅ GENERATION COMPLETE")
            }

            val parts = sc["modelTurn"]?.jsonObject?.get("parts") as? JsonArray ?: return

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

    private fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true
        audioTrack?.apply { pause(); flush(); play() }
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
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startRecording()
            else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startRecording() {
        val currentState = sessionState.value
        if (currentState == SessionState.Recording) return

        lifecycleScope.launch {
            if (currentState != SessionState.Ready) {
                log("Waiting for setupComplete…")
                runCatching { setupComplete.await() }.onFailure {
                    log("Setup not completed — cannot record")
                    return@launch
                }
            }
            if (sessionState.value != SessionState.Ready) {
                log("Session no longer ready — aborting")
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
            log("AudioRecord.getMinBufferSize failed: $minBuf"); return
        }

        try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord init failed"); recorder.release(); return
            }

            if (AcousticEchoCanceler.isAvailable()) {
                try {
                    echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply {
                        enabled = true; log("AEC enabled")
                    }
                } catch (e: Exception) { log("AEC error: ${e.message}") }
            }

            try { recorder.startRecording() } catch (e: Exception) {
                log("startRecording failed: ${e.message}"); recorder.release(); return
            }

            audioRecord = recorder
            sessionState.value = SessionState.Recording
            log("🎙 Recording started")

            recordJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(minBuf)
                val byteBuffer = ByteBuffer.allocate(minBuf * 2).order(ByteOrder.LITTLE_ENDIAN)
                val rawBytes = byteBuffer.array()

                while (isActive && sessionState.value == SessionState.Recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        byteBuffer.clear()
                        byteBuffer.asShortBuffer().put(buffer, 0, read)
                        sendAudioChunk(Base64.encodeToString(rawBytes, 0, read * 2, Base64.NO_WRAP))
                    }
                }
            }
        } catch (e: SecurityException) { log("SECURITY: ${e.message}")
        } catch (e: Exception) { log("AUDIO ERROR: ${e.message}") }
    }

    private fun stopRecording() {
        if (sessionState.value != SessionState.Recording) return
        releaseAudioRecord()
        if (sessionState.value == SessionState.Disconnected) {
            log("🎙 Stopped (disconnected)"); return
        }
        sendTurnComplete()
        sessionState.value = SessionState.Ready
        log("🎙 Recording stopped")
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
                log("⚠ ${OUTPUT_SAMPLE_RATE}Hz not supported (minBuf=$minBuf)"); return@launch
            }
            log("AudioTrack: ${OUTPUT_SAMPLE_RATE}Hz OK (minBuf=$minBuf)")

            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(OUTPUT_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2).build()

            audioTrack = track
            track.play()
            log("Speaker ready")

            for (chunk in audioPlaybackChannel) {
                if (!isActive) break

                if (isFirstBatch) {
                    val preBuffer = mutableListOf(chunk)
                    repeat(JITTER_PRE_BUFFER_CHUNKS - 1) {
                        try {
                            val next = withTimeoutOrNull(150L) { audioPlaybackChannel.receive() }
                            if (next != null) preBuffer.add(next)
                        } catch (_: ClosedReceiveChannelException) { return@repeat
                        } catch (_: Exception) { return@repeat }
                    }
                    for (b in preBuffer) track.write(b, 0, b.size)
                    isFirstBatch = false
                    log("Jitter buffer: ${preBuffer.size} chunks")
                } else {
                    track.write(chunk, 0, chunk.size)
                }

                if (awaitingPlaybackDrain && audioPlaybackChannel.isEmpty) {
                    log("⏹ Playback drained")
                    awaitingPlaybackDrain = false
                    isFirstBatch = true
                }
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let { runCatching { it.stop(); it.release() } }
        audioTrack = null
    }
}

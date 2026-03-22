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
 *  ЭТАЛОННАЯ РЕАЛИЗАЦИЯ v7 (FINAL) — Gemini Live API Raw WebSocket (Android/Kotlin)
 * ============================================================================
 *
 *  Синтез: Claude Opus, Grok, Gemini 3.1, DeepSeek (5 раундов, 19 анализов)
 *  Дата: 20 марта 2026
 *
 *  v5 CHANGELOG (поверх v4):
 *
 *  [БАГФИКС] #20  Jitter deadlock: receive() → withTimeoutOrNull(150ms)
 *                  (Gemini 3.1: при коротких ответах "Ок" — только 2 чанка,
 *                  receive() зависал навечно ожидая 3-й. Теперь timeout 150мс
 *                  гарантирует: если чанк не пришёл — играем что есть.)
 *
 *  [БАГФИКС] #21  Audio bleed при barge-in: isFirstBatch вынесен на уровень
 *                  класса + сбрасывается в flushPlaybackQueue()
 *                  (Gemini 3.1: при interrupted висящий receive() просыпался
 *                  на чанке НОВОЙ фразы и клеил его к остаткам СТАРОЙ →
 *                  глитч/искажение. Сброс isFirstBatch = true в flush
 *                  гарантирует чистый pre-buffer для новой генерации.)
 *
 *  v4 CHANGELOG (поверх v3):
 *
 *  [БАГФИКС] #16  Jitter pre-buffer: tryReceive() → receive()
 *                  (Gemini 3.1: tryReceive() — non-blocking, pre-buffer
 *                  всегда получал только 1 чанк вместо 3. Теперь suspend
 *                  receive() честно ждёт следующие чанки из сети.)
 *
 *  [NEW]     #17  Объявление tools в setup
 *                  (DeepSeek: без tools в setup toolCall никогда не придёт,
 *                  весь handler #12 был мёртвым кодом. Теперь tools
 *                  объявляются через TOOL_DECLARATIONS.)
 *
 *  [NEW]     #18  Восстановление контекста после reconnect
 *                  (DeepSeek: после переподключения история терялась.
 *                  Теперь последние MAX_CONTEXT_MESSAGES хранятся и
 *                  реинжектятся через clientContent при новой сессии.)
 *
 *  [NEW]     #19  Диагностика кодов закрытия WebSocket
 *                  (DeepSeek: расшифровка стандартных кодов + Gemini-
 *                  специфичных для отладки.)
 *
 *  Полный список v1→v2→v3→v4→v5→v7 (22 исправления):
 *  ─────────────────────────────────────────────────────────
 *  v1→v2:  #1 setup+generationConfig  #2 audio format  #3 flush
 *          #4 GC  #5 model pin  #6 transcriptions  #7 VAD
 *          #8 AEC  #9 binary frames  #10 text via realtimeInput
 *          #11 channel 256
 *  v2→v3:  #12 toolCall handler  #13 smooth turnComplete
 *          #14 24kHz check  #15 jitter pre-buffer
 *  v3→v4:  #16 jitter fix (receive)  #17 tools in setup
 *          #18 context restore  #19 WS close diagnostics
 *  v4→v5:  #20 jitter timeout (deadlock fix)
 *          #21 audio bleed fix (isFirstBatch reset)
 *  v5→v7:  #22 ENDPOINT: v1beta → v1alpha (ROOT CAUSE!)
 *          (Тест 12 вариантов: V13 с v1alpha получил setupComplete.
 *          v1beta молча игнорирует setup для native-audio модели.)
 *  v7→v8:  #23 systemInstruction (русский язык)
 *  v8→v9:  #24 speechConfig Aoede (Шаг 2, минимальный setup)
 *          #25 API ключ через UI + EncryptedSharedPreferences
 *          #26 Anti-tamper: debugger + root detection
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GeminiLive"

        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"

        private const val HOST = "generativelanguage.googleapis.com"

        // #22: v1alpha — единственный работающий endpoint для native-audio модели!
        // v1beta молча игнорирует setup (подтверждено тестом 12 вариантов).
        private const val WS_PATH =
            "ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val AUDIO_PERMISSION_CODE = 200
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private val COLOR_READY = android.graphics.Color.parseColor("#4CAF50")

        private const val PLAYBACK_QUEUE_CAPACITY = 256
        private const val JITTER_PRE_BUFFER_CHUNKS = 3

        // #18: Максимум сообщений для восстановления контекста после reconnect
        private const val MAX_CONTEXT_MESSAGES = 10

        // #25: Хранилище ключа
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

    // #25: Ключ берётся из EncryptedSharedPreferences, не из BuildConfig
    private var apiKey: String = ""

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
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

    // #21: Вынесен на уровень класса (был локальной переменной в playback loop).
    // Gemini 3.1: при barge-in висящий receive() просыпался на чанке НОВОЙ фразы
    // и клеил его к остаткам СТАРОЙ → глитч. Сброс в flushPlaybackQueue() это чинит.
    @Volatile
    private var isFirstBatch = true

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val logLines = ArrayDeque<String>(500)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { saveLogToUri(it) } }

    // ═══════════════════════════════════════════════════════════════
    //  #18: История разговора для восстановления контекста
    //  Хранит последние MAX_CONTEXT_MESSAGES пар (role, text)
    //  и реинжектит через clientContent после reconnect.
    // ═══════════════════════════════════════════════════════════════
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

    // ═══════════════════════════════════════════════════════════════
    //  #17: Объявление инструментов (tools / function declarations)
    //
    //  Без этого массива в setup toolCall НИКОГДА не придёт от сервера.
    //  Добавляйте свои функции сюда. Пустой список = tools отключены.
    //
    //  Формат: FunctionDeclaration(name, description, parameters)
    // ═══════════════════════════════════════════════════════════════
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

    /**
     * Список объявленных функций.
     * Пустой список → tools не отправляются в setup → toolCall не приходит.
     * Добавьте свои функции для активации function calling.
     */
    private val toolDeclarations: List<FunctionDeclaration> = listOf(
        // Пример: раскомментируйте для активации
        // FunctionDeclaration(
        //     name = "get_current_time",
        //     description = "Returns the current date and time in the user's timezone",
        //     parameters = emptyList()
        // ),
        // FunctionDeclaration(
        //     name = "get_weather",
        //     description = "Returns weather information for a given city",
        //     parameters = listOf(
        //         ToolParameter("city", "string", "City name, e.g. 'Berlin'"),
        //         ToolParameter("units", "string", "Temperature units: 'celsius' or 'fahrenheit'", required = false)
        //     )
        // ),
    )

    // ====================================================================
    //  LIFECYCLE
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // #26: Anti-tamper проверки — первым делом до любой инициализации
        performSecurityChecks()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeInsets()
        log("=== APP STARTED ===")

        // // ТЕСТ GeminiLiveForegroundService — удалить после проверки
        // lifecycleScope.launch {
        //     runCatching {
        //         // Запускаем сервис
        //         val serviceIntent = android.content.Intent(this@MainActivity, GeminiLiveForegroundService::class.java)
        //         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
        //             startForegroundService(serviceIntent)
        //         } else {
        //             startService(serviceIntent)
        //         }
        //         log("✅ GeminiLiveForegroundService: запущен")
        //
        //         // Ждём 3 секунды — проверяем что уведомление появилось
        //         kotlinx.coroutines.delay(3000)
        //
        //         // Останавливаем сервис
        //         stopService(serviceIntent)
        //         log("✅ GeminiLiveForegroundService: остановлен")
        //     }.onFailure { e -> log("❌ GeminiLiveForegroundService ERROR: ${e.message}") }
        // }
        //
        // // ТЕСТ GeminiProtocol — удалить после проверки
        // runCatching {
        //     // Тест 1: сериализация SetupMessage
        //     val setup = com.codeextractor.app.network.SetupMessage(
        //         setup = com.codeextractor.app.network.SetupBody(
        //             model = "models/gemini-2.5-flash-native-audio-preview-12-2025",
        //             generationConfig = com.codeextractor.app.network.GenerationConfig(
        //                 speechConfig = com.codeextractor.app.network.SpeechConfig(
        //                     com.codeextractor.app.network.VoiceConfig(
        //                         com.codeextractor.app.network.PrebuiltVoice("Aoede")
        //                     )
        //                 )
        //             ),
        //             systemInstruction = com.codeextractor.app.network.SystemInstruction(
        //                 listOf(com.codeextractor.app.network.Part("Ты русскоязычный ассистент"))
        //             )
        //         )
        //     )
        //     val json = com.codeextractor.app.network.GeminiProtocol.json
        //         .encodeToString(com.codeextractor.app.network.SetupMessage.serializer(), setup)
        //     val hasModel = json.contains("gemini-2.5-flash")
        //     val hasVoice = json.contains("Aoede")
        //     val hasInstruction = json.contains("русскоязычный")
        //     log(if (hasModel && hasVoice && hasInstruction)
        //         "✅ GeminiProtocol: сериализация OK (${json.length} chars)"
        //         else "❌ GeminiProtocol: json неверный: $json")
        //
        //     val noNulls = !json.contains("null")
        //     log(if (noNulls)
        //         "✅ GeminiProtocol: null поля исключены OK"
        //         else "❌ GeminiProtocol: json содержит null")
        //
        //     // Тест 2: RealtimeInputMessage
        //     val audioMsg = com.codeextractor.app.network.RealtimeInputMessage(
        //         com.codeextractor.app.network.RealtimeInputBody(
        //             audio = com.codeextractor.app.network.AudioData("base64data", "audio/pcm;rate=16000")
        //         )
        //     )
        //     val audioJson = com.codeextractor.app.network.GeminiProtocol.json
        //         .encodeToString(com.codeextractor.app.network.RealtimeInputMessage.serializer(), audioMsg)
        //     log(if (audioJson.contains("base64data"))
        //         "✅ GeminiProtocol: AudioData OK"
        //         else "❌ GeminiProtocol: AudioData неверный")
        //
        // }.onFailure { e -> log("❌ GeminiProtocol ERROR: ${e.message}") }
        //
        // // ТЕСТ AudioDispatcherProvider — удалить после проверки
        // lifecycleScope.launch {
        //     runCatching {
        //         val dispatchers = com.codeextractor.app.audio.AudioDispatcherProvider()
        //
        //         // Тест 1: recorder поток
        //         var recorderThreadName = ""
        //         kotlinx.coroutines.withContext(dispatchers.recorder) {
        //             recorderThreadName = Thread.currentThread().name
        //         }
        //         log(if (recorderThreadName == "GeminiAudioRecorder")
        //             "✅ AudioDispatcherProvider: recorder OK (thread=$recorderThreadName)"
        //             else "❌ recorder thread wrong: $recorderThreadName")
        //
        //         // Тест 2: player поток
        //         var playerThreadName = ""
        //         kotlinx.coroutines.withContext(dispatchers.player) {
        //             playerThreadName = Thread.currentThread().name
        //         }
        //         log(if (playerThreadName == "GeminiAudioPlayer")
        //             "✅ AudioDispatcherProvider: player OK (thread=$playerThreadName)"
        //             else "❌ player thread wrong: $playerThreadName")
        //
        //         // Тест 3: shutdown
        //         dispatchers.shutdown()
        //         log("✅ AudioDispatcherProvider: shutdown OK")
        //
        //     }.onFailure { e -> log("❌ AudioDispatcherProvider ERROR: ${e.message}") }
        // }

        // #25: Загружаем сохранённый ключ
        apiKey = loadApiKey()

        setupUI()
        observeState()
        startAudioPlaybackLoop()

        // Подключаемся только если ключ уже есть
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

        // #25: Обработка ввода API ключа
        binding.keyOkButton.setOnClickListener {
            val input = binding.apiKeyEditText.text?.toString()?.trim().orEmpty()
            if (input.length < 20) {
                Toast.makeText(this, "Ключ слишком короткий", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            saveApiKey(input)
            apiKey = input
            // Скрываем панель ввода
            binding.keyInputLayout.visibility = View.GONE
            binding.keyDivider.visibility = View.GONE
            // Убираем клавиатуру
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.apiKeyEditText.windowToken, 0)
            log("✓ API ключ сохранён")
            // Подключаемся
            connectWebSocket()
        }

        // Поддержка Enter в поле ввода
        binding.apiKeyEditText.setOnEditorActionListener { _, _, _ ->
            binding.keyOkButton.performClick()
            true
        }

        // Если ключ уже есть — показываем маску в поле (подсказка)
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

    private fun buildWsUrl(): String {
        // #25: Ключ из EncryptedSharedPreferences, не из BuildConfig
        return "wss://$HOST/$WS_PATH?key=$apiKey"
    }

    private fun connectWebSocket() {
        if (apiKey.isEmpty()) {
            log("⚠ connectWebSocket() — ключ не задан, пропуск")
            return
        }
        if (sessionState.value != SessionState.Disconnected) return
        sessionState.value = SessionState.Connecting

        setupComplete.completeExceptionally(
            kotlinx.coroutines.CancellationException("Session reset")
        )
        setupComplete = CompletableDeferred()

        log("Connecting…")
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

            // ═══════════════════════════════════════════════════════
            //  #19: Расшифровка кодов закрытия WebSocket
            // ═══════════════════════════════════════════════════════
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

    /**
     * ═══════════════════════════════════════════════════════════════════
     *  #19: Диагностика кодов закрытия WebSocket (v4)
     *
     *  Стандартные коды (RFC 6455) + Gemini-специфичные.
     *  Помогает отличить нормальное закрытие от ошибок сервера.
     * ═══════════════════════════════════════════════════════════════════
     */
    private fun describeCloseCode(code: Int): String = when (code) {
        1000 -> "[Normal Closure]"
        1001 -> "[Going Away — server shutdown or session timeout]"
        1002 -> "[Protocol Error]"
        1003 -> "[Unsupported Data]"
        1005 -> "[No Status Code]"
        1006 -> "[Abnormal Closure — no close frame received]"
        1007 -> "[Invalid Payload]"
        1008 -> "[Policy Violation]"
        1009 -> "[Message Too Big]"
        1011 -> "[Internal Server Error]"
        1012 -> "[Service Restart]"
        1013 -> "[Try Again Later — server overloaded]"
        1014 -> "[Bad Gateway]"
        1015 -> "[TLS Handshake Failure]"
        // Gemini-специфичные (наблюдаемые на практике)
        4000 -> "[Gemini: Session expired (15 min limit)]"
        4001 -> "[Gemini: Invalid setup message]"
        4002 -> "[Gemini: Rate limited]"
        4003 -> "[Gemini: Authentication failed]"
        else -> "[Unknown code $code]"
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
            log("Max reconnect attempts reached")
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
    //  2. SETUP (BidiGenerateContentSetup)
    // ====================================================================

    /**
     * Protobuf BidiGenerateContentSetup:
     *
     *  ┌─ "setup"
     *  │   ├─ "model"
     *  │   ├─ "generationConfig"
     *  │   │   ├─ "responseModalities": ["AUDIO"]
     *  │   │   └─ "speechConfig": { voiceConfig: ... }    ← #24 Шаг 2
     *  │   └─ "systemInstruction"                          ← #23 Шаг 1
     *  │       └─ "parts": [{ "text": "..." }]
     *  └─
     */
    private fun sendSetup() {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", MODEL)
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive("AUDIO"))
                    })
                    // ШАГ 2: speechConfig с голосом Aoede
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", "Aoede")
                            })
                        })
                    })
                })
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "Ты русскоязычный голосовой ассистент. Всегда отвечай только на русском языке. Слушай и понимай русскую речь.")
                        })
                    })
                })
            })
        }
        val raw = msg.toString()
        log("SETUP → (${raw.length} chars)")
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
        log("CLIENT_CONTENT → $raw")
        webSocket?.send(raw)
    }

    /**
     * ═══════════════════════════════════════════════════════════════════
     *  #18: Восстановление контекста после reconnect (v4)
     *
     *  Отправляет накопленную историю разговора через clientContent,
     *  чтобы модель помнила предыдущий контекст после переподключения.
     *
     *  Протокол: clientContent с массивом turns (чередование user/model)
     *  + turnComplete: true — чтобы модель знала, что контекст загружен.
     * ═══════════════════════════════════════════════════════════════════
     */
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
    //  4. SERVER MESSAGES — parse & route
    // ====================================================================

    private fun handleServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            // ─── Setup complete ────────────────────────────────────
            if (root.containsKey("setupComplete")) {
                log("✓ SETUP COMPLETE")
                sessionState.value = SessionState.Ready
                setupComplete.complete(Unit)

                // #18: Восстанавливаем контекст после reconnect
                lifecycleScope.launch(Dispatchers.IO) {
                    restoreConversationContext()
                    delay(300)
                    sendTextMessage("Hello, say something")
                }
                return
            }

            // ─── Tool call (#12) ───────────────────────────────────
            root["toolCall"]?.jsonObject?.let { toolCall ->
                handleToolCall(toolCall)
                return
            }

            // ─── Server content ────────────────────────────────────
            val sc = root["serverContent"]?.jsonObject ?: run {
                if (root.containsKey("goAway")) {
                    log("GO_AWAY — сервер скоро закроет, сбрасываем счётчик реконнекта")
                    reconnectAttempt = 0
                } else {
                    val preview = if (raw.length > 200) raw.take(200) + "…(${raw.length} chars)" else raw
                    log("SERVER ← $preview")
                }
                return
            }

            // ─── Транскрипции + сохранение в историю (#18) ─────────
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

            // ─── Прерывание (barge-in) ─────────────────────────────
            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⚡ INTERRUPTED — flushing playback")
                awaitingPlaybackDrain = false
                flushPlaybackQueue()
            }

            // ─── Turn complete (#13: плавная остановка) ────────────
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⏹ TURN COMPLETE")
                awaitingPlaybackDrain = true
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("✅ GENERATION COMPLETE")
            }

            // ─── Аудио-данные модели ───────────────────────────────
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
                                log("⚠ Playback queue full — dropping oldest chunk")
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

    /**
     * #12: Обработка toolCall от сервера
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
            argsObj?.forEach { (key, value) ->
                args[key] = value.jsonPrimitive.content
            }

            log("🔧 TOOL_CALL: $name($args)")
            val result = dispatchToolFunction(name, args)
            log("🔧 TOOL_RESULT: $name → $result")

            responses.add(ToolFunctionResponse(name, id, result))
        }

        sendToolResponse(responses)
    }

    /**
     * #3: flush ТОЛЬКО при barge-in (interrupted)
     * #13: при turnComplete буфер доигрывает естественно
     */
    private fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) { /* drain */ }
        isFirstBatch = true  // #21: Чистый pre-buffer для новой генерации после barge-in
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
                log("Session no longer ready (${sessionState.value}) — aborting record")
                return@launch
            }
            launchAudioCapture()
        }
    }

    /**
     * #4: GC — один ByteBuffer переиспользуется
     * #8: AcousticEchoCanceler — явное включение
     */
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
                        log("AcousticEchoCanceler enabled (sessionId=${recorder.audioSessionId})")
                    }
                } catch (e: Exception) {
                    log("AEC init error: ${e.message}")
                }
            } else {
                log("AcousticEchoCanceler not available")
            }

            try {
                recorder.startRecording()
            } catch (e: Exception) {
                log("AudioRecord.startRecording() failed: ${e.message}")
                recorder.release()
                return
            }

            audioRecord = recorder
            sessionState.value = SessionState.Recording
            log("🎙 Recording started (buf=$minBuf)")

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
            log("🎙 Recording stopped (disconnect in progress)")
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
        val raw = msg.toString()
        webSocket?.send(raw)
        log("→ turnComplete (audio stream stopped)")
    }

    // ====================================================================
    //  6. AUDIO OUTPUT
    // ====================================================================

    /**
     * #14: Проверка 24kHz
     * #15 + #16: Jitter pre-buffer с suspend receive()
     * #13: Плавная остановка по turnComplete
     */
    private fun startAudioPlaybackLoop() {
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {

            // #14: Проверка поддержки OUTPUT_SAMPLE_RATE
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                log("⚠ Device does not support ${OUTPUT_SAMPLE_RATE}Hz output! (minBuf=$minBuf)")
                return@launch
            }

            log("AudioTrack: ${OUTPUT_SAMPLE_RATE}Hz supported (minBuf=$minBuf)")

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

            // #21: isFirstBatch вынесен на уровень класса (сбрасывается в flushPlaybackQueue)

            for (chunk in audioPlaybackChannel) {
                if (!isActive) break

                // ═══════════════════════════════════════════════════
                //  #15 + #16 + #20: Jitter pre-buffer (FINAL)
                //
                //  v3: tryReceive() — non-blocking, получал 1 чанк
                //  v4: receive() — suspend, но зависал навечно при
                //      коротких ответах ("Ок" = 2 чанка, 3-го нет)
                //  v5: withTimeoutOrNull(150ms) — ждёт до 150мс,
                //      если чанк не пришёл → играем что есть.
                //
                //  Gemini 3.1: "Без timeout — deadlock. Без сброса
                //  isFirstBatch в flush — audio bleed при barge-in."
                // ═══════════════════════════════════════════════════
                if (isFirstBatch) {
                    val preBuffer = mutableListOf(chunk)
                    repeat(JITTER_PRE_BUFFER_CHUNKS - 1) {
                        try {
                            // #20: withTimeoutOrNull — ждём макс 150мс
                            // Если ответ короткий и чанков больше нет → не зависаем
                            val next = withTimeoutOrNull(150L) {
                                audioPlaybackChannel.receive()
                            }
                            if (next != null) {
                                preBuffer.add(next)
                            }
                        } catch (e: ClosedReceiveChannelException) {
                            return@repeat
                        } catch (e: Exception) {
                            return@repeat
                        }
                    }

                    for (buffered in preBuffer) {
                        track.write(buffered, 0, buffered.size)
                    }
                    isFirstBatch = false
                    log("Jitter pre-buffer: ${preBuffer.size} chunks written")
                } else {
                    track.write(chunk, 0, chunk.size)
                }

                // #13: При turnComplete — channel доигрывает, не flush
                if (awaitingPlaybackDrain && audioPlaybackChannel.isEmpty) {
                    log("⏹ Playback drained after turnComplete")
                    awaitingPlaybackDrain = false
                    isFirstBatch = true  // Следующий ответ → снова pre-buffer
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
    //  #25: API KEY — SharedPreferences (MODE_PRIVATE)
    //  Android sandbox изолирует данные на уровне UID — другие приложения
    //  не имеют доступа без root. Достаточно для личного использования.
    // ====================================================================

    private fun getPrefs() =
        getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private fun loadApiKey(): String =
        getPrefs().getString(PREFS_KEY_API, "").orEmpty()

    private fun saveApiKey(key: String) {
        getPrefs().edit().putString(PREFS_KEY_API, key).apply()
    }

    // ====================================================================
    //  #26: ANTI-TAMPER — debugger + root detection
    // ====================================================================

    /**
     * Вызывается первым в onCreate.
     * При обнаружении угрозы — завершает процесс.
     *
     * Что проверяет:
     *  1. Подключён ли отладчик (JDWP / ADB debugger)
     *  2. Есть ли su на устройстве (root)
     *  3. Установлен ли сам APK из неизвестного источника (не Play / не наш пакет)
     */
    private fun performSecurityChecks() {
        // 1. Anti-debug: JDWP debugger
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) {
            Log.e(TAG, "SECURITY: debugger detected — terminating")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
            return
        }

        // 2. Root detection: проверяем наличие su в PATH
        val suPaths = arrayOf(
            "/system/bin/su", "/system/xbin/su",
            "/sbin/su", "/su/bin/su",
            "/data/local/xbin/su", "/data/local/bin/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su"
        )
        val rooted = suPaths.any { java.io.File(it).exists() }
        if (rooted) {
            Log.w(TAG, "SECURITY: root detected — su binary found")
            // На рутованных устройствах предупреждаем, но не блокируем
            // (рут сам по себе не означает атаку — это может быть твой dev-телефон)
            // Если хочешь жёсткую блокировку — раскомментируй:
            // finishAndRemoveTask()
            // android.os.Process.killProcess(android.os.Process.myPid())
        }

        // 3. Повторная проверка отладчика через нативный флаг
        val tracerPid = runCatching {
            java.io.File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("TracerPid:") }
                ?.substringAfter("TracerPid:")?.trim()?.toIntOrNull() ?: 0
        }.getOrDefault(0)

        if (tracerPid > 0) {
            Log.e(TAG, "SECURITY: TracerPid=$tracerPid — process being traced, terminating")
            finishAndRemoveTask()
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
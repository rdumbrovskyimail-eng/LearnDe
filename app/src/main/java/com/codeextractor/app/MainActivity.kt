package com.codeextractor.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.codeextractor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
 * Gemini Live API — raw WebSocket (OkHttp) implementation.
 *
 * Targeting: AI Studio API keys + v1beta endpoint.
 * Protocol reference (2026-03-13):
 *   https://ai.google.dev/api/live
 *
 * Audio format:
 *   Input  — PCM 16-bit LE mono 16 kHz
 *   Output — PCM 16-bit LE mono 24 kHz
 */
class MainActivity : AppCompatActivity() {

    // ====================================================================
    //  CONSTANTS
    // ====================================================================

    companion object {
        private const val TAG = "GeminiLive"

        // Актуальная модель для AI Studio ключей (декабрь 2025 preview)
        private const val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000

        private const val AUDIO_PERMISSION_CODE = 200

        /** Ёмкость очереди воспроизведения — ~64 чанка ≈ нескольких секунд аудио. */
        private const val PLAYBACK_QUEUE_CAPACITY = 64

        /** Макс. попыток реконнекта. */
        private const val MAX_RECONNECT_ATTEMPTS = 5

        /** Базовая задержка реконнекта (мс), множится экспоненциально. */
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
    }

    // ====================================================================
    //  SEALED STATE — потокобезопасная машина состояний
    // ====================================================================

    private sealed interface SessionState {
        data object Disconnected : SessionState
        data object Connecting : SessionState
        data object Connected : SessionState       // WS открыт, setup отправлен
        data object Ready : SessionState           // setupComplete получен
        data object Recording : SessionState       // аудио идёт на вход
    }

    private val sessionState = MutableStateFlow<SessionState>(SessionState.Disconnected)

    // ====================================================================
    //  FIELDS
    // ====================================================================

    private lateinit var binding: ActivityMainBinding

    private val json = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)   // WS keep-alive
        .pingInterval(20, TimeUnit.SECONDS)       // heartbeat
        .build()

    private var webSocket: WebSocket? = null
    private var setupComplete = CompletableDeferred<Unit>()

    // Audio record
    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    // Audio playback
    private var audioTrack: AudioTrack? = null
    private val audioPlaybackChannel = Channel<ByteArray>(PLAYBACK_QUEUE_CAPACITY)
    private var playbackJob: Job? = null

    // Reconnect
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    // Logging
    private val logLines = ArrayDeque<String>(500)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { saveLogToUri(it) } }

    // ====================================================================
    //  LIFECYCLE
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        log("=== APP STARTED ===")

        setupUI()
        observeState()
        startAudioPlaybackLoop()
        connectWebSocket()
    }

    override fun onDestroy() {
        super.onDestroy()
        reconnectJob?.cancel()
        releaseAudioRecord()
        // Закрываем канал ПЕРВЫМ — for-loop в playbackJob выйдет штатно,
        // иначе корутина может зависнуть внутри track.write() после cancel.
        audioPlaybackChannel.close()
        playbackJob?.cancel()
        releaseAudioTrack()
        disconnectWebSocket()
        client.dispatcher.cancelAll()
    }

    // ====================================================================
    //  UI
    // ====================================================================

    private fun setupUI() {
        binding.startButton.setOnClickListener { requestPermissionAndRecord() }
        binding.stopButton.setOnClickListener { stopRecording() }
        binding.saveLogButton.setOnClickListener {
            val ts = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            saveLogLauncher.launch("gemini_log_$ts.txt")
        }
    }

    /** Подписка на [sessionState] → обновление UI-индикатора. */
    private fun observeState() {
        lifecycleScope.launch {
            sessionState.collectLatest { state ->
                val (icon, color) = when (state) {
                    SessionState.Disconnected,
                    SessionState.Connecting -> android.R.drawable.presence_busy to android.graphics.Color.RED

                    SessionState.Connected -> android.R.drawable.presence_online to android.graphics.Color.YELLOW

                    SessionState.Ready -> android.R.drawable.presence_online to android.graphics.Color.GRAY

                    SessionState.Recording -> android.R.drawable.presence_audio_online to android.graphics.Color.GREEN
                }
                binding.statusIndicator.setImageResource(icon)
                binding.statusIndicator.setColorFilter(color)
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
        return "wss://$HOST/$WS_PATH?key=$key"
    }

    private fun connectWebSocket() {
        if (sessionState.value != SessionState.Disconnected) return
        sessionState.value = SessionState.Connecting

        // Завершаем старый deferred ошибкой — любая корутина,
        // ждущая setupComplete.await(), выйдет через runCatching.onFailure.
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
                // Gemini Live API отвечает текстовыми фреймами (JSON).
                // Бинарный фрейм — аномалия; пробуем прочитать как UTF-8.
                log("BINARY frame ${bytes.size} bytes")
                handleServerMessage(bytes.utf8())
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                log("WS closed: $code $reason")
                handleDisconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: okhttp3.Response?) {
                log("WS failure: ${t.message}")
                handleDisconnect()
            }
        })
    }

    private fun disconnectWebSocket() {
        webSocket?.close(1000, "bye")
        webSocket = null
        sessionState.value = SessionState.Disconnected
    }

    private fun handleDisconnect() {
        // Остановить запись ДО смены состояния, иначе stopRecording()
        // увидит state != Recording и выйдет без очистки ресурсов.
        releaseAudioRecord()
        sessionState.value = SessionState.Disconnected
        scheduleReconnect()
    }

    /** Безусловная очистка AudioRecord (без проверки state). Thread-safe. */
    @Synchronized
    private fun releaseAudioRecord() {
        recordJob?.cancel()
        recordJob = null
        audioRecord?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioRecord = null
    }

    // ---- Exponential-backoff reconnect --------------------------------

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
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            connectWebSocket()
        }
    }

    // ====================================================================
    //  2. SETUP — BidiGenerateContentSetup
    // ====================================================================

    /**
     * Первое сообщение после WS open.
     *
     * Формат «setup» — формальный ключ из API reference (2026-03-13).
     * `responseModalities`, `speechConfig` вложены в `generationConfig`.
     *
     * ⚠ FALLBACK: если сервер не принимает «setup», попробовать «config» —
     * альтернативный формат из getting started guide, где responseModalities
     * лежат на верхнем уровне config (без generationConfig обёртки):
     *
     *   {"config": {"model": "...", "responseModalities": ["AUDIO"], ...}}
     */
    private fun sendSetup() {
        val msg = buildJsonObject {
            put("setup", buildJsonObject {
                put("model", MODEL)
                put("generationConfig", buildJsonObject {
                    put("responseModalities", buildJsonArray {
                        add(JsonPrimitive("AUDIO"))
                    })
                    put("speechConfig", buildJsonObject {
                        put("voiceConfig", buildJsonObject {
                            put("prebuiltVoiceConfig", buildJsonObject {
                                put("voiceName", "Kore")
                            })
                        })
                    })
                })
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject {
                            put("text", "You are a helpful voice assistant. Respond concisely.")
                        })
                    })
                })
                put("inputAudioTranscription", buildJsonObject {})
                put("outputAudioTranscription", buildJsonObject {})
            })
        }
        val raw = json.encodeToString(msg)
        log("SETUP → (${raw.length} chars)")
        webSocket?.send(raw)
    }

    // ====================================================================
    //  3. CLIENT MESSAGES — audio, text
    // ====================================================================

    /**
     * Аудио-чанк через `realtimeInput.audio` (Blob).
     *
     * ‼️ `mediaChunks` DEPRECATED (API ref 2026-03-13).
     *    Актуальное поле — `audio` (одиночный Blob, не массив).
     *
     * Оптимизация: строковый шаблон вместо buildJsonObject + encodeToString
     * на каждый чанк (~30мс интервал). Снижает GC pressure.
     */
    private fun sendAudioChunk(b64: String) {
        if (sessionState.value != SessionState.Recording) return
        // b64 гарантированно Base64 NO_WRAP — без спецсимволов JSON, escaping не нужен.
        val raw = """{"realtimeInput":{"audio":{"data":"$b64","mimeType":"audio/pcm;rate=$INPUT_SAMPLE_RATE"}}}"""
        webSocket?.send(raw)
    }

    /**
     * Текстовое сообщение через `clientContent` + `turnComplete`.
     *
     * Используется для однократных фраз (не стриминг текста).
     * `realtimeInput.text` тоже валиден, но не сигнализирует конец хода.
     */
    private fun sendTextMessage(text: String) {
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
                put("turnComplete", true)
            })
        }
        val raw = json.encodeToString(msg)
        log("TEXT → $raw")
        webSocket?.send(raw)
    }

    // ====================================================================
    //  4. SERVER MESSAGES — parse & route
    // ====================================================================

    private fun handleServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            // ---- setupComplete ------------------------------------------
            if (root.containsKey("setupComplete")) {
                log("✓ SETUP COMPLETE")
                sessionState.value = SessionState.Ready
                setupComplete.complete(Unit)

                // Отправляем приветствие после небольшой паузы
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(300)
                    sendTextMessage("Hello, say something")
                }
                return
            }

            // ---- serverContent ------------------------------------------
            val sc = root["serverContent"]?.jsonObject ?: run {
                // Может быть usageMetadata, toolCall, goAway и т.д.
                if (root.containsKey("toolCall")) {
                    log("TOOL_CALL (not handled): $raw")
                } else if (root.containsKey("goAway")) {
                    log("GO_AWAY — сервер скоро закроет, сбрасываем счётчик реконнекта")
                    // Не переподключаемся здесь напрямую — сервер сам закроет WS,
                    // сработает onClosed → handleDisconnect → scheduleReconnect.
                    // Сбрасываем счётчик, чтобы не штрафовать за server-initiated close.
                    reconnectAttempt = 0
                } else {
                    // Логируем только начало — raw может содержать base64-аудио (100+ КБ)
                    val preview = if (raw.length > 200) raw.take(200) + "…(${raw.length} chars)" else raw
                    log("SERVER ← $preview")
                }
                return
            }

            // Input transcription
            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🎤 USER: $it") }

            // Output transcription
            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🔊 GEMINI: $it") }

            // Interrupted
            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⚡ INTERRUPTED — flushing playback")
                flushPlaybackQueue()
            }

            // Turn complete
            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⏹ TURN COMPLETE")
            }

            // Generation complete
            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("✅ GENERATION COMPLETE")
            }

            // Model turn → audio / text parts
            val parts = sc["modelTurn"]?.jsonObject
                ?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val obj = part.jsonObject

                // Текстовый part (редко при AUDIO modality)
                obj["text"]?.jsonPrimitive?.content?.let { log("MODEL_TEXT: $it") }

                // Audio part → decode & enqueue
                obj["inlineData"]?.jsonObject?.let { inline ->
                    val mime = inline["mimeType"]?.jsonPrimitive?.content.orEmpty()
                    if (mime.startsWith("audio/pcm")) {
                        inline["data"]?.jsonPrimitive?.content?.let { b64 ->
                            val pcm = Base64.decode(b64, Base64.DEFAULT)
                            val sent = audioPlaybackChannel.trySend(pcm)
                            if (sent.isFailure) {
                                log("⚠ Playback queue full — dropping oldest chunk")
                                audioPlaybackChannel.tryReceive()   // drop oldest
                                audioPlaybackChannel.trySend(pcm)   // retry
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log("PARSE ERROR: ${e.message}")
        }
    }

    /** Очищаем очередь воспроизведения (при barge-in / interrupted). */
    private fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) { /* drain */ }
        audioTrack?.flush()
    }

    // ====================================================================
    //  5. AUDIO INPUT (запись с микрофона)
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

        // Ждём setupComplete — без таймеров!
        lifecycleScope.launch {
            if (currentState != SessionState.Ready) {
                log("Waiting for setupComplete…")
                runCatching { setupComplete.await() }
                    .onFailure {
                        log("Setup not completed — cannot record")
                        return@launch
                    }
            }
            // Перепроверяем состояние: WS мог упасть пока ждали.
            if (sessionState.value != SessionState.Ready) {
                log("Session no longer ready (${sessionState.value}) — aborting record")
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
                minBuf * 2  // двойной буфер для стабильности
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                log("AudioRecord init failed")
                recorder.release()
                return
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
                while (isActive && sessionState.value == SessionState.Recording) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val bytes = ByteBuffer
                            .allocate(read * 2)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .apply { for (i in 0 until read) putShort(buffer[i]) }
                            .array()
                        sendAudioChunk(Base64.encodeToString(bytes, Base64.NO_WRAP))
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
        // Уведомляем сервер что аудиопоток закрыт (API ref: audioStreamEnd).
        // Без этого Auto VAD ждёт тишину → лишний latency перед turnComplete.
        sendAudioStreamEnd()
        sessionState.value = SessionState.Ready
        log("🎙 Recording stopped")
    }

    /**
     * Сигнал серверу что микрофон выключен.
     *
     * API ref: "Indicates that the audio stream has ended.
     * This should only be sent when automatic activity detection is enabled
     * (which is the default). The client can reopen the stream by sending
     * an audio message."
     */
    private fun sendAudioStreamEnd() {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return
        val raw = """{"realtimeInput":{"audioStreamEnd":true}}"""
        webSocket?.send(raw)
        log("→ audioStreamEnd")
    }

    // ====================================================================
    //  6. AUDIO OUTPUT (воспроизведение)
    // ====================================================================

    private fun startAudioPlaybackLoop() {
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            val minBuf = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf == AudioTrack.ERROR || minBuf == AudioTrack.ERROR_BAD_VALUE) {
                log("AudioTrack.getMinBufferSize failed: $minBuf")
                return@launch
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
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
                track.write(chunk, 0, chunk.size)
            }
        }
    }

    private fun releaseAudioTrack() {
        audioTrack?.let {
            runCatching {
                it.stop()
                it.release()
            }
        }
        audioTrack = null
    }
}

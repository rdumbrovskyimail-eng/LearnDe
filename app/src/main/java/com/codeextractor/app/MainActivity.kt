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

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "GeminiLive"
        private const val MODEL = "models/gemini-2.5-flash"
        private const val HOST = "generativelanguage.googleapis.com"
        private const val WS_PATH =
            "ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val INPUT_SAMPLE_RATE = 16_000
        private const val OUTPUT_SAMPLE_RATE = 24_000
        private const val AUDIO_PERMISSION_CODE = 200
        private const val PLAYBACK_QUEUE_CAPACITY = 64
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val RECONNECT_BASE_DELAY_MS = 2_000L
        private val COLOR_READY = android.graphics.Color.parseColor("#4CAF50")
    }

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

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Убрали pingInterval(20, TimeUnit.SECONDS), чтобы избежать обрыва связи по таймауту Pong
        .build()

    private var webSocket: WebSocket? = null
    private var setupComplete = CompletableDeferred<Unit>()

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private val audioPlaybackChannel = Channel<ByteArray>(PLAYBACK_QUEUE_CAPACITY)
    private var playbackJob: Job? = null

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    private val logLines = ArrayDeque<String>(500)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? -> uri?.let { saveLogToUri(it) } }

    // ====================================================================
    //  LIFECYCLE
    // ====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupEdgeToEdgeInsets()
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

            binding.headerLayout.setPadding(
                pad16, systemBars.top + pad12, pad16, pad12
            )

            binding.controlsLayout.setPadding(
                pad16, pad12, pad16, systemBars.bottom + pad12
            )

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
        val key = BuildConfig.GEMINI_API_KEY
        return "wss://$HOST/$WS_PATH?key=$key"
    }

    private fun connectWebSocket() {
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
        releaseAudioRecord()
        sessionState.value = SessionState.Disconnected
        scheduleReconnect()
    }

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
        reconnectJob?.cancel()
        reconnectJob = lifecycleScope.launch {
            delay(delayMs)
            connectWebSocket()
        }
    }

    // ====================================================================
    //  2. SETUP
    // ====================================================================

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
                                put("voiceName", "Aoede") // Доступные голоса: Aoede, Charon, Kore, Fenrir, Puck
                            })
                        })
                    })
                })
            })
        }
        // ИСПОЛЬЗУЕМ .toString() вместо json.encodeToString()
        val raw = msg.toString()
        log("SETUP → (${raw.length} chars)")
        webSocket?.send(raw)
    }

    // ====================================================================
    //  3. CLIENT MESSAGES — audio, text
    // ====================================================================

    private fun sendAudioChunk(b64: String) {
        if (sessionState.value != SessionState.Recording) return

        // ВАЖНО: Gemini ждет массив "mediaChunks", а не объект "audio"
        val raw = """{"realtimeInput":{"mediaChunks":[{"data":"$b64","mimeType":"audio/pcm;rate=$INPUT_SAMPLE_RATE"}]}}"""
        webSocket?.send(raw)
    }

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

        // ИСПОЛЬЗУЕМ .toString() вместо json.encodeToString()
        val raw = msg.toString()
        log("TEXT → $raw")
        webSocket?.send(raw)
    }

    // ====================================================================
    //  4. SERVER MESSAGES — parse & route
    // ====================================================================

    private fun handleServerMessage(raw: String) {
        try {
            val root = json.parseToJsonElement(raw).jsonObject

            if (root.containsKey("setupComplete")) {
                log("✓ SETUP COMPLETE")
                sessionState.value = SessionState.Ready
                setupComplete.complete(Unit)

                lifecycleScope.launch(Dispatchers.IO) {
                    delay(300)
                    sendTextMessage("Hello, say something")
                }
                return
            }

            val sc = root["serverContent"]?.jsonObject ?: run {
                if (root.containsKey("toolCall")) {
                    val preview = if (raw.length > 200) raw.take(200) + "…" else raw
                    log("TOOL_CALL (not handled): $preview")
                } else if (root.containsKey("goAway")) {
                    log("GO_AWAY — сервер скоро закроет, сбрасываем счётчик реконнекта")
                    reconnectAttempt = 0
                } else {
                    val preview = if (raw.length > 200) raw.take(200) + "…(${raw.length} chars)" else raw
                    log("SERVER ← $preview")
                }
                return
            }

            sc["inputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🎤 USER: $it") }

            sc["outputTranscription"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { log("🔊 GEMINI: $it") }

            if (sc["interrupted"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⚡ INTERRUPTED — flushing playback")
                flushPlaybackQueue()
            }

            if (sc["turnComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("⏹ TURN COMPLETE")
            }

            if (sc["generationComplete"]?.jsonPrimitive?.booleanOrNull == true) {
                log("✅ GENERATION COMPLETE")
            }

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

    private fun flushPlaybackQueue() {
        while (audioPlaybackChannel.tryReceive().isSuccess) { /* drain */ }
        audioTrack?.flush()
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
        if (sessionState.value == SessionState.Disconnected) {
            log("🎙 Recording stopped (disconnect in progress)")
            return
        }
        sendAudioStreamEnd()
        sessionState.value = SessionState.Ready
        log("🎙 Recording stopped")
    }

    private fun sendAudioStreamEnd() {
        val state = sessionState.value
        if (state != SessionState.Ready && state != SessionState.Recording) return

        // ВАЖНО: Поля "audioStreamEnd" в API не существует.
        // Чтобы передать ход модели после остановки микрофона, отправляем turnComplete
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
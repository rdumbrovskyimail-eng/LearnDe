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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null

    private var isRecording = false
    private var isConnected = false
    private var isSetupComplete = false

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null

    private var audioTrack: AudioTrack? = null
    private val audioChannel = Channel<ByteArray>(Channel.UNLIMITED)
    private var playbackJob: Job? = null

    private val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private val API_KEY = BuildConfig.GEMINI_API_KEY
    private val HOST = "generativelanguage.googleapis.com"
    private val URL = "wss://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$API_KEY"

    private val AUDIO_REQUEST_CODE = 200
    private val INPUT_SAMPLE_RATE = 16000
    private val OUTPUT_SAMPLE_RATE = 24000

    private val jsonSerializer = Json { ignoreUnknownKeys = true }

    private val logBuffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private var logcatJob: Job? = null

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        uri?.let { saveLogToUri(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        writeLog("=== APP STARTED ===")
        startLogcatCapture()

        setupUI()
        startAudioPlaybackLoop()
        connectWebSocket()

        lifecycleScope.launch {
            writeLog("Auto-start scheduled in 10 seconds")
            delay(10_000)
            writeLog("Auto-starting audio input...")
            startAudioInput()

            delay(40_000)
            writeLog("=== AUTO-STOP AFTER 40 SECONDS ===")
            stopAudioInput()
        }
    }

    // ==========================================
    // LOGCAT CAPTURE
    // ==========================================

    private fun startLogcatCapture() {
        logcatJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec("logcat -c")
                val process = Runtime.getRuntime().exec("logcat -v time")
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (isActive) {
                    line = reader.readLine()
                    if (line != null) {
                        synchronized(logBuffer) {
                            logBuffer.appendLine(line)
                        }
                        if (logBuffer.count { it == '\n' } % 50 == 0) {
                            updateLogUI()
                        }
                    }
                }
                reader.close()
                process.destroy()
            } catch (e: Exception) {
                writeLog("Logcat capture error: ${e.message}")
            }
        }
    }

    private fun updateLogUI() {
        lifecycleScope.launch(Dispatchers.Main) {
            val text = synchronized(logBuffer) { logBuffer.toString() }
            val display = if (text.length > 3000) text.takeLast(3000) else text
            binding.chatLog.text = display
        }
    }

    // ==========================================
    // LOG WRITING & SAVING
    // ==========================================

    private fun writeLog(message: String) {
        val timestamp = timeFormat.format(Date())
        val line = "[$timestamp] $message"
        Log.d("GeminiLog", line)
    }

    private fun saveLogToUri(uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri)?.use { outputStream ->
                    val text = synchronized(logBuffer) { logBuffer.toString() }
                    outputStream.write(text.toByteArray())
                }
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Log saved!", Toast.LENGTH_SHORT).show()
                }
                writeLog("Log saved via SAF")
            } catch (e: Exception) {
                writeLog("Save error: ${e.message}")
                lifecycleScope.launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Save failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==========================================
    // UI SETUP
    // ==========================================

    private fun setupUI() {
        updateStatusIndicator()
        binding.startButton.setOnClickListener { checkRecordAudioPermission() }
        binding.stopButton.setOnClickListener { stopAudioInput() }
        binding.saveLogButton.setOnClickListener {
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Date())
            saveLogLauncher.launch("gemini_log_$timestamp.txt")
        }
    }

    // ==========================================
    // 1. WEBSOCKET & OKHTTP
    // ==========================================

    private fun connectWebSocket() {
        writeLog("Connecting to WebSocket: $URL")
        val request = Request.Builder().url(URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                writeLog("WebSocket OPENED: ${response.code}")
                isConnected = true
                updateStatusIndicator()
                sendInitialSetupMessage()
            }

            // Текстовые сообщения
            override fun onMessage(webSocket: WebSocket, text: String) {
                writeLog("RAW TEXT: $text")
                handleIncomingMessage(text)
            }

            // Бинарные сообщения — Gemini отвечает именно так
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val text = bytes.utf8()
                writeLog("RAW BINARY: $text")
                handleIncomingMessage(text)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                writeLog("WebSocket CLOSED: code=$code reason=$reason")
                handleDisconnect("Closed: $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                writeLog("WebSocket FAILURE: ${t.message}")
                handleDisconnect("Error: ${t.message}")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        writeLog("DISCONNECT: $reason")
        isConnected = false
        isSetupComplete = false
        stopAudioInput()
        updateStatusIndicator()
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Connection lost: $reason", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendInitialSetupMessage() {
        val setupMsg = buildJsonObject {
            put("config", buildJsonObject {
                put("model", MODEL)
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
                put("inputAudioTranscription", buildJsonObject {})
                put("outputAudioTranscription", buildJsonObject {})
            })
        }
        val json = jsonSerializer.encodeToString(setupMsg)
        writeLog("SETUP SENT: $json")
        webSocket?.send(json)
    }

    private fun sendMediaChunk(b64Data: String) {
        if (!isConnected || !isSetupComplete) return
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("mediaChunks", buildJsonArray {
                    add(buildJsonObject {
                        put("mimeType", "audio/pcm;rate=$INPUT_SAMPLE_RATE")
                        put("data", b64Data)
                    })
                })
            })
        }
        webSocket?.send(jsonSerializer.encodeToString(msg))
    }

    private fun sendTextMessage(text: String) {
        if (!isConnected) return
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("text", text)
            })
        }
        val json = jsonSerializer.encodeToString(msg)
        writeLog("TEXT SENT: $json")
        webSocket?.send(json)
    }

    private fun handleIncomingMessage(message: String) {
        writeLog("HANDLING: $message")
        try {
            val root = jsonSerializer.parseToJsonElement(message).jsonObject

            if (root.containsKey("setupComplete")) {
                isSetupComplete = true
                writeLog("SETUP COMPLETE!")
                lifecycleScope.launch(Dispatchers.IO) {
                    delay(500)
                    sendTextMessage("Hello, say something")
                }
                return
            }

            val serverContent = root["serverContent"]?.jsonObject ?: return

            serverContent["inputTranscription"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?.let { writeLog("INPUT_TRANSCRIPTION: $it") }

            serverContent["outputTranscription"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?.let { writeLog("OUTPUT_TRANSCRIPTION: $it") }

            val parts = serverContent["modelTurn"]?.jsonObject
                ?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val partObj = part.jsonObject
                partObj["text"]?.jsonPrimitive?.content?.let {
                    writeLog("MODEL_TEXT: $it")
                }
                partObj["inlineData"]?.jsonObject?.let { inlineData ->
                    val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: ""
                    if (mime.startsWith("audio/pcm")) {
                        writeLog("AUDIO_CHUNK received mime=$mime")
                        inlineData["data"]?.jsonPrimitive?.content?.let { b64 ->
                            audioChannel.trySend(Base64.decode(b64, Base64.DEFAULT))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            writeLog("PARSE ERROR: ${e.message}")
        }
    }

    // ==========================================
    // 2. AUDIO INPUT
    // ==========================================

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST_CODE
            )
        } else {
            startAudioInput()
        }
    }

    private fun startAudioInput() {
        if (isRecording) return
        val minBuffer = AudioRecord.getMinBufferSize(
            INPUT_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                INPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                writeLog("AudioRecord init failed")
                throw IllegalStateException("AudioRecord init failed")
            }
            audioRecord?.startRecording()
            isRecording = true
            writeLog("AudioRecord started, minBuffer=$minBuffer")
            updateStatusIndicator()

            recordJob = lifecycleScope.launch(Dispatchers.IO) {
                val buffer = ShortArray(minBuffer)
                while (isActive && isRecording) {
                    val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (readSize > 0) {
                        val byteBuffer = ByteBuffer.allocate(readSize * 2).order(ByteOrder.LITTLE_ENDIAN)
                        for (i in 0 until readSize) { byteBuffer.putShort(buffer[i]) }
                        val base64 = Base64.encodeToString(byteBuffer.array(), Base64.NO_WRAP)
                        sendMediaChunk(base64)
                    }
                }
            }
        } catch (e: SecurityException) {
            writeLog("SECURITY ERROR: ${e.message}")
        } catch (e: Exception) {
            writeLog("AUDIO ERROR: ${e.message}")
        }
    }

    private fun stopAudioInput() {
        isRecording = false
        recordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        writeLog("AudioRecord stopped")
        updateStatusIndicator()
    }

    // ==========================================
    // 3. AUDIO OUTPUT
    // ==========================================

    private fun startAudioPlaybackLoop() {
        playbackJob = lifecycleScope.launch(Dispatchers.IO) {
            val minBuffer = AudioTrack.getMinBufferSize(
                OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioTrack = AudioTrack.Builder()
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
                .setBufferSizeInBytes(minBuffer)
                .build()

            audioTrack?.play()

            for (audioChunk in audioChannel) {
                if (!isActive) break
                audioTrack?.write(audioChunk, 0, audioChunk.size)
            }
        }
    }

    // ==========================================
    // 4. UI & LIFECYCLE
    // ==========================================

    private fun updateStatusIndicator() {
        lifecycleScope.launch(Dispatchers.Main) {
            when {
                !isConnected -> {
                    binding.statusIndicator.setImageResource(android.R.drawable.presence_busy)
                    binding.statusIndicator.setColorFilter(android.graphics.Color.RED)
                }
                isRecording -> {
                    binding.statusIndicator.setImageResource(android.R.drawable.presence_audio_online)
                    binding.statusIndicator.setColorFilter(android.graphics.Color.GREEN)
                }
                else -> {
                    binding.statusIndicator.setImageResource(android.R.drawable.presence_audio_online)
                    binding.statusIndicator.setColorFilter(android.graphics.Color.GRAY)
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startAudioInput()
            } else {
                Toast.makeText(this, "Audio permission is required", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logcatJob?.cancel()
        stopAudioInput()
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioChannel.close()
        webSocket?.close(1000, "Activity destroyed")
        client.dispatcher.executorService.shutdown()
    }
}
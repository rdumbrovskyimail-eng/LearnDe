package com.codeextractor.app

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.codeextractor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        startAudioPlaybackLoop()
        connectWebSocket()
    }

    private fun setupUI() {
        updateStatusIndicator()
        binding.startButton.setOnClickListener { checkRecordAudioPermission() }
        binding.stopButton.setOnClickListener { stopAudioInput() }
    }

    // ==========================================
    // 1. WEBSOCKET & OKHTTP
    // ==========================================

    private fun connectWebSocket() {
        val request = Request.Builder().url(URL).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                isConnected = true
                updateStatusIndicator()
                sendInitialSetupMessage()
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleIncomingMessage(text)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                handleDisconnect("Closed: $reason")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                handleDisconnect("Error: ${t.message}")
            }
        })
    }

    private fun handleDisconnect(reason: String) {
        Log.e("WebSocket", reason)
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
            put("setup", buildJsonObject {
                put("model", MODEL)
                put("generation_config", buildJsonObject {
                    put("response_modalities", buildJsonArray {
                        add(JsonPrimitive("AUDIO"))
                    })
                    put("speech_config", buildJsonObject {
                        put("voice_config", buildJsonObject {
                            put("prebuilt_voice_config", buildJsonObject {
                                put("voice_name", "Kore")
                            })
                        })
                    })
                })
            })
        }
        webSocket?.send(jsonSerializer.encodeToString(setupMsg))
        Log.d("WebSocket", "Setup sent")
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

    private fun handleIncomingMessage(message: String) {
        try {
            val root = jsonSerializer.parseToJsonElement(message).jsonObject

            if (root.containsKey("setupComplete")) {
                isSetupComplete = true
                Log.d("WebSocket", "Setup complete!")
                return
            }

            val serverContent = root["serverContent"]?.jsonObject ?: return

            serverContent["inputTranscription"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?.let { displayMessage("USER: $it") }

            serverContent["outputTranscription"]?.jsonObject?.get("text")
                ?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
                ?.let { displayMessage("GEMINI: $it") }

            val parts = serverContent["modelTurn"]?.jsonObject
                ?.get("parts") as? JsonArray ?: return

            for (part in parts) {
                val partObj = part.jsonObject
                partObj["text"]?.jsonPrimitive?.content
                    ?.let { displayMessage("GEMINI: $it") }
                partObj["inlineData"]?.jsonObject?.let { inlineData ->
                    val mime = inlineData["mimeType"]?.jsonPrimitive?.content ?: ""
                    if (mime.startsWith("audio/pcm")) {
                        inlineData["data"]?.jsonPrimitive?.content?.let { b64 ->
                            audioChannel.trySend(Base64.decode(b64, Base64.DEFAULT))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Receive", "Error: ${e.message}", e)
        }
    }

    // ==========================================
    // 2. AUDIO INPUT (Микрофон)
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
                throw IllegalStateException("AudioRecord init failed")
            }
            audioRecord?.startRecording()
            isRecording = true
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
            Log.e("Audio", "Permission denied", e)
        } catch (e: Exception) {
            Log.e("Audio", "Microphone error: ${e.message}", e)
        }
    }

    private fun stopAudioInput() {
        isRecording = false
        recordJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        updateStatusIndicator()
    }

    // ==========================================
    // 3. AUDIO OUTPUT (Динамик)
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

    private fun displayMessage(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            val currentText = binding.chatLog.text.toString()
            binding.chatLog.text = if (currentText.isEmpty()) message else "$currentText\n$message"
        }
    }

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
        stopAudioInput()
        playbackJob?.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioChannel.close()
        webSocket?.close(1000, "Activity destroyed")
        client.dispatcher.executorService.shutdown()
    }
}
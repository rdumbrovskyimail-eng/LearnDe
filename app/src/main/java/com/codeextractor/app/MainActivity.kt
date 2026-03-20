package com.codeextractor.app

import android.Manifest
import android.content.pm.PackageManager
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
import com.codeextractor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.json.JSONObject
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webSocket: WebSocketClient? = null
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var recordInterval: Job? = null
    private var isSetupComplete = false

    private val MODEL = "models/gemini-2.5-flash-native-audio-preview-12-2025"
    private val API_KEY = "AIzaSyDFxs8iKlunr6kT8f8hsqKJP3LyBeCkWvs"
    private val HOST = "generativelanguage.googleapis.com"
    private val URL = "wss://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$API_KEY"

    private val AUDIO_REQUEST_CODE = 200
    private val AUDIO_INPUT_SAMPLE_RATE = 16000
    private val AUDIO_OUTPUT_SAMPLE_RATE = 24000
    private val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val AUDIO_BUFFER_SIZE = AudioRecord.getMinBufferSize(
        AUDIO_INPUT_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING
    )

    private val audioQueue = mutableListOf<ByteArray>()
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    private var isConnected = false
    private var isSpeaking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatusIndicator()

        binding.startButton.setOnClickListener { checkRecordAudioPermission() }
        binding.stopButton.setOnClickListener { stopAudioInput() }

        connect()
    }

    // region WebSocket

    private fun connect() {
        val headers = mutableMapOf("Content-Type" to "application/json")
        webSocket = object : WebSocketClient(URI(URL), Draft_6455(), headers) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isConnected = true
                updateStatusIndicator()
                sendInitialSetupMessage()
            }
            override fun onMessage(message: String?) { receiveMessage(message) }
            override fun onMessage(bytes: ByteBuffer?) {
                bytes?.let { receiveMessage(String(it.array(), Charsets.UTF_8)) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                Log.e("WebSocket", "Closed: code=$code reason=$reason")
                isConnected = false
                isSetupComplete = false
                updateStatusIndicator()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection closed: $reason", Toast.LENGTH_LONG).show()
                }
            }
            override fun onError(ex: Exception?) {
                Log.e("WebSocket", "Error: ${ex?.message}")
                isConnected = false
                updateStatusIndicator()
            }
        }
        webSocket?.connect()
    }

    private fun sendInitialSetupMessage() {
        val setupMessage = JSONObject().apply {
            put("config", JSONObject().apply {
                put("model", MODEL)
                put("generationConfig", JSONObject().apply {
                    put("responseModalities", org.json.JSONArray().apply { put("AUDIO") })
                    put("speechConfig", JSONObject().apply {
                        put("voiceConfig", JSONObject().apply {
                            put("prebuiltVoiceConfig", JSONObject().apply {
                                put("voiceName", "Kore")
                            })
                        })
                    })
                })
            })
        }
        webSocket?.send(setupMessage.toString())
        Log.d("WebSocket", "Setup sent")
    }

    private fun sendMediaChunk(b64Data: String, mimeType: String) {
        if (!isConnected || !isSetupComplete) return
        val msg = buildJsonObject {
            put("realtimeInput", buildJsonObject {
                put("mediaChunks", buildJsonArray {
                    add(buildJsonObject {
                        put("mimeType", mimeType)
                        put("data", b64Data)
                    })
                })
            })
        }
        webSocket?.send(Json { prettyPrint = false }.encodeToString(msg))
    }

    private fun receiveMessage(message: String?) {
        if (message == null) return
        Log.d("WebSocket", "Received: $message")
        try {
            val root = JSONObject(message)

            if (root.has("setupComplete")) {
                isSetupComplete = true
                Log.d("WebSocket", "Setup complete!")
                return
            }

            if (!root.has("serverContent")) return
            val serverContent = root.getJSONObject("serverContent")

            if (serverContent.has("inputTranscription")) {
                val text = serverContent.getJSONObject("inputTranscription").optString("text")
                if (text.isNotEmpty()) displayMessage("USER: $text")
            }
            if (serverContent.has("outputTranscription")) {
                val text = serverContent.getJSONObject("outputTranscription").optString("text")
                if (text.isNotEmpty()) displayMessage("GEMINI: $text")
            }

            if (!serverContent.has("modelTurn")) return
            val parts = serverContent.getJSONObject("modelTurn").optJSONArray("parts") ?: return
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) displayMessage("GEMINI: ${part.getString("text")}")
                if (part.has("inlineData")) {
                    val inlineData = part.getJSONObject("inlineData")
                    if (inlineData.optString("mimeType").startsWith("audio/pcm")) {
                        injestAudioChunkToPlay(inlineData.getString("data"))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Receive", "Error parsing message", e)
        }
    }

    // endregion

    // region Audio Input

    private fun checkRecordAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_REQUEST_CODE)
        } else {
            startAudioInput()
        }
    }

    private fun startAudioInput() {
        if (isRecording) return
        isRecording = true
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AUDIO_INPUT_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING, AUDIO_BUFFER_SIZE
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("Audio", "AudioRecord init failed"); return
        }
        audioRecord?.startRecording()
        isSpeaking = true
        updateStatusIndicator()
        recordInterval = GlobalScope.launch(Dispatchers.IO) {
            while (isRecording) {
                val buffer = ShortArray(AUDIO_BUFFER_SIZE)
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    val byteBuffer = ByteBuffer.allocate(readSize * 2).order(ByteOrder.LITTLE_ENDIAN)
                    buffer.take(readSize).forEach { byteBuffer.putShort(it) }
                    val base64 = Base64.encodeToString(byteBuffer.array(), Base64.DEFAULT or Base64.NO_WRAP)
                    sendMediaChunk(base64, "audio/pcm;rate=16000")
                }
            }
        }
    }

    private fun stopAudioInput() {
        isRecording = false
        recordInterval?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        isSpeaking = false
        updateStatusIndicator()
    }

    // endregion

    // region Audio Output

    private fun injestAudioChunkToPlay(base64AudioChunk: String?) {
        if (base64AudioChunk == null) return
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val bytes = Base64.decode(base64AudioChunk, Base64.DEFAULT)
                synchronized(audioQueue) { audioQueue.add(bytes) }
                if (!isPlaying) playNextAudioChunk()
            } catch (e: Exception) { Log.e("AudioChunk", "Error", e) }
        }
    }

    private fun playNextAudioChunk() {
        GlobalScope.launch(Dispatchers.IO) {
            while (true) {
                val chunk = synchronized(audioQueue) {
                    if (audioQueue.isNotEmpty()) audioQueue.removeAt(0) else null
                } ?: break
                isPlaying = true
                playAudio(chunk)
            }
            isPlaying = false
            synchronized(audioQueue) { if (audioQueue.isNotEmpty()) playNextAudioChunk() }
        }
    }

    private fun playAudio(byteArray: ByteArray) {
        if (audioTrack == null) {
            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                AUDIO_OUTPUT_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.getMinBufferSize(AUDIO_OUTPUT_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT),
                AudioTrack.MODE_STREAM
            )
        }
        audioTrack?.write(byteArray, 0, byteArray.size)
        audioTrack?.play()
        GlobalScope.launch(Dispatchers.IO) {
            while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) { delay(10) }
            audioTrack?.stop()
        }
    }

    // endregion

    // region UI

    private fun displayMessage(message: String) {
        runOnUiThread { binding.chatLog.text = "${binding.chatLog.text}\n$message" }
    }

    private fun updateStatusIndicator() {
        runOnUiThread {
            when {
                !isConnected -> {
                    binding.statusIndicator.setImageResource(android.R.drawable.presence_busy)
                    binding.statusIndicator.setColorFilter(android.graphics.Color.RED)
                }
                isSpeaking -> {
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

    // endregion

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AUDIO_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                startAudioInput()
            else
                Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioInput()
        webSocket?.close()
        audioTrack?.release()
    }
}
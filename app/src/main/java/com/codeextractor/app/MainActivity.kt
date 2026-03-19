package com.codeextractor.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
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
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var webSocket: WebSocketClient? = null
    private var isRecording = false
    private var audioRecord: AudioRecord? = null
    private var pcmData = mutableListOf<Short>()
    private var recordInterval: Job? = null

    private val MODEL = "models/gemini-2.5-flash-preview-native-audio-dialog"
    private val API_KEY = "AIzaSyDFxs8iKlunr6kT8f8hsqKJP3LyBeCkWvs"
    private val HOST = "generativelanguage.googleapis.com"
    private val URL = "wss://$HOST/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=$API_KEY"

    private val CAMERA_REQUEST_CODE = 100
    private val AUDIO_REQUEST_CODE = 200
    private val AUDIO_SAMPLE_RATE = 24000
    private val RECEIVE_SAMPLE_RATE = 24000
    private val AUDIO_CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_ENCODING = AudioFormat.ENCODING_PCM_16BIT
    private val AUDIO_BUFFER_SIZE = AudioRecord.getMinBufferSize(
        AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING
    )

    private val audioQueue = mutableListOf<ByteArray>()
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null

    private val MAX_IMAGE_DIMENSION = 1024
    private val JPEG_QUALITY = 70
    private var lastImageSendTime: Long = 0
    private val IMAGE_SEND_INTERVAL: Long = 3000
    private var isConnected = false
    private var isSpeaking = false

    private var cameraDevice: CameraDevice? = null
    private var cameraCaptureSession: CameraCaptureSession? = null
    private var captureRequestBuilder: CaptureRequest.Builder? = null
    private var imageReader: android.media.ImageReader? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private lateinit var cameraId: String
    private lateinit var previewSize: Size
    private var isCameraActive = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        updateStatusIndicator()

        binding.captureButton.setOnClickListener {
            if (isCameraActive) {
                stopCameraPreview()
                binding.captureButton.text = "Start Capture"
                isCameraActive = false
            } else {
                if (binding.textureView.isAvailable) {
                    startCameraPreview()
                    binding.captureButton.text = "Stop Capture"
                    isCameraActive = true
                } else {
                    binding.textureView.surfaceTextureListener = surfaceTextureListener
                }
            }
        }

        binding.startButton.setOnClickListener {
            checkRecordAudioPermission()
        }

        binding.stopButton.setOnClickListener {
            stopAudioInput()
        }

        connect()
    }

    // region Camera

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            startCameraPreview()
            binding.captureButton.text = "Stop Capture"
            isCameraActive = true
        }
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}
        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            stopCameraPreview()
            binding.captureButton.text = "Start Capture"
            isCameraActive = false
            return true
        }
        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }

    private fun startCameraPreview() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        } else {
            openCameraForPreview()
        }
    }

    private fun stopCameraPreview() {
        closeCamera()
        binding.textureView.surfaceTexture?.let {
            val surface = Surface(it)
            val canvas = surface.lockCanvas(null)
            canvas?.drawColor(android.graphics.Color.BLACK)
            surface.unlockCanvasAndPost(canvas)
            surface.release()
        }
    }

    private fun openCameraForPreview() {
        val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            cameraId = cameraManager.cameraIdList[0]
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
            previewSize = map.getOutputSizes(SurfaceTexture::class.java)[0]

            imageReader = android.media.ImageReader.newInstance(
                MAX_IMAGE_DIMENSION, MAX_IMAGE_DIMENSION, ImageFormat.JPEG, 2
            ).apply {
                setOnImageAvailableListener(imageAvailableListener, cameraHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Error opening camera", e)
        } catch (e: SecurityException) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCameraPreviewSession()
        }
        override fun onDisconnected(camera: CameraDevice) {
            cameraDevice?.close(); cameraDevice = null
        }
        override fun onError(camera: CameraDevice, error: Int) {
            cameraDevice?.close(); cameraDevice = null
            Log.e("Camera", "Camera error: $error")
        }
    }

    private fun createCameraPreviewSession() {
        try {
            val surfaceTexture = binding.textureView.surfaceTexture?.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }
            val previewSurface = Surface(surfaceTexture)

            captureRequestBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                addTarget(previewSurface)
                addTarget(imageReader!!.surface)
            }

            cameraDevice?.createCaptureSession(
                listOf(previewSurface, imageReader!!.surface),
                cameraCaptureSessionCallback, cameraHandler
            )
        } catch (e: CameraAccessException) {
            Log.e("Camera", "Error creating preview session", e)
        }
    }

    private val cameraCaptureSessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            cameraCaptureSession = session
            if (cameraDevice == null) return
            captureRequestBuilder?.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val thread = HandlerThread("UpdatePreview").apply { start() }
            try {
                cameraCaptureSession?.setRepeatingRequest(
                    captureRequestBuilder?.build()!!, null, Handler(thread.looper)
                )
            } catch (e: CameraAccessException) {
                Log.e("Camera", "Error starting repeating request", e)
            }
        }
        override fun onConfigureFailed(session: CameraCaptureSession) {
            Toast.makeText(this@MainActivity, "Camera configuration failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun closeCamera() {
        cameraCaptureSession?.close(); cameraCaptureSession = null
        cameraDevice?.close(); cameraDevice = null
        imageReader?.close(); imageReader = null
    }

    private val imageAvailableListener = android.media.ImageReader.OnImageAvailableListener { reader ->
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastImageSendTime >= IMAGE_SEND_INTERVAL) {
            val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            image.close()
            GlobalScope.launch(Dispatchers.IO) { processAndSendImage(bytes) }
            lastImageSendTime = currentTime
        } else {
            reader.acquireLatestImage()?.close()
        }
    }

    private suspend fun processAndSendImage(imageBytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) ?: return
        val scaled = scaleBitmap(bitmap, MAX_IMAGE_DIMENSION)
        val baos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
        val b64 = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT or Base64.NO_WRAP)
        sendMediaChunk(b64, "image/jpeg")
        scaled.recycle()
        baos.close()
    }

    private fun scaleBitmap(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap
        return if (w > h) {
            val ratio = w.toFloat() / maxDimension
            Bitmap.createScaledBitmap(bitmap, maxDimension, (h / ratio).toInt(), true)
        } else {
            val ratio = h.toFloat() / maxDimension
            Bitmap.createScaledBitmap(bitmap, (w / ratio).toInt(), maxDimension, true)
        }
    }

    // endregion

    // region WebSocket

    private fun connect() {
        val headers = mutableMapOf("Content-Type" to "application/json")
        webSocket = object : WebSocketClient(URI(URL), Draft_6455(), headers) {
            override fun onOpen(handshakedata: ServerHandshake?) {
                isConnected = true
                updateStatusIndicator()
                sendInitialSetupMessage()
            }
            override fun onMessage(message: String?) {
                receiveMessage(message)
            }
            override fun onMessage(bytes: ByteBuffer?) {
                bytes?.let { receiveMessage(String(it.array(), Charsets.UTF_8)) }
            }
            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                isConnected = false
                updateStatusIndicator()
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Connection closed: $reason", Toast.LENGTH_SHORT).show()
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
            put("setup", JSONObject().apply {
                put("model", MODEL)
                put("generation_config", JSONObject().apply {
                    put("response_modalities", org.json.JSONArray().apply { put("AUDIO") })
                })
            })
        }
        webSocket?.send(setupMessage.toString())
    }

    private fun sendMediaChunk(b64Data: String, mimeType: String) {
        if (!isConnected) return
        val msg = buildJsonObject {
            put("realtime_input", buildJsonObject {
                put("media_chunks", buildJsonArray {
                    add(buildJsonObject {
                        put("mime_type", mimeType)
                        put("data", b64Data)
                    })
                })
            })
        }
        webSocket?.send(Json { prettyPrint = false }.encodeToString(msg))
    }

    private fun receiveMessage(message: String?) {
        if (message == null) return
        try {
            val root = JSONObject(message)
            if (!root.has("serverContent")) return
            val serverContent = root.getJSONObject("serverContent")
            if (!serverContent.has("modelTurn")) return
            val parts = serverContent.getJSONObject("modelTurn").optJSONArray("parts") ?: return
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.has("text")) {
                    displayMessage("GEMINI: ${part.getString("text")}")
                }
                if (part.has("inlineData")) {
                    val inlineData = part.getJSONObject("inlineData")
                    if (inlineData.optString("mimeType") == "audio/pcm;rate=24000") {
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
            AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_CONFIG, AUDIO_ENCODING, AUDIO_BUFFER_SIZE
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("Audio", "AudioRecord init failed")
            return
        }
        audioRecord?.startRecording()
        isSpeaking = true
        updateStatusIndicator()
        recordInterval = GlobalScope.launch(Dispatchers.IO) {
            while (isRecording) {
                val buffer = ShortArray(AUDIO_BUFFER_SIZE)
                val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (readSize > 0) {
                    pcmData.addAll(buffer.take(readSize).toList())
                    if (pcmData.size >= readSize) recordChunk()
                }
            }
        }
    }

    private fun recordChunk() {
        if (pcmData.isEmpty()) return
        GlobalScope.launch(Dispatchers.IO) {
            val buffer = ByteBuffer.allocate(pcmData.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            pcmData.forEach { buffer.putShort(it) }
            val base64 = Base64.encodeToString(buffer.array(), Base64.DEFAULT or Base64.NO_WRAP)
            sendMediaChunk(base64, "audio/pcm")
            pcmData.clear()
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
            } catch (e: Exception) {
                Log.e("AudioChunk", "Error processing chunk", e)
            }
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
            synchronized(audioQueue) {
                if (audioQueue.isNotEmpty()) playNextAudioChunk()
            }
        }
    }

    private fun playAudio(byteArray: ByteArray) {
        if (audioTrack == null) {
            audioTrack = AudioTrack(
                android.media.AudioManager.STREAM_MUSIC,
                RECEIVE_SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioTrack.getMinBufferSize(
                    RECEIVE_SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ),
                AudioTrack.MODE_STREAM
            )
        }
        audioTrack?.write(byteArray, 0, byteArray.size)
        audioTrack?.play()
        GlobalScope.launch(Dispatchers.IO) {
            while (audioTrack?.playState == AudioTrack.PLAYSTATE_PLAYING) {
                delay(10)
            }
            audioTrack?.stop()
        }
    }

    // endregion

    // region UI

    private fun displayMessage(message: String) {
        runOnUiThread {
            binding.chatLog.text = "${binding.chatLog.text}\n$message"
        }
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

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            CAMERA_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    startCameraPreview()
                else
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
            }
            AUDIO_REQUEST_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                    startAudioInput()
                else
                    Toast.makeText(this, "Audio permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAudioInput()
        closeCamera()
        cameraThread.quitSafely()
        webSocket?.close()
        audioTrack?.release()
    }
}
package com.codeextractor.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground Service для непрерывной работы аудио-сессии.
 *
 * Обеспечивает:
 *  1. Notification в шторке — система не убьёт процесс
 *  2. Bluetooth SCO — маршрутизация аудио через TWS-наушники
 *  3. Audio Focus — пауза музыки во время сессии
 *
 * foregroundServiceType="microphone|mediaPlayback" в Manifest.
 */
@AndroidEntryPoint
class GeminiLiveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_live_channel"
        private const val NOTIFICATION_ID = 2026
        const val ACTION_START = "com.codeextractor.app.ACTION_START_SESSION"
        const val ACTION_STOP = "com.codeextractor.app.ACTION_STOP_SESSION"

        fun startIntent(context: Context): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_START
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var audioManager: AudioManager? = null
    private var bluetoothScoActive = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        buildNotification(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
                requestAudioFocus()
                routeAudioToBluetooth()
            }
            ACTION_STOP -> {
                releaseAudioFocus()
                releaseBluetoothSco()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                stopSelf()
            }
        }
        return START_STICKY
    }

    // ════════════════════════════════════════════════════════════
    //  BLUETOOTH SCO — аудио через наушники
    // ════════════════════════════════════════════════════════════

    private fun routeAudioToBluetooth() {
        val am = audioManager ?: return
        if (am.isBluetoothScoAvailableOffCall) {
            try {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                bluetoothScoActive = true
            } catch (_: Exception) {
                // Bluetooth SCO недоступен — используем встроенный микрофон
            }
        }
        am.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun releaseBluetoothSco() {
        if (bluetoothScoActive) {
            audioManager?.let {
                it.stopBluetoothSco()
                it.isBluetoothScoOn = false
                it.mode = AudioManager.MODE_NORMAL
            }
            bluetoothScoActive = false
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO FOCUS — пауза музыки
    // ════════════════════════════════════════════════════════════

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* no-op for now */ }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        audioManager?.requestAudioFocus(
            audioFocusListener,
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
    }

    @Suppress("DEPRECATION")
    private fun releaseAudioFocus() {
        audioManager?.abandonAudioFocus(audioFocusListener)
    }

    // ════════════════════════════════════════════════════════════
    //  NOTIFICATION
    // ════════════════════════════════════════════════════════════

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopPendingIntent = PendingIntent.getService(
            this, 1,
            Intent(this, GeminiLiveForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Gemini Live активен")
            .setContentText("Голосовой ассистент слушает")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Стоп", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление активной голосовой сессии"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseBluetoothSco()
        releaseAudioFocus()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

package com.learnde.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground Service для непрерывной работы голосовой сессии.
 *
 * КРИТИЧЕСКИЕ ФИКСЫ:
 *   [1] Если intent==null или action неизвестен — ВСЁ РАВНО вызываем
 *       startForeground(dummy notification) до stopSelf(). Это требование
 *       Android 12+ FGS контракта — иначе ForegroundServiceDidNotStartInTimeException.
 *   [2] onTaskRemoved: корректная остановка при свайпе приложения.
 *   [3] Android 31+: используем AudioManager.setCommunicationDevice (стабильнее).
 *   [4] Android 26+ обязателен NotificationChannel ДО любой отправки.
 */
@AndroidEntryPoint
class GeminiLiveForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "gemini_live_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_START = "com.learnde.app.ACTION_START_SESSION"
        const val ACTION_STOP = "com.learnde.app.ACTION_STOP_SESSION"
        const val EXTRA_FORCE_SPEAKER = "extra_force_speaker"

        fun startIntent(context: Context, forceSpeaker: Boolean = true): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_FORCE_SPEAKER, forceSpeaker)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private var audioManager: AudioManager? = null
    private var bluetoothScoActive = false
    private var communicationDeviceSet = false

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // ═══ ВСЕГДА startForeground() сначала, ДО любых проверок — иначе FGS contract violated ═══
        startForegroundSafe()

        when (intent?.action) {
            ACTION_START -> {
                requestAudioFocus()
                val forceSpeaker = intent.getBooleanExtra(EXTRA_FORCE_SPEAKER, true)
                routeAudio(forceSpeaker)
            }
            ACTION_STOP -> {
                releaseAudioFocus()
                releaseAudioRouting()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            else -> {
                // intent == null (rescheduled by system) или неизвестный action.
                // Мы ДОЛЖНЫ были вызвать startForeground (сделано выше), теперь можно stopSelf.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification())
            }
        } catch (e: Exception) {
            // На некоторых OEM бывают нестандартные рестрикции — просто логируем.
            android.util.Log.e("FGS", "startForeground failed: ${e.message}")
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO ROUTING
    // ════════════════════════════════════════════════════════════

    private fun routeAudio(forceSpeaker: Boolean) {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: современный API
            runCatching {
                val devices = am.availableCommunicationDevices
                val target: AudioDeviceInfo? = if (forceSpeaker) {
                    devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                } else {
                    devices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                    } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                if (target != null) {
                    communicationDeviceSet = am.setCommunicationDevice(target)
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                }
            }
            return
        }

        // ═══ Legacy (Android 8-11): старый путь ═══
        @Suppress("DEPRECATION")
        val hasBtHeadset = runCatching {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        }.getOrDefault(false)

        @Suppress("DEPRECATION")
        if (!forceSpeaker && hasBtHeadset && am.isBluetoothScoAvailableOffCall) {
            runCatching {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                bluetoothScoActive = true
                am.mode = AudioManager.MODE_IN_COMMUNICATION
            }
        } else {
            am.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        }
    }

    private fun releaseAudioRouting() {
        val am = audioManager ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (communicationDeviceSet) {
                runCatching { am.clearCommunicationDevice() }
                communicationDeviceSet = false
            }
            am.mode = AudioManager.MODE_NORMAL
            return
        }

        @Suppress("DEPRECATION")
        run {
            if (bluetoothScoActive) {
                runCatching { am.stopBluetoothSco() }
                am.isBluetoothScoOn = false
            }
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
        bluetoothScoActive = false
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO FOCUS
    // ════════════════════════════════════════════════════════════

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { /* no-op */ }

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
            Intent(this, GeminiLiveForegroundService::class.java).apply {
                action = ACTION_STOP
            },
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
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "AI Assistant",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление активной голосовой сессии"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    // ════════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ════════════════════════════════════════════════════════════

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        releaseAudioFocus()
        releaseAudioRouting()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseAudioRouting()
        releaseAudioFocus()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
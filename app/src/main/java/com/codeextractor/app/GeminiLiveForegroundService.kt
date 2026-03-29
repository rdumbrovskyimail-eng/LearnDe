package com.codeextractor.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stub — будет реализован на Этапе 8 (Lifecycle/WakeLock).
 * Объявлен в AndroidManifest, поэтому класс обязан существовать.
 */
class GeminiLiveForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf()
        return START_NOT_STICKY
    }
}
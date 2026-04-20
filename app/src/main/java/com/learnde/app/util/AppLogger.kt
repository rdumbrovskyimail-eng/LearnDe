package com.learnde.app.util

import android.util.Log
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Централизованный логгер приложения.
 * - Timber для logcat
 * - In-memory буфер для UI-лога и экспорта
 * - Потокобезопасный доступ к буферу
 */
@Singleton
class AppLogger @Inject constructor(
    private val buffer: LogBuffer,
) {

    companion object {
        private const val TAG = "GeminiLive"
        private const val MAX_LOG_LINES = 500
        private const val MAX_DISPLAY_CHARS = 3000
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val logBuffer = ArrayDeque<String>(MAX_LOG_LINES + 10)

    private var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Timber.plant(Timber.DebugTree())
        Timber.tag(TAG).d("AppLogger initialized")
    }

    fun d(msg: String) {
        Timber.tag(TAG).d(msg)
        buffer.append(LogLevel.D, msg)
    }

    fun i(msg: String) {
        Timber.tag(TAG).i(msg)
        buffer.append(LogLevel.I, msg)
    }

    fun w(msg: String) {
        Timber.tag(TAG).w(msg)
        buffer.append(LogLevel.W, msg)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        Timber.tag(TAG).e(throwable, msg)
        buffer.append(LogLevel.E, msg, throwable)
    }


}
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

    // ─── Patch 4: UI access to log buffer ───

    /** Полный дамп всех логов из буфера. Для handleSaveLog(). */
    fun getFullLog(): String = buffer.exportAsText()

    /**
     * Последние N записей как одна строка для UI-дебаг-блока.
     * Ограничен чарами, чтобы не раздувать state VoiceScreen.
     */
    fun getDisplayLog(): String {
        val entries = buffer.entries.value
        val recent = entries.takeLast(100)
        val text = recent.joinToString("\n") { it.formatted() }
        return if (text.length > MAX_DISPLAY_CHARS)
            text.takeLast(MAX_DISPLAY_CHARS)
        else text
    }
}
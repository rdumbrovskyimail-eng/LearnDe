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
class AppLogger @Inject constructor() {

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
        val line = "[${timeFormat.format(Date())}] $msg"
        Timber.tag(TAG).d(msg)
        appendToBuffer(line)
    }

    fun w(msg: String) {
        val line = "[${timeFormat.format(Date())}] ⚠ $msg"
        Timber.tag(TAG).w(msg)
        appendToBuffer(line)
    }

    fun e(msg: String, throwable: Throwable? = null) {
        val line = "[${timeFormat.format(Date())}] ✖ $msg"
        Timber.tag(TAG).e(throwable, msg)
        appendToBuffer(line)
    }

    private fun appendToBuffer(line: String) {
        synchronized(logBuffer) {
            logBuffer.addLast(line)
            while (logBuffer.size > MAX_LOG_LINES) {
                logBuffer.removeFirst()
            }
        }
    }

    /** Полный лог для экспорта в файл */
    fun getFullLog(): String = synchronized(logBuffer) {
        logBuffer.joinToString("\n")
    }

    /** Обрезанный лог для отображения в UI */
    fun getDisplayLog(): String {
        val full = getFullLog()
        return if (full.length > MAX_DISPLAY_CHARS) full.takeLast(MAX_DISPLAY_CHARS) else full
    }

    /** Очистка буфера */
    fun clear() = synchronized(logBuffer) {
        logBuffer.clear()
    }
}
// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/util/DateFormatters.kt
//
// Singleton-форматтеры дат для модуля A1.
// SimpleDateFormat — тяжёлый объект; создавать его на каждый recompose
// в LazyColumn вызывает GC churn и микрофризы при скролле.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object A1DateFormatters {
    private val ruLocale = Locale("ru")
    
    // SimpleDateFormat НЕ потокобезопасен, но мы вызываем его только из UI thread.
    private val shortDate by lazy { SimpleDateFormat("d MMM", ruLocale) }
    private val fullDate by lazy { SimpleDateFormat("d MMM yyyy, HH:mm", ruLocale) }
    private val timeOnly by lazy { SimpleDateFormat("HH:mm", ruLocale) }
    
    fun formatShortDate(ts: Long): String = shortDate.format(Date(ts))
    fun formatFullDate(ts: Long): String = fullDate.format(Date(ts))
    fun formatTimeOnly(ts: Long): String = timeOnly.format(Date(ts))

    /** Двухстрочное представление: дата сверху, время снизу. Не обрезается на 360dp экранах. */
    fun formatTwoLine(timestampMs: Long): Pair<String, String> {
        val date = java.text.SimpleDateFormat("d MMM yyyy", ruLocale).format(Date(timestampMs))
        val time = java.text.SimpleDateFormat("HH:mm", ruLocale).format(Date(timestampMs))
        return date to time
    }
}
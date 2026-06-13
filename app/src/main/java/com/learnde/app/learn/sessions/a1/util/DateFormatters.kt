// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/util/DateFormatters.kt
//
// Singleton-форматтеры дат для модуля A1.
// SimpleDateFormat — тяжёлый объект; создавать его на каждый recompose
// в LazyColumn вызывает GC churn и микрофризы при скролле.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object A1DateFormatters {
    private val ruLocale = Locale("ru")
    private val systemZone = ZoneId.systemDefault()

    private val shortDateFormatter = DateTimeFormatter.ofPattern("d MMM", ruLocale)
    private val fullDateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", ruLocale)
    private val timeOnlyFormatter = DateTimeFormatter.ofPattern("HH:mm", ruLocale)
    private val dateOnlyYearFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", ruLocale)

    fun formatShortDate(ts: Long): String {
        val instant = Instant.ofEpochMilli(ts)
        return shortDateFormatter.format(instant.atZone(systemZone))
    }

    fun formatFullDate(ts: Long): String {
        val instant = Instant.ofEpochMilli(ts)
        return fullDateFormatter.format(instant.atZone(systemZone))
    }

    fun formatTimeOnly(ts: Long): String {
        val instant = Instant.ofEpochMilli(ts)
        return timeOnlyFormatter.format(instant.atZone(systemZone))
    }

    fun formatTwoLine(timestampMs: Long): Pair<String, String> {
        val instant = Instant.ofEpochMilli(timestampMs)
        val zonedDateTime = instant.atZone(systemZone)
        val date = dateOnlyYearFormatter.format(zonedDateTime)
        val time = timeOnlyFormatter.format(zonedDateTime)
        return date to time
    }
}
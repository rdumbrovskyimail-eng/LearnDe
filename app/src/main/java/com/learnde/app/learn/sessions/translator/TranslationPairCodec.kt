// ════════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslationPairCodec.kt
//
// Кодирует пару (оригинал, перевод) в строку, которая хранится
// в ConversationMessage.text. UI расшифровывает обратно для
// двухсекционного пузыря.
//
// Формат: JSON-объект с короткими ключами (минимум байт).
// Префикс "TP::" — маркер того, что это translation pair, а не
// обычный текст. Позволяет fallback на отображение как Text если
// парсинг сломался.
// ════════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import org.json.JSONObject

/**
 * Атомарная пара оригинал+перевод, хранится сериализованно
 * в ConversationMessage.text для совместимости с существующей
 * моделью данных.
 */
data class TranslationPair(
    val originalText: String,
    val originalLang: String,    // "ru" | "uk" | "de"
    val translatedText: String,  // пусто = ждём перевод
    val translatedLang: String,  // "de" | "ru" | "unknown"
)

object TranslationPairCodec {

    private const val PREFIX = "TP::"

    fun encode(pair: TranslationPair): String {
        val json = JSONObject().apply {
            put("o", pair.originalText)
            put("ol", pair.originalLang)
            put("t", pair.translatedText)
            put("tl", pair.translatedLang)
        }
        return PREFIX + json.toString()
    }

    fun decode(raw: String): TranslationPair? {
        if (!raw.startsWith(PREFIX)) return null
        return runCatching {
            val json = JSONObject(raw.substring(PREFIX.length))
            TranslationPair(
                originalText = json.optString("o", ""),
                originalLang = json.optString("ol", "unknown"),
                translatedText = json.optString("t", ""),
                translatedLang = json.optString("tl", "unknown"),
            )
        }.getOrNull()
    }

    /** Быстрая проверка без парсинга — для определения типа пузыря в UI. */
    fun isPair(raw: String): Boolean = raw.startsWith(PREFIX)
}
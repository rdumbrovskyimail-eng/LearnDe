// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/domain/ErrorDiagnosis.kt
//
// Двухосевая модель диагностики ошибок L2-ученика
// (по Selinker, "Interlanguage", 1972 + Corder "Error Analysis", 1967).
//
// ОСЬ 1 (SOURCE) — источник ошибки:
//   L1_TRANSFER          — влияние родного языка (русского).
//                          Пример: "Ich habe Buch" (в русском нет артиклей).
//   OVERGENERALIZATION   — ученик применил правило слишком широко.
//                          Пример: *"gehte" вместо "ging" (правило -te для всех глаголов).
//   SIMPLIFICATION       — упрощение формы.
//                          Пример: *"Ich gehen" вместо "Ich gehe".
//   COMMUNICATION_STRATEGY — сознательный "обход" (перефразирование).
//                          Пример: "рыба в воде" вместо "Aquarium".
//   NONE                  — ошибки нет.
//
// ОСЬ 2 (DEPTH) — глубина ошибки:
//   SLIP     — ученик знает правило, но оговорился (оцени: под стрессом? в шуме?)
//   MISTAKE  — ученик неуверен, может исправить при подсказке
//   ERROR    — ученик не знает правила вообще (нужна эксплицитная инструкция)
//   NONE     — ошибки нет.
//
// КАТЕГОРИЯ (ERROR_CATEGORY):
//   GENDER, CASE, WORD_ORDER, LEXICAL, PHONOLOGY, PRAGMATICS,
//   CONJUGATION, NEGATION, PLURAL, PREPOSITION, NONE
//
// Эта триада (source × depth × category) однозначно определяет
// тип интервенции:
//   - L1_TRANSFER × ERROR × GENDER → концептуальное объяснение рода
//   - OVERGENERALIZATION × MISTAKE × CONJUGATION → pattern drill
//   - ANY × SLIP × ANY → просто повтори, не объясняй
//   - ANY × MISTAKE × PRAGMATICS → расширь контекст
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

enum class ErrorSource {
    NONE,
    L1_TRANSFER,
    OVERGENERALIZATION,
    SIMPLIFICATION,
    COMMUNICATION_STRATEGY;

    companion object {
        fun fromString(s: String?): ErrorSource =
            runCatching { valueOf(s?.uppercase() ?: "NONE") }.getOrDefault(NONE)
    }
}

enum class ErrorDepth {
    NONE,
    SLIP,
    MISTAKE,
    ERROR;

    companion object {
        fun fromString(s: String?): ErrorDepth =
            runCatching { valueOf(s?.uppercase() ?: "NONE") }.getOrDefault(NONE)
    }
}

enum class ErrorCategory {
    NONE,
    GENDER,       // der/die/das
    CASE,         // Akkusativ/Dativ/Genitiv
    WORD_ORDER,   // глагол на 2-й позиции и т.д.
    LEXICAL,      // не знал слово
    PHONOLOGY,    // произношение
    PRAGMATICS,   // du vs Sie, регистр
    CONJUGATION,  // спряжение глагола
    NEGATION,     // nicht/kein
    PLURAL,       // -e/-er/-(e)n/-s
    PREPOSITION;  // mit+Dat, für+Akk

    companion object {
        fun fromString(s: String?): ErrorCategory =
            runCatching { valueOf(s?.uppercase() ?: "NONE") }.getOrDefault(NONE)
    }
}

/**
 * Полная диагностика одного ответа ученика.
 * Возвращается Gemini через FN_EVALUATE_AND_UPDATE и сохраняется в БД
 * для аналитики и адаптивного планирования.
 */
data class ErrorDiagnosis(
    val source: ErrorSource = ErrorSource.NONE,
    val depth: ErrorDepth = ErrorDepth.NONE,
    val category: ErrorCategory = ErrorCategory.NONE,
    /** Конкретика: "использовал Nominativ вместо Akkusativ после haben". */
    val specifics: String = "",
) {
    val isError: Boolean get() = depth != ErrorDepth.NONE

    companion object {
        /** Синглтон "ошибки нет" — удобно для reset-состояний. */
        val None = ErrorDiagnosis()
    }

    /** Какая педагогическая интервенция требуется. */
    fun recommendedIntervention(): Intervention = when {
        !isError -> Intervention.PRAISE
        depth == ErrorDepth.SLIP -> Intervention.SILENT_RECAST
        depth == ErrorDepth.MISTAKE && category == ErrorCategory.PHONOLOGY -> Intervention.PRONUNCIATION_DRILL
        depth == ErrorDepth.MISTAKE -> Intervention.GUIDED_SELF_CORRECTION
        depth == ErrorDepth.ERROR && source == ErrorSource.L1_TRANSFER -> Intervention.CONCEPTUAL_EXPLANATION
        depth == ErrorDepth.ERROR && source == ErrorSource.OVERGENERALIZATION -> Intervention.CONTRAST_DRILL
        depth == ErrorDepth.ERROR -> Intervention.EXPLICIT_INSTRUCTION
        else -> Intervention.PRAISE
    }

    fun toJson(): String = """
        {"source":"$source","depth":"$depth","category":"$category","specifics":"${specifics.replace("\"", "'")}"}
    """.trimIndent()
}

enum class Intervention {
    PRAISE,                    // просто похвали
    SILENT_RECAST,             // незаметно повтори правильно, не комментируя
    GUIDED_SELF_CORRECTION,    // задай наводящий вопрос, пусть исправит сам
    PRONUNCIATION_DRILL,       // попроси повторить звук/слово
    CONTRAST_DRILL,            // покажи 2 похожих формы для сравнения
    CONCEPTUAL_EXPLANATION,    // объясни концепт (артикли, падежи)
    EXPLICIT_INSTRUCTION,      // дай правило явно
}
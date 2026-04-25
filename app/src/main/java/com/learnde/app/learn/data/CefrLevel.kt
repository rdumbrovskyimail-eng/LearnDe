// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/data/CefrLevel.kt
//
// Унифицированный enum уровней CEFR для будущей поддержки A2/B1/B2.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data

enum class CefrLevel(
    val code: String,
    val displayName: String,
    val assetsFolder: String,
    val targetLemmaCount: Int,
) {
    A1("A1", "A1 — Начинающий", "a1", 835),
    A2("A2", "A2 — Элементарный", "a2", 1300),
    B1("B1", "B1 — Средний", "b1", 2400),
    B2("B2", "B2 — Выше среднего", "b2", 4000);
    
    companion object {
        fun fromCode(code: String): CefrLevel? = values().firstOrNull { it.code == code }
    }
}
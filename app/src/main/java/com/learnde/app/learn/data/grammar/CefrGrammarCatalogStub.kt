// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (заглушка)
// Путь: app/src/main/java/com/learnde/app/learn/data/grammar/CefrGrammarCatalogStub.kt
//
// Структура для будущих каталогов грамматики A2/B1/B2.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.grammar

import com.learnde.app.learn.data.CefrLevel
import com.learnde.app.learn.data.db.GrammarRuleA1Entity

object CefrGrammarCatalogs {
    fun rulesForLevel(level: CefrLevel): List<GrammarRuleA1Entity> = when (level) {
        CefrLevel.A1 -> A1GrammarCatalog.RULES
        // TODO: Добавить когда будет готов каталог.
        CefrLevel.A2 -> emptyList()
        CefrLevel.B1 -> emptyList()
        CefrLevel.B2 -> emptyList()
    }
}
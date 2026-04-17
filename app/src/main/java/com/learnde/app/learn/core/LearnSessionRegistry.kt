// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionRegistry.kt
//
// Реестр всех учебных сессий приложения.
// Чтобы добавить новую — инжектьте её в конструктор и добавьте в map.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.learn.sessions.a0a1.A0a1LearnSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnSessionRegistry @Inject constructor(
    a0a1: A0a1LearnSession
) {
    private val sessions: Map<String, LearnSession> = mapOf(
        a0a1.id to a0a1
    )

    fun get(id: String): LearnSession? = sessions[id]

    fun all(): List<LearnSession> = sessions.values.toList()
}

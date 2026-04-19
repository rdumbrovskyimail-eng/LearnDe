// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionRegistry.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.learn.sessions.a0a1.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnSessionRegistry @Inject constructor(
    a0: A0LearnSession,
    a1: A1LearnSession,
    a2: A2LearnSession,
    b1: B1LearnSession,
    b2: B2LearnSession
) {
    private val sessions: Map<String, LearnSession> = mapOf(
        a0.id to a0,
        a1.id to a1,
        a2.id to a2,
        b1.id to b1,
        b2.id to b2
    )

    fun get(id: String): LearnSession? = sessions[id]
    fun all(): List<LearnSession> = sessions.values.toList()
}
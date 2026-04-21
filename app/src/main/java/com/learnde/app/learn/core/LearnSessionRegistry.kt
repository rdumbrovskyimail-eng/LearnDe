// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionRegistry.kt
//
// Добавлена A1SituationSession (новая учебная сессия A1).
// Остальные (тестовые) оставлены как были.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.learn.sessions.a0a1.A0LearnSession
import com.learnde.app.learn.sessions.a0a1.A1LearnSession
import com.learnde.app.learn.sessions.a0a1.A2LearnSession
import com.learnde.app.learn.sessions.a0a1.B1LearnSession
import com.learnde.app.learn.sessions.a0a1.B2LearnSession
import com.learnde.app.learn.sessions.a1.A1SituationSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnSessionRegistry @Inject constructor(
    // Тестовые сессии (определяют уровень ученика)
    a0: A0LearnSession,
    a1Test: A1LearnSession,
    a2: A2LearnSession,
    b1: B1LearnSession,
    b2: B2LearnSession,
    // Учебная сессия A1
    a1Learning: A1SituationSession,
    // Живой переводчик
    translator: com.learnde.app.learn.sessions.translator.TranslatorSession,
) {
    private val sessions: Map<String, LearnSession> = mapOf(
        a0.id       to a0,
        a1Test.id   to a1Test,
        a2.id       to a2,
        b1.id       to b1,
        b2.id       to b2,
        a1Learning.id to a1Learning,
        translator.id to translator,
    )

    fun get(id: String): LearnSession? = sessions[id]
    fun all(): List<LearnSession> = sessions.values.toList()
}

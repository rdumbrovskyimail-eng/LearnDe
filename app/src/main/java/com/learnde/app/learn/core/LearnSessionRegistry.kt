// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionRegistry.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Добавлена A1ReviewSession (id="a1_review")
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.learn.sessions.a0a1.A0LearnSession
import com.learnde.app.learn.sessions.a0a1.A1LearnSession
import com.learnde.app.learn.sessions.a0a1.A2LearnSession
import com.learnde.app.learn.sessions.a0a1.B1LearnSession
import com.learnde.app.learn.sessions.a0a1.B2LearnSession
import com.learnde.app.learn.sessions.a1.A1ReviewSession
import com.learnde.app.learn.sessions.a1.A1SituationSession
import com.learnde.app.learn.sessions.translator.TranslatorSession
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
    // Учебные сессии A1
    a1Learning: A1SituationSession,
    a1Review: A1ReviewSession,                // v3.2: NEW
    // Живой переводчик
    translator: TranslatorSession,
) {
    private val sessions: Map<String, LearnSession> = mapOf(
        a0.id         to a0,
        a1Test.id     to a1Test,
        a2.id         to a2,
        b1.id         to b1,
        b2.id         to b2,
        a1Learning.id to a1Learning,
        a1Review.id   to a1Review,             // v3.2: NEW
        translator.id to translator,
    )

    fun get(id: String): LearnSession? = sessions[id]
    fun all(): List<LearnSession> = sessions.values.toList()
}
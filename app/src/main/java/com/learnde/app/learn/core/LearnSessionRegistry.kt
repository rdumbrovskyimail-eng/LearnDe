// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionRegistry.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - Добавлена A1ReviewSession (id="a1_review")
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.learn.sessions.a1.A1ReviewSession
import com.learnde.app.learn.sessions.a1.A1SituationSession
import com.learnde.app.learn.sessions.a1.v2.A1AdaptiveSession
// import com.learnde.app.learn.sessions.a1book.A1BookSession
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnSessionRegistry @Inject constructor(
    a1Learning: A1SituationSession,
    a1Review: A1ReviewSession,
    a1Adaptive: A1AdaptiveSession,
    // a1Book: A1BookSession,
) {
    private val sessions: Map<String, LearnSession> = mapOf(
        a1Learning.id to a1Learning,
        a1Review.id   to a1Review,
        a1Adaptive.id to a1Adaptive,
        // a1Book.id     to a1Book,
    )

    fun get(id: String): LearnSession? = sessions[id]
    fun all(): List<LearnSession> = sessions.values.toList()
}
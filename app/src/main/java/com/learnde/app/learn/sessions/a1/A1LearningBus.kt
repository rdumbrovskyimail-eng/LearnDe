// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningBus.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - extraBufferCapacity: 32 → 128 (было мало при параллельных tool calls)
//   - Добавлен emitSuspend() для критичных событий (LemmaEvaluated,
//     SessionFinished) — они не должны теряться никогда
//   - emit() остался для UI-некритичных уведомлений
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.Intervention
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class A1LearningEvent {
    data class PhaseChanged(val phase: A1Phase) : A1LearningEvent()
    data class LemmaHeard(val lemma: String) : A1LearningEvent()
    data class LemmaProduced(val lemma: String, val quality: Int) : A1LearningEvent()

    data class LemmaEvaluated(
        val lemma: String,
        val quality: Int,
        val diagnosis: ErrorDiagnosis,
        val intervention: Intervention,
        val feedback: String,
    ) : A1LearningEvent() {
        val wasCorrect: Boolean get() = !diagnosis.isError
    }

    data class GrammarIntroduced(val ruleId: String, val ruleName: String) : A1LearningEvent()
    data class SessionFinished(val overallQuality: Int, val feedback: String) : A1LearningEvent()
}

enum class A1Phase { IDLE, WARM_UP, INTRODUCE, DRILL, APPLY, GRAMMAR, COOL_DOWN, FINISHED }

@Singleton
class A1LearningBus @Inject constructor() {

    private val _events = MutableSharedFlow<A1LearningEvent>(
        replay = 0,
        extraBufferCapacity = 128,          // v3.2: было 32
        onBufferOverflow = BufferOverflow.SUSPEND  // v3.2: было DROP_OLDEST
    )
    val events: SharedFlow<A1LearningEvent> = _events.asSharedFlow()

    /**
     * Быстрый emit для UI-событий (PhaseChanged, LemmaHeard) —
     * может уронить событие, если буфер переполнен, но для UI это не критично.
     */
    fun emit(event: A1LearningEvent) {
        _events.tryEmit(event)
    }

    /**
     * v3.2: Suspend-emit для КРИТИЧНЫХ событий (LemmaEvaluated, SessionFinished,
     * GrammarIntroduced). Если буфер полон — приостановится, но не потеряет.
     */
    suspend fun emitSuspend(event: A1LearningEvent) {
        _events.emit(event)
    }
}
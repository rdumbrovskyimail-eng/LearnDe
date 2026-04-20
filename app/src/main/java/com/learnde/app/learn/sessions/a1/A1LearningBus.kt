// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningBus.kt
//
// SharedFlow-шина для событий обучения A1 — UI подписывается
// и обновляется в реальном времени. Пока Gemini "живёт" внутри
// сессии, ученик видит каждое событие: фаза сменилась, лемма
// проверена, правило введено.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Событие, которое UI получает из сессии. */
sealed class A1LearningEvent {
    data class PhaseChanged(val phase: A1Phase) : A1LearningEvent()
    data class LemmaHeard(val lemma: String) : A1LearningEvent()
    data class LemmaProduced(val lemma: String, val quality: Int) : A1LearningEvent()
    data class LemmaEvaluated(
        val lemma: String,
        val quality: Int,
        val wasCorrect: Boolean,
        val feedback: String,
    ) : A1LearningEvent()
    data class GrammarIntroduced(val ruleId: String, val ruleName: String) : A1LearningEvent()
    data class SessionFinished(val overallQuality: Int, val feedback: String) : A1LearningEvent()
}

enum class A1Phase { IDLE, WARM_UP, INTRODUCE, DRILL, APPLY, GRAMMAR, COOL_DOWN, FINISHED }

@Singleton
class A1LearningBus @Inject constructor() {

    private val _events = MutableSharedFlow<A1LearningEvent>(
        replay = 0,
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<A1LearningEvent> = _events.asSharedFlow()

    fun emit(event: A1LearningEvent) {
        _events.tryEmit(event)
    }
}

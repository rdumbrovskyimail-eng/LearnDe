// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/test/a0a1/A0a1TestBus.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.test.a0a1

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

data class AwardPayload(
    val points: Int,
    val feedback: String,
    val isCorrect: Boolean,
    val reason: String,
    val scoreRationale: String
)

data class QuestionPayload(
    val text: String,
    val index: Int
)

@Singleton
class A0a1TestBus @Inject constructor() {

    // ───── Оценки: полный разбор от ИИ ─────
    private val _awards = MutableSharedFlow<AwardPayload>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val awards: SharedFlow<AwardPayload> = _awards.asSharedFlow()

    // ───── Вопросы: текст перед озвучиванием ─────
    private val _questions = MutableSharedFlow<QuestionPayload>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val questions: SharedFlow<QuestionPayload> = _questions.asSharedFlow()

    // ───── Финал ─────
    private val _finished = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    // ───── Dedup по callId ─────
    private val processedIds = ConcurrentHashMap.newKeySet<String>()
    private val maxProcessed = 512

    fun tryConsume(id: String): Boolean {
        if (id.isBlank()) return true
        val added = processedIds.add(id)
        if (processedIds.size > maxProcessed) {
            val iter = processedIds.iterator()
            if (iter.hasNext()) {
                iter.next()
                iter.remove()
            }
        }
        return added
    }

    fun reset() {
        processedIds.clear()
    }

    fun publishAward(payload: AwardPayload) { _awards.tryEmit(payload) }
    fun publishQuestion(payload: QuestionPayload) { _questions.tryEmit(payload) }
    fun publishFinish() { _finished.tryEmit(Unit) }
}
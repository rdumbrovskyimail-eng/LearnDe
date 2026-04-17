// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestBus.kt
//
// Упрощено: вместо PointsAwarded — просто Int (балл 0..3).
// Номер вопроса считает ViewModel сам (счётчик вызовов).
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class A0a1TestBus @Inject constructor() {

    // ───── Оценки: просто балл 0..3 ─────
    private val _awards = MutableSharedFlow<Int>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val awards: SharedFlow<Int> = _awards.asSharedFlow()

    // ───── Финал ─────
    private val _finished = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val finished: SharedFlow<Unit> = _finished.asSharedFlow()

    // ───── Сигнал начала теста (UI → VoiceViewModel) ─────
    private val _startSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val startSignal: SharedFlow<Unit> = _startSignal.asSharedFlow()

    // ───── Сигнал выхода из теста (UI → VoiceViewModel) ─────
    private val _exitSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 2,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val exitSignal: SharedFlow<Unit> = _exitSignal.asSharedFlow()

    fun publishAward(points: Int) { _awards.tryEmit(points) }
    fun publishFinish()            { _finished.tryEmit(Unit) }
    fun publishStart()             { _startSignal.tryEmit(Unit) }
    fun publishExit()              { _exitSignal.tryEmit(Unit) }
}

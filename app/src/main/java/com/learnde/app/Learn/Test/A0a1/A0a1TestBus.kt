// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/Learn/Test/A0a1/A0a1TestBus.kt
//
// Шина событий теста A0-A1. Связывает три компонента:
//   • ToolRegistry        — публикует awards и finish при function calls от Gemini.
//   • VoiceViewModel      — слушает startSignal и exitSignal, переключает режим сессии.
//   • A0a1TestViewModel   — слушает awards и finished, рисует UI.
//
// Вся логика суммирования баллов и определения A0/A1 живёт в A0a1TestViewModel.
// Bus — только транспорт.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.Learn.Test.A0a1

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Одна оценка, прилетевшая от модели через award_points. */
data class PointsAwarded(
    val questionNumber: Int,   // 1..20
    val points: Int,           // 0..3
    val reason: String
)

@Singleton
class A0a1TestBus @Inject constructor() {

    // ───── Оценки ─────
    private val _awards = MutableSharedFlow<PointsAwarded>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val awards: SharedFlow<PointsAwarded> = _awards.asSharedFlow()

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

    fun publishAward(award: PointsAwarded) { _awards.tryEmit(award) }
    fun publishFinish()                    { _finished.tryEmit(Unit) }
    fun publishStart()                     { _startSignal.tryEmit(Unit) }
    fun publishExit()                      { _exitSignal.tryEmit(Unit) }
}

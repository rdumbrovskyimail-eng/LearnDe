// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionController.kt
//
// Singleton-координатор активной учебной сессии.
//
// Одна активная сессия в каждый момент времени. VoiceViewModel
// подписывается на `active` (+ на `restartSignal` для re-enter того
// же сеанса) и переключает Gemini Live setup.
//
// Изменения v2:
//   • restartTick: StateFlow<Int> → restartSignal: SharedFlow<Unit>.
//     SharedFlow без replay чище: не требует firstTick-костыля
//     на стороне коллектора.
//   • enter(): сначала публикуем _active.value, потом onEnter().
//     Это даёт подписчикам увидеть новую сессию и среагировать на
//     bus-события, которые onEnter() может сразу опубликовать.
//   • exit(): сначала снимаем _active.value=null, потом onExit().
//     Симметрично enter — подписчики видят null до onExit.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LearnSessionController @Inject constructor() {

    private val lock = Mutex()

    private val _active = MutableStateFlow<LearnSession?>(null)
    /** Текущая активная сессия, null если нет. */
    val active: StateFlow<LearnSession?> = _active.asStateFlow()

    /**
     * Сигнал повторного входа в уже активную сессию («пройти заново»).
     * SharedFlow без replay: коллектор получает только события, которые
     * случились ПОСЛЕ подписки — без костыля drop(1) / firstTick.
     * Используется вместе с `active`: если активная сессия та же, но
     * пришёл сигнал — полный reconnect/restart.
     */
    private val _restartSignal = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val restartSignal: SharedFlow<Unit> = _restartSignal.asSharedFlow()

    /**
     * Войти в сессию.
     *  - Если активна та же — публикуем restartSignal и зовём onEnter()
     *    для сброса внутреннего state. _active не меняется.
     *  - Если активна другая — вызываем её onExit(), затем выставляем
     *    _active (подписчики видят новую сессию) и только потом
     *    зовём onEnter() — чтобы шина событий onEnter()'а летела
     *    уже в актуальный active.
     */
    suspend fun enter(session: LearnSession) {
        lock.withLock {
            val prev = _active.value
            if (prev === session) {
                // Повторный вход — публикуем сигнал, пусть коллекторы сами решат, сбрасывать ли onEnter
                _restartSignal.tryEmit(Unit)
                session.onEnter()  // reset внутреннего state
                return@withLock
            }
            if (prev != null) runCatching { prev.onExit() }
            _active.value = session       // ← СНАЧАЛА публикуем
            session.onEnter()             // ← потом onEnter (может публиковать в bus'ы)
        }
    }

    /**
     * Выход из активной сессии.
     * Сначала снимаем флаг, потом onExit — симметрично enter:
     * подписчики сразу видят null и не дёргают onExit параллельно.
     */
    suspend fun exit() {
        lock.withLock {
            val cur = _active.value ?: return@withLock
            _active.value = null
            runCatching { cur.onExit() }
        }
    }
}
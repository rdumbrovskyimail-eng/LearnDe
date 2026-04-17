// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnSessionController.kt
//
// Singleton-координатор активной учебной сессии.
//
// Одна активная сессия в каждый момент времени. VoiceViewModel
// подписывается на `active` (+ на `restartTick` для re-enter того же
// же сеанса) и переключает Gemini Live setup.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
     * Счётчик re-enter в уже активную сессию (для «пройти заново»).
     * StateFlow<Int>, drop(1) в коллекторе, чтобы не реагировать на
     * начальное значение 0. Используется вместе с active: если active
     * не изменился, но счётчик увеличился → полный reconnect/restart.
     */
    private val _restartTick = MutableStateFlow(0)
    val restartTick: StateFlow<Int> = _restartTick.asStateFlow()

    /**
     * Войти в сессию. Если уже активна та же — bump restartTick.
     * Если активна другая — вызовет её onExit(), затем onEnter() новой.
     */
    suspend fun enter(session: LearnSession) {
        lock.withLock {
            val prev = _active.value
            if (prev === session) {
                session.onEnter()           // повторный вход = reset внутреннего state
                _restartTick.update { it + 1 }
                return@withLock
            }
            if (prev != null) runCatching { prev.onExit() }
            session.onEnter()
            _active.value = session
        }
    }

    /** Выход из активной сессии. */
    suspend fun exit() {
        lock.withLock {
            val cur = _active.value ?: return@withLock
            runCatching { cur.onExit() }
            _active.value = null
        }
    }
}

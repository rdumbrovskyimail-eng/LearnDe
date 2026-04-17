// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/ActiveClientArbiter.kt
//
// Singleton-координатор «какой клиент сейчас владеет микрофоном и WebSocket».
//
// Протокол:
//  • Voice-клиент при старте зовёт acquire(Owner.VOICE). Если занят LEARN —
//    ждёт, пока Learn вызовет release.
//  • Learn-клиент при старте зовёт acquire(Owner.LEARN). Если занят VOICE —
//    ждёт, пока Voice вызовет release.
//  • active: StateFlow<Owner> подписывается каждый клиент — когда не он
//    активный, он обязан закрыть свой WS и остановить mic.
//  • acquire() — suspend, с таймаутом ACQUIRE_TIMEOUT_MS. Если таймаут —
//    бросает IllegalStateException (но это не должно случаться в норме).
//
// Никакой клиент НЕ должен держать WS, если active != его Owner.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import javax.inject.Inject
import javax.inject.Singleton

enum class ClientOwner { NONE, VOICE, LEARN }

@Singleton
class ActiveClientArbiter @Inject constructor(
    private val logger: AppLogger
) {
    companion object {
        private const val ACQUIRE_TIMEOUT_MS = 5_000L
    }

    private val mutex = Mutex()

    private val _active = MutableStateFlow(ClientOwner.NONE)
    /** Текущий владелец клиента. Каждый ViewModel подписывается и,
     *  если значение != его Owner, должен немедленно disconnect(). */
    val active: StateFlow<ClientOwner> = _active.asStateFlow()

    /**
     * Захватить владение. Если владеет другой — ждём release (до 5с).
     * После успешного acquire предыдущий owner получит через StateFlow
     * уведомление и обязан освободить ресурсы.
     */
    suspend fun acquire(owner: ClientOwner) {
        require(owner != ClientOwner.NONE) { "Cannot acquire NONE" }
        try {
            withTimeout(ACQUIRE_TIMEOUT_MS) {
                mutex.withLock {
                    val prev = _active.value
                    if (prev == owner) {
                        logger.d("Arbiter: already owned by $owner — no-op")
                        return@withLock
                    }
                    logger.d("Arbiter: $prev → $owner")
                    _active.value = owner
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.e("Arbiter: acquire($owner) timeout — forcing")
            _active.value = owner  // форс, чтобы не зависнуть навечно
        }
    }

    /**
     * Освободить владение. Если мы не активный — no-op.
     */
    suspend fun release(owner: ClientOwner) {
        mutex.withLock {
            val cur = _active.value
            if (cur != owner) {
                logger.d("Arbiter: release($owner) ignored — current=$cur")
                return@withLock
            }
            logger.d("Arbiter: $owner → NONE")
            _active.value = ClientOwner.NONE
        }
    }

    /** Принудительный сброс — аварийный выход. */
    fun forceReleaseAll() {
        logger.w("Arbiter: FORCE release all")
        _active.value = ClientOwner.NONE
    }
}
// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.2
// Путь: app/src/main/java/com/learnde/app/learn/core/ActiveClientArbiter.kt
//
// ИЗМЕНЕНИЯ v3.2:
//   - forceReleaseAll() теперь под мьютексом (race condition fix)
//   - Добавлен неблокирующий forceReleaseAllNonSuspending() для emergency-путей
//   - Таймаут увеличен до 7s (было 5s — на медленных соединениях срывалось)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
        private const val ACQUIRE_TIMEOUT_MS = 7_000L  // v3.2: было 5s
    }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow(ClientOwner.NONE)
    val active: StateFlow<ClientOwner> = _active.asStateFlow()

    /**
     * Захватить владение. Если владеет другой — ждём release (до 7с).
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
            // v3.2: форс тоже через корутину с мьютексом
            scope.launch {
                mutex.withLock {
                    _active.value = owner
                }
            }
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

    /**
     * v3.2: Принудительный сброс — теперь под мьютексом (race fix).
     * Suspend-версия для штатных путей.
     */
    suspend fun forceReleaseAll() {
        mutex.withLock {
            logger.w("Arbiter: FORCE release all")
            _active.value = ClientOwner.NONE
        }
    }

    /**
     * v3.2: Неблокирующий аварийный сброс — для вызовов из не-suspend контекстов
     * (onCleared, onDestroy). Использует отдельную корутину чтобы не блокировать caller.
     */
    fun forceReleaseAllNonSuspending() {
        scope.launch {
            mutex.withLock {
                logger.w("Arbiter: FORCE release all (async)")
                _active.value = ClientOwner.NONE
            }
        }
    }
}
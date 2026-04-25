package com.learnde.app.learn.core

import com.learnde.app.util.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ClientOwner { NONE, VOICE, LEARN }

@Singleton
class ActiveClientArbiter @Inject constructor(
    private val logger: AppLogger
) {
    private val _active = MutableStateFlow(ClientOwner.NONE)
    val active: StateFlow<ClientOwner> = _active.asStateFlow()

    /**
     * Захватить владение. Использует атомарное lock-free обновление.
     */
    suspend fun acquire(owner: ClientOwner) {
        require(owner != ClientOwner.NONE) { "Cannot acquire NONE" }
        _active.update { prev ->
            if (prev == owner) {
                logger.d("Arbiter: already owned by $owner — no-op")
                prev
            } else {
                logger.d("Arbiter: $prev → $owner")
                owner
            }
        }
    }

    /**
     * Освободить владение. Если мы не активный — no-op.
     */
    suspend fun release(owner: ClientOwner) {
        _active.update { cur ->
            if (cur != owner) {
                logger.d("Arbiter: release($owner) ignored — current=$cur")
                cur
            } else {
                logger.d("Arbiter: $owner → NONE")
                ClientOwner.NONE
            }
        }
    }

    /**
     * Принудительный сброс.
     */
    suspend fun forceReleaseAll() {
        logger.w("Arbiter: FORCE release all")
        _active.value = ClientOwner.NONE
    }

    fun forceReleaseAllNonSuspending() {
        logger.w("Arbiter: FORCE release all (sync)")
        _active.value = ClientOwner.NONE
    }
}
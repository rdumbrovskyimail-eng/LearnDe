package com.learnde.app.domain.functions

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шина событий выполнения тестовых функций.
 *
 * ФИКС: replay=0 — после рестарта приложения лампочки НЕ зажигаются сами.
 * Добавлен lastExecuted: StateFlow<TestFunction?> с авто-сбросом через
 * LIGHT_TTL_MS — для UI-индикаторов, которые должны "погаснуть" через
 * пару секунд, а не гореть вечно.
 */
@Singleton
class FunctionsEventBus @Inject constructor() {

    companion object {
        private const val LIGHT_TTL_MS = 5_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var resetJob: Job? = null

    private val _executed = MutableSharedFlow<FunctionsRegistry.TestFunction>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val executed: SharedFlow<FunctionsRegistry.TestFunction> = _executed.asSharedFlow()

    private val _lastExecuted = MutableStateFlow<FunctionsRegistry.TestFunction?>(null)
    val lastExecuted: StateFlow<FunctionsRegistry.TestFunction?> = _lastExecuted.asStateFlow()

    fun publish(fn: FunctionsRegistry.TestFunction) {
        _executed.tryEmit(fn)
        _lastExecuted.value = fn
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(LIGHT_TTL_MS)
            _lastExecuted.value = null
        }
    }
}
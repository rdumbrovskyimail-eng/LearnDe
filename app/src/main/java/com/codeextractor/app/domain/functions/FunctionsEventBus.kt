package com.codeextractor.app.domain.functions

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Шина событий выполнения тестовых функций.
 *
 * replay = 1 — FunctionsTestScreen, открытый ПОСЛЕ вызова функции,
 * всё равно получает последнее событие и отобразит корректное состояние
 * лампочек/статуса (если они ещё не успели угаснуть — это решается TTL
 * на стороне FunctionsViewModel).
 */
@Singleton
class FunctionsEventBus @Inject constructor() {

    private val _executed = MutableSharedFlow<FunctionsRegistry.TestFunction>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val executed: SharedFlow<FunctionsRegistry.TestFunction> = _executed.asSharedFlow()

    fun publish(fn: FunctionsRegistry.TestFunction) {
        _executed.tryEmit(fn)
    }
}
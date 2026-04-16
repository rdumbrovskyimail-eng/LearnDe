package com.codeextractor.app.domain.functions

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Глобальная шина уведомлений о выполнении тестовых функций.
 *
 * ToolRegistry при dispatch-е функции публикует TestFunction, а
 * FunctionsTestScreen (через FunctionsViewModel) слушает этот поток
 * и обновляет UI лампочек + строку «Сейчас выполняется: …».
 *
 * Таким образом UI теста работает в любом месте приложения —
 * не обязательно находиться на экране в момент вызова.
 */
@Singleton
class FunctionsEventBus @Inject constructor() {

    private val _executed = MutableSharedFlow<FunctionsRegistry.TestFunction>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val executed: SharedFlow<FunctionsRegistry.TestFunction> = _executed.asSharedFlow()

    fun publish(fn: FunctionsRegistry.TestFunction) {
        _executed.tryEmit(fn)
    }
}
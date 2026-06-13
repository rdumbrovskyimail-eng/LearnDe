package com.learnde.app.learn.sessions.a1.v2

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SystemTextBus @Inject constructor() {
    private val _texts = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val texts: SharedFlow<String> = _texts.asSharedFlow()

    fun send(text: String) {
        _texts.tryEmit(text)
    }
}

enum class SessionControlAction { PAUSE, RESUME, STOP, SKIP_STEP, SKIP_BREAK;

    companion object {
        fun fromString(s: String?): SessionControlAction? =
            runCatching { valueOf(s?.trim()?.uppercase() ?: "") }.getOrNull()
    }
}

data class SessionControlEvent(
    val action: SessionControlAction,
    /** true — пришла голосом (через модель), false — кнопкой UI. */
    val byVoice: Boolean,
    val atMs: Long = System.currentTimeMillis(),
)

@Singleton
class SessionControlBus @Inject constructor() {
    private val _events = MutableSharedFlow<SessionControlEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<SessionControlEvent> = _events.asSharedFlow()

    fun emit(action: SessionControlAction, byVoice: Boolean) {
        _events.tryEmit(SessionControlEvent(action, byVoice))
    }
}
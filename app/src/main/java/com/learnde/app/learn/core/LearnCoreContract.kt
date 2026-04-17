// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreContract.kt
//
// MVI-контракт автономного Learn-стека.
// Аналог VoiceContract.kt, но полностью отделён.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.util.UiText

enum class LearnConnectionStatus {
    Disconnected,
    Connecting,
    Negotiating,
    Ready,
    Recording,
    Reconnecting,
}

data class LearnCoreState(
    val connectionStatus: LearnConnectionStatus = LearnConnectionStatus.Disconnected,
    val sessionId: String? = null,             // id активной LearnSession
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val error: UiText? = null,
    val apiKeySet: Boolean = false,
    /** Блок захватил Arbiter (т.е. Voice-клиент уступил). */
    val arbiterOwned: Boolean = false,
)

sealed class LearnCoreIntent {
    /** Запустить сессию по её id (из LearnSessionRegistry). */
    data class Start(val sessionId: String) : LearnCoreIntent()
    /** Остановить текущую сессию и отпустить Arbiter. */
    data object Stop : LearnCoreIntent()
    /** Перезапустить активную сессию (reset + reconnect). */
    data object Restart : LearnCoreIntent()
    /** Включить/выключить mic (по умолчанию включается автоматически после SetupComplete). */
    data object ToggleMic : LearnCoreIntent()
    /** Сбросить error для UI. */
    data object ClearError : LearnCoreIntent()
}

sealed class LearnCoreEffect {
    data class ShowToast(val message: UiText) : LearnCoreEffect()
    data class Error(val message: UiText) : LearnCoreEffect()
}
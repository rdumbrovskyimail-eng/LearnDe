package com.codeextractor.app.presentation.voice

import androidx.compose.runtime.Immutable
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.util.UiText

/**
 * MVI-контракт для голосового экрана.
 * State — единственный источник правды для Compose.
 * Intent — все действия пользователя.
 * Effect — одноразовые события (Toast, навигация).
 */

@Immutable
data class VoiceState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val logText: String = "",
    val apiKeySet: Boolean = false,
    val showApiKeyInput: Boolean = true,
    val error: UiText? = null
)

enum class ConnectionStatus(val label: String) {
    Disconnected("Disconnected"),
    Connecting("Connecting…"),
    Negotiating("Setting up…"),
    Ready("Ready — tap Start"),
    Recording("● Recording"),
    Reconnecting("Reconnecting…")
}

sealed interface VoiceIntent {
    data class SubmitApiKey(val key: String) : VoiceIntent
    data object Connect : VoiceIntent
    data object Disconnect : VoiceIntent
    data object ToggleMic : VoiceIntent
    data class SendText(val text: String) : VoiceIntent
    data object SaveLog : VoiceIntent
}

sealed interface VoiceEffect {
    data class ShowToast(val message: UiText) : VoiceEffect
    data class SaveLogToFile(val content: String) : VoiceEffect
}
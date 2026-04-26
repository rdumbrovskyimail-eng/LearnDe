package com.learnde.app.learn.core

import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.util.UiText

enum class LearnConnectionStatus {
    Disconnected, Connecting, Negotiating, Ready, Recording, Reconnecting,
}

data class LearnCoreState(
    val connectionStatus: LearnConnectionStatus = LearnConnectionStatus.Disconnected,
    val sessionId: String? = null,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val error: UiText? = null,
    val apiKeySet: Boolean = false,
    val arbiterOwned: Boolean = false,
    // ФИНАЛ: Флаг для показа красивой анимации загрузки
    val isPreparingSession: Boolean = false, 
    val isFinishingSession: Boolean = false,
)

sealed class LearnCoreIntent {
    data class Start(val sessionId: String) : LearnCoreIntent()
    data object Stop : LearnCoreIntent()
    data object ToggleMic : LearnCoreIntent()
    data object ClearError : LearnCoreIntent()
}

sealed class LearnCoreEffect {
    data class ShowToast(val message: UiText) : LearnCoreEffect()
    data class Error(val message: UiText) : LearnCoreEffect()
}
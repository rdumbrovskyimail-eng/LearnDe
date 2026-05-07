package com.learnde.app.learn.core

import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.util.UiText

enum class LearnConnectionStatus {
    Disconnected, Connecting, Negotiating, Ready, Recording, Reconnecting,
}

/**
 * Пара "оригинал + перевод" — одна строка чата translator-сессии.
 * Заполняется через 3 источника:
 *   • Vosk partial → текст в *Text, *IsFinal=false → светло-серый цвет
 *   • Vosk final   → текст в *Text, *IsFinal=true  → серый цвет
 *   • Gemini REST  → текст в *Text, *IsRefined=true → чёрный цвет
 */
data class TranslationPair(
    val id: Long,
    val originalText: String = "",
    val translationText: String = "",
    val originalIsFinal: Boolean = false,
    val translationIsFinal: Boolean = false,
    val originalIsRefined: Boolean = false,
    val translationIsRefined: Boolean = false,
    val originalLang: String = "",
    val translationLang: String = "",
)

data class LearnCoreState(
    val connectionStatus: LearnConnectionStatus = LearnConnectionStatus.Disconnected,
    val sessionId: String? = null,
    val isMicActive: Boolean = false,
    val isAiSpeaking: Boolean = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val error: UiText? = null,
    val apiKeySet: Boolean = false,
    val arbiterOwned: Boolean = false,
    val liveUserTranscript: String = "",
    val isPreparingSession: Boolean = false,
    val isFinishingSession: Boolean = false,
    // Translator: пары для UI (заполняются Vosk + Gemini REST)
    val translatorPairs: List<TranslationPair> = emptyList(),
    // Старые поля (не используются в новом UI — оставлены для совместимости со старым кодом)
    val translatorOriginal: String = "",
    val translatorTranslation: String = "",
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
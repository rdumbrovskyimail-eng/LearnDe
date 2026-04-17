// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceContract.kt
//
// Изменения:
//   • a0a1TestActive → learnActive: Boolean + learnId: String? (generic).
//     VoiceViewModel теперь не знает про A0a1, только про LearnSession.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.voice

import androidx.compose.runtime.Immutable
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.scene.SceneMode
import com.learnde.app.util.UiText

@Immutable
data class VoiceState(
    val connectionStatus: ConnectionStatus    = ConnectionStatus.Disconnected,
    val isMicActive: Boolean                  = false,
    val isAiSpeaking: Boolean                 = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val logText: String                       = "",
    val apiKeySet: Boolean                    = false,
    val showApiKeyInput: Boolean              = true,
    val error: UiText?                        = null,

    // ── Voice & Model ──
    val currentVoiceId: String                = "Aoede",
    val currentLatencyProfile: LatencyProfile = LatencyProfile.UltraLow,
    val model: String                         = "models/gemini-3.1-flash-live-preview",
    val languageCode: String                  = "",

    // ── Generation ──
    val temperature: Float                    = 1.0f,
    val topP: Float                           = 0.95f,
    val topK: Int                             = 40,
    val maxOutputTokens: Int                  = 8192,

    // ── System ──
    val systemInstruction: String             = "",

    // ── Audio ──
    val useAec: Boolean                       = true,
    val playbackVolume: Int                   = 90,
    val forceSpeakerOutput: Boolean           = true,

    // ── Session ──
    val enableGoogleSearch: Boolean           = false,
    val enableCompression: Boolean            = true,
    val enableResumption: Boolean             = true,

    // ── Debug ──
    val showDebugLog: Boolean                 = false,
    val logRawFrames: Boolean                 = false,
    val showUsageMetadata: Boolean            = false,

    // ── Usage ──
    val promptTokens: Int                     = 0,
    val responseTokens: Int                   = 0,
    val totalTokens: Int                      = 0,

    // ── Scene ──
    val sceneMode: SceneMode                  = SceneMode.AVATAR,
    val sceneBgHasImage: Boolean              = false,
    val isSceneFullscreen: Boolean            = false,

    // ── Chat ──
    val chatFontScale: Float                  = 1.0f,
    val chatShowRoleLabels: Boolean           = true,
    val chatShowTimestamps: Boolean           = false,
    val chatAutoScroll: Boolean               = true,
    val chatBackgroundAlpha: Int              = 30
)

enum class ConnectionStatus(val label: String) {
    Disconnected("Отключено"),
    Connecting  ("Подключение…"),
    Negotiating ("Настройка…"),
    Ready       ("Готово"),
    Recording   ("● Запись"),
    Reconnecting("Переподключение…")
}

sealed interface VoiceIntent {
    data class SubmitApiKey(val key: String)                 : VoiceIntent
    data object Connect                                      : VoiceIntent
    data object Disconnect                                   : VoiceIntent
    data object ToggleMic                                    : VoiceIntent
    data class SendText(val text: String)                    : VoiceIntent
    data object SaveLog                                      : VoiceIntent
    data object ClearConversation                            : VoiceIntent
    data object ToggleFullscreenScene                        : VoiceIntent
}

sealed interface VoiceEffect {
    data class ShowToast(val message: UiText)    : VoiceEffect
    data class SaveLogToFile(val content: String): VoiceEffect
}

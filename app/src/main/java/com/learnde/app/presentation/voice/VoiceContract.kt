// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceContract.kt
//
// Изменения:
//   • 5 диагностических VoiceIntent для поиска источника 1007:
//     ConnectBaseline, ConnectWithoutThinking, ConnectWithoutVad,
//     ConnectWithoutSessionMgmt, ConnectWithoutTranscription
//   • VoiceState.lastTestedProfile — показывает какой профиль сейчас
//     тестируется (для StatusBadge)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.voice

import androidx.compose.runtime.Immutable
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.scene.SceneMode
import com.learnde.app.util.UiText

/**
 * Диагностический профиль setup — используется для поиска источника
 * close code 1007. Каждый профиль отключает определённый блок setup.
 */
enum class DiagnosticProfile(val label: String, val shortLabel: String, val description: String) {
    FULL(
        label = "Full (production)",
        shortLabel = "FULL",
        description = "Полный setup со всеми блоками"
    ),
    BASELINE(
        label = "1. Baseline",
        shortLabel = "BASE",
        description = "Минимум: model + responseModalities + speechConfig + systemInstruction"
    ),
    WITHOUT_THINKING(
        label = "2. No Thinking",
        shortLabel = "-THINK",
        description = "Без thinkingConfig (подозрение на thinkingLevel)"
    ),
    WITHOUT_VAD(
        label = "3. No VAD",
        shortLabel = "-VAD",
        description = "Без realtimeInputConfig (подозрение на sensitivity enum)"
    ),
    WITHOUT_SESSION_MGMT(
        label = "4. No Session Mgmt",
        shortLabel = "-SESS",
        description = "Без sessionResumption + contextWindowCompression"
    ),
    WITHOUT_TRANSCRIPTION(
        label = "5. No Transcription",
        shortLabel = "-TRANS",
        description = "Без inputAudioTranscription + outputAudioTranscription"
    )
}

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
    val model: String                         = "gemini-3.1-flash-live-preview",
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
    val chatBackgroundAlpha: Int              = 30,

    // ── ДИАГНОСТИКА ──
    /** Какой профиль сейчас тестируется (показывается в StatusBadge) */
    val lastTestedProfile: DiagnosticProfile  = DiagnosticProfile.FULL,
    /** Результат последнего теста (для истории) */
    val diagnosticLog: List<DiagnosticResult> = emptyList()
)

/** Результат одного теста для истории на экране */
data class DiagnosticResult(
    val profile: DiagnosticProfile,
    val result: String,
    val timestamp: Long = System.currentTimeMillis()
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

    // ── ДИАГНОСТИЧЕСКИЕ INTENT (для поиска 1007) ──
    /** Полный setup (как было раньше) */
    data object ConnectFull                                  : VoiceIntent
    /** Baseline: минимальный setup */
    data object ConnectBaseline                              : VoiceIntent
    /** Без thinkingConfig */
    data object ConnectWithoutThinking                       : VoiceIntent
    /** Без realtimeInputConfig/VAD */
    data object ConnectWithoutVad                            : VoiceIntent
    /** Без sessionResumption + contextWindowCompression */
    data object ConnectWithoutSessionMgmt                    : VoiceIntent
    /** Без transcription-блоков */
    data object ConnectWithoutTranscription                  : VoiceIntent
}

sealed interface VoiceEffect {
    data class ShowToast(val message: UiText)    : VoiceEffect
    data class SaveLogToFile(val content: String): VoiceEffect
}

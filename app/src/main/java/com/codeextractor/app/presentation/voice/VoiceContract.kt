package com.codeextractor.app.presentation.voice

import androidx.compose.runtime.Immutable
import com.codeextractor.app.domain.model.ConversationMessage
import com.codeextractor.app.domain.model.LatencyProfile
import com.codeextractor.app.util.UiText

@Immutable
data class VoiceState(
    val connectionStatus: ConnectionStatus  = ConnectionStatus.Disconnected,
    val isMicActive: Boolean                = false,
    val isAiSpeaking: Boolean               = false,
    val transcript: List<ConversationMessage> = emptyList(),
    val logText: String                     = "",
    val apiKeySet: Boolean                  = false,
    val showApiKeyInput: Boolean            = true,
    val error: UiText?                      = null,

    // ── Voice & Model ──
    val currentVoiceId: String              = "Aoede",
    val currentLatencyProfile: LatencyProfile = LatencyProfile.UltraLow,
    val model: String                       = "models/gemini-3.1-flash-live-preview",
    val languageCode: String                = "",

    // ── Generation Config ──
    val temperature: Float                  = 1.0f,
    val topP: Float                         = 0.95f,
    val topK: Int                           = 40,
    val maxOutputTokens: Int                = 8192,

    // ── System ──
    val systemInstruction: String           = "",

    // ── Audio ──
    val useAec: Boolean                     = true,

    // ── Session ──
    val enableGoogleSearch: Boolean         = false,
    val enableCompression: Boolean          = true,
    val enableResumption: Boolean           = true,

    // ── Debug ──
    val showDebugLog: Boolean               = false,
    val logRawFrames: Boolean               = false,
    val showUsageMetadata: Boolean          = false,

    // ── Usage Metadata (live tracking) ──
    val promptTokens: Int                   = 0,
    val responseTokens: Int                 = 0,
    val totalTokens: Int                    = 0
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
    // ── Core actions ──
    data class SubmitApiKey(val key: String)             : VoiceIntent
    data object Connect                                  : VoiceIntent
    data object Disconnect                               : VoiceIntent
    data object ToggleMic                                : VoiceIntent
    data class SendText(val text: String)                : VoiceIntent
    data object SaveLog                                  : VoiceIntent
    data object ClearConversation                        : VoiceIntent

    // ── Voice & Model ──
    data class UpdateVoiceId(val voiceId: String)        : VoiceIntent
    data class UpdateLatencyProfile(val profile: LatencyProfile) : VoiceIntent
    data class UpdateModel(val model: String)            : VoiceIntent
    data class UpdateLanguage(val code: String)          : VoiceIntent

    // ── Generation Config ──
    data class UpdateTemperature(val value: Float)       : VoiceIntent
    data class UpdateTopP(val value: Float)              : VoiceIntent
    data class UpdateTopK(val value: Int)                : VoiceIntent
    data class UpdateMaxTokens(val value: Int)           : VoiceIntent
    data class UpdateSystemInstruction(val text: String) : VoiceIntent

    // ── Audio ──
    data class UpdateAec(val enabled: Boolean)           : VoiceIntent
    data class UpdateServerVad(val enabled: Boolean)     : VoiceIntent

    // ── Session & Tools ──
    data class UpdateGoogleSearch(val enabled: Boolean)  : VoiceIntent
    data class UpdateCompression(val enabled: Boolean)   : VoiceIntent
    data class UpdateResumption(val enabled: Boolean)    : VoiceIntent
    data class UpdateBackupKey(val key: String)          : VoiceIntent

    // ── Debug ──
    data class UpdateDebugLog(val enabled: Boolean)      : VoiceIntent
    data class UpdateLogRawFrames(val enabled: Boolean)  : VoiceIntent
    data class UpdateShowUsage(val enabled: Boolean)     : VoiceIntent
}

sealed interface VoiceEffect {
    data class ShowToast(val message: UiText)    : VoiceEffect
    data class SaveLogToFile(val content: String): VoiceEffect
}

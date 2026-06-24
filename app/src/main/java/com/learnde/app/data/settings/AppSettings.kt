package com.learnde.app.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { AUTO, LIGHT, DARK }

@Serializable
data class AppSettings(
    val apiKey: String = "",
    val model: String = "models/gemini-3.1-flash-live-preview",
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 0,
    val maxOutputTokens: Int = 8192,
    val responseModality: String = "AUDIO",
    val voiceId: String = "Aoede",
    val useAec: Boolean = true,
    val jitterPreBufferChunks: Int = 3,
    val jitterTimeoutMs: Long = 150L,
    val playbackQueueCapacity: Int = 256,
    val sendAudioStreamEnd: Boolean = true,
    val playbackVolume: Int = 90,
    val micGain: Int = 100,
    val forceSpeakerOutput: Boolean = true,
    val enableSessionResumption: Boolean = true,
    val transparentResumption: Boolean = true,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Long = 0L,
    val compressionTargetTokens: Long = 0L,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 2000L,
    val reconnectMaxDelayMs: Long = 30000L,
    val sessionHeartbeatMs: Long = 0L,
    val enableServerVad: Boolean = true,
    val vadStartOfSpeechSensitivity: Float = 0.5f,
    val vadEndOfSpeechSensitivity: Float = 0.5f,
    val vadSilenceTimeoutMs: Int = 0,
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,
    val enableGoogleSearch: Boolean = false,
    val latencyProfile: String = "Low",
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    val chatFontScale: Float = 1.0f,
    val chatShowTimestamps: Boolean = false,
    val chatShowRoleLabels: Boolean = true,
    val chatAutoScroll: Boolean = true,
    val chatBackgroundAlpha: Int = 30,
    val showDebugLog: Boolean = false,
    val logRawWebSocketFrames: Boolean = false,
    val showUsageMetadata: Boolean = false
) {
    companion object {
        const val DEFAULT_SYSTEM_INSTRUCTION = "Ты — полезный голосовой ассистент. Отвечай кратко и по делу."
    }
}
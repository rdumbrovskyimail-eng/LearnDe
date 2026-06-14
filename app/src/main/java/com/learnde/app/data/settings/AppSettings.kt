// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/data/settings/AppSettings.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { AUTO, LIGHT, DARK }

@Serializable
data class AppSettings(

    // ═══════════════════ 0. ОНБОРДИНГ (НОВОЕ) ═══════════════════
    val userName: String = "",
    val learningGoals: String = "",
    val learningTopics: String = "",
    val a1DataImported: Boolean = false,
    val a1DataVersion: Int = 0,
    val testPassed: Boolean = false,

    // ═══════════════════ 1. AUTH ═══════════════════
    val apiKey: String = "",

    // ═══════════════ 1b. TUTOR-INFO MODEL (параллельная) ═══════════════
    /** Отдельный ключ для информирующей модели gemini-2.5-flash-lite. */
    val tutorApiKey: String = "",
    val tutorModel: String = "gemini-3.1-flash-lite",
    val enableTutorHints: Boolean = true,

    // ═══════════════════ 2. MODEL (только 3.1) ═══════════════════
    val model: String = "models/gemini-3.1-flash-live-preview",
    val temperature: Float = 0.8f,     // стабильность речи репетитора
    val topP: Float = 0.95f,
    val topK: Int = 0,
    val maxOutputTokens: Int = 8192,
    val responseModality: String = "AUDIO",

    // ═══════════════════ 3. VOICE ═══════════════════
    val voiceId: String = "Aoede",

    // ═══════════════════ 4. AUDIO ═══════════════════
    val useAec: Boolean = true,
    val jitterPreBufferChunks: Int = 3,
    val jitterTimeoutMs: Long = 150L,
    val playbackQueueCapacity: Int = 256,
    val sendAudioStreamEnd: Boolean = true,
    val playbackVolume: Int = 90,
    val micGain: Int = 100,
    val forceSpeakerOutput: Boolean = true,

    // ═══════════════════ 5. SESSION ═══════════════════
    val enableSessionResumption: Boolean = true,
    val transparentResumption: Boolean = true,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Long = 0L,
    val compressionTargetTokens: Long = 0L,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 2000L,
    val reconnectMaxDelayMs: Long = 30000L,
    val sessionHeartbeatMs: Long = 0L,

    // ═══════════════════ 6. VAD ═══════════════════
    val enableServerVad: Boolean = true,
    val vadStartOfSpeechSensitivity: Float = 0.5f,
    val vadEndOfSpeechSensitivity: Float = 0.5f,
    val vadSilenceTimeoutMs: Int = 0,

    // ═══════════════════ 7. TRANSCRIPTION ═══════════════════
    val inputTranscription: Boolean = true,
    val outputTranscription: Boolean = true,

    // ═══════════════════ 8. TOOLS ═══════════════════
    val enableGoogleSearch: Boolean = false,

    // ═══════════════════ 9. THINKING ═══════════════════
    val latencyProfile: String = "Low",

    // ═══════════════════ 10. SYSTEM ═══════════════════
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ═══════════════════ 11. UI / THEME ═══════════════════
    val themeMode: ThemeMode = ThemeMode.AUTO,

    // ═══════════════════ 12. CHAT ═══════════════════
    val chatFontScale: Float = 1.0f,
    val chatShowTimestamps: Boolean = false,
    val chatShowRoleLabels: Boolean = true,
    val chatAutoScroll: Boolean = true,
    val chatBackgroundAlpha: Int = 30,

    // ═══════════════════ 13. LEARN ═══════════════════

    // ═══════════════════ 14. DEBUG ═══════════════════
    val showDebugLog: Boolean = false,
    val logRawWebSocketFrames: Boolean = false,
    val showUsageMetadata: Boolean = false
) {
    companion object {
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты — голосовой репетитор немецкого языка для русскоязычного ученика уровня A1. " +
            "Говори коротко, дружелюбно и в темпе. Всё, что пишешь, ты озвучиваешь."
    }
}
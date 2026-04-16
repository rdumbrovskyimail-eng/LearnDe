// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/data/settings/AppSettings.kt
// Изменения:
//   + themeMode (AUTO / LIGHT / DARK)
//   + playbackVolume (0..100) — программный gain в AudioTrack
//   + micGain (0..200%) — усиление захвата
//   + sceneMode (AVATAR / VISUALIZER / CUSTOM_IMAGE)
//   + sceneBgHasImage (для быстрой проверки без чтения файла)
//   + Chat: chatBubbleSize, chatShowTimestamps, chatAutoScroll,
//           chatShowRoleLabels, chatFontScale
//   + Только модель 3.1 — остальные удалены из defaults
//   + Удалено поле autoRotateKeys из обязательных, логика осталась
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.data.settings

import kotlinx.serialization.Serializable

@Serializable
enum class ThemeMode { AUTO, LIGHT, DARK }

@Serializable
data class AppSettings(

    // ═══════════════════ 1. AUTH ═══════════════════
    val apiKey: String = "",
    val apiKeyBackup: String = "",
    val autoRotateKeys: Boolean = false,

    // ═══════════════════ 2. MODEL (только 3.1) ═══════════════════
    /** Единственная поддерживаемая модель. */
    val model: String = "models/gemini-3.1-flash-live-preview",

    /** Generation defaults взяты из официальных референсов Gemini 3.1 Live. */
    val temperature: Float = 1.0f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val maxOutputTokens: Int = 8192,
    val presencePenalty: Float = 0.0f,
    val frequencyPenalty: Float = 0.0f,
    val responseModality: String = "AUDIO",

    // ═══════════════════ 3. VOICE ═══════════════════
    val voiceId: String = "Aoede",
    val languageCode: String = "",

    // ═══════════════════ 4. AUDIO ═══════════════════
    val useAec: Boolean = true,
    val jitterPreBufferChunks: Int = 3,
    val jitterTimeoutMs: Long = 150L,
    val playbackQueueCapacity: Int = 256,
    val sendAudioStreamEnd: Boolean = true,

    /** Громкость воспроизведения (0..100 %). Программный gain. */
    val playbackVolume: Int = 90,

    /** Усиление захвата (50..200 %). */
    val micGain: Int = 100,

    /** Принудительно использовать громкоговоритель (SPEAKER), а не earpiece. */
    val forceSpeakerOutput: Boolean = true,

    // ═══════════════════ 5. SESSION ═══════════════════
    val enableSessionResumption: Boolean = true,
    val transparentResumption: Boolean = true,
    val enableContextCompression: Boolean = true,
    val compressionTriggerTokens: Int = 0,
    val maxReconnectAttempts: Int = 5,
    val reconnectBaseDelayMs: Long = 2000L,
    val reconnectMaxDelayMs: Long = 30000L,

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

    /** Передавать декларации 10 тестовых функций в сессию. */
    val enableTestFunctions: Boolean = true,

    // ═══════════════════ 9. THINKING ═══════════════════
    val latencyProfile: String = "UltraLow",

    // ═══════════════════ 10. SYSTEM ═══════════════════
    val systemInstruction: String = DEFAULT_SYSTEM_INSTRUCTION,

    // ═══════════════════ 11. UI / THEME ═══════════════════
    /** Тема оформления. */
    val themeMode: ThemeMode = ThemeMode.AUTO,

    /** Режим сцены: 3D-аватар, визуализатор или своё изображение. */
    val sceneMode: String = "avatar",

    /** true, если пользователь загрузил PNG-фон в BackgroundImageStore. */
    val sceneBgHasImage: Boolean = false,

    // ═══════════════════ 12. CHAT ═══════════════════
    /** Размер пузырька: SMALL / MEDIUM / LARGE — мап в font scale. */
    val chatFontScale: Float = 1.0f,
    val chatShowTimestamps: Boolean = false,
    val chatShowRoleLabels: Boolean = true,
    val chatAutoScroll: Boolean = true,
    /** Прозрачность фона списка (0..100, проценты). */
    val chatBackgroundAlpha: Int = 30,

    // ═══════════════════ 13. DEBUG ═══════════════════
    val showDebugLog: Boolean = false,
    val logRawWebSocketFrames: Boolean = false,
    val showUsageMetadata: Boolean = false
) {
    companion object {
        const val DEFAULT_SYSTEM_INSTRUCTION =
            "Ты русскоязычный голосовой ассистент. " +
            "Всегда отвечай только на русском языке. " +
            "Слушай и понимай русскую речь. " +
            "Отвечай кратко и по делу, не более 2-3 предложений, " +
            "если пользователь не просит подробного ответа. " +
            "Если пользователь говорит «выполни функцию N» или «вызови функцию N», " +
            "ты ОБЯЗАТЕЛЬНО вызываешь соответствующий tool test_function_N через function calling, " +
            "а не отвечаешь текстом."
    }
}
// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ v1.0
// Путь: app/src/main/java/com/learnde/app/learn/sessions/translator/TranslatorTextTranscriber.kt
//
// Параллельный transcription-клиент для translator-сессии.
//
// Архитектура:
//   - Использует отдельный @TranslatorTextScope LiveClient
//     (свой WebSocket, не пересекается с основным audio-клиентом).
//   - Работает в text modality — модель отвечает только текстом.
//   - Принимает PCM от микрофона (дублируется из основного потока),
//     слушает event ModelText и эмитит UserTranscriptEvent в UI.
//   - Полностью независим: если падает — audio перевод продолжается.
//
// Формат ответа модели:
//   Текст в формате "<lang>|<transcript>", например:
//     "ru|Привет, как дела"
//     "uk|Дякую дуже"
//     "de|Wie geht's"
//   Если непонятно — "unknown|...".
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.translator

import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.core.TranslatorTextScope
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class UserTranscriptEvent(
    val text: String,
    val language: String,           // ru | uk | de | en | unknown
    val timestamp: Long = System.currentTimeMillis(),
)

@Singleton
class TranslatorTextTranscriber @Inject constructor(
    @TranslatorTextScope private val client: LiveClient,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _transcripts = MutableSharedFlow<UserTranscriptEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val transcripts: SharedFlow<UserTranscriptEvent> = _transcripts.asSharedFlow()

    @Volatile private var eventJob: Job? = null
    @Volatile private var isActive: Boolean = false

    private val turnBuffer = StringBuilder()

    val isReady: Boolean get() = client.isReady

    // ═══════════════════════════════════════════════════════
    //  PROMPT — ультра-минимальный, без function calling
    // ═══════════════════════════════════════════════════════
    private val systemInstruction = """
You are a speech transcriber. Your ONLY task: transcribe what the user says.

OUTPUT FORMAT (strict):
<lang>|<transcript>

Where <lang> is one of: ru, uk, de, en, unknown.
Where <transcript> is the EXACT text the user said in original script.

EXAMPLES:
ru|Привет, как дела
uk|Дякую дуже
de|Wie geht es dir
en|Hello there
unknown|...

RULES:
- ONE line per user utterance. No explanations. No translations. No extra text.
- Always include the language code prefix and pipe character.
- Use proper Cyrillic for Russian/Ukrainian. Distinguish them by markers
  (ї,і,є,ґ,що,як,ти→Ukrainian; otherwise Russian).
- Use proper German script with umlauts (ä,ö,ü,ß).
- If utterance is silent or unintelligible, output: unknown|...
- Never output multiple lines. Never speak. Never use voice. TEXT ONLY.
""".trimIndent()

    suspend fun start(apiKey: String, model: String, logRaw: Boolean) {
        if (isActive) {
            logger.d("TranslatorTextTranscriber: already active, skipping start")
            return
        }
        isActive = true
        turnBuffer.clear()

        val config = SessionConfig(
            model = model,
            responseModality = "TEXT",
            temperature = 0.0f,
            topP = 0.6f,
            topK = 10,
            maxOutputTokens = 256,
            voiceId = "Aoede",          // не используется в TEXT mode, но поле обязательное
            languageCode = "",
            latencyProfile = LatencyProfile.Off,
            autoActivityDetection = true,
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_HIGH",
            vadPrefixPaddingMs = 80,
            vadSilenceDurationMs = 350,
            systemInstruction = systemInstruction,
            inputTranscription = false,    // не нужно — мы сами транскрибируем
            outputTranscription = false,   // ответ модели и так в text-mode
            enableSessionResumption = false,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = emptyList(),
            sendAudioStreamEnd = true,
            setupTimeoutMs = 8_000L,
            sendThinkingConfig = false,    // дополнительная защита: не шлём thinking
        )

        logger.d("TranslatorTextTranscriber: starting (TEXT mode, model=$model)")

        eventJob = scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }

        runCatching { client.connect(apiKey, config, logRaw) }
            .onFailure { e ->
                logger.e("TranslatorTextTranscriber: connect failed: ${e.message}")
                isActive = false
            }
    }

    suspend fun stop() {
        if (!isActive) return
        isActive = false
        runCatching { client.disconnect() }
        eventJob?.cancel()
        eventJob = null
        turnBuffer.clear()
        logger.d("TranslatorTextTranscriber: stopped")
    }

    /**
     * Передать PCM-чанк от микрофона в text-сессию.
     * Вызывается параллельно с основной audio-сессией.
     */
    fun sendAudio(pcm: ByteArray) {
        if (!isActive || !client.isReady) return
        client.sendAudio(pcm)
    }

    fun sendAudioStreamEnd() {
        if (!isActive || !client.isReady) return
        runCatching { client.sendAudioStreamEnd() }
    }

    private fun handleEvent(event: GeminiEvent) {
        when (event) {
            is GeminiEvent.SetupComplete -> {
                logger.d("TranslatorTextTranscriber: ready")
            }
            is GeminiEvent.ModelText -> {
                turnBuffer.append(event.text)
            }
            is GeminiEvent.TurnComplete, is GeminiEvent.GenerationComplete -> {
                val raw = turnBuffer.toString().trim()
                turnBuffer.clear()
                if (raw.isNotEmpty()) {
                    parseAndEmit(raw)
                }
            }
            is GeminiEvent.Interrupted -> {
                turnBuffer.clear()
            }
            is GeminiEvent.ConnectionError -> {
                logger.e("TranslatorTextTranscriber: connection error: ${event.message}")
            }
            is GeminiEvent.Disconnected -> {
                logger.d("TranslatorTextTranscriber: disconnected ${event.code}")
            }
            else -> { /* ignore */ }
        }
    }

    private fun parseAndEmit(raw: String) {
        // Берём только первую строку, на случай если модель насочиняла
        val line = raw.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: return
        val pipeIdx = line.indexOf('|')

        val (lang, text) = if (pipeIdx in 1..6) {
            val l = line.substring(0, pipeIdx).trim().lowercase()
            val t = line.substring(pipeIdx + 1).trim()
            l to t
        } else {
            // Fallback: модель не дала формат — считаем unknown и берём как есть
            "unknown" to line
        }

        if (text.isBlank() || text == "...") {
            logger.d("TranslatorTextTranscriber: empty/unintelligible, skipping")
            return
        }

        val normalizedLang = when (lang) {
            "ru", "uk", "de", "en", "unknown" -> lang
            else -> "unknown"
        }

        logger.d("TranslatorTextTranscriber: [$normalizedLang] $text")
        _transcripts.tryEmit(UserTranscriptEvent(text = text, language = normalizedLang))
    }

    fun shutdown() {
        scope.cancel()
    }
}
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

sealed class TranscriberEvent {
    data class LiveUpdate(val original: String, val translation: String) : TranscriberEvent()
    data class FinalTurn(val original: String, val translation: String, val lang: String) : TranscriberEvent()
}

@Singleton
class TranslatorTextTranscriber @Inject constructor(
    @TranslatorTextScope private val client: LiveClient,
    private val logger: AppLogger,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _events = MutableSharedFlow<TranscriberEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val events: SharedFlow<TranscriberEvent> = _events.asSharedFlow()

    @Volatile private var eventJob: Job? = null
    @Volatile private var isActive: Boolean = false

    private val turnBuffer = StringBuilder()
    private val pcmBuffer = mutableListOf<ByteArray>()

    val isReady: Boolean get() = client.isReady

    private val systemInstruction = """
You are a real-time text translator. For every user utterance you produce TWO things: the original transcript AND the translation, following STRICT direction rules identical to the audio translator.

TRANSLATION DIRECTIONS — STRICT, NO EXCEPTIONS:
- Russian input   → German output
- Ukrainian input → German output
- German input    → Russian output (never Ukrainian, even after Ukrainian turns)
- Any other language → output nothing.

OUTPUT FORMAT — exactly two lines, no exceptions:
ORIGINAL: <exact transcript of what the user said, in the original language and script>
TRANSLATION: <the translation, following the direction rules above>

STYLE OF TRANSLATION (same rules as audio translator):
- Preserve first person: "меня зовут Иван" → "Ich heiße Ivan".
- Formality: Вы / ви → Sie; ты / ти → du.
- Idiomatic, not literal: "Как дела?" → "Wie geht's?"; "Alles klar" → "Понятно".
- Match register and length of the source.
- GERMAN OUTPUT — 100% GERMAN, ZERO ENGLISH: cool→toll, OK→in Ordnung, sorry→Entschuldigung, hi→hallo, bye→tschüss, thanks→danke, nice→schön, please→bitte.
- RUSSIAN OUTPUT — natural Russian word order. No German calques. No English loanwords.

ORIGINAL TRANSCRIPT RULES:
- Use Cyrillic for Russian and Ukrainian. Use Latin with umlauts for German (ä, ö, ü, ß).
- Distinguish Russian vs Ukrainian by markers: ї, і, є, ґ, "що", "як", "ти", "дякую", "привіт" → Ukrainian; otherwise Russian.
- Write what the user actually said. Do not paraphrase. Do not fix grammar.

ABSOLUTE RULES:
- Exactly 2 lines: one ORIGINAL line, one TRANSLATION line. Nothing else.
- No explanations. No alternatives. No commentary.
- Never use voice. Text only.
- If the language is not Russian, Ukrainian or German, or audio is unintelligible — output nothing.
""".trimIndent()

    suspend fun start(apiKey: String, model: String, logRaw: Boolean) {
        if (isActive) {
            logger.d("TranslatorTextTranscriber: already active, skipping start")
            return
        }
        isActive = true
        turnBuffer.clear()
        pcmBuffer.clear()

        val config = SessionConfig(
            model = model,
            responseModality = "TEXT",
            temperature = 0.0f,
            topP = 0.6f,
            topK = 10,
            maxOutputTokens = 256,
            voiceId = "Aoede",
            languageCode = "",
            latencyProfile = LatencyProfile.Off,
            autoActivityDetection = true,
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_HIGH",
            vadPrefixPaddingMs = 80,
            vadSilenceDurationMs = 350,
            systemInstruction = systemInstruction,
            inputTranscription = false,
            outputTranscription = false,
            enableSessionResumption = true,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = emptyList(),
            sendAudioStreamEnd = true,
            setupTimeoutMs = 8_000L,
            sendThinkingConfig = false,
        )

        logger.d("TranslatorTextTranscriber: starting (TEXT mode, model=$model)")

        eventJob = scope.launch {
            client.events.collect { event ->
                handleEvent(event)
            }
        }

        runCatching { client.connect(apiKey, config, logRaw) }
            .onSuccess {
                logger.d("TranslatorTextTranscriber: connected successfully")
            }
            .onFailure { e ->
                logger.e("TranslatorTextTranscriber: connect failed: ${e.message}")
                isActive = false
                eventJob?.cancel()
            }
    }

    suspend fun stop() {
        if (!isActive) return
        isActive = false
        runCatching { client.disconnect() }
        eventJob?.cancel()
        eventJob = null
        turnBuffer.clear()
        pcmBuffer.clear()
        logger.d("TranslatorTextTranscriber: stopped")
    }

    fun sendAudio(pcm: ByteArray) {
        if (!isActive) return
        if (!client.isReady) {
            pcmBuffer.add(pcm)
            return
        }
        if (pcmBuffer.isNotEmpty()) {
            pcmBuffer.forEach { client.sendAudio(it) }
            pcmBuffer.clear()
        }
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
                if (pcmBuffer.isNotEmpty()) {
                    pcmBuffer.forEach { client.sendAudio(it) }
                    pcmBuffer.clear()
                }
            }
            is GeminiEvent.ModelText -> {
                turnBuffer.append(event.text)
                parseAndEmitLive(turnBuffer.toString())
            }
            is GeminiEvent.TurnComplete, is GeminiEvent.GenerationComplete -> {
                val raw = turnBuffer.toString().trim()
                turnBuffer.clear()
                if (raw.isNotEmpty()) {
                    parseAndEmitFinal(raw)
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

    private fun parseAndEmitLive(raw: String) {
        val originalMatch = Regex("ORIGINAL:(.*?)(?:\\nTRANSLATION:|$)", RegexOption.DOT_MATCHES_ALL).find(raw)
        val translationMatch = Regex("TRANSLATION:(.*)", RegexOption.DOT_MATCHES_ALL).find(raw)
        val orig = originalMatch?.groupValues?.get(1)?.trim() ?: ""
        val trans = translationMatch?.groupValues?.get(1)?.trim() ?: ""
        _events.tryEmit(TranscriberEvent.LiveUpdate(orig, trans))
    }

    private fun parseAndEmitFinal(raw: String) {
        val originalMatch = Regex("ORIGINAL:(.*?)(?:\\nTRANSLATION:|$)", RegexOption.DOT_MATCHES_ALL).find(raw)
        val translationMatch = Regex("TRANSLATION:(.*)", RegexOption.DOT_MATCHES_ALL).find(raw)
        val orig = originalMatch?.groupValues?.get(1)?.trim() ?: ""
        val trans = translationMatch?.groupValues?.get(1)?.trim() ?: ""

        if (orig.isBlank() && trans.isBlank()) {
            logger.d("TranslatorTextTranscriber: empty/unintelligible, skipping")
            return
        }

        val lang = detectLang(orig)
        logger.d("TranslatorTextTranscriber: [$lang] $orig -> $trans")
        _events.tryEmit(TranscriberEvent.FinalTurn(orig, trans, lang))
    }

    private fun detectLang(text: String): String {
        val lower = text.lowercase()
        if (lower.any { it in "ієґї" }) return "uk"
        if (lower.any { it in "а-яё" }) return "ru"
        if (lower.any { it in "äöüß" } || lower.contains(Regex("\\b(der|die|das|und|ist|ich)\\b"))) return "de"
        return "unknown"
    }

    fun shutdown() {
        scope.launch { stop() }
    }
}
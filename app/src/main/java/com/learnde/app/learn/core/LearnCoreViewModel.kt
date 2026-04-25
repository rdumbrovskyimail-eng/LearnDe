// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.4.1 (Патч: безопасная отправка SystemText)
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.learn.domain.VocabularyViolation
import com.learnde.app.util.AppLogger
import com.learnde.app.util.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

@HiltViewModel
class LearnCoreViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @LearnScope private val liveClient: LiveClient,
    @LearnScope private val audioEngine: AudioEngine,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
    private val arbiter: ActiveClientArbiter,
    private val statusBus: LearnFunctionStatusBus,
    private val registry: LearnSessionRegistry,
    private val vocabularyEnforcer: com.learnde.app.learn.domain.VocabularyEnforcer,
) : ViewModel() {

    companion object {
        /** FIFO-лимит транскрипта в памяти (защита от OOM в долгих сессиях). */
        private const val MAX_TRANSCRIPT_SIZE = 150

        /** Таймаут флаша текста, если аудио так и не пришло. */
        private const val TEXT_FLUSH_TIMEOUT_MS = 2_000L

        /** Порог тишины ученика, после которого шлём ИИ промпт «подбодри его». */
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L

        /** Короткая стабилизация WebSocket перед первым sendText. */
        private const val GREETING_WARMUP_MS = 150L

        /** Длительность warmup-тишины для пробуждения серверного VAD (мс). */
        private const val SILENCE_WARMUP_MS = 400L

        /** Сколько мс реального/тихого аудио слать перед sendText. */
        private const val MIC_PREWARM_MS = 200L

        /** Если за столько мс после sendText модель не начала говорить —
         * пробуем ещё раз с более явной фразой. */
        private const val GREETING_RETRY_MS = 4_000L

        /** Если и после повторной попытки модель молчит — сдаёмся
         * и оставляем мик включённым. */
        private const val GREETING_FINAL_MS = 8_000L

        /** 16kHz mono 16-bit PCM — столько байт нужно на SILENCE_WARMUP_MS тишины. */
        private const val SILENCE_PCM_BYTES = (2 * 16000 * 400) / 1000 // = 12800
    }

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<LearnCoreEffect>(extraBufferCapacity = 8)
    val effects: SharedFlow<LearnCoreEffect> = _effects.asSharedFlow()

    val audioPlaybackFlow get() = audioEngine.playbackSync
    val functionStatus: StateFlow<FunctionStatus> = statusBus.status

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    @Volatile private var activeApiKey: String = ""
    @Volatile private var activeSession: LearnSession? = null
    @Volatile private var contextSeeded = false

    private val pendingToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val toolCallJobs = ConcurrentHashMap<String, Job>()
    private val startStopMutex = Mutex()
    private val micOperationMutex = Mutex()
    private var micJob: Job? = null
    private var silenceTimerJob: Job? = null
    private var greetingFallbackJob: Job? = null
    private var setupJob: Job? = null

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""

    @Volatile private var modelStartedSpeakingThisTurn = false
    @Volatile private var awaitingInitialGreeting = false

    private val transcriptMutex = Mutex()
    @Volatile private var transcriptBuffer: List<ConversationMessage> = emptyList()
    private val pendingModelText = StringBuilder()
    private var audioReceivedThisTurn = false
    private var pendingFlushJob: Job? = null

    @Volatile private var pendingVocabViolation: VocabularyViolation? = null

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        observeVocabularyViolations()
        viewModelScope.launch { audioEngine.initPlayback() }
    }

    private fun observeVocabularyViolations() {
        viewModelScope.launch {
            vocabularyEnforcer.violations.collect { violation ->
                if (activeSession?.id == "a1_situation") {
                    pendingVocabViolation = violation
                    logger.d("Learn: vocab violation buffered (${violation.violatingWords})")
                }
            }
        }
    }

    fun onIntent(intent: LearnCoreIntent) {
        when (intent) {
            is LearnCoreIntent.Start     -> handleStart(intent.sessionId)
            is LearnCoreIntent.Stop      -> handleStop()
            is LearnCoreIntent.Restart   -> handleRestart()
            is LearnCoreIntent.ToggleMic -> handleToggleMic()
            is LearnCoreIntent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    /**
     * ПАТЧ 3.4.1: Безопасная отправка системного текста.
     * Если ИИ говорит, ждем до 4 сек, чтобы не перебивать текущую генерацию.
     */
    fun sendSystemText(text: String) {
        if (!liveClient.isReady) {
            logger.w("Learn.sendSystemText: liveClient not ready, dropping: $text")
            return
        }
        viewModelScope.launch {
            if (_state.value.isAiSpeaking) {
                var waited = 0L
                val maxWaitMs = 4_000L
                while (_state.value.isAiSpeaking && waited < maxWaitMs) {
                    delay(120)
                    waited += 120
                }
            }
            runCatching { liveClient.sendText(text) }
                .onFailure { logger.e("Learn.sendSystemText failed: ${it.message}") }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e ->
                    logger.e("Learn: DataStore read error: ${e.message}")
                    emit(AppSettings())
                }
                .collect { settings ->
                    cachedSettings = settings
                    activeApiKey = settings.apiKey
                    _state.update { it.copy(apiKeySet = settings.apiKey.isNotEmpty()) }
                    audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
                    audioEngine.setMicGain(settings.micGain / 100f)
                    audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)
                }
        }
    }

    private fun buildLearnSessionConfig(session: LearnSession): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

        val userInfo = buildString {
            if (cachedSettings.userName.isNotBlank()) append("Имя ученика: ${cachedSettings.userName}. ")
            if (cachedSettings.learningGoals.isNotBlank()) append("Цель изучения: ${cachedSettings.learningGoals}. ")
            if (cachedSettings.learningTopics.isNotBlank()) append("Интересные темы: ${cachedSettings.learningTopics}. ")
        }

        val finalSystemInstruction = if (userInfo.isNotBlank()) {
            "${session.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\n" +
                "Обращайся к ученику по имени. Учитывай эти данные: $userInfo"
        } else {
            session.systemInstruction
        }

        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"   -> Triple(1200, 300, 0.3f)
            "a1_situation" -> Triple(1000, 300, cachedSettings.temperature)
            "a1_review"    -> Triple(1000, 300, cachedSettings.temperature)
            else           -> Triple(1000, 300, cachedSettings.temperature)
        }

        val finalSilenceMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
            maxOf(cachedSettings.vadSilenceTimeoutMs, 500)
        else silenceMs

        return SessionConfig(
            model = cachedSettings.model,
            temperature = temp,
            topP = cachedSettings.topP,
            topK = cachedSettings.topK,
            maxOutputTokens = cachedSettings.maxOutputTokens,
            presencePenalty = cachedSettings.presencePenalty,
            frequencyPenalty = cachedSettings.frequencyPenalty,
            voiceId = cachedSettings.voiceId,
            languageCode = cachedSettings.languageCode,
            latencyProfile = profile,
            autoActivityDetection = cachedSettings.enableServerVad,
            vadStartSensitivity = if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f)
                "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f)
                "END_SENSITIVITY_HIGH" else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = finalSilenceMs,
            vadPrefixPaddingMs = prefixMs,
            systemInstruction = finalSystemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = false,
            sessionHandle = null,
            enableContextCompression = false,
            enableGoogleSearch = false,
            functionDeclarations = session.functionDeclarations,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
        )
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                if (!owned && activeSession != null) {
                    logger.w("Learn: lost arbiter ownership → stopping")
                    handleStop()
                }
            }
        }
    }

    private suspend fun stopInternal() = startStopMutex.withLock {
        logger.d("▶ Learn.stopInternal")
        val session = activeSession
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        audioEngine.stopCapture()
        safeStopForegroundService()

        runCatching { liveClient.disconnect() }
        runCatching { session?.onExit() }

        activeSession = null
        pendingToolCalls.clear()
        transcriptMutex.withLock { transcriptBuffer = emptyList() }
        statusBus.reset()
        vocabularyEnforcer.reset()
        contextSeeded = false
        pendingVocabViolation = null
        pendingModelText.clear()
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        modelStartedSpeakingThisTurn = false
        awaitingInitialGreeting = false
        setupJob?.cancel()
        setupJob = null

        _state.update {
            it.copy(
                sessionId = null,
                connectionStatus = LearnConnectionStatus.Disconnected,
                isMicActive = false,
                isAiSpeaking = false,
                isPreparingSession = false,
            )
        }
        arbiter.release(ClientOwner.LEARN)
    }

    private suspend fun startInternal(sessionId: String) = startStopMutex.withLock {
        val session = registry.get(sessionId) ?: return@withLock
        if (activeApiKey.isEmpty()) {
            _state.update { it.copy(error = UiText.Plain("API ключ не задан. Задайте его в Настройках.")) }
            return@withLock
        }

        arbiter.acquire(ClientOwner.LEARN)
        runCatching { liveClient.disconnect() }

        pendingToolCalls.clear()
        contextSeeded = false
        statusBus.reset()
        lastInputText = ""
        pendingVocabViolation = null
        pendingModelText.clear()
        pendingFlushJob?.cancel()
        pendingFlushJob = null
        modelStartedSpeakingThisTurn = false
        awaitingInitialGreeting = false
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        setupJob = null

        session.onEnter()
        activeSession = session
        if (session.id == "a1_situation" || session.id == "a1_review") {
            vocabularyEnforcer.warmUp()
        }

        _state.update {
            it.copy(
                sessionId = session.id,
                connectionStatus = LearnConnectionStatus.Connecting,
                error = null,
                isMicActive = false,
                isAiSpeaking = false,
                isPreparingSession = true,
            )
        }

        runCatching {
            liveClient.connect(
                apiKey = activeApiKey,
                config = buildLearnSessionConfig(session),
                logRaw = cachedSettings.logRawWebSocketFrames
            )
        }.onFailure { e ->
            logger.e("Learn: connect failed: ${e.message}", e)
            _state.update {
                it.copy(
                    connectionStatus = LearnConnectionStatus.Disconnected,
                    isPreparingSession = false,
                    error = UiText.Plain("Не удалось подключиться: ${e.message}")
                )
            }
            arbiter.release(ClientOwner.LEARN)
            activeSession = null
        }
    }

    private fun handleStart(sessionId: String) {
        viewModelScope.launch {
            transcriptMutex.withLock { transcriptBuffer = emptyList() }
            _state.update { it.copy(transcript = emptyList()) }
            startInternal(sessionId)
        }
    }

    private fun handleStop() {
        viewModelScope.launch { stopInternal() }
    }

    private fun handleRestart() {
        val s = activeSession ?: return
        viewModelScope.launch {
            stopInternal()
            delay(150)
            startInternal(s.id)
        }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == LearnConnectionStatus.Ready
            || _state.value.connectionStatus == LearnConnectionStatus.Negotiating) {
            startMic()
        }
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return

        val fgsOk = safeStartForegroundService()
        if (!fgsOk) {
            _effects.tryEmit(LearnCoreEffect.ShowToast(
                UiText.Plain("Запусти обучение когда приложение на переднем плане")
            ))
        }

        _state.update {
            it.copy(isMicActive = true, connectionStatus = LearnConnectionStatus.Recording)
        }
        micJob = viewModelScope.launch {
            micOperationMutex.withLock {
                launch {
                    audioEngine.micOutput.collect { chunk ->
                        if (!_state.value.isAiSpeaking) {
                            liveClient.sendAudio(chunk)
                        }
                    }
                }
                audioEngine.startCapture()
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        silenceTimerJob?.cancel()
        viewModelScope.launch {
            micOperationMutex.withLock { audioEngine.stopCapture() }
            when {
                cachedSettings.sendAudioStreamEnd -> liveClient.sendAudioStreamEnd()
                else -> liveClient.sendTurnComplete()
            }
            _state.update {
                it.copy(
                    isMicActive = false,
                    connectionStatus = if (liveClient.isReady) LearnConnectionStatus.Ready
                    else LearnConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun safeStartForegroundService(): Boolean {
        return try {
            appContext.startForegroundService(
                com.learnde.app.GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
            true
        } catch (e: Exception) {
            logger.w("FGS start error: ${e.message}")
            false
        }
    }

    private fun safeStopForegroundService() {
        try {
            appContext.startService(
                com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)
            )
        } catch (_: Exception) {}
    }

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> handleSetupComplete()

                    is GeminiEvent.AudioChunk -> {
                        if (!modelStartedSpeakingThisTurn) {
                            modelStartedSpeakingThisTurn = true
                            if (awaitingInitialGreeting) {
                                awaitingInitialGreeting = false
                                greetingFallbackJob?.cancel()
                            }
                        }
                        _state.update { it.copy(isAiSpeaking = true, isPreparingSession = false) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        if (!audioReceivedThisTurn) {
                            audioReceivedThisTurn = true
                            if (pendingModelText.isNotEmpty()) flushPendingModelText()
                        }
                    }

                    is GeminiEvent.Interrupted -> {
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                        pendingModelText.clear()
                        audioReceivedThisTurn = false
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null
                    }

                    is GeminiEvent.TurnComplete -> {
                        if (pendingModelText.isNotEmpty()) flushPendingModelText()
                        audioReceivedThisTurn = false
                        modelStartedSpeakingThisTurn = false
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null

                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }

                        flushPendingVocabViolation()

                        lastInputTs = System.currentTimeMillis()
                        if (_state.value.isMicActive) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                val quietFor = System.currentTimeMillis() - lastInputTs
                                if (quietFor > SILENCE_CHECK_WINDOW_MS && liveClient.isReady) {
                                    liveClient.sendText(
                                        "[СИСТЕМА]: Ученик молчит. Коротко подбодри его по-русски."
                                    )
                                    lastInputTs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    is GeminiEvent.InputTranscript -> {
                        silenceTimerJob?.cancel()
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputText || now - lastInputTs > 5_000) {
                            lastInputText = event.text
                            lastInputTs = now
                            launch { appendTranscript(ConversationMessage.user(event.text)) }
                        } else {
                            lastInputTs = now
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        appendOrAppendToLastModel(event.text)
                        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
                            vocabularyEnforcer.analyze(event.text)
                        }
                    }

                    is GeminiEvent.ModelText -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        appendOrAppendToLastModel(event.text)
                    }

                    is GeminiEvent.ToolCall -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        handleToolCalls(event)
                    }

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            pendingToolCalls.remove(id)
                            toolCallJobs.remove(id)?.cancel()
                            statusBus.onCompleted("<cancelled>", id, success = false)
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        greetingFallbackJob?.cancel()
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                    }

                    is GeminiEvent.ConnectionError -> {
                        greetingFallbackJob?.cancel()
                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                                error = UiText.Plain(event.message),
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun handleSetupComplete() {
        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
        val session = activeSession ?: return
        contextSeeded = true
        modelStartedSpeakingThisTurn = false

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            delay(GREETING_WARMUP_MS)
            if (!liveClient.isReady) return@launch

            if (session.initialUserMessage.isBlank()) {
                if (!_state.value.isMicActive) startMic()
                return@launch
            }

            awaitingInitialGreeting = true
            runCatching { sendSilenceWarmup() }

            if (!liveClient.isReady) return@launch

            if (!_state.value.isMicActive) startMic()
            delay(MIC_PREWARM_MS)

            if (!liveClient.isReady) return@launch

            liveClient.sendText(session.initialUserMessage)

            greetingFallbackJob?.cancel()
            greetingFallbackJob = viewModelScope.launch {
                delay(GREETING_RETRY_MS)
                if (awaitingInitialGreeting && liveClient.isReady) {
                    runCatching { sendSilenceWarmup() }
                    liveClient.sendText("Ты меня слышишь? Поприветствуй ученика.")
                }
            }
        }
    }

    private suspend fun sendSilenceWarmup() {
        val silence = ByteArray(SILENCE_PCM_BYTES)
        val chunkSize = 1280
        var offset = 0
        while (offset < silence.size) {
            val end = minOf(offset + chunkSize, silence.size)
            liveClient.sendAudio(silence.copyOfRange(offset, end))
            offset = end
            delay(5)
        }
    }

    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review")
            && liveClient.isReady) {
            val prompt = vocabularyEnforcer.buildCorrectionPrompt(violation)
            liveClient.sendText(prompt)
        }
    }

    private suspend fun appendTranscript(msg: ConversationMessage) {
        transcriptMutex.withLock {
            val next = (transcriptBuffer + msg).takeLast(MAX_TRANSCRIPT_SIZE)
            transcriptBuffer = next
            _state.update { it.copy(transcript = next) }
        }
    }

    private fun appendOrAppendToLastModel(text: String) {
        if (text.isEmpty()) return
        pendingModelText.append(text)

        if (audioReceivedThisTurn) {
            viewModelScope.launch { flushPendingModelText() }
            return
        }

        pendingFlushJob?.cancel()
        pendingFlushJob = viewModelScope.launch {
            delay(TEXT_FLUSH_TIMEOUT_MS)
            if (pendingModelText.isNotEmpty()) flushPendingModelText()
        }
    }

    private suspend fun flushPendingModelText() {
        if (pendingModelText.isEmpty()) return
        val text = pendingModelText.toString()
        pendingModelText.clear()
        pendingFlushJob?.cancel()
        pendingFlushJob = null

        transcriptMutex.withLock {
            val last = transcriptBuffer.lastOrNull()
            if (last != null && last.role == ConversationMessage.ROLE_MODEL) {
                if (last.text.endsWith(text)) return
                val updated = last.copy(text = last.text + text)
                val next = transcriptBuffer.toMutableList().also { it[it.size - 1] = updated }
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                val next = (transcriptBuffer + ConversationMessage(
                    role = ConversationMessage.ROLE_MODEL,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
        }
    }

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        val session = activeSession
        val responses = java.util.concurrent.ConcurrentLinkedQueue<ToolResponse>()
        for (call in event.calls) {
            pendingToolCalls.add(call.id)
            statusBus.onDetected(call.name, call.id)
        }
        val children = event.calls.map { call ->
            viewModelScope.launch {
                try {
                    if (call.id !in pendingToolCalls) return@launch
                    statusBus.onExecuting(call.name, call.id)
                    val result = runCatching {
                        session?.handleToolCall(call) ?: """{"error":"no active session"}"""
                    }.getOrElse { """{"error":"${it.message}"}""" }
                    statusBus.onCompleted(call.name, call.id, !result.contains("\"error\""))
                    responses.add(ToolResponse(call.name, call.id, result))
                } finally {
                    pendingToolCalls.remove(call.id)
                    toolCallJobs.remove(call.id)
                }
            }.also { toolCallJobs[call.id] = it }
        }
        viewModelScope.launch {
            children.joinAll()
            if (responses.isNotEmpty() && liveClient.isReady) {
                liveClient.sendToolResponse(responses.toList())
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        pendingFlushJob?.cancel()
        safeStopForegroundService()
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            runCatching { arbiter.release(ClientOwner.LEARN) }
        }
    }
}

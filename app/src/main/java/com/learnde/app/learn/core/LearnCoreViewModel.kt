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
        private const val MAX_TRANSCRIPT_SIZE = 150
        private const val TEXT_FLUSH_TIMEOUT_MS = 2_000L
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L
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
    private val startStopMutex = Mutex()
    private var micJob: Job? = null
    private var silenceTimerJob: Job? = null

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""
    @Volatile private var lastOutputTs: Long = 0L
    @Volatile private var lastOutputText: String = ""

    private val transcriptBuffer = mutableListOf<ConversationMessage>()
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

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data.catch { emit(AppSettings()) }.collect { settings ->
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
        val profile = runCatching { enumValueOf<LatencyProfile>(cachedSettings.latencyProfile) }.getOrDefault(LatencyProfile.UltraLow)
        val userInfo = buildString {
            if (cachedSettings.userName.isNotBlank()) append("Имя ученика: ${cachedSettings.userName}. ")
        }
        val finalSystemInstruction = if (userInfo.isNotBlank()) "${session.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\n$userInfo" else session.systemInstruction

        // ИСПРАВЛЕНИЕ: Возвращаем стабильные настройки VAD. 
        // 800мс тишины достаточно для быстрого отклика, но не обрезает слова.
        // 300мс префикса не перегружает буфер.
        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"    -> Triple(1000, 300, 0.3f)
            "a1_situation"  -> Triple(800, 300, cachedSettings.temperature)
            "a1_review"     -> Triple(800, 300, cachedSettings.temperature)
            else            -> Triple(1000, 300, cachedSettings.temperature)
        }

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
            vadStartSensitivity = "START_SENSITIVITY_HIGH",
            vadEndSensitivity = "END_SENSITIVITY_HIGH",
            vadSilenceDurationMs = silenceMs,
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
                if (!owned && activeSession != null) handleStop()
            }
        }
    }

    private fun handleStart(sessionId: String) {
        viewModelScope.launch {
            startStopMutex.withLock {
                val session = registry.get(sessionId) ?: return@withLock
                if (activeApiKey.isEmpty()) return@withLock

                arbiter.acquire(ClientOwner.LEARN)
                runCatching { liveClient.disconnect() }

                transcriptBuffer.clear()
                pendingToolCalls.clear()
                contextSeeded = false
                statusBus.reset()
                
                pendingModelText.clear()
                pendingFlushJob?.cancel()
                pendingFlushJob = null

                session.onEnter()
                activeSession = session

                _state.update {
                    it.copy(
                        sessionId = session.id,
                        connectionStatus = LearnConnectionStatus.Connecting,
                        transcript = emptyList(),
                        error = null,
                        isMicActive = false,
                        isAiSpeaking = false,
                        isPreparingSession = true // Включаем анимацию загрузки
                    )
                }

                runCatching {
                    liveClient.connect(activeApiKey, buildLearnSessionConfig(session), cachedSettings.logRawWebSocketFrames)
                }.onFailure { e ->
                    _state.update { it.copy(connectionStatus = LearnConnectionStatus.Disconnected, isPreparingSession = false) }
                    arbiter.release(ClientOwner.LEARN)
                    activeSession = null
                }
            }
        }
    }

    private fun handleStop() {
        viewModelScope.launch {
            startStopMutex.withLock {
                val session = activeSession
                micJob?.cancel()
                silenceTimerJob?.cancel()
                audioEngine.stopCapture()
                safeStopForegroundService()
                runCatching { liveClient.disconnect() }
                runCatching { session?.onExit() }

                pendingModelText.clear()
                pendingFlushJob?.cancel()
                pendingFlushJob = null
                
                activeSession = null
                pendingToolCalls.clear()
                statusBus.reset()

                _state.update {
                    it.copy(sessionId = null, connectionStatus = LearnConnectionStatus.Disconnected, isMicActive = false, isAiSpeaking = false, isPreparingSession = false)
                }
                arbiter.release(ClientOwner.LEARN)
            }
        }
    }

    private fun handleRestart() {
        val s = activeSession ?: return
        handleStop()
        handleStart(s.id)
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == LearnConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return
        safeStartForegroundService()
        _state.update { it.copy(isMicActive = true, connectionStatus = LearnConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            launch { audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) } }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        silenceTimerJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            if (cachedSettings.sendAudioStreamEnd) liveClient.sendAudioStreamEnd()
            else if (!cachedSettings.enableServerVad) liveClient.sendTurnComplete()
            _state.update { it.copy(isMicActive = false, connectionStatus = if (liveClient.isReady) LearnConnectionStatus.Ready else LearnConnectionStatus.Disconnected) }
        }
    }

    private fun safeStartForegroundService(): Boolean {
        return try { appContext.startForegroundService(com.learnde.app.GeminiLiveForegroundService.startIntent(appContext, cachedSettings.forceSpeakerOutput)); true }
        catch (e: Exception) { false }
    }

    private fun safeStopForegroundService() {
        try { appContext.startService(com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)) } catch (_: Exception) {}
    }

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected -> _state.update { it.copy(connectionStatus = LearnConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> {
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
                        val session = activeSession ?: return@collect
                        contextSeeded = true
                        
                        if (session.initialUserMessage.isNotBlank()) {
                            viewModelScope.launch {
                                delay(500) // ИСПРАВЛЕНИЕ: Даем сокету полсекунды на стабилизацию
                                liveClient.sendText(session.initialUserMessage)
                                // ИСПРАВЛЕНИЕ: Убрали sendTurnComplete(), который ломал генерацию!
                            }
                        }
                        if (!_state.value.isMicActive) startMic()
                    }

                    is GeminiEvent.AudioChunk -> {
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
                        pendingFlushJob?.cancel()
                        pendingFlushJob = null
                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }
                        flushPendingVocabViolation()
                        lastInputTs = System.currentTimeMillis()
                    }

                    is GeminiEvent.GenerationComplete -> _state.update { it.copy(isAiSpeaking = false) }

                    is GeminiEvent.InputTranscript -> {
                        silenceTimerJob?.cancel()
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputText || now - lastInputTs > 5_000) {
                            lastInputText = event.text
                            lastInputTs = now
                            appendTranscript(ConversationMessage.user(event.text))
                        } else lastInputTs = now
                    }

                    is GeminiEvent.OutputTranscript -> {
                        appendOrAppendToLastModel(event.text)
                        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") vocabularyEnforcer.analyze(event.text)
                    }

                    is GeminiEvent.ModelText -> {
                        if (!cachedSettings.outputTranscription) appendOrAppendToLastModel(event.text)
                    }

                    is GeminiEvent.ToolCall -> handleToolCalls(event)

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            pendingToolCalls.remove(id)
                            statusBus.onCompleted("<cancelled>", id, success = false)
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Disconnected, isMicActive = false, isPreparingSession = false) }
                        audioEngine.stopCapture()
                    }

                    is GeminiEvent.ConnectionError -> {
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Disconnected, isMicActive = false, isPreparingSession = false, error = UiText.Plain(event.message)) }
                        audioEngine.stopCapture()
                    }
                    else -> {}
                }
            }
        }
    }

    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") && liveClient.isReady) {
            liveClient.sendText(vocabularyEnforcer.buildCorrectionPrompt(violation))
        }
    }

    private fun appendTranscript(msg: ConversationMessage) {
        transcriptBuffer.add(msg)
        while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) transcriptBuffer.removeAt(0)
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    private fun appendOrAppendToLastModel(text: String) {
        if (text.isEmpty()) return
        pendingModelText.append(text)
        if (audioReceivedThisTurn) {
            flushPendingModelText()
            return
        }
        pendingFlushJob?.cancel()
        pendingFlushJob = viewModelScope.launch {
            delay(TEXT_FLUSH_TIMEOUT_MS)
            if (pendingModelText.isNotEmpty()) flushPendingModelText()
        }
    }

    private fun flushPendingModelText() {
        if (pendingModelText.isEmpty()) return
        val text = pendingModelText.toString()
        pendingModelText.clear()
        pendingFlushJob?.cancel()
        pendingFlushJob = null

        val last = transcriptBuffer.lastOrNull()
        if (last != null && last.role == ConversationMessage.ROLE_MODEL) {
            transcriptBuffer[transcriptBuffer.size - 1] = last.copy(text = last.text + text)
        } else {
            transcriptBuffer.add(ConversationMessage(role = ConversationMessage.ROLE_MODEL, text = text, timestamp = System.currentTimeMillis()))
            while (transcriptBuffer.size > MAX_TRANSCRIPT_SIZE) transcriptBuffer.removeAt(0)
        }
        _state.update { it.copy(transcript = transcriptBuffer.toList()) }
    }

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        viewModelScope.launch {
            for (call in event.calls) {
                pendingToolCalls.add(call.id)
                statusBus.onDetected(call.name, call.id)
            }
            val responses = mutableListOf<ToolResponse>()
            val session = activeSession

            for (call in event.calls) {
                if (call.id !in pendingToolCalls) continue
                statusBus.onExecuting(call.name, call.id)
                val result = runCatching { session?.handleToolCall(call) ?: """{"error":"no active session"}""" }
                    .getOrElse { """{"error":"${it.message?.replace("\"", "'")}"}""" }
                val success = !result.contains("\"error\"")
                pendingToolCalls.remove(call.id)
                statusBus.onCompleted(call.name, call.id, success)
                responses.add(ToolResponse(call.name, call.id, result))
            }
            if (responses.isNotEmpty() && liveClient.isReady) liveClient.sendToolResponse(responses)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        micJob?.cancel()
        silenceTimerJob?.cancel()
        pendingFlushJob?.cancel()
        pendingModelText.clear()
        safeStopForegroundService()
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            runCatching { arbiter.release(ClientOwner.LEARN) }
        }
    }
}
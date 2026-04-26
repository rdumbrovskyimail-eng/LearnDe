// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceViewModel.kt
//
// Изменения v3 (диагностика 1007):
//  [D1] 5 handler'ов для диагностических профилей setup:
//       handleConnectFull, handleConnectBaseline,
//       handleConnectWithoutThinking, handleConnectWithoutVad,
//       handleConnectWithoutSessionMgmt, handleConnectWithoutTranscription
//  [D2] connectWithProfile() — общий метод, сначала disconnect старого
//       соединения, потом connect с нужным профилем
//  [D3] DiagnosticResult логируется в state.diagnosticLog при каждом
//       Disconnected/SetupComplete/ConnectionError
//  [D4] Убран auto-reconnect во время диагностики (scheduleReconnect
//       не вызывается если profile != FULL)
//
// Предыдущие фичи (v2) сохранены:
//  • pendingToolCalls.clear() в disconnect/enter/exit/Disconnected/Error
//  • dedup транскрипций на reconnect (in/out, окно 5 сек)
//  • [5.1] VAD-sensitivity мапится в enum-строки
//  • [5.5] stopMic: один сигнал (audioStreamEnd ИЛИ turnComplete)
//  • [5.6] handleSendText: sendText(initial) vs sendRealtimeText(in-dialog)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.GeminiLiveForegroundService
import com.learnde.app.data.BackgroundImageStore
import com.learnde.app.data.NetworkMonitor
import com.learnde.app.data.PersistentConversationRepository
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.domain.AudioEngine
import com.learnde.app.domain.ConversationRepository
import com.learnde.app.domain.LiveClient
import com.learnde.app.domain.ToolResponse
import com.learnde.app.domain.avatar.AvatarAnimator
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.domain.model.GeminiEvent
import com.learnde.app.domain.model.LatencyProfile
import com.learnde.app.domain.model.SessionConfig
import com.learnde.app.domain.scene.SceneMode
import com.learnde.app.domain.tools.ToolRegistry
import com.learnde.app.learn.core.ActiveClientArbiter
import com.learnde.app.learn.core.ClientOwner
import com.learnde.app.presentation.voice.haptics.HapticEngine
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @com.learnde.app.learn.core.VoiceScope private val liveClient: LiveClient,
    @com.learnde.app.learn.core.VoiceScope private val audioEngine: AudioEngine,
    private val conversationRepository: ConversationRepository,
    private val logger: AppLogger,
    private val settingsStore: DataStore<AppSettings>,
    private val toolRegistry: ToolRegistry,
    private val hapticEngine: HapticEngine,
    private val networkMonitor: NetworkMonitor,
    val avatarAnimator: AvatarAnimator,
    private val backgroundStore: BackgroundImageStore,
    private val arbiter: ActiveClientArbiter
) : ViewModel() {

    val audioPlaybackFlow get() = audioEngine.playbackSync
    val backgroundBitmap = backgroundStore.bitmap

    private val _state = MutableStateFlow(VoiceState())
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VoiceEffect>(extraBufferCapacity = 8)
    val effects = _effects.asSharedFlow()

    @Volatile private var cachedSettings: AppSettings = AppSettings()
    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null
    private var micJob: Job? = null
    @Volatile private var contextSeeded = false
    @Volatile private var activeApiKey: String = ""
    private val pendingToolCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val pendingSelfCloseEvents = AtomicInteger(0)
    private val modeSwitchMutex = Mutex()

    @Volatile private var lastInputTranscript: String = ""
    @Volatile private var lastInputTranscriptTime: Long = 0L
    @Volatile private var lastOutputTranscript: String = ""
    @Volatile private var lastOutputTranscriptTime: Long = 0L

    init {
        observeArbiter()
        observeSettings()
        observeGeminiEvents()
        observeTranscript()
        initAudioPlayback()
        observeNetwork()
        avatarAnimator.start()
    }

    // ════════════════════════════════════════════════════════════
    //  INTENT DISPATCHER
    // ════════════════════════════════════════════════════════════

    fun onIntent(intent: VoiceIntent) {
        when (intent) {
            is VoiceIntent.SubmitApiKey          -> handleSubmitApiKey(intent.key)
            is VoiceIntent.Connect               -> handleConnect()
            is VoiceIntent.Disconnect            -> handleDisconnect()
            is VoiceIntent.ToggleMic             -> handleToggleMic()
            is VoiceIntent.SendText              -> handleSendText(intent.text)
            is VoiceIntent.SaveLog               -> handleSaveLog()
            is VoiceIntent.ClearConversation     -> handleClearConversation()
            is VoiceIntent.ToggleFullscreenScene -> _state.update {
                it.copy(isSceneFullscreen = !it.isSceneFullscreen)
            }

            // ── Диагностические интенты ──
            is VoiceIntent.ConnectFull ->
                connectWithProfile(DiagnosticProfile.FULL)
            is VoiceIntent.ConnectBaseline ->
                connectWithProfile(DiagnosticProfile.BASELINE)
            is VoiceIntent.ConnectWithoutThinking ->
                connectWithProfile(DiagnosticProfile.WITHOUT_THINKING)
            is VoiceIntent.ConnectWithoutVad ->
                connectWithProfile(DiagnosticProfile.WITHOUT_VAD)
            is VoiceIntent.ConnectWithoutSessionMgmt ->
                connectWithProfile(DiagnosticProfile.WITHOUT_SESSION_MGMT)
            is VoiceIntent.ConnectWithoutTranscription ->
                connectWithProfile(DiagnosticProfile.WITHOUT_TRANSCRIPTION)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  DIAGNOSTIC CONNECT [D1][D2]
    // ════════════════════════════════════════════════════════════

    /**
     * Общая точка входа для диагностических тестов.
     * 1. Отменяет автоматический reconnect
     * 2. Полностью закрывает текущее WS-соединение
     * 3. Обновляет lastTestedProfile в state
     * 4. Открывает новое соединение с нужным профилем setup
     */
    private fun connectWithProfile(profile: DiagnosticProfile) {
        if (activeApiKey.isEmpty()) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Сначала введи API key")))
            return
        }

        logger.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logger.d("🔬 DIAGNOSTIC TEST: ${profile.label}")
        logger.d("   ${profile.description}")
        logger.d("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")

        viewModelScope.launch {
            // 1. Отменить reconnect и закрыть предыдущее соединение
            reconnectJob?.cancel()
            micJob?.cancel()
            audioEngine.stopCapture()
            pendingToolCalls.clear()

            if (_state.value.connectionStatus != ConnectionStatus.Disconnected) {
                pendingSelfCloseEvents.incrementAndGet()
                liveClient.disconnect()
            }

            // 2. Обновить state — показать какой профиль тестируется
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Connecting,
                    lastTestedProfile = profile,
                    isMicActive = false,
                    isAiSpeaking = false,
                    error = null
                )
            }

            contextSeeded = false
            reconnectAttempt = 0

            // 3. Захватить арбитра
            arbiter.acquire(ClientOwner.VOICE)

            (conversationRepository as? PersistentConversationRepository)?.startNewSession()

            // 4. Запустить ForegroundService (для микрофона)
            if (ContextCompat.checkSelfPermission(
                    appContext, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                try {
                    appContext.startForegroundService(
                        GeminiLiveForegroundService.startIntent(
                            appContext, cachedSettings.forceSpeakerOutput
                        )
                    )
                } catch (e: Exception) {
                    logger.w("ForegroundService start failed: ${e.message}")
                }
            }

            // 5. Connect с нужным профилем
            try {
                val config = buildConfigForProfile(profile)
                logger.d("Using config: minimalSetup=${config.diagnosticMinimalSetup} " +
                        "thinking=${config.sendThinkingConfig} vad=${config.sendVadConfig} " +
                        "transcr=${config.sendTranscriptionConfig} " +
                        "resumption=${config.sendSessionResumptionConfig} " +
                        "compression=${config.sendContextCompressionConfig}")

                liveClient.connect(
                    apiKey = activeApiKey,
                    config = config,
                    logRaw = true  // ВСЕГДА логируем raw во время диагностики
                )
            } catch (e: Exception) {
                logger.e("liveClient.connect error: ${e.message}", e)
                addDiagnosticResult(profile, "ERROR: ${e.message}")
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
            }
        }
    }

    /**
     * Строит SessionConfig для диагностического профиля, используя
     * пользовательские настройки (API key, voice, system instruction и т.д.)
     * но с выключенными блоками согласно профилю.
     */
    private fun buildConfigForProfile(profile: DiagnosticProfile): SessionConfig {
        val base = buildSessionConfig()  // пользовательские настройки

        return when (profile) {
            DiagnosticProfile.FULL -> base

            DiagnosticProfile.BASELINE -> base.copy(
                diagnosticMinimalSetup = true,
                enableSessionResumption = false,
                enableContextCompression = false,
                inputTranscription = false,
                outputTranscription = false,
                logFullSetupJson = true
            )

            DiagnosticProfile.WITHOUT_THINKING -> base.copy(
                sendThinkingConfig = false,
                logFullSetupJson = true
            )

            DiagnosticProfile.WITHOUT_VAD -> base.copy(
                sendVadConfig = false,
                logFullSetupJson = true
            )

            DiagnosticProfile.WITHOUT_SESSION_MGMT -> base.copy(
                sendSessionResumptionConfig = false,
                sendContextCompressionConfig = false,
                enableSessionResumption = false,
                enableContextCompression = false,
                logFullSetupJson = true
            )

            DiagnosticProfile.WITHOUT_TRANSCRIPTION -> base.copy(
                sendTranscriptionConfig = false,
                inputTranscription = false,
                outputTranscription = false,
                logFullSetupJson = true
            )
        }
    }

    /** [D3] Добавить результат теста в лог на экране. */
    private fun addDiagnosticResult(profile: DiagnosticProfile, result: String) {
        _state.update {
            val newLog = (it.diagnosticLog + DiagnosticResult(profile, result))
                .takeLast(10)  // храним последние 10
            it.copy(diagnosticLog = newLog)
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TRANSCRIPT
    // ════════════════════════════════════════════════════════════

    private fun observeTranscript() {
        viewModelScope.launch {
            conversationRepository.getAllFlow()
                .catch { e -> logger.e("Transcript flow error: ${e.message}") }
                .collect { list ->
                    _state.update { it.copy(transcript = list) }
                }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  NETWORK
    // ════════════════════════════════════════════════════════════

    private fun observeNetwork() {
        viewModelScope.launch {
            var wasDisconnected = false
            networkMonitor.isConnected.collect { connected ->
                if (!connected) {
                    wasDisconnected = true
                } else if (wasDisconnected) {
                    wasDisconnected = false
                    // [D4] Во время диагностики не переподключаемся автоматически
                    if (_state.value.lastTestedProfile == DiagnosticProfile.FULL &&
                        _state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        activeApiKey.isNotEmpty()
                    ) {
                        logger.d("Network restored → reconnecting")
                        reconnectAttempt = 0
                        handleConnect()
                    }
                }
            }
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                when (owner) {
                    ClientOwner.LEARN -> {
                        if (_state.value.connectionStatus != ConnectionStatus.Disconnected) {
                            logger.d("Voice: arbiter=LEARN → disconnecting Voice client")
                            reconnectJob?.cancel()
                            micJob?.cancel()
                            audioEngine.stopCapture()
                            pendingToolCalls.clear()
                            pendingSelfCloseEvents.incrementAndGet()
                            liveClient.disconnect()
                            _state.update {
                                it.copy(
                                    connectionStatus = ConnectionStatus.Disconnected,
                                    isMicActive = false,
                                    isAiSpeaking = false,
                                )
                            }
                        }
                    }
                    ClientOwner.VOICE, ClientOwner.NONE -> {
                        logger.d("Voice: arbiter=$owner — ok")
                    }
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SETTINGS
    // ════════════════════════════════════════════════════════════

    private fun observeSettings() {
        viewModelScope.launch {
            settingsStore.data
                .catch { e -> logger.e("DataStore read error: ${e.message}"); emit(AppSettings()) }
                .collect { settings ->
                    val wasKeyEmpty = cachedSettings.apiKey.isEmpty()
                    cachedSettings = settings
                    activeApiKey = settings.apiKey

                    val profile = runCatching {
                        enumValueOf<LatencyProfile>(settings.latencyProfile)
                    }.getOrDefault(LatencyProfile.UltraLow)

                    val hasKey = settings.apiKey.isNotEmpty()
                    val sceneMode = SceneMode.from(settings.sceneMode)

                    _state.update {
                        it.copy(
                            apiKeySet = hasKey, showApiKeyInput = !hasKey,
                            currentVoiceId = settings.voiceId,
                            currentLatencyProfile = profile,
                            useAec = settings.useAec,
                            showDebugLog = settings.showDebugLog,
                            temperature = settings.temperature,
                            topP = settings.topP,
                            topK = settings.topK,
                            maxOutputTokens = settings.maxOutputTokens,
                            model = settings.model,
                            systemInstruction = settings.systemInstruction,
                            enableGoogleSearch = settings.enableGoogleSearch,
                            enableCompression = settings.enableContextCompression,
                            enableResumption = settings.enableSessionResumption,
                            languageCode = settings.languageCode,
                            logRawFrames = settings.logRawWebSocketFrames,
                            showUsageMetadata = settings.showUsageMetadata,
                            playbackVolume = settings.playbackVolume,
                            forceSpeakerOutput = settings.forceSpeakerOutput,
                            sceneMode = sceneMode,
                            sceneBgHasImage = settings.sceneBgHasImage,
                            chatFontScale = settings.chatFontScale,
                            chatShowRoleLabels = settings.chatShowRoleLabels,
                            chatShowTimestamps = settings.chatShowTimestamps,
                            chatAutoScroll = settings.chatAutoScroll,
                            chatBackgroundAlpha = settings.chatBackgroundAlpha
                        )
                    }
                    audioEngine.setPlaybackVolume(settings.playbackVolume / 100f)
                    audioEngine.setMicGain(settings.micGain / 100f)
                    audioEngine.setSpeakerRouting(settings.forceSpeakerOutput)

                    // [D4] Не автоконнектимся при изменении ключа — пусть пользователь
                    // сам нажмёт нужную диагностическую кнопку
                    if (hasKey && wasKeyEmpty &&
                        _state.value.connectionStatus == ConnectionStatus.Disconnected
                    ) {
                        logger.d("Key received — жди, нажми диагностическую кнопку")
                    }
                }
        }
    }

    private fun buildSessionConfig(): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

        val userInfo = buildString {
            if (cachedSettings.userName.isNotBlank()) append("Имя ученика: ${cachedSettings.userName}. ")
            if (cachedSettings.learningGoals.isNotBlank()) append("Цель изучения: ${cachedSettings.learningGoals}. ")
            if (cachedSettings.learningTopics.isNotBlank()) append("Интересные темы для обсуждения: ${cachedSettings.learningTopics}. ")
        }

        val finalSystemInstruction = if (userInfo.isNotBlank()) {
            "${cachedSettings.systemInstruction}\n\n[ДАННЫЕ ПОЛЬЗОВАТЕЛЯ]:\nУчитывай эту информацию в диалоге: $userInfo"
        } else {
            cachedSettings.systemInstruction
        }

        return SessionConfig(
            model = cachedSettings.model,
            temperature = cachedSettings.temperature,
            topP = cachedSettings.topP,
            topK = cachedSettings.topK,
            maxOutputTokens = cachedSettings.maxOutputTokens,
            presencePenalty = cachedSettings.presencePenalty,
            frequencyPenalty = cachedSettings.frequencyPenalty,
            voiceId = cachedSettings.voiceId,
            languageCode = cachedSettings.languageCode,
            latencyProfile = profile,
            autoActivityDetection = cachedSettings.enableServerVad,
            // [5.1] VAD sensitivity — enum-строки v1beta API
            vadStartSensitivity = if (cachedSettings.vadStartOfSpeechSensitivity > 0.5f)
                "START_SENSITIVITY_HIGH" else "START_SENSITIVITY_LOW",
            vadEndSensitivity = if (cachedSettings.vadEndOfSpeechSensitivity > 0.5f)
                "END_SENSITIVITY_HIGH" else "END_SENSITIVITY_LOW",
            vadSilenceDurationMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
                cachedSettings.vadSilenceTimeoutMs else 100,
            vadPrefixPaddingMs = 20,
            systemInstruction = finalSystemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = cachedSettings.enableSessionResumption,
            transparentResumption = cachedSettings.transparentResumption,
            sessionHandle = liveClient.sessionHandle,
            enableContextCompression = cachedSettings.enableContextCompression,
            compressionTriggerTokens = cachedSettings.compressionTriggerTokens,
            compressionTargetTokens = cachedSettings.compressionTargetTokens,
            enableGoogleSearch = cachedSettings.enableGoogleSearch,
            sendAudioStreamEnd = cachedSettings.sendAudioStreamEnd,
            functionDeclarations = if (cachedSettings.enableTestFunctions)
                toolRegistry.getFunctionDeclarationConfigs()
            else
                toolRegistry.getFunctionDeclarationConfigs().filter {
                    it.name == "get_current_time" || it.name == "get_device_status"
                },
        )
    }

    // ════════════════════════════════════════════════════════════
    //  LEGACY HANDLERS
    // ════════════════════════════════════════════════════════════

    private fun handleSubmitApiKey(key: String) {
        if (key.length < 20) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Ключ слишком короткий")))
            return
        }
        viewModelScope.launch { settingsStore.updateData { it.copy(apiKey = key) } }
    }

    /**
     * Старый handleConnect — теперь делегирует в FULL-профиль.
     * Оставлен для обратной совместимости (network auto-reconnect,
     * arbiter callback).
     */
    private fun handleConnect() {
        connectWithProfile(DiagnosticProfile.FULL)
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel(); micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            pendingToolCalls.clear()
            pendingSelfCloseEvents.incrementAndGet()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false, isAiSpeaking = false
                )
            }
        }
        try { appContext.startService(GeminiLiveForegroundService.stopIntent(appContext)) } catch (_: Exception) { }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == ConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
        val hasMic = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasMic) {
            logger.w("startMic called but RECORD_AUDIO permission is missing!")
            return
        }

        try {
            appContext.startForegroundService(
                GeminiLiveForegroundService.startIntent(
                    appContext, cachedSettings.forceSpeakerOutput
                )
            )
        } catch (e: Exception) {
            logger.w("ForegroundService start failed in startMic: ${e.message}")
        }

        _state.update { it.copy(isMicActive = true, connectionStatus = ConnectionStatus.Recording) }
        micJob = viewModelScope.launch {
            launch { audioEngine.micOutput.collect { chunk -> liveClient.sendAudio(chunk) } }
            audioEngine.startCapture()
        }
    }

    private fun stopMic() {
        micJob?.cancel(); micJob = null
        viewModelScope.launch {
            audioEngine.stopCapture()
            if (cachedSettings.sendAudioStreamEnd) {
                liveClient.sendAudioStreamEnd()
            } else if (!cachedSettings.enableServerVad) {
                liveClient.sendTurnComplete()
            }
            _state.update {
                it.copy(
                    isMicActive = false,
                    connectionStatus = if (liveClient.isReady) ConnectionStatus.Ready
                    else ConnectionStatus.Disconnected
                )
            }
        }
    }

    private fun handleSendText(text: String) {
        if (text.isBlank()) return
        if (contextSeeded && _state.value.transcript.isNotEmpty()) {
            liveClient.sendRealtimeText(text)
        } else {
            liveClient.sendText(text)
        }
        viewModelScope.launch { conversationRepository.add(ConversationMessage.user(text)) }
    }

    private fun handleClearConversation() {
        viewModelScope.launch { conversationRepository.clear() }
    }

    private fun handleSaveLog() {
        _effects.tryEmit(VoiceEffect.SaveLogToFile(logger.getFullLog()))
    }

    // ════════════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ════════════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> {
                        reconnectAttempt = 0
                        _state.update { it.copy(connectionStatus = ConnectionStatus.Ready) }

                        // [D3] Логируем успех в диагностический лог
                        val profile = _state.value.lastTestedProfile
                        addDiagnosticResult(profile, "✅ SetupComplete — зелёный статус")
                        logger.d("🟢 ✅ TEST PASSED: ${profile.label}")

                        if (!contextSeeded && liveClient.sessionHandle == null) {
                            val history = conversationRepository.getAll()
                            if (history.isNotEmpty()) {
                                logger.d("Seeding initial context (${history.size} msgs)")
                                liveClient.restoreContext(history)
                            }
                        } else if (liveClient.sessionHandle != null) {
                            logger.d("Resumed via sessionHandle — skip manual context seed")
                        }
                        contextSeeded = true
                    }

                    is GeminiEvent.AudioChunk -> {
                        _state.update { it.copy(isAiSpeaking = true) }
                        audioEngine.enqueuePlayback(event.pcmData)
                        avatarAnimator.setSpeaking(true)
                    }

                    is GeminiEvent.Interrupted -> {
                        audioEngine.flushPlayback()
                        avatarAnimator.bargeInClear()
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.TurnComplete -> {
                        audioEngine.onTurnComplete()
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        avatarAnimator.setSpeaking(false)
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        val now = System.currentTimeMillis()
                        if (event.text != lastInputTranscript ||
                            now - lastInputTranscriptTime > 5_000
                        ) {
                            lastInputTranscript = event.text
                            lastInputTranscriptTime = now
                            conversationRepository.add(ConversationMessage.user(event.text))
                        }
                    }

                    is GeminiEvent.OutputTranscript -> {
                        val now = System.currentTimeMillis()
                        if (event.text != lastOutputTranscript ||
                            now - lastOutputTranscriptTime > 5_000
                        ) {
                            lastOutputTranscript = event.text
                            lastOutputTranscriptTime = now
                            conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        }
                        avatarAnimator.feedModelText(event.text)
                    }

                    is GeminiEvent.ModelText -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
                        avatarAnimator.feedModelText(event.text)
                    }

                    is GeminiEvent.ToolCall -> handleToolCalls(event)

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) pendingToolCalls.remove(id)
                    }

                    is GeminiEvent.SessionHandleUpdate -> { /* no-op */ }

                    is GeminiEvent.GoAway -> {
                        reconnectAttempt = 0
                        // [D4] Во время диагностики не переподключаемся
                        if (_state.value.lastTestedProfile == DiagnosticProfile.FULL) {
                            scheduleReconnect(proactive = true)
                        }
                    }

                    is GeminiEvent.UsageMetadata -> {
                        if (cachedSettings.showUsageMetadata) {
                            _state.update {
                                it.copy(
                                    promptTokens = event.promptTokens,
                                    responseTokens = event.responseTokens,
                                    totalTokens = event.totalTokens
                                )
                            }
                        }
                    }

                    is GeminiEvent.GroundingMetadata -> { }

                    is GeminiEvent.Disconnected -> {
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false
                            )
                        }
                        pendingToolCalls.clear()
                        audioEngine.stopCapture()

                        val selfClose = consumeSelfCloseIfAny()
                        val profile = _state.value.lastTestedProfile

                        if (selfClose) {
                            logger.d("WS closed by self — no reconnect")
                        } else {
                            // [D3] Логируем закрытие
                            val msg = "WS closed ${event.code}: ${event.reason.take(80)}"
                            addDiagnosticResult(profile, "❌ $msg")
                            logger.d("🔴 TEST FAILED: ${profile.label} — $msg")

                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain(msg)
                                )
                            )
                            // [D4] Во время диагностики не переподключаемся
                            if (profile == DiagnosticProfile.FULL) {
                                scheduleReconnect()
                            }
                        }
                    }

                    is GeminiEvent.ConnectionError -> {
                        val isRateLimit = event.message.contains("429") ||
                                event.message.contains("rate", ignoreCase = true)
                        if (isRateLimit && cachedSettings.autoRotateKeys &&
                            cachedSettings.apiKeyBackup.isNotEmpty()
                        ) {
                            activeApiKey = if (activeApiKey == cachedSettings.apiKey)
                                cachedSettings.apiKeyBackup else cachedSettings.apiKey
                            logger.d("Rate limit — rotating to backup key")
                        }
                        _state.update {
                            it.copy(
                                connectionStatus = ConnectionStatus.Disconnected,
                                isMicActive = false, error = UiText.Plain(event.message)
                            )
                        }
                        pendingToolCalls.clear()
                        audioEngine.stopCapture()

                        val selfClose = consumeSelfCloseIfAny()
                        val profile = _state.value.lastTestedProfile

                        if (selfClose) {
                            logger.d("WS error during self-disconnect — ignored")
                        } else {
                            addDiagnosticResult(profile, "❌ ERROR: ${event.message.take(80)}")
                            logger.d("🔴 TEST FAILED: ${profile.label} — ${event.message}")

                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain("Ошибка: ${event.message.take(160)}")
                                )
                            )
                            if (profile == DiagnosticProfile.FULL) {
                                scheduleReconnect()
                            }
                        }
                    }
                }

                if (cachedSettings.showDebugLog) {
                    _state.update { it.copy(logText = logger.getDisplayLog()) }
                }
            }
        }
    }

    private fun consumeSelfCloseIfAny(): Boolean {
        while (true) {
            val n = pendingSelfCloseEvents.get()
            if (n <= 0) return false
            if (pendingSelfCloseEvents.compareAndSet(n, n - 1)) return true
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TOOL CALLING
    // ════════════════════════════════════════════════════════════

    private suspend fun handleToolCalls(event: GeminiEvent.ToolCall) {
        for (call in event.calls) pendingToolCalls.add(call.id)

        val responses = mutableListOf<ToolResponse>()

        for (call in event.calls) {
            if (call.id !in pendingToolCalls) continue

            val result = toolRegistry.dispatch(call)

            pendingToolCalls.remove(call.id)
            responses.add(ToolResponse(call.name, call.id, result))
        }
        if (responses.isNotEmpty()) liveClient.sendToolResponse(responses)
    }

    // ════════════════════════════════════════════════════════════
    //  RECONNECT
    // ════════════════════════════════════════════════════════════

    private fun scheduleReconnect(proactive: Boolean = false) {
        val maxAttempts = cachedSettings.maxReconnectAttempts
        if (reconnectAttempt >= maxAttempts && !proactive) {
            _effects.tryEmit(
                VoiceEffect.ShowToast(
                    UiText.Plain(
                        "Соединение потеряно после $maxAttempts попыток. Проверьте ключ и модель в настройках."
                    )
                )
            )
            reconnectAttempt = 0
            return
        }
        val baseDelay = cachedSettings.reconnectBaseDelayMs
        val maxDelay = cachedSettings.reconnectMaxDelayMs
        val delayMs = if (proactive) 1000L
        else (baseDelay * (1L shl reconnectAttempt)).coerceAtMost(maxDelay)

        if (!proactive) reconnectAttempt++
        _state.update { it.copy(connectionStatus = ConnectionStatus.Reconnecting) }

        reconnectJob?.cancel()
        reconnectJob = viewModelScope.launch {
            delay(delayMs)
            contextSeeded = false
            handleConnect()
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO + HAPTIC
    // ════════════════════════════════════════════════════════════

    private fun initAudioPlayback() {
        viewModelScope.launch { audioEngine.initPlayback() }
        viewModelScope.launch {
            audioEngine.playbackSync.collect { pcmChunk -> avatarAnimator.feedAudio(pcmChunk) }
        }
        viewModelScope.launch {
            hapticEngine.attachToAudioStream(audioEngine.playbackSync)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel(); micJob?.cancel()
        avatarAnimator.stop()
        try { appContext.startService(GeminiLiveForegroundService.stopIntent(appContext)) } catch (_: Exception) { }
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            logger.d("VoiceViewModel cleanup complete")
        }
    }
}

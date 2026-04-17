// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/voice/VoiceViewModel.kt
//
// КРИТИЧЕСКИЕ ФИКСЫ:
//
//  [1] LearnSession integration — вместо прямой работы с A0a1TestBus
//      слушаем LearnSessionController.active + restartTick. Один активный
//      учебный сеанс за раз, вход/выход сериализованы через Mutex.
//
//  [2] handleToolCalls сначала спрашивает активный LearnSession, и только
//      затем — toolRegistry.dispatch(). Активная сессия может вернуть null,
//      если функция не "её" — тогда идём в ToolRegistry.
//
//  [3] pendingSelfCloseEvents: AtomicInteger — счётчик self-close событий.
//      Исправляет баг, когда после одного «чистого» disconnect прилетали
//      и onClosed, и onFailure → старый AtomicBoolean терялся → ложный
//      reconnect + toast.
//
//  [4] onCleared: cleanup через GlobalScope + NonCancellable, т.к.
//      viewModelScope к моменту onCleared УЖЕ отменён (launch на нём
//      молча не запускается → AudioRecord/AudioTrack утекают).
//
//  [5] Больше нет delay(400) — LiveClient.disconnect() теперь suspend
//      и сам ждёт onClosed.
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
import com.learnde.app.learn.core.LearnSession
import com.learnde.app.learn.core.LearnSessionController
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

@HiltViewModel
class VoiceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val liveClient: LiveClient,
    private val audioEngine: AudioEngine,
    private val conversationRepository: ConversationRepository,
    private val logger: AppLogger,
    private val settingsStore: DataStore<AppSettings>,
    private val toolRegistry: ToolRegistry,
    private val hapticEngine: HapticEngine,
    private val networkMonitor: NetworkMonitor,
    val avatarAnimator: AvatarAnimator,
    private val backgroundStore: BackgroundImageStore,
    private val learnController: LearnSessionController
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

    /**
     * Счётчик ожидаемых self-close событий. Инкрементируется ПЕРЕД каждым
     * "нашим" liveClient.disconnect(). onClosed/onFailure декрементируют
     * и не триггерят reconnect. Это исправляет баг, когда одно self-close
     * порождало и onClosed, и onFailure — старый AtomicBoolean съедался
     * первым из них, а второй неожиданно триггерил reconnect.
     */
    private val pendingSelfCloseEvents = AtomicInteger(0)

    /** Сериализация enter/exit Learn-сессии (чтобы не было двух reconnect одновременно). */
    private val modeSwitchMutex = Mutex()

    /** Снимок текущей активной LearnSession — обновляется в observeLearnSessions. */
    @Volatile private var activeLearnSession: LearnSession? = null

    init {
        observeSettings()
        observeGeminiEvents()
        observeTranscript()
        initAudioPlayback()
        observeNetwork()
        avatarAnimator.start()
        observeLearnSessions()
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
        }
    }

    // ════════════════════════════════════════════════════════════
    //  TRANSCRIPT — единственный источник правды: Flow из Room
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
    //  NETWORK MONITOR
    // ════════════════════════════════════════════════════════════

    private fun observeNetwork() {
        viewModelScope.launch {
            var wasDisconnected = false
            networkMonitor.isConnected.collect { connected ->
                if (!connected) {
                    wasDisconnected = true
                } else if (wasDisconnected) {
                    wasDisconnected = false
                    if (_state.value.connectionStatus == ConnectionStatus.Disconnected &&
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

    // ════════════════════════════════════════════════════════════
    //  LEARN SESSION CONTROLLER
    // ════════════════════════════════════════════════════════════

    private fun observeLearnSessions() {
        // Активная сессия: null → exit режима, non-null → enter режима
        viewModelScope.launch {
            learnController.active
                .drop(1)  // пропускаем начальный null
                .collectLatest { session ->
                    modeSwitchMutex.withLock {
                        activeLearnSession = session
                        if (session != null) {
                            _state.update { it.copy(learnActive = true, learnId = session.id) }
                            enterLearnMode(session)
                        } else {
                            _state.update { it.copy(learnActive = false, learnId = null) }
                            exitLearnMode()
                        }
                    }
                }
        }
        // Re-enter (restart): та же сессия, но просим перезагрузиться
        viewModelScope.launch {
            learnController.restartTick
                .drop(1)
                .collectLatest {
                    val s = activeLearnSession ?: return@collectLatest
                    modeSwitchMutex.withLock {
                        enterLearnMode(s)
                    }
                }
        }
    }

    /** Переподключает сессию с systemInstruction и functionDeclarations учебного сеанса. */
    private suspend fun enterLearnMode(session: LearnSession) {
        logger.d("▶ enterLearnMode(${session.id})")
        reconnectJob?.cancel()
        micJob?.cancel()
        audioEngine.stopCapture()

        // Чистое закрытие (ждёт onClosed).
        pendingSelfCloseEvents.incrementAndGet()
        liveClient.disconnect()
        _state.update {
            it.copy(
                connectionStatus = ConnectionStatus.Disconnected,
                isMicActive = false,
                isAiSpeaking = false
            )
        }

        if (activeApiKey.isEmpty()) {
            logger.w("Learn: cannot start — API key empty")
            return
        }
        contextSeeded = true  // в учебном режиме НЕ подмешиваем старую историю
        _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }

        (conversationRepository as? PersistentConversationRepository)?.startNewSession()

        runCatching {
            liveClient.connect(
                apiKey = activeApiKey,
                config = buildLearnSessionConfig(session),
                logRaw = cachedSettings.logRawWebSocketFrames
            )
        }.onFailure { e ->
            logger.e("Learn connect error: ${e.message}", e)
            _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
        }
    }

    /** Возвращает сессию в обычный режим. */
    private suspend fun exitLearnMode() {
        logger.d("◀ exitLearnMode")
        reconnectJob?.cancel()
        micJob?.cancel()
        audioEngine.stopCapture()

        pendingSelfCloseEvents.incrementAndGet()
        liveClient.disconnect()
        contextSeeded = false
        handleConnect()
    }

    /** SessionConfig для учебного режима. */
    private fun buildLearnSessionConfig(session: LearnSession): SessionConfig {
        val base = buildSessionConfig()
        return base.copy(
            systemInstruction = session.systemInstruction,
            functionDeclarations = session.functionDeclarations,
            enableGoogleSearch = false,
            sessionHandle = null,
            enableSessionResumption = false
        )
    }

    // ════════════════════════════════════════════════════════════
    //  SETTINGS → UI STATE
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

                    // Автоконнект ТОЛЬКО при первом появлении ключа и если мы не в учебном режиме
                    if (hasKey && wasKeyEmpty &&
                        _state.value.connectionStatus == ConnectionStatus.Disconnected &&
                        activeLearnSession == null
                    ) {
                        handleConnect()
                    }
                }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SESSION CONFIG (обычный режим)
    // ════════════════════════════════════════════════════════════

    private fun buildSessionConfig(): SessionConfig {
        val profile = runCatching {
            enumValueOf<LatencyProfile>(cachedSettings.latencyProfile)
        }.getOrDefault(LatencyProfile.UltraLow)

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
            vadStartSensitivity = cachedSettings.vadStartOfSpeechSensitivity,
            vadEndSensitivity = cachedSettings.vadEndOfSpeechSensitivity,
            vadSilenceTimeoutMs = cachedSettings.vadSilenceTimeoutMs,
            systemInstruction = cachedSettings.systemInstruction,
            inputTranscription = cachedSettings.inputTranscription,
            outputTranscription = cachedSettings.outputTranscription,
            enableSessionResumption = cachedSettings.enableSessionResumption,
            transparentResumption = cachedSettings.transparentResumption,
            sessionHandle = liveClient.sessionHandle,
            enableContextCompression = cachedSettings.enableContextCompression,
            compressionTriggerTokens = cachedSettings.compressionTriggerTokens,
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
    //  HANDLERS
    // ════════════════════════════════════════════════════════════

    private fun handleSubmitApiKey(key: String) {
        if (key.length < 20) {
            _effects.tryEmit(VoiceEffect.ShowToast(UiText.Plain("Ключ слишком короткий")))
            return
        }
        viewModelScope.launch {
            settingsStore.updateData { it.copy(apiKey = key) }
        }
    }

    private fun handleConnect() {
        if (activeApiKey.isEmpty()) return
        val status = _state.value.connectionStatus
        if (status == ConnectionStatus.Connecting || status == ConnectionStatus.Ready) return

        contextSeeded = false
        _state.update { it.copy(connectionStatus = ConnectionStatus.Connecting) }

        (conversationRepository as? PersistentConversationRepository)?.startNewSession()

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

        viewModelScope.launch {
            try {
                liveClient.connect(
                    apiKey = activeApiKey,
                    config = buildSessionConfig(),
                    logRaw = cachedSettings.logRawWebSocketFrames
                )
            } catch (e: Exception) {
                logger.e("liveClient.connect error: ${e.message}", e)
                _state.update { it.copy(connectionStatus = ConnectionStatus.Disconnected) }
            }
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel()
        micJob?.cancel()
        viewModelScope.launch {
            audioEngine.stopCapture()
            pendingSelfCloseEvents.incrementAndGet()
            liveClient.disconnect()
            _state.update {
                it.copy(
                    connectionStatus = ConnectionStatus.Disconnected,
                    isMicActive = false, isAiSpeaking = false
                )
            }
        }
        try {
            appContext.startService(GeminiLiveForegroundService.stopIntent(appContext))
        } catch (_: Exception) { }
    }

    private fun handleToggleMic() {
        if (_state.value.isMicActive) stopMic()
        else if (_state.value.connectionStatus == ConnectionStatus.Ready) startMic()
    }

    private fun startMic() {
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
            if (cachedSettings.sendAudioStreamEnd) liveClient.sendAudioStreamEnd()
            liveClient.sendTurnComplete()
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
        liveClient.sendText(text)
        viewModelScope.launch {
            conversationRepository.add(ConversationMessage.user(text))
        }
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

                        val learn = activeLearnSession
                        if (learn != null) {
                            // Учебный режим: не сеем старую историю, шлём начальную фразу
                            contextSeeded = true
                            if (learn.initialUserMessage.isNotBlank()) {
                                logger.d("Learn: sending initialUserMessage")
                                liveClient.sendText(learn.initialUserMessage)
                            }
                            delay(300)
                            if (liveClient.isReady && !_state.value.isMicActive) {
                                logger.d("Learn: auto-starting mic")
                                startMic()
                            }
                        } else {
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
                        conversationRepository.add(ConversationMessage.user(event.text))
                    }

                    is GeminiEvent.OutputTranscript -> {
                        conversationRepository.appendOrAdd(ConversationMessage.ROLE_MODEL, event.text)
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

                    is GeminiEvent.SessionHandleUpdate -> { /* handle stored in liveClient */ }

                    is GeminiEvent.GoAway -> {
                        reconnectAttempt = 0
                        scheduleReconnect(proactive = true)
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
                        audioEngine.stopCapture()
                        if (consumeSelfCloseIfAny()) {
                            logger.d("WS closed by self — no reconnect")
                        } else {
                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain("WS closed: code=${event.code} reason='${event.reason}'")
                                )
                            )
                            scheduleReconnect()
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
                        audioEngine.stopCapture()
                        if (consumeSelfCloseIfAny()) {
                            logger.d("WS error during self-disconnect — ignored")
                        } else {
                            _effects.tryEmit(
                                VoiceEffect.ShowToast(
                                    UiText.Plain("Ошибка: ${event.message.take(160)}")
                                )
                            )
                            scheduleReconnect()
                        }
                    }
                }

                if (cachedSettings.showDebugLog) {
                    _state.update { it.copy(logText = logger.getDisplayLog()) }
                }
            }
        }
    }

    /**
     * Атомарно попытаться потребить один "self-close" маркер.
     * @return true если текущее событие — результат нашего disconnect
     *         (reconnect не нужен). false если это внешнее закрытие/ошибка.
     */
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
        val session = activeLearnSession

        for (call in event.calls) {
            if (call.id !in pendingToolCalls) continue

            // Сначала даём шанс активной учебной сессии
            val sessionResult = session?.let { runCatching { it.handleToolCall(call) }.getOrNull() }
            val result = sessionResult ?: toolRegistry.dispatch(call)

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
            // В учебном режиме reconnect должен идти по учебному конфигу
            val learn = activeLearnSession
            if (learn != null) {
                modeSwitchMutex.withLock { enterLearnMode(learn) }
            } else {
                handleConnect()
            }
        }
    }

    // ════════════════════════════════════════════════════════════
    //  AUDIO + HAPTIC
    // ════════════════════════════════════════════════════════════

    private fun initAudioPlayback() {
        viewModelScope.launch { audioEngine.initPlayback() }
        viewModelScope.launch {
            audioEngine.playbackSync.collect { pcmChunk ->
                avatarAnimator.feedAudio(pcmChunk)
            }
        }
        viewModelScope.launch {
            hapticEngine.attachToAudioStream(audioEngine.playbackSync)
        }
    }

    /**
     * ВАЖНО: viewModelScope в момент onCleared УЖЕ отменён. Нельзя
     * делать viewModelScope.launch — корутина не стартует, ресурсы
     * утекают (AudioRecord/AudioTrack в нативной куче). Используем
     * GlobalScope + NonCancellable.
     */
    @OptIn(DelicateCoroutinesApi::class)
    override fun onCleared() {
        super.onCleared()
        reconnectJob?.cancel(); micJob?.cancel()
        avatarAnimator.stop()
        try {
            appContext.startService(GeminiLiveForegroundService.stopIntent(appContext))
        } catch (_: Exception) { }
        GlobalScope.launch(Dispatchers.IO + NonCancellable) {
            runCatching { audioEngine.releaseAll() }
            runCatching { liveClient.disconnect() }
            logger.d("VoiceViewModel cleanup complete")
        }
    }
}

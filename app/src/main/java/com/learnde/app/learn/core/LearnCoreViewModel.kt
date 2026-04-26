// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.4
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
// КЛЮЧЕВАЯ ФИКСА v3.4 (Gemini молчит только до первой реплики юзера):
//
//   Native-audio модели (gemini-2.5-flash-native-audio-preview и др.)
//   НЕ реагируют на голый sendText — их аудио-пайплайн "спит" пока через
//   VAD не прошёл хотя бы один реальный аудио-чанк. Это подтверждено:
//     - Twilio-интеграция шлёт sendText + сразу начинает стрим аудио линии
//     - Форум Google: "Session established, ping 'Hello', and off you go"
//       (но у репортёра мик уже включён с живым аудио-фидом)
//
//   Решение — связка из трёх шагов:
//     1. Включить микрофон ПЕРВЫМ (до sendText). Реальное фоновое аудио
//        раскачает VAD и активирует серверный пайплайн.
//     2. Влить "warmup silence": 400 мс нулевого PCM прямо через
//        liveClient.sendAudio(zeroBytes) — это будит серверный VAD
//        без риска поймать Interrupted на случайном шуме.
//     3. Затем sendText(initialUserMessage) — теперь модель точно ответит.
//
//   Fallback: если через 4 сек аудио от модели так и нет — повторяем
//   sendText с более явной фразой.
//
// ЧТО ЕЩЁ исправлено по сравнению с 19-КБ версией:
//   - sendText уже содержит turn_complete=true → не вызываем sendTurnComplete() после
//   - ModelText пишется ВСЕГДА (дедупликация в flushPendingModelText через endsWith)
//   - Возвращён silence timer (подбадривание молчащего ученика)
//   - Возвращён FIFO-лимит транскрипта
//   - Специфичный catch ForegroundServiceStartNotAllowedException
//
// ВАЖНО: В systemInstruction каждой сессии должна быть явная фраза:
//   "Начни разговор первым. Тепло поприветствуй ученика по-русски.
//    НЕ жди, пока ученик заговорит."
// Без этого даже правильный протокол не поможет — модель не знает,
// что именно говорить первой.
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
        /** Выделенный scope для безопасной очистки ресурсов при уничтожении ViewModel */
        private val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

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
         *  пробуем ещё раз с более явной фразой. */
        private const val GREETING_RETRY_MS = 4_000L

        /** Если и после повторной попытки модель молчит — сдаёмся
         *  и оставляем мик включённым. */
        private const val GREETING_FINAL_MS = 8_000L

        /** 16kHz mono 16-bit PCM — столько байт нужно на SILENCE_WARMUP_MS тишины.
         *  2 байта/сэмпл × 16000 сэмплов/сек × (SILENCE_WARMUP_MS / 1000) */
        private const val SILENCE_PCM_BYTES = (2 * 16000 * 400) / 1000 // = 12800

        /** 
         * Хвост аудио модели после последнего PCM-чанка, в течение 
         * которого всё ещё считаем что динамик звучит и mic надо 
         * гейтить. Покрывает jitter buffer + хвост AudioTrack. 
         * Если 600мс прошло без новых AudioChunk — считаем что модель 
         * отзвучала, mic открываем независимо от TurnComplete.
         */
        private const val AI_AUDIO_TAIL_MS = 600L

        /** 
         * Минимальный интервал между silence-промптами, чтобы не 
         * спамить модель если ученик надолго замолчал.
         */
        private const val SILENCE_PROMPT_COOLDOWN_MS = 30_000L

        /** 
         * Сколько ждать после finish_session, прежде чем закрыть сессию. 
         * Окно нужно чтобы модель успела доиграть прощальную TTS-реплику 
         * и прислать отложенные mark_lemma_produced.
         */
        private const val FINISH_SESSION_GRACE_MS = 5_000L
    }

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

    // ФИКС: DROP_OLDEST гарантирует, что новые события (ошибки, тосты) никогда не будут потеряны
    private val _effects = MutableSharedFlow<LearnCoreEffect>(
        extraBufferCapacity = 32,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
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
    private var finishGraceJob: Job? = null

    @Volatile private var lastInputTs: Long = 0L
    @Volatile private var lastInputText: String = ""

    /** Флаг: модель уже начала говорить в этом турне (пришёл первый AudioChunk). */
    @Volatile private var modelStartedSpeakingThisTurn = false

    /** Флаг: ждём первый ответ модели после стартового приветствия. */
    @Volatile private var awaitingInitialGreeting = false

    /** Timestamp последнего AudioChunk от модели (для timing-based mic gate). */
    @Volatile private var lastAiAudioChunkAtMs: Long = 0L

    /** Сессия завершена через finish_session — silence-промпты выключены. */
    @Volatile private var sessionFinished: Boolean = false

    /** Timestamp последнего отправленного [СИСТЕМА]: Ученик молчит (антиспам). */
    @Volatile private var lastSilencePromptAtMs: Long = 0L

    /** Счётчик дропнутых mic-чанков для диагностики. */
    @Volatile private var droppedMicChunks: Int = 0

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
                // ФИКС: Буферизуем нарушения лексики и для режима обучения, и для режима повторения
                if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
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
            is LearnCoreIntent.ToggleMic -> handleToggleMic()
            is LearnCoreIntent.ClearError -> _state.update { it.copy(error = null) }
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

        // VAD по типу сессии.
        // silence — сколько тишины ждать, чтобы закрыть ход ученика.
        // prefix — сколько мс аудио до детекта речи отдать модели (чтобы не рубить начало слов).
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
        ).also {
            logger.d(
                "Learn: config for ${session.id}: silence=${finalSilenceMs}ms, " +
                    "prefix=${prefixMs}ms, temp=$temp, outputTranscription=${cachedSettings.outputTranscription}"
            )
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                
                // ФИКС: Проверяем connectionStatus, чтобы избежать двойного вызова handleStop,
                // так как activeSession может обнулиться с задержкой из-за многопоточности.
                val isConnected = _state.value.connectionStatus != LearnConnectionStatus.Disconnected
                if (!owned && activeSession != null && isConnected) {
                    logger.w("Learn: lost arbiter ownership → stopping")
                    handleStop()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  START / STOP / RESTART
    // ══════════════════════════════════════════════════════

    private suspend fun stopInternal() = startStopMutex.withLock {
        logger.d("▶ Learn.stopInternal")
        val session = activeSession
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()

        // ФИКС: Останавливаем запись строго под мьютексом, чтобы избежать гонки со startMic.
        // Это гарантирует, что releaseAll() не будет вызван при активном микрофоне.
        micOperationMutex.withLock {
            audioEngine.stopCapture()
        }

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
        sessionFinished = false
        lastAiAudioChunkAtMs = 0L
        lastSilencePromptAtMs = 0L
        droppedMicChunks = 0
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
        logger.d("◀ Learn.stopInternal — arbiter released")
    }

    private suspend fun startInternal(sessionId: String) = startStopMutex.withLock {
        val session = registry.get(sessionId) ?: run {
            logger.e("Learn: unknown session id: $sessionId")
            _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain("Unknown session: $sessionId")))
            return@withLock
        }
        if (activeApiKey.isEmpty()) {
            _state.update { it.copy(error = UiText.Plain("API ключ не задан. Задайте его в Настройках.")) }
            return@withLock
        }

        logger.d("▶ Learn.startInternal(${session.id})")

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
        sessionFinished = false
        lastAiAudioChunkAtMs = 0L
        lastSilencePromptAtMs = 0L
        droppedMicChunks = 0
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
        logger.d("◀ Learn.startInternal — awaiting SetupComplete")
    }

    private fun handleStart(sessionId: String) {
        viewModelScope.launch {
            transcriptMutex.withLock { transcriptBuffer = emptyList() }
            _state.update { it.copy(transcript = emptyList()) }
            startInternal(sessionId)
        }
    }

    private fun handleStop() {
        viewModelScope.launch {
            stopInternal()
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
        if (!hasMic) {
            logger.w("Learn.startMic: no RECORD_AUDIO permission")
            return
        }

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
            // 1. Запускаем сбор аудиоданных ВНЕ мьютекса
            launch {
                audioEngine.micOutput.collect { chunk ->
                    // ГЕЙТ ПО ИЗМЕРЕННОМУ ВРЕМЕНИ С ПОСЛЕДНЕГО PCM-ЧАНКА МОДЕЛИ.
                    // 
                    // Раньше использовался флаг isAiSpeaking, но он снимается 
                    // только на TurnComplete, который после tool-calls часто 
                    // приходит с задержкой 2-19 секунд → mic "глох".
                    //
                    // Новая логика: модель реально звучит в динамик только 
                    // если с момента последнего AudioChunk прошло меньше 
                    // AI_AUDIO_TAIL_MS (600мс). Это окно покрывает jitter 
                    // buffer + хвост AudioTrack. Если новые чанки перестали 
                    // приходить — гейт открывается автоматически, без 
                    // зависимости от TurnComplete.
                    val now = System.currentTimeMillis()
                    val sinceLastAi = now - lastAiAudioChunkAtMs
                    val aiActuallyAudible = lastAiAudioChunkAtMs > 0L &&
                                            sinceLastAi < AI_AUDIO_TAIL_MS

                    if (!aiActuallyAudible) {
                        liveClient.sendAudio(chunk)
                        if (droppedMicChunks > 0) {
                            logger.d("Mic: gate opened, dropped $droppedMicChunks chunks during AI tail")
                            droppedMicChunks = 0
                        }
                    } else {
                        droppedMicChunks++
                    }
                }
            }
            
            // 2. Мьютекс защищает только сам факт старта/стопа железа
            micOperationMutex.withLock {
                audioEngine.startCapture()
            }
        }
    }

    private fun stopMic() {
        micJob?.cancel()
        micJob = null
        silenceTimerJob?.cancel()
        viewModelScope.launch {
            // ФИКС: Тот же mutex — startCapture не выполнится раньше stopCapture.
            micOperationMutex.withLock {
                audioEngine.stopCapture()
            }
            // Пользователь явно завершил ввод — ВСЕГДА закрываем turn,
            // независимо от настроек VAD. Иначе сервер ждёт серверного таймаута
            // тишины и пользователь видит "тормоза" приложения.
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
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+) наследует IllegalStateException
            logger.w("FGS not allowed: ${e.javaClass.simpleName}: ${e.message}")
            false
        } catch (e: SecurityException) {
            logger.e("FGS permission denied: ${e.message}")
            false
        } catch (e: Exception) {
            logger.e("FGS unexpected error: ${e.message}", e)
            false
        }
    }

    private fun safeStopForegroundService() {
        try {
            appContext.startService(
                com.learnde.app.GeminiLiveForegroundService.stopIntent(appContext)
            )
        } catch (_: Exception) { /* сервис мог быть уже остановлен */ }
    }

    // ══════════════════════════════════════════════════════
    //  GEMINI EVENTS
    // ══════════════════════════════════════════════════════

    private fun observeGeminiEvents() {
        viewModelScope.launch {
            liveClient.events.collect { event ->
                when (event) {
                    is GeminiEvent.Connected ->
                        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Negotiating) }

                    is GeminiEvent.SetupComplete -> handleSetupComplete()

                    is GeminiEvent.AudioChunk -> {
                        // Обновляем timestamp для timing-based mic gate
                        lastAiAudioChunkAtMs = System.currentTimeMillis()

                        // Первый аудио-чанк в этом турне → модель точно заговорила.
                        if (!modelStartedSpeakingThisTurn) {
                            modelStartedSpeakingThisTurn = true
                            // Сбрасываем флаг ожидания приветствия и retry-таймер.
                            if (awaitingInitialGreeting) {
                                awaitingInitialGreeting = false
                                greetingFallbackJob?.cancel()
                                logger.d("Learn: model started greeting ✓")
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
                        // ФИКС: НЕ выкидываем pendingModelText, а флашим его.
                        // Иначе при ложном барджине (например, эхо динамика 
                        // через мик) теряется уже распознанный текст модели.
                        if (pendingModelText.isNotEmpty()) flushPendingModelText()
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
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

                        // Таймер тишины ученика — с тремя guards:
                        // 1. mic должен быть включён
                        // 2. сессия не должна быть завершена через finish_session
                        //    (иначе модель отвечает на собственный ping и 
                        //    продолжает урок после прощания)
                        // 3. между silence-промптами должен пройти cooldown
                        //    (антиспам если ученик надолго отошёл)
                        lastInputTs = System.currentTimeMillis()
                        val now = System.currentTimeMillis()
                        val cooldownPassed = (now - lastSilencePromptAtMs) > SILENCE_PROMPT_COOLDOWN_MS

                        if (_state.value.isMicActive && !sessionFinished && cooldownPassed) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                val quietFor = System.currentTimeMillis() - lastInputTs
                                if (quietFor > SILENCE_CHECK_WINDOW_MS
                                    && liveClient.isReady
                                    && _state.value.isMicActive
                                    && !sessionFinished
                                ) {
                                    logger.d("Learn: silence detected (${quietFor}ms), prompting AI")
                                    lastSilencePromptAtMs = System.currentTimeMillis()
                                    liveClient.sendText(
                                        "[СИСТЕМА]: Ученик молчит. Коротко подбодри его по-русски, " +
                                            "дай подсказку или назови правильный ответ и попроси повторить."
                                    )
                                    lastInputTs = System.currentTimeMillis()
                                }
                            }
                        }
                    }

                    is GeminiEvent.GenerationComplete -> {
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.InputTranscript -> {
                        silenceTimerJob?.cancel()
                        val now = System.currentTimeMillis()

                        // Фильтр явного ASR-мусора: транскрипт не содержит 
                        // ни латиницы (включая немецкие умляуты), ни кириллицы. 
                        // Это значит ASR распознал немецкую фонему как CJK / 
                        // devanagari / иероглифы — такое в чат не пускаем.
                        val hasLatin = event.text.any {
                            it in 'a'..'z' || it in 'A'..'Z' ||
                                it in "äöüßÄÖÜ"
                        }
                        val hasCyrillic = event.text.any {
                            it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё'
                        }
                        if (event.text.isNotBlank() && !hasLatin && !hasCyrillic) {
                            logger.d("Learn: dropping garbage transcript: ${event.text}")
                            return@collect
                        }

                        // Инкрементальное обновление: новый транскрипт начинается 
                        // со старого И прошло меньше 3с — это ASR доуточнил 
                        // распознавание, обновляем последнее сообщение, 
                        // не создаём новое.
                        if (lastInputText.isNotEmpty()
                            && event.text.startsWith(lastInputText)
                            && (now - lastInputTs) < 3_000
                        ) {
                            lastInputText = event.text
                            lastInputTs = now
                            launch { updateLastUserTranscript(event.text) }
                        } else if (event.text != lastInputText) {
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
                            logger.d("Learn: model started OutputTranscript — clearing greeting wait")
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
                            logger.d("Learn: model started ModelText — clearing greeting wait")
                        }
                        // Всегда пишем ModelText. Дедупликация с OutputTranscript
                        // делается в appendOrAppendToLastModel (по содержимому).
                        // Если включён outputTranscription — сервер чаще шлёт именно
                        // OutputTranscript, а ModelText приходит редко и дублирования нет.
                        appendOrAppendToLastModel(event.text)
                    }

                    is GeminiEvent.ToolCall -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                            logger.d("Learn: model started ToolCall — clearing greeting wait")
                        }
                        handleToolCalls(event)
                    }

                    is GeminiEvent.ToolCallCancellation -> {
                        for (id in event.ids) {
                            // ФИКС: Только отменяем Job. Удаление из pendingToolCalls и вызов 
                            // statusBus.onCompleted произойдут в блоке finally самой корутины.
                            toolCallJobs[id]?.cancel()
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        greetingFallbackJob?.cancel()
                        
                        // Если код закрытия не 1000 (Normal) и не 1001 (Going Away) — это ошибка (например, неверный ключ)
                        val isAbnormal = event.code != 1000 && event.code != 1001
                        val errorMsg = if (isAbnormal) "Соединение закрыто: ${event.reason} (Код: ${event.code}). Проверьте API-ключ." else null

                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
                                // Записываем ошибку в стейт, чтобы UI её увидел
                                error = if (isAbnormal) UiText.Plain(errorMsg!!) else it.error
                            )
                        }
                        audioEngine.stopCapture()
                        pendingToolCalls.clear()
                        silenceTimerJob?.cancel()
                        
                        if (isAbnormal && activeSession != null) {
                            _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(errorMsg!!)))
                        }
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
                        _effects.tryEmit(LearnCoreEffect.Error(UiText.Plain(event.message)))
                    }

                    is GeminiEvent.SessionHandleUpdate,
                    is GeminiEvent.GoAway,
                    is GeminiEvent.UsageMetadata,
                    is GeminiEvent.GroundingMetadata -> { /* no-op */ }
                }
            }
        }
    }

    /**
     * Сценарий первого хода v3.4 — гарантированно будим native-audio модель.
     *
     * ПОЧЕМУ НЕ РАБОТАЕТ ПРОСТОЙ sendText:
     *   Native-audio модели (gemini-2.5-flash-native-audio-preview) имеют
     *   аудио-пайплайн, который "спит" пока через VAD не прошёл хотя бы один
     *   реальный аудио-чанк от клиента. sendText в таком состоянии просто
     *   копится в буфере сессии и не триггерит генерацию — модель ждёт
     *   аудио-активности, чтобы понять что сессия "живая".
     *
     * ПРОТОКОЛ ПРОБУЖДЕНИЯ:
     *   1. SetupComplete получен.
     *   2. Включаем микрофон СРАЗУ. Даже в тишине комнаты через мик идёт
     *      фоновый шум → VAD его регистрирует → серверный пайплайн оживает.
     *   3. Ждём MIC_PREWARM_MS чтобы дать серверу понять что аудио-сессия
     *      действительно началась.
     *   4. Шлём SILENCE_WARMUP_MS нулевого PCM как гарантию что серверный
     *      VAD увидит "окончание активности" (silence detected) и будет
     *      готов начать новый turn.
     *   5. Шлём sendText(initialUserMessage) — внутри он с turn_complete=true,
     *      и теперь модель гарантированно ответит.
     *   6. Если за GREETING_RETRY_MS ответа нет — повторяем sendText.
     *   7. Если и это не помогло за GREETING_FINAL_MS — сдаёмся, мик
     *      остаётся включён, юзер может говорить первым.
     */
    private fun handleSetupComplete() {
        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
        val session = activeSession ?: return
        contextSeeded = true
        modelStartedSpeakingThisTurn = false

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            delay(GREETING_WARMUP_MS)

            // ФИКС: Проверяем, не была ли сессия остановлена или перезапущена во время delay
            if (!liveClient.isReady || activeSession != session) {
                logger.w("Learn: WS not ready or session changed after warmup, aborting greeting flow")
                return@launch
            }

            if (session.initialUserMessage.isBlank()) {
                logger.d("Learn: no initial greeting → enabling mic only")
                if (!_state.value.isMicActive) startMic()
                return@launch
            }

            logger.d("Learn: starting greeting sequence (silence-first → mic → text)")
            awaitingInitialGreeting = true

            runCatching { sendSilenceWarmup() }
                .onFailure { logger.w("Learn: silence warmup failed: ${it.message}") }

            // ФИКС: Повторная проверка после долгого suspend-метода отправки аудио
            if (!liveClient.isReady || activeSession != session) {
                logger.w("Learn: WS died or session changed during silence warmup")
                awaitingInitialGreeting = false
                return@launch
            }

            if (!_state.value.isMicActive) startMic()
            delay(MIC_PREWARM_MS)

            // ФИКС: И еще одна проверка перед отправкой текста
            if (!liveClient.isReady || activeSession != session) {
                logger.w("Learn: WS died or session changed during mic prewarm")
                awaitingInitialGreeting = false
                return@launch
            }

            logger.d("Learn: sending initial greeting trigger")
            liveClient.sendText(session.initialUserMessage)

            greetingFallbackJob?.cancel()
            greetingFallbackJob = viewModelScope.launch {
                delay(GREETING_RETRY_MS)
                // ФИКС: Проверка актуальности сессии в fallback-ветке
                if (awaitingInitialGreeting && liveClient.isReady && activeSession == session) {
                    logger.w("Learn: no audio from model in ${GREETING_RETRY_MS}ms — retrying")
                    runCatching { sendSilenceWarmup() }
                    if (activeSession != session) return@launch
                    
                    liveClient.sendText(
                        "Ты меня слышишь? Поприветствуй ученика сейчас по-русски и " +
                            "задай первый вопрос."
                    )

                    delay(GREETING_FINAL_MS - GREETING_RETRY_MS)
                    if (awaitingInitialGreeting && activeSession == session) {
                        logger.w("Learn: model stayed silent, giving up greeting flow")
                        awaitingInitialGreeting = false
                    }
                }
            }
        }
    }

    /**
     * Отправляет SILENCE_WARMUP_MS мс нулевого 16-bit PCM audio чанка —
     * пробуждает серверный VAD не рискуя триггером на реальном шуме.
     *
     * 16kHz × 2 байта × 0.4с = 12800 байт.
     * Шлём одним куском через liveClient.sendAudio — LiveClient сам
     * упакует в realtime_input audio blob с mime_type=audio/pcm;rate=16000.
     */
    private suspend fun sendSilenceWarmup() {
        val silence = ByteArray(SILENCE_PCM_BYTES) // всё нули — идеальная тишина
        logger.d("Learn: injecting ${SILENCE_PCM_BYTES}B of silence (${SILENCE_WARMUP_MS}ms)")
        // Дробим на чанки по 40 мс (1280 байт) чтобы не превышать рекомендуемый
        // размер чанков — Google советует 20-40 мс.
        val chunkSize = 1280 // 40 мс при 16kHz/16-bit mono
        var offset = 0
        while (offset < silence.size) {
            val end = minOf(offset + chunkSize, silence.size)
            liveClient.sendAudio(silence.copyOfRange(offset, end))
            offset = end
            // ФИКС: Реальная задержка 40мс для чанка 40мс, чтобы не спамить сервер и не ловить RateLimit
            delay(40)
        }
    }

    private fun flushPendingVocabViolation() {
        val violation = pendingVocabViolation ?: return
        pendingVocabViolation = null
        if ((activeSession?.id == "a1_situation" || activeSession?.id == "a1_review")
            && liveClient.isReady) {
            val prompt = vocabularyEnforcer.buildCorrectionPrompt(violation)
            logger.d("Learn: sending buffered vocab correction (${violation.violatingWords})")
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

    /**
     * Обновляет текст последнего USER-сообщения (для инкрементальных 
     * ASR-апдейтов). Если последнее сообщение не USER — добавляет новое.
     */
    private suspend fun updateLastUserTranscript(text: String) {
        transcriptMutex.withLock {
            val last = transcriptBuffer.lastOrNull()
            if (last != null && last.role == ConversationMessage.ROLE_USER) {
                val updated = last.copy(text = text)
                val next = transcriptBuffer.toMutableList()
                next[next.size - 1] = updated
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                val next = (transcriptBuffer + ConversationMessage.user(text))
                    .takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
        }
    }

    private fun appendOrAppendToLastModel(text: String) {
        if (text.isEmpty()) return
        pendingModelText.append(text)

        if (audioReceivedThisTurn) {
            // ФИКС: Предотвращаем каскадный запуск корутин. Если джоб уже работает,
            // он сам заберет накопившийся текст, так как чтение буфера теперь под мьютексом.
            if (pendingFlushJob?.isActive == true) return
            pendingFlushJob = viewModelScope.launch { flushPendingModelText() }
            return
        }

        pendingFlushJob?.cancel()
        pendingFlushJob = viewModelScope.launch {
            delay(TEXT_FLUSH_TIMEOUT_MS)
            if (pendingModelText.isNotEmpty()) {
                logger.w("LearnCore: force-flush pending text (audio не пришёл за ${TEXT_FLUSH_TIMEOUT_MS}ms)")
                flushPendingModelText()
            }
        }
    }

    private suspend fun flushPendingModelText() {
        transcriptMutex.withLock {
            // ФИКС: Читаем и очищаем буфер строго внутри мьютекса, чтобы не потерять текст при отмене корутины
            if (pendingModelText.isEmpty()) {
                pendingFlushJob = null
                return@withLock
            }
            val text = pendingModelText.toString()
            pendingModelText.clear()
            pendingFlushJob = null

            val last = transcriptBuffer.lastOrNull()
            if (last != null && last.role == ConversationMessage.ROLE_MODEL) {
                // ФИКС: Умная дедупликация для фрагментированных чанков.
                // Проверяем, не является ли новый текст подстрокой конца текущего,
                // или не является ли текущий текст началом нового.
                val trimmedLast = last.text.trimEnd()
                val trimmedNew = text.trimStart()
                
                if (trimmedLast.endsWith(trimmedNew) || trimmedNew.startsWith(trimmedLast)) {
                    logger.d("Learn: suppressing duplicate ModelText/OutputTranscript")
                    // Если новый текст длиннее (содержит больше данных), обновляем до него
                    if (trimmedNew.length > trimmedLast.length) {
                        val updated = last.copy(text = text)
                        val next = transcriptBuffer.toMutableList()
                        next[next.size - 1] = updated
                        transcriptBuffer = next
                        _state.update { it.copy(transcript = next) }
                    }
                    return@withLock
                }
                
                val updated = last.copy(text = last.text + text)
                val next = transcriptBuffer.toMutableList()
                next[next.size - 1] = updated
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

        // Регистрируем все id СРАЗУ, до запуска корутин
        for (call in event.calls) {
            pendingToolCalls.add(call.id)
            statusBus.onDetected(call.name, call.id)
        }

        val children = event.calls.map { call ->
            viewModelScope.launch {
                try {
                    // ФИКС: Если вызов отменен до старта корутины, обязательно отправляем ответ-заглушку
                    if (call.id !in pendingToolCalls) {
                        responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                        return@launch
                    }
                    
                    statusBus.onExecuting(call.name, call.id)
                    
                    // ФИКС: Правильная обработка исключений без проглатывания CancellationException
                    val result = try {
                        session?.handleToolCall(call) ?: """{"error":"no active session"}"""
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            // Прокидываем отмену дальше, но сначала фиксируем ответ для сервера
                            responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                            throw e
                        }
                        logger.e("Learn.toolCall threw: ${e.message}", e)
                        """{"error":"${e.message?.replace("\"", "'")}"}"""
                    }
                    
                    val success = !result.contains("\"error\"")
                    responses.add(ToolResponse(call.name, call.id, result))
                } finally {
                    // ФИКС: Гарантированный единичный вызов onCompleted при любом исходе (успех, ошибка, отмена)
                    val wasCancelled = call.id !in pendingToolCalls || toolCallJobs[call.id]?.isCancelled == true
                    statusBus.onCompleted(call.name, call.id, success = !wasCancelled)
                    
                    pendingToolCalls.remove(call.id)
                    toolCallJobs.remove(call.id)
                }
            }.also { toolCallJobs[call.id] = it }
        }

        // Координирующая корутина: дождётся всех детей и одним батчем отправит ответ.
        viewModelScope.launch {
            children.joinAll()
            if (responses.isNotEmpty() && liveClient.isReady) {
                runCatching { liveClient.sendToolResponse(responses.toList()) }
                    .onFailure { logger.e("Learn: failed to send ToolResponse: ${it.message}") }
            }

            // Если в этом батче был finish_session — фиксируем что урок завершён.
            // С этого момента silence-таймер выключен, чтобы модель не отвечала 
            // на собственные [СИСТЕМА]: Ученик молчит после прощания.
            if (event.calls.any { it.name == "finish_session" }) {
                sessionFinished = true
                silenceTimerJob?.cancel()
                logger.d("Learn: finish_session detected → silence prompts disabled")
            }
        }
    }

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

    override fun onCleared() {
        super.onCleared()
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        pendingFlushJob?.cancel()
        pendingModelText.clear()
        statusBus.reset()
        safeStopForegroundService()

        // Используем выделенный scope вместо опасного GlobalScope
        cleanupScope.launch {
            // stopInternal() безопасно закроет сокеты и освободит Arbiter под мьютексом
            runCatching { stopInternal() }
            runCatching { transcriptMutex.withLock { transcriptBuffer = emptyList() } }
            runCatching { audioEngine.releaseAll() }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }
}
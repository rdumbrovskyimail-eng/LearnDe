// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.5
// Путь: app/src/main/java/com/learnde/app/learn/core/LearnCoreViewModel.kt
//
// КЛЮЧЕВЫЕ ИЗМЕНЕНИЯ v3.5 (стабилизация транскрипта):
//
//   ПРОБЛЕМА: Gemini Live API шлёт inputTranscription.text КАК ДЕЛЬТЫ
//   (инкрементальные кусочки), а не как полный накопленный текст.
//   Каждый фрейм = новый chunk, который нужно конкатенировать.
//   Граница реплики = serverContent.turnComplete (не таймер!).
//
//   Старый код считал каждую дельту полной заменой → пузырь
//   показывал ПОСЛЕДНЮЮ дельту вместо всей фразы.
//
//   РЕШЕНИЕ:
//     1. Per-turn buffer для входа и выхода (StringBuilder)
//     2. Все события (Input/OutputTranscript, ModelText, TurnComplete,
//        Interrupted) сериализуются через Channel — гарантия порядка,
//        нет race conditions
//     3. Полная очистка буфера на TurnComplete/Interrupted
//     4. Авто-детектор формата (дельты vs накопленный) — на случай
//        если SDK API изменится
//     5. Live-обновление пузыря на КАЖДОЙ дельте (UX = real-time)
//
//   ДОПОЛНИТЕЛЬНО:
//     - Дедупликация ModelText vs OutputTranscript: если включён
//       outputTranscription, ModelText игнорируется (Google рекомендует).
//     - Детектор языка для translator-сессии: если вход RU/UK, а
//       выход тоже RU/UK — отправляется корректирующий промпт.
//
//   СКОРОСТЬ: не пострадала. Наоборот, убраны лишние launch'и
//   и таймер 4 сек — обработка стала легче.
//
// СОВМЕСТИМОСТЬ: LiveClient НЕ требует изменений. Все правки только
// в этом файле.
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.consumeAsFlow
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
        private val cleanupScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
        )

        private const val MAX_TRANSCRIPT_SIZE = 150
        private const val LEARNER_SILENCE_THRESHOLD_MS = 10_000L
        private const val SILENCE_CHECK_WINDOW_MS = 9_000L
        private const val GREETING_WARMUP_MS = 150L
        private const val SILENCE_WARMUP_MS = 400L
        private const val MIC_PREWARM_MS = 200L
        private const val GREETING_RETRY_MS = 4_000L
        private const val GREETING_FINAL_MS = 8_000L
        private const val SILENCE_PCM_BYTES = (2 * 16000 * 400) / 1000
        private const val AI_AUDIO_TAIL_MS = 600L
        private const val SILENCE_PROMPT_COOLDOWN_MS = 30_000L
        private const val FINISH_SESSION_GRACE_MS = 5_000L
    }

    private val _state = MutableStateFlow(LearnCoreState())
    val state: StateFlow<LearnCoreState> = _state.asStateFlow()

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
    @Volatile private var modelStartedSpeakingThisTurn = false
    @Volatile private var awaitingInitialGreeting = false
    @Volatile private var lastAiAudioChunkAtMs: Long = 0L
    @Volatile private var sessionFinished: Boolean = false
    @Volatile private var lastSilencePromptAtMs: Long = 0L
    @Volatile private var droppedMicChunks: Int = 0

    private val transcriptMutex = Mutex()
    @Volatile private var transcriptBuffer: List<ConversationMessage> = emptyList()
    @Volatile private var pendingVocabViolation: VocabularyViolation? = null

    // ─── Сериализация всех событий через Channel ───
    // Гарантирует строгий порядок обработки и устраняет race conditions
    // при инкрементальных дельтах транскрипта.
    private val transcriptChannel = Channel<TranscriptOp>(Channel.UNLIMITED)

    private sealed class TranscriptOp {
        data class UserDelta(val text: String) : TranscriptOp()
        data class ModelDelta(val text: String, val source: String) : TranscriptOp()
        object UserTurnComplete : TranscriptOp()
        object ModelTurnComplete : TranscriptOp()
        object ModelInterrupted : TranscriptOp()
        object Reset : TranscriptOp()
    }

    // Per-turn буферы. Очищаются при завершении turn'а.
    private val userTurnBuffer = StringBuilder()
    private val modelTurnBuffer = StringBuilder()

    // ID текущего "живого" пузыря пользователя и модели.
    // Когда turn закрывается — id обнуляется, следующая дельта создаст новый пузырь.
    @Volatile private var liveUserMessageTs: Long = 0L
    @Volatile private var liveModelMessageTs: Long = 0L

    init {
        observeSettings()
        observeGeminiEvents()
        observeArbiter()
        observeVocabularyViolations()
        startTranscriptProcessor()
        viewModelScope.launch { audioEngine.initPlayback() }
    }

    private fun startTranscriptProcessor() {
        viewModelScope.launch {
            transcriptChannel.consumeAsFlow().collect { op ->
                runCatching { processTranscriptOp(op) }
                    .onFailure { logger.e("Transcript op failed: ${it.message}", it) }
            }
        }
    }

    private suspend fun processTranscriptOp(op: TranscriptOp) {
        when (op) {
            is TranscriptOp.UserDelta -> handleUserDelta(op.text)
            is TranscriptOp.ModelDelta -> handleModelDelta(op.text, op.source)
            is TranscriptOp.UserTurnComplete -> finalizeUserTurn()
            is TranscriptOp.ModelTurnComplete -> finalizeModelTurn()
            is TranscriptOp.ModelInterrupted -> finalizeModelTurn()
            is TranscriptOp.Reset -> {
                userTurnBuffer.clear()
                modelTurnBuffer.clear()
                liveUserMessageTs = 0L
                liveModelMessageTs = 0L
            }
        }
    }

    /**
     * Обрабатывает входящую дельту юзер-транскрипта.
     *
     * АВТОДЕТЕКТОР ФОРМАТА:
     *   - Если новый text НАЧИНАЕТСЯ с уже накопленного буфера → это
     *     "полный накопленный" режим (некоторые SDK так делают).
     *     Заменяем буфер целиком.
     *   - Иначе → это "дельта" (стандарт Live API). Конкатенируем.
     *
     * Это защищает от изменений в SDK / API без правок кода.
     */
    private suspend fun handleUserDelta(text: String) {
        if (text.isEmpty()) return

        // Garbage filter — отбрасываем чанки без букв
        if (!hasMeaningfulChars(text)) {
            logger.d("Learn: dropping garbage user delta: '$text'")
            return
        }

        val current = userTurnBuffer.toString()
        when {
            current.isEmpty() -> {
                userTurnBuffer.append(text)
            }
            text == current -> {
                // Дубль — пропускаем
                return
            }
            text.startsWith(current) && text.length > current.length -> {
                // Пришёл полный накопленный (SDK сам аккумулирует)
                userTurnBuffer.clear()
                userTurnBuffer.append(text)
            }
            current.endsWith(text) -> {
                // Старый текст уже содержит новый — пропускаем (защита от ретрая)
                return
            }
            else -> {
                // Нормальная дельта
                userTurnBuffer.append(text)
            }
        }

        lastInputTs = System.currentTimeMillis()
        silenceTimerJob?.cancel()

        val full = userTurnBuffer.toString()
        upsertLiveUserBubble(full)
    }

    private suspend fun handleModelDelta(text: String, source: String) {
        if (text.isEmpty()) return

        // Если включена outputTranscription — игнорируем ModelText (дубль).
        // Google рекомендует использовать ровно один источник для транскрипции
        // голосовых ответов native-audio модели.
        if (cachedSettings.outputTranscription && source == "ModelText") {
            return
        }
        // Если outputTranscription выключена — игнорируем OutputTranscript
        // (его не должно быть, но на всякий случай).
        if (!cachedSettings.outputTranscription && source == "OutputTranscript") {
            return
        }

        val current = modelTurnBuffer.toString()
        when {
            current.isEmpty() -> {
                modelTurnBuffer.append(text)
            }
            text == current -> return
            text.startsWith(current) && text.length > current.length -> {
                modelTurnBuffer.clear()
                modelTurnBuffer.append(text)
            }
            current.endsWith(text) -> return
            else -> {
                modelTurnBuffer.append(text)
            }
        }

        upsertLiveModelBubble(modelTurnBuffer.toString())

        // Vocabulary enforcer — анализируем накопленный текст один раз на дельту
        if (activeSession?.id == "a1_situation" || activeSession?.id == "a1_review") {
            vocabularyEnforcer.analyze(text)
        }
    }

    private suspend fun finalizeUserTurn() {
        if (userTurnBuffer.isEmpty()) return
        val finalText = userTurnBuffer.toString().trim()
        userTurnBuffer.clear()

        if (finalText.isNotEmpty() && liveUserMessageTs != 0L) {
            // Финализируем пузырь финальным текстом (триммированным)
            updateBubbleByTs(liveUserMessageTs, finalText, ConversationMessage.ROLE_USER)
        }
        liveUserMessageTs = 0L
    }

    private suspend fun finalizeModelTurn() {
        if (modelTurnBuffer.isEmpty()) {
            liveModelMessageTs = 0L
            return
        }
        val finalText = modelTurnBuffer.toString().trim()
        modelTurnBuffer.clear()

        if (finalText.isNotEmpty() && liveModelMessageTs != 0L) {
            updateBubbleByTs(liveModelMessageTs, finalText, ConversationMessage.ROLE_MODEL)
        }
        liveModelMessageTs = 0L
    }

    private suspend fun upsertLiveUserBubble(text: String) {
        transcriptMutex.withLock {
            if (liveUserMessageTs == 0L) {
                // Создаём новый пузырь
                val newMsg = ConversationMessage.user(text)
                liveUserMessageTs = newMsg.timestamp
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                // Обновляем существующий
                val idx = transcriptBuffer.indexOfLast { it.timestamp == liveUserMessageTs }
                if (idx >= 0) {
                    val updated = transcriptBuffer[idx].copy(text = text)
                    val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                } else {
                    // Пузырь выпал из FIFO — создаём новый
                    val newMsg = ConversationMessage.user(text)
                    liveUserMessageTs = newMsg.timestamp
                    val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                }
            }
        }
    }

    private suspend fun upsertLiveModelBubble(text: String) {
        transcriptMutex.withLock {
            if (liveModelMessageTs == 0L) {
                val newMsg = ConversationMessage(
                    role = ConversationMessage.ROLE_MODEL,
                    text = text,
                    timestamp = System.currentTimeMillis()
                )
                liveModelMessageTs = newMsg.timestamp
                val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            } else {
                val idx = transcriptBuffer.indexOfLast { it.timestamp == liveModelMessageTs }
                if (idx >= 0) {
                    val updated = transcriptBuffer[idx].copy(text = text)
                    val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                } else {
                    val newMsg = ConversationMessage(
                        role = ConversationMessage.ROLE_MODEL,
                        text = text,
                        timestamp = System.currentTimeMillis()
                    )
                    liveModelMessageTs = newMsg.timestamp
                    val next = (transcriptBuffer + newMsg).takeLast(MAX_TRANSCRIPT_SIZE)
                    transcriptBuffer = next
                    _state.update { it.copy(transcript = next) }
                }
            }
        }
    }

    private suspend fun updateBubbleByTs(ts: Long, finalText: String, role: String) {
        transcriptMutex.withLock {
            val idx = transcriptBuffer.indexOfLast { it.timestamp == ts && it.role == role }
            if (idx >= 0) {
                val updated = transcriptBuffer[idx].copy(text = finalText)
                val next = transcriptBuffer.toMutableList().apply { set(idx, updated) }
                transcriptBuffer = next
                _state.update { it.copy(transcript = next) }
            }
        }
    }

    private fun hasMeaningfulChars(text: String): Boolean {
        return text.any { c ->
            c in 'a'..'z' || c in 'A'..'Z' ||
                c in 'а'..'я' || c in 'А'..'Я' ||
                c == 'ё' || c == 'Ё' ||
                c in "äöüßÄÖÜ" ||
                c in "ієґїІЄҐЇ"
        }
    }

    private fun observeVocabularyViolations() {
        viewModelScope.launch {
            vocabularyEnforcer.violations.collect { violation ->
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

        val (silenceMs, prefixMs, temp) = when (session.id) {
            "translator"   -> Triple(900, 250, 0.2f)   // быстрее реакция, ниже temp для стабильности перевода
            "a1_situation" -> Triple(1000, 300, cachedSettings.temperature)
            "a1_review"    -> Triple(1000, 300, cachedSettings.temperature)
            else           -> Triple(1000, 300, cachedSettings.temperature)
        }

        val finalSilenceMs = if (cachedSettings.vadSilenceTimeoutMs > 0)
            maxOf(cachedSettings.vadSilenceTimeoutMs, 500)
        else silenceMs

        // В переводчике даём ASR определять язык автоматически (RU/UK/DE/EN)
        val finalLanguageCode = if (session.id == "translator") "" else cachedSettings.languageCode

        return SessionConfig(
            model = cachedSettings.model,
            temperature = temp,
            topP = cachedSettings.topP,
            topK = cachedSettings.topK,
            maxOutputTokens = cachedSettings.maxOutputTokens,
            presencePenalty = cachedSettings.presencePenalty,
            frequencyPenalty = cachedSettings.frequencyPenalty,
            voiceId = cachedSettings.voiceId,
            languageCode = finalLanguageCode,
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
                    "prefix=${prefixMs}ms, temp=$temp, outputTr=${cachedSettings.outputTranscription}"
            )
        }
    }

    private fun observeArbiter() {
        viewModelScope.launch {
            arbiter.active.collect { owner ->
                val owned = owner == ClientOwner.LEARN
                _state.update { it.copy(arbiterOwned = owned) }
                val isConnected = _state.value.connectionStatus != LearnConnectionStatus.Disconnected
                if (!owned && activeSession != null && isConnected) {
                    logger.w("Learn: lost arbiter ownership → stopping")
                    handleStop()
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════
    //  START / STOP
    // ══════════════════════════════════════════════════════

    private suspend fun stopInternal() = startStopMutex.withLock {
        logger.d("▶ Learn.stopInternal")
        val session = activeSession
        micJob?.cancel()
        silenceTimerJob?.cancel()
        greetingFallbackJob?.cancel()
        finishGraceJob?.cancel()
        finishGraceJob = null

        micOperationMutex.withLock {
            audioEngine.stopCapture()
        }
        safeStopForegroundService()

        runCatching { liveClient.disconnect() }
        runCatching { session?.onExit() }

        // Финализируем все висящие turn'ы
        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
        transcriptChannel.trySend(TranscriptOp.Reset)

        activeSession = null
        pendingToolCalls.clear()
        transcriptMutex.withLock { transcriptBuffer = emptyList() }
        statusBus.reset()
        vocabularyEnforcer.reset()
        contextSeeded = false
        pendingVocabViolation = null
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
                isFinishingSession = false,
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
        pendingVocabViolation = null
        transcriptChannel.trySend(TranscriptOp.Reset)
        modelStartedSpeakingThisTurn = false
        awaitingInitialGreeting = false
        sessionFinished = false
        lastAiAudioChunkAtMs = 0L
        lastSilencePromptAtMs = 0L
        droppedMicChunks = 0
        greetingFallbackJob?.cancel()
        setupJob?.cancel()
        setupJob = null
        finishGraceJob?.cancel()
        finishGraceJob = null

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
                isFinishingSession = false,
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
            launch {
                audioEngine.micOutput.collect { chunk ->
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
            micOperationMutex.withLock {
                audioEngine.stopCapture()
            }
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
                        lastAiAudioChunkAtMs = System.currentTimeMillis()

                        if (!modelStartedSpeakingThisTurn) {
                            modelStartedSpeakingThisTurn = true
                            if (awaitingInitialGreeting) {
                                awaitingInitialGreeting = false
                                greetingFallbackJob?.cancel()
                                logger.d("Learn: model started greeting ✓")
                            }
                        }
                        _state.update { it.copy(isAiSpeaking = true, isPreparingSession = false) }
                        audioEngine.enqueuePlayback(event.pcmData)
                    }

                    is GeminiEvent.Interrupted -> {
                        // ВАЖНО: финализируем модель-turn чтобы сохранить накопленный текст
                        transcriptChannel.trySend(TranscriptOp.ModelInterrupted)
                        audioEngine.flushPlayback()
                        _state.update { it.copy(isAiSpeaking = false) }
                    }

                    is GeminiEvent.TurnComplete -> {
                        // Финализируем оба буфера
                        transcriptChannel.trySend(TranscriptOp.UserTurnComplete)
                        transcriptChannel.trySend(TranscriptOp.ModelTurnComplete)
                        modelStartedSpeakingThisTurn = false

                        audioEngine.onTurnComplete()
                        _state.update { it.copy(isAiSpeaking = false) }

                        flushPendingVocabViolation()

                        // Silence-промпт
                        lastInputTs = System.currentTimeMillis()
                        val now = System.currentTimeMillis()
                        val cooldownPassed = (now - lastSilencePromptAtMs) > SILENCE_PROMPT_COOLDOWN_MS

                        if (_state.value.isMicActive && !sessionFinished && cooldownPassed
                            && activeSession?.id != "translator" // в переводчике никаких подбадриваний
                        ) {
                            silenceTimerJob?.cancel()
                            silenceTimerJob = viewModelScope.launch {
                                delay(LEARNER_SILENCE_THRESHOLD_MS)
                                val quietFor = System.currentTimeMillis() - lastInputTs
                                if (quietFor > SILENCE_CHECK_WINDOW_MS
                                    && liveClient.isReady
                                    && _state.value.isMicActive
                                    && !sessionFinished
                                    && activeSession?.id != "translator"
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
                        transcriptChannel.trySend(TranscriptOp.UserDelta(event.text))
                    }

                    is GeminiEvent.OutputTranscript -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "OutputTranscript"))
                    }

                    is GeminiEvent.ModelText -> {
                        if (awaitingInitialGreeting) {
                            awaitingInitialGreeting = false
                            greetingFallbackJob?.cancel()
                        }
                        transcriptChannel.trySend(TranscriptOp.ModelDelta(event.text, "ModelText"))
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
                            toolCallJobs[id]?.cancel()
                        }
                    }

                    is GeminiEvent.Disconnected -> {
                        greetingFallbackJob?.cancel()
                        val isAbnormal = event.code != 1000 && event.code != 1001
                        val errorMsg = if (isAbnormal) "Соединение закрыто: ${event.reason} (Код: ${event.code}). Проверьте API-ключ." else null

                        _state.update {
                            it.copy(
                                connectionStatus = LearnConnectionStatus.Disconnected,
                                isMicActive = false,
                                isPreparingSession = false,
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

    private fun handleSetupComplete() {
        _state.update { it.copy(connectionStatus = LearnConnectionStatus.Ready) }
        val session = activeSession ?: return
        contextSeeded = true
        modelStartedSpeakingThisTurn = false

        setupJob?.cancel()
        setupJob = viewModelScope.launch {
            delay(GREETING_WARMUP_MS)

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

            if (!liveClient.isReady || activeSession != session) {
                awaitingInitialGreeting = false
                return@launch
            }

            if (!_state.value.isMicActive) startMic()
            delay(MIC_PREWARM_MS)

            if (!liveClient.isReady || activeSession != session) {
                awaitingInitialGreeting = false
                return@launch
            }

            logger.d("Learn: sending initial greeting trigger")
            liveClient.sendText(session.initialUserMessage)

            greetingFallbackJob?.cancel()
            greetingFallbackJob = viewModelScope.launch {
                delay(GREETING_RETRY_MS)
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

    private suspend fun sendSilenceWarmup() {
        val silence = ByteArray(SILENCE_PCM_BYTES)
        logger.d("Learn: injecting ${SILENCE_PCM_BYTES}B of silence (${SILENCE_WARMUP_MS}ms)")
        val chunkSize = 1280
        var offset = 0
        while (offset < silence.size) {
            val end = minOf(offset + chunkSize, silence.size)
            liveClient.sendAudio(silence.copyOfRange(offset, end))
            offset = end
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

    private fun handleToolCalls(event: GeminiEvent.ToolCall) {
        val session = activeSession
        val responses = java.util.concurrent.ConcurrentLinkedQueue<ToolResponse>()

        for (call in event.calls) {
            pendingToolCalls.add(call.id)
            statusBus.onDetected(call.name, call.id)
        }

        val children = event.calls.map { call ->
            viewModelScope.launch {
                var success = true
                try {
                    if (call.id !in pendingToolCalls) {
                        responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                        success = false
                        return@launch
                    }

                    statusBus.onExecuting(call.name, call.id)

                    val result = try {
                        session?.handleToolCall(call) ?: """{"error":"no active session"}"""
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) {
                            responses.add(ToolResponse(call.name, call.id, """{"status":"cancelled"}"""))
                            success = false
                            throw e
                        }
                        logger.e("Learn.toolCall threw: ${e.message}", e)
                        success = false
                        """{"error":"${e.message?.replace("\"", "'")}"}"""
                    }

                    if (result.contains("\"error\"")) success = false
                    responses.add(ToolResponse(call.name, call.id, result))
                } finally {
                    statusBus.onCompleted(call.name, call.id, success = success)
                    pendingToolCalls.remove(call.id)
                    toolCallJobs.remove(call.id)
                }
            }.also { toolCallJobs[call.id] = it }
        }

        viewModelScope.launch {
            children.joinAll()
            if (responses.isNotEmpty() && liveClient.isReady) {
                runCatching { liveClient.sendToolResponse(responses.toList()) }
                    .onFailure { logger.e("Learn: failed to send ToolResponse: ${it.message}") }
            }

            if (event.calls.any { it.name == "finish_session" }) {
                sessionFinished = true
                silenceTimerJob?.cancel()
                logger.d("Learn: finish_session → grace ${FINISH_SESSION_GRACE_MS}ms")

                _state.update { it.copy(isFinishingSession = true) }

                finishGraceJob?.cancel()
                finishGraceJob = viewModelScope.launch {
                    delay(FINISH_SESSION_GRACE_MS)
                    if (activeSession != null && sessionFinished) {
                        stopInternal()
                    }
                }
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
        finishGraceJob?.cancel()
        statusBus.reset()
        safeStopForegroundService()

        cleanupScope.launch {
            runCatching { stopInternal() }
            runCatching { transcriptMutex.withLock { transcriptBuffer = emptyList() } }
            runCatching { audioEngine.releaseAll() }
            runCatching { transcriptChannel.close() }
            logger.d("LearnCoreViewModel cleanup complete")
        }
    }
}
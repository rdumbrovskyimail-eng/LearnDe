package com.learnde.app.learn.tutor

import androidx.datastore.core.DataStore
import com.learnde.app.data.settings.AppSettings
import com.learnde.app.learn.sessions.a1.A1LearningBus
import com.learnde.app.learn.sessions.a1.A1LearningEvent
import com.learnde.app.learn.sessions.a1.A1Phase
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TutorHintEngine @Inject constructor(
    private val bus: A1LearningBus,
    private val client: TutorHintClient,
    private val settingsStore: DataStore<AppSettings>,
    private val logger: AppLogger,
) {

    companion object {
        private const val MAX_CARDS = 12
        private const val MIN_INTERVAL_MS = 12_000L
        private const val QUEUE_CAPACITY = 16
    }

    /** Карточки для UI — новые в конце списка. */
    private val _cards = MutableStateFlow<List<TutorHintCard>>(emptyList())
    val cards: StateFlow<List<TutorHintCard>> = _cards.asStateFlow()

    /** Есть ли непрочитанные карточки (для бейджа на свёрнутой панели). */
    private val _unreadCount = MutableStateFlow(0)
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private val running = AtomicBoolean(false)
    private var collectJob: Job? = null
    private var workerJob: Job? = null

    /** Очередь заданий. Дедуп — по requestId. */
    private data class HintRequest(
        val requestId: String,
        val type: TutorHintType,
        val prompt: String,
    )

    private val queue = Channel<HintRequest>(QUEUE_CAPACITY)
    private val seenRequestIds = HashSet<String>()
    private var lastEmitAt = 0L

    /** Контекст текущего урока — выставляет A1LearningViewModel при старте. */
    @Volatile private var sessionTopic: String = ""
    @Volatile private var sessionLemmas: List<String> = emptyList()

    // ────────────────────────────────────────────────────────
    //  LIFECYCLE
    // ────────────────────────────────────────────────────────

    /**
     * Запустить контур на время урока.
     * Вызывать из ViewModel при старте сессии:
     *   tutorHintEngine.start(viewModelScope, topic = cluster.titleRu, lemmas = [...])
     */
    fun start(scope: CoroutineScope, topic: String, lemmas: List<String>) {
        if (!running.compareAndSet(false, true)) {
            // Уже запущен — просто обновляем контекст (новый кластер).
            sessionTopic = topic
            sessionLemmas = lemmas
            return
        }
        sessionTopic = topic
        sessionLemmas = lemmas
        seenRequestIds.clear()
        logger.d("TutorHint: engine started (topic=$topic, ${lemmas.size} lemmas)")

        collectJob = scope.launch {
            bus.events.collect { event -> onLearningEvent(event) }
        }
        workerJob = scope.launch {
            for (req in queue) {
                processRequest(req)
            }
        }
    }

    /** Остановить и очистить карточки (вызывать при стопе сессии). */
    fun stopAndClear() {
        running.set(false)
        collectJob?.cancel(); collectJob = null
        workerJob?.cancel(); workerJob = null
        while (queue.tryReceive().isSuccess) { /* drain */ }
        seenRequestIds.clear()
        _cards.value = emptyList()
        _unreadCount.value = 0
        logger.d("TutorHint: engine stopped")
    }

    /** UI сообщает, что панель раскрыта — сбрасываем бейдж. */
    fun markAllRead() {
        _unreadCount.value = 0
    }

    // ────────────────────────────────────────────────────────
    //  СОБЫТИЯ УРОКА → ЗАДАНИЯ
    // ────────────────────────────────────────────────────────

    private fun onLearningEvent(event: A1LearningEvent) {
        when (event) {
            is A1LearningEvent.GrammarIntroduced -> enqueue(
                HintRequest(
                    requestId = "grammar:${event.ruleId}",
                    type = TutorHintType.GRAMMAR,
                    prompt = """
                        Событие урока: репетитор только что объяснил правило
                        «${event.ruleName}» (id: ${event.ruleId}).
                        Тема урока: $sessionTopic.
                        Сделай карточку-закрепление этого правила: суть одним-двумя
                        предложениями + 3 контрастных примера уровня A1.
                    """.trimIndent()
                )
            )

            is A1LearningEvent.LemmaEvaluated -> {
                // Карточка только при содержательной ошибке (не SLIP, не верный ответ).
                if (event.wasCorrect) return
                val d = event.diagnosis
                enqueue(
                    HintRequest(
                        // Дедуп по лемме+категории: одну и ту же ошибку не объясняем дважды.
                        requestId = "attention:${event.lemma}:${d.category}",
                        type = TutorHintType.ATTENTION,
                        prompt = """
                            Событие урока: ученик ошибся в слове «${event.lemma}».
                            Категория ошибки: ${d.category}. Источник: ${d.source}.
                            Конкретика: ${d.specifics.ifBlank { "не указана" }}.
                            Сделай карточку «Обрати внимание»: что именно ученик
                            делает не так, мнемоника или короткое правило, и 2-3
                            правильных примера с этим словом (уровень A1).
                        """.trimIndent()
                    )
                )
            }

            is A1LearningEvent.PhaseChanged -> {
                if (event.phase != A1Phase.INTRODUCE) return
                if (sessionLemmas.isEmpty()) return
                val lemmaLine = sessionLemmas.take(8).joinToString(", ")
                enqueue(
                    HintRequest(
                        requestId = "vocab:$sessionTopic",
                        type = TutorHintType.VOCAB,
                        prompt = """
                            Событие урока: началась фаза знакомства со словами.
                            Тема: $sessionTopic. Слова урока: $lemmaLine.
                            Сделай карточку-шпаргалку: для существительных укажи
                            артикль и множественное число, для глаголов — форму
                            ich/du/er. На что обратить внимание в произношении.
                            Примеры — короткие словосочетания A1.
                        """.trimIndent()
                    )
                )
            }

            else -> Unit // LemmaHeard / LemmaProduced / SessionFinished — без карточек
        }
    }

    private fun enqueue(req: HintRequest) {
        if (!running.get()) return
        synchronized(seenRequestIds) {
            if (!seenRequestIds.add(req.requestId)) return // уже было
        }
        val offered = queue.trySend(req).isSuccess
        if (!offered) logger.w("TutorHint: queue full — dropped ${req.requestId}")
    }

    // ────────────────────────────────────────────────────────
    //  ВЫПОЛНЕНИЕ
    // ────────────────────────────────────────────────────────

    private suspend fun processRequest(req: HintRequest) {
        // Троттлинг — карточки не должны сыпаться чаще, чем их можно читать.
        val sinceLast = System.currentTimeMillis() - lastEmitAt
        if (sinceLast < MIN_INTERVAL_MS) delay(MIN_INTERVAL_MS - sinceLast)
        if (!running.get()) return

        val settings = runCatching { settingsStore.data.first() }.getOrNull() ?: return
        if (!settings.enableTutorHints) return
        val effectiveKey = settings.tutorApiKey.ifBlank { settings.apiKey }
        if (effectiveKey.isBlank()) {
            logger.d("TutorHint: ключ не задан — карточки выключены")
            return
        }

        val response = client.fetchHint(
            apiKey = effectiveKey,
            model = settings.tutorModel,
            prompt = req.prompt,
            thinkingLevel = if (req.type == TutorHintType.GRAMMAR) "low" else "minimal",
        ) ?: return

        if (!running.get()) return

        val card = TutorHintCard(
            id = req.requestId,
            type = req.type,
            title = response.title.take(60),
            body = response.body,
            examples = response.examples.take(4),
        )

        _cards.value = (_cards.value + card).takeLast(MAX_CARDS)
        _unreadCount.value = _unreadCount.value + 1
        lastEmitAt = System.currentTimeMillis()
        logger.d("TutorHint: card emitted [${card.type}] ${card.title}")
    }
}
package com.learnde.app.learn.domain.v2

import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.learn.data.db.v2.A1AssociationDao
import com.learnde.app.learn.domain.SessionContext
import com.learnde.app.util.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LessonScriptPlanner @Inject constructor(
    private val associationDao: A1AssociationDao,
    private val logger: AppLogger,
) {

    companion object {
        /** Оптимум новых слов за урок: 3-5 (когнитивная нагрузка A1). */
        const val MAX_NEW_LEMMAS = 4

        /** Интервал между INTRODUCE и первым RETRIEVE (в шагах). */
        private const val FIRST_RETRIEVE_GAP = 2
    }

    suspend fun buildScript(context: SessionContext): LessonScript {
        val now = System.currentTimeMillis()
        val cluster = context.cluster

        // Новые: незатронутые леммы кластера, max 4. Если кластер уже
        // частично пройден — берём самые слабые из его лемм.
        val newLemmas = context.primaryLemmas
            .sortedBy { it.productionScore }
            .take(MAX_NEW_LEMMAS)

        val reviewLemmas = context.reviewLemmas.take(8)

        // Персональные ассоциации (elaborative cues).
        val associations: Map<String, String> = associationDao
            .getForLemmas((newLemmas + reviewLemmas).map { it.lemma })
            .associate { it.lemma to it.text }

        val steps = mutableListOf<LessonStep>()
        var idCounter = 0
        fun nextId() = "s%02d".format(++idCounter)

        fun add(
            kind: StepKind,
            lemma: LemmaA1Entity? = null,
            ruleId: String? = null,
            instruction: String,
        ) {
            steps += LessonStep(
                id = nextId(),
                kind = kind,
                lemma = lemma?.lemma,
                lemmaRu = lemma?.let { translationHint(it) },
                ruleId = ruleId,
                instruction = instruction,
            )
        }

        val reviewQueue = ArrayDeque(reviewLemmas)
        fun popReview(): LemmaA1Entity? = reviewQueue.removeFirstOrNull()

        // ── GREETING ──
        add(
            kind = StepKind.GREETING,
            instruction = "Тепло поздоровайся ОДНОЙ фразой и скажи тему урока: " +
                "«${cluster.titleRu}». Не перечисляй слова заранее. " +
                "Затем сразу step_done.",
        )

        // ── Стартовый RECALL_OLD: разогрев памяти ──
        popReview()?.let { r ->
            add(
                kind = StepKind.RECALL_OLD,
                lemma = r,
                instruction = recallInstruction(r, associations[r.lemma]),
            )
        }

        // ── Конвейер introduce/echo/retrieve/use с interleaving ──
        // pendingRetrieve: слова, ждущие первого извлечения.
        // pendingUse: слова, ждущие фазы продукции.
        val pendingRetrieve = ArrayDeque<LemmaA1Entity>()
        val pendingUse = ArrayDeque<LemmaA1Entity>()
        var introducedSinceFlex = 0

        newLemmas.forEachIndexed { index, n ->
            // INTRODUCE + ECHO — всегда парой.
            add(
                kind = StepKind.INTRODUCE,
                lemma = n,
                instruction = introduceInstruction(n),
            )
            add(
                kind = StepKind.ECHO,
                lemma = n,
                instruction = "Попроси ученика произнести «${fullForm(n)}» вслух. " +
                    "Поправляй произношение мягко, по слогам. Ученик может " +
                    "тренироваться сколько хочет — НЕ торопи. Когда получится " +
                    "хорошо (или ученик скажет «дальше») — похвали и step_done.",
            )
            pendingRetrieve.addLast(n)
            introducedSinceFlex++

            // Через каждые ~2 introduce: извлекаем самое раннее из очереди
            // (gap ≈ FIRST_RETRIEVE_GAP шагов) + вставляем review.
            if (pendingRetrieve.size >= FIRST_RETRIEVE_GAP || index == newLemmas.lastIndex) {
                while (pendingRetrieve.isNotEmpty()) {
                    val target = pendingRetrieve.removeFirst()
                    popReview()?.let { r ->
                        add(
                            kind = StepKind.RECALL_OLD,
                            lemma = r,
                            instruction = recallInstruction(r, associations[r.lemma]),
                        )
                    }
                    add(
                        kind = StepKind.RETRIEVE_NEW,
                        lemma = target,
                        instruction = "Спроси по-русски: «Как сказать " +
                            "‘${translationHint(target)}’?» Жди ответа. " +
                            "После ответа ОБЯЗАТЕЛЬНО evaluate_and_update_lemma " +
                            "(lemma=\"${target.lemma}\"), затем step_done.",
                    )
                    pendingUse.addLast(target)
                }
            }

            // FLEX-окно после первых двух слов: ассоциации по свежему.
            if (introducedSinceFlex >= 2 && index < newLemmas.lastIndex) {
                introducedSinceFlex = 0
                add(
                    kind = StepKind.FLEX,
                    instruction = flexInstruction(
                        lemmas = newLemmas.take(index + 1).map { fullForm(it) },
                    ),
                )
            }

            // USE_IN_CONTEXT для слова, извлечённого 2+ шага назад.
            if (pendingUse.size >= 2 || index == newLemmas.lastIndex) {
                pendingUse.removeFirstOrNull()?.let { u ->
                    add(
                        kind = StepKind.USE_IN_CONTEXT,
                        lemma = u,
                        instruction = "Попроси ученика составить КОРОТКУЮ фразу " +
                            "со словом «${fullForm(u)}» (2-5 слов, лексика A1). " +
                            "Помоги если застрял. После попытки — " +
                            "evaluate_and_update_lemma, затем step_done.",
                    )
                }
            }
        }

        // Добиваем оставшиеся USE.
        while (pendingUse.isNotEmpty()) {
            val u = pendingUse.removeFirst()
            add(
                kind = StepKind.USE_IN_CONTEXT,
                lemma = u,
                instruction = "Попроси короткую фразу со словом «${fullForm(u)}». " +
                    "После попытки evaluate_and_update_lemma → step_done.",
            )
        }

        // ── MICRO_DIALOG по сценарию кластера ──
        add(
            kind = StepKind.MICRO_DIALOG,
            instruction = "Разыграй мини-сцену: «${cluster.scenarioHint}». " +
                "Ты — одна роль, ученик — другая. 2-4 обмена репликами, " +
                "используй слова урока: ${newLemmas.joinToString { it.lemma }}. " +
                "За каждое удачно использованное учеником слово — " +
                "mark_lemma_produced. Затем step_done.",
        )

        // ── GRAMMAR_SPOT (опционально) ──
        context.grammarRuleToIntroduce?.let { rule ->
            add(
                kind = StepKind.GRAMMAR_SPOT,
                ruleId = rule.id,
                instruction = grammarInstruction(rule),
            )
        }

        // ── Остаток review-очереди ──
        while (reviewQueue.isNotEmpty()) {
            val r = reviewQueue.removeFirst()
            add(
                kind = StepKind.RECALL_OLD,
                lemma = r,
                instruction = recallInstruction(r, associations[r.lemma]),
            )
        }

        // ── Финальное FLEX-окно ──
        add(
            kind = StepKind.FLEX,
            instruction = flexInstruction(newLemmas.map { fullForm(it) }),
        )

        // ── FINAL_RECALL: все новые одним блицем ──
        add(
            kind = StepKind.FINAL_RECALL,
            instruction = "Финальный блиц: быстро спроси перевод КАЖДОГО " +
                "слова урока (${newLemmas.joinToString { "«${translationHint(it)}»" }}). " +
                "Темп бодрый, реакция в 2-3 слова. После КАЖДОГО ответа — " +
                "evaluate_and_update_lemma. Когда все слова спрошены — step_done.",
        )

        // ── WRAP_UP ──
        add(
            kind = StepKind.WRAP_UP,
            instruction = "Подведи итог ОДНОЙ тёплой фразой (что выучили, " +
                "что получилось лучше всего). Затем вызови " +
                "finish_session(overall_quality, feedback). step_done НЕ нужен — " +
                "finish_session завершает урок сам.",
        )

        val script = LessonScript(
            planId = "plan_${cluster.id}_$now",
            clusterId = cluster.id,
            clusterTitleRu = cluster.titleRu,
            scenarioHint = cluster.scenarioHint,
            newLemmas = newLemmas.map { it.lemma },
            reviewLemmas = reviewLemmas.map { it.lemma },
            steps = steps,
            createdAt = now,
        )
        logger.d(
            "PlannerV2: script ${script.planId} — ${steps.size} steps, " +
            "${newLemmas.size} new, ${reviewLemmas.size} review, " +
            "grammar=${context.grammarRuleToIntroduce?.id}"
        )
        return script
    }

    /**
     * Сценарий чистого повторения (для review-сессий и Слепого режима):
     * только RECALL_OLD + одно FLEX-окно в середине + WRAP_UP.
     */
    suspend fun buildReviewScript(lemmas: List<LemmaA1Entity>): LessonScript {
        val now = System.currentTimeMillis()
        val associations = associationDao
            .getForLemmas(lemmas.map { it.lemma })
            .associate { it.lemma to it.text }

        val steps = mutableListOf<LessonStep>()
        var idCounter = 0
        fun nextId() = "s%02d".format(++idCounter)

        steps += LessonStep(
            id = nextId(), kind = StepKind.GREETING,
            instruction = "Скажи одной фразой: повторяем слова, " +
                "${lemmas.size} штук, поехали. step_done.",
        )
        lemmas.forEachIndexed { i, r ->
            steps += LessonStep(
                id = nextId(), kind = StepKind.RECALL_OLD,
                lemma = r.lemma, lemmaRu = translationHint(r),
                instruction = recallInstruction(r, associations[r.lemma]),
            )
            if (i == lemmas.size / 2) {
                steps += LessonStep(
                    id = nextId(), kind = StepKind.FLEX,
                    instruction = flexInstruction(
                        lemmas.take(i + 1).map { fullForm(it) }
                    ),
                )
            }
        }
        steps += LessonStep(
            id = nextId(), kind = StepKind.WRAP_UP,
            instruction = "Итог одной фразой → finish_session(overall_quality, feedback).",
        )

        return LessonScript(
            planId = "plan_review_$now",
            clusterId = "review",
            clusterTitleRu = "Повторение",
            scenarioHint = "",
            newLemmas = emptyList(),
            reviewLemmas = lemmas.map { it.lemma },
            steps = steps,
            createdAt = now,
        )
    }

    // ─────────────────────────────────────────────────────────────
    //  Шаблоны инструкций
    // ─────────────────────────────────────────────────────────────

    private fun introduceInstruction(n: LemmaA1Entity): String {
        val full = fullForm(n)
        return "Введи слово «$full» (${n.pos}): произнеси его чётко, " +
            "дай русский перевод, ОДИН живой пример-микрофразу на немецком " +
            "с переводом. mark_lemma_heard(\"${n.lemma}\"). Спроси, понятно ли. " +
            "Затем step_done."
    }

    private fun recallInstruction(r: LemmaA1Entity, association: String?): String {
        val hint = if (!association.isNullOrBlank())
            " Если не вспомнит — подсказка-2: напомни ЕГО ассоциацию: «$association»."
        else
            " Если не вспомнит — подскажи первый слог."
        return "Повторение: спроси перевод «${translationHint(r)}» на немецкий." +
            hint + " После ответа ОБЯЗАТЕЛЬНО evaluate_and_update_lemma " +
            "(lemma=\"${r.lemma}\"), затем step_done."
    }

    private fun flexInstruction(lemmas: List<String>): String =
        "FLEX-окно (свободное общение!). Спроси у ученика, с чем у него " +
        "ассоциируется одно из слов (${lemmas.joinToString()}), или где он " +
        "мог бы его встретить. Поболтай ЖИВО и с интересом — это его время. " +
        "Если ученик делится ассоциацией/историей — ОБЯЗАТЕЛЬНО " +
        "log_association(lemma, association). Можно отвечать на любые его " +
        "вопросы о словах, языке, Германии. Когда разговор естественно " +
        "затихнет (или придёт системное напоминание) — мягко закругли и step_done."

    private fun grammarInstruction(rule: GrammarRuleA1Entity): String =
        "Грамматическая вставка: «${rule.nameRu}». Объясни по-русски в 2-3 " +
        "коротких предложениях: ${rule.shortExplanation}. Дай 1 пример на " +
        "немецком. Спроси, понятно ли; ответь на вопросы если есть. " +
        "Затем introduce_grammar_rule(rule_id=\"${rule.id}\") и step_done."

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────

    private fun fullForm(l: LemmaA1Entity): String =
        l.article?.let { "$it ${l.lemma}" } ?: l.lemma

    /**
     * Русская подсказка для инструкций. В LemmaA1Entity нет поля перевода,
     * поэтому используем лемму как опору: модель знает переводы сама,
     * а формат «спроси перевод ‘X’» она однозначно понимает как
     * «русское значение слова X». Если в будущем добавится поле
     * translationRu — подставлять его здесь.
     */
    private fun translationHint(l: LemmaA1Entity): String = l.lemma
}
// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/domain/VocabularyEnforcer.kt
//
// Post-generation фильтр лексики A1 (Krashen "Input Hypothesis" i+1).
//
// Проблема:
//   LLM любят "умничать". Gemini может сказать "eine komplexe Situation"
//   ученику уровня A0, поднимая аффективный фильтр (тревожность).
//
// Решение:
//   1. Держим in-memory Set<String> разрешённой A1-лексики (835 слов).
//   2. Анализируем OutputTranscript Gemini в реальном времени.
//   3. Если > threshold слов вне whitelist за одно высказывание —
//      эмитим Effect, который просит LiveClient отправить скрытый
//      системный промпт "упрости лексику".
//
// Важно: это soft-enforcement. Мы не блокируем ответ, а корректируем
// курс. Слишком агрессивная блокировка ломает диалог.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.domain

import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Событие для VoiceScreen/LearnCoreViewModel — эмитится когда лексика вышла за A1. */
data class VocabularyViolation(
    val violatingWords: List<String>,
    val totalWordsInUtterance: Int,
) {
    val ratio: Float get() =
        if (totalWordsInUtterance == 0) 0f
        else violatingWords.size.toFloat() / totalWordsInUtterance
}

@Singleton
class VocabularyEnforcer @Inject constructor(
    private val lemmaDao: A1LemmaDao,
    private val logger: AppLogger,
) {
    companion object {
        /** Если доля слов вне A1 > 0.25 — эмитим violation. */
        private const val VIOLATION_THRESHOLD = 0.25f

        /** Минимум слов в высказывании, ниже которого не анализируем. */
        private const val MIN_WORDS_TO_ANALYZE = 5

        /** Не штрафуем за короткие функциональные слова, даже если их нет в A1. */
        private val ALWAYS_ALLOWED = setOf(
            "ja", "nein", "doch", "okay", "ok", "hm", "ah", "oh",
            "eins", "zwei", "drei", "vier", "fünf",
            "hallo", "tschüss"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    /** In-memory whitelist базовых форм A1. Загружается один раз. */
    @Volatile private var whitelist: Set<String> = emptySet()
    @Volatile private var loaded: Boolean = false

    private val _violations = MutableSharedFlow<VocabularyViolation>(
        replay = 0, extraBufferCapacity = 4
    )
    val violations: SharedFlow<VocabularyViolation> = _violations.asSharedFlow()

    /** Вызывается при старте сессии A1. */
    suspend fun warmUp() {
        lock.withLock {
            if (loaded) return
            // КРИТИЧНО: грузим ВСЕ леммы A1, иначе выученные слова ИИ
            // будут классифицироваться как "сложные" и ученик получит штрафной prompt
            // каждый раз, когда модель скажет уже выученное слово.
            val all = lemmaDao.getAll()
            val set = mutableSetOf<String>()
            all.forEach { set.add(it.lemma.lowercase()) }
            set.addAll(ALWAYS_ALLOWED)
            // A1-strong-verb forms, которые "стеммер бедняка" не свяжет с инфинитивом
            set.addAll(listOf(
                "war", "warst", "waren", "wart",                  // sein
                "hatte", "hattest", "hatten", "hattet",           // haben
                "ging", "gingst", "gingen", "gingt",              // gehen
                "kam", "kamst", "kamen", "kamt",                  // kommen
                "sah", "sahst", "sahen", "saht",                  // sehen
                "sprach", "sprachst", "sprachen", "spracht",      // sprechen
                "aß", "aßt", "aßen",                              // essen
                "trank", "tranken",                               // trinken
                "fuhr", "fuhren",                                 // fahren
                "schlief", "schliefen",                           // schlafen
                "las", "lasen",                                   // lesen
                "schrieb", "schrieben",                           // schreiben
                "blieb", "blieben",                               // bleiben
                "fand", "fanden",                                 // finden
                "wurde", "wurdest", "wurden", "wurdet",           // werden
                "musste", "mussten", "konnte", "konnten",         // modal Praeteritum
                "wollte", "wollten", "sollte", "sollten",
                "durfte", "durften", "mochte", "mochten",
                // Partizip II частотные
                "gewesen", "gehabt", "gegangen", "gekommen", "gesehen",
                "gesprochen", "gegessen", "getrunken", "gefahren",
                "geschlafen", "gelesen", "geschrieben", "geblieben",
                "gefunden", "geworden", "gemacht", "gearbeitet",
                "gelernt", "gewohnt", "gespielt", "gekauft",
            ))
            whitelist = set
            loaded = true
            logger.d("VocabEnforcer: loaded ${whitelist.size} A1 lemmas into whitelist")
        }
    }

    /** Сбрасываем кеш при выходе из Learn. */
    fun reset() {
        scope.launch {
            lock.withLock {
                whitelist = emptySet()
                loaded = false
            }
        }
    }

    /**
     * Анализ одного высказывания Gemini.
     * Вызывается из LearnCoreViewModel при получении OutputTranscript
     * с status=TURN_COMPLETE (не на каждый chunk).
     */
    fun analyze(utterance: String) {
        if (!loaded || utterance.isBlank()) return

        // ФИКС: Оставляем кириллицу, чтобы честно считать общее количество слов во фразе
        val words = utterance
            .lowercase()
            .replace(Regex("[^a-zäöüßа-яё ]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (words.size < MIN_WORDS_TO_ANALYZE) return

        val cyrillicRegex = Regex("[а-яё]")
        val violators = words.filter { word ->
            // Игнорируем русские слова (они не являются сложной немецкой лексикой)
            if (word.contains(cyrillicRegex)) return@filter false
            // Игнорируем короткие слова, чтобы не штрафовать за междометия
            if (word.length < 3 && word !in ALWAYS_ALLOWED) return@filter false
            
            word !in whitelist && !wordMatchesAnyLemmaForm(word) && !isCompoundOfWhitelist(word)
        }

        if (violators.isEmpty()) return

        val violation = VocabularyViolation(
            violatingWords = violators.distinct().take(5),
            totalWordsInUtterance = words.size,
        )

        if (violation.ratio >= VIOLATION_THRESHOLD) {
            logger.w(
                "VocabEnforcer: violation! ratio=${(violation.ratio * 100).toInt()}% " +
                "words=${violation.violatingWords}"
            )
            _violations.tryEmit(violation)
        }
    }

    /**
     * Грубая проверка "похоже ли слово на A1-лемму".
     * Например: "Häuser" → "haus" (убираем окончания и Umlaut).
     * Это заглушка; честная реализация — через Stanford/SpaCy morphology,
     * но для A1 такой эвристики достаточно.
     */
    private fun wordMatchesAnyLemmaForm(word: String): Boolean {
        val normalized = word
            .replace("ä", "a").replace("ö", "o").replace("ü", "u").replace("ß", "ss")
        
        val stems = listOfNotNull(
            word,
            word.removeSuffix("st"),
            word.removeSuffix("en"),
            word.removeSuffix("er"),
            word.removeSuffix("e"),
            word.removeSuffix("s"),
            word.removeSuffix("t"),
            normalized,
        ).filter { it.length >= 3 }

        // ФИКС: Используем точное совпадение вместо startsWith, 
        // чтобы короткие леммы (in, zu) не пропускали сложные слова (innerhalb, zusammen)
        return stems.any { stem -> 
            whitelist.contains(stem) || 
            whitelist.contains("${stem}en") || 
            whitelist.contains("${stem}n") || 
            whitelist.contains("${stem}e")
        }
    }

    /**
     * Базовый декомпозитор: проверяет, можно ли разбить слово на 2 части,
     * каждая из которых есть в whitelist. Покрывает ~80% A1-композитов.
     * Минимум 4 символа в каждой части (короче — служебные).
     */
    private fun isCompoundOfWhitelist(word: String): Boolean {
        if (word.length < 8) return false
        for (split in 4 until word.length - 3) {
            val left = word.substring(0, split)
            val right = word.substring(split)
            if (left in whitelist && right in whitelist) return true
            // Учитываем "соединительный" -s- (Arbeits-tag, Geburts-tag)
            if (right.startsWith("s") && right.length > 4) {
                val rightTrim = right.substring(1)
                if (left in whitelist && rightTrim in whitelist) return true
            }
        }
        return false
    }

    /** Готовый скрытый промпт, который можно послать Gemini через sendText. */
    fun buildCorrectionPrompt(violation: VocabularyViolation): String {
        val wordsList = violation.violatingWords.joinToString(", ")
        return "[СИСТЕМА]: Ты используешь слишком сложную лексику для A1 " +
            "(например: $wordsList). Упрости следующие фразы, используй только A1-слова, " +
            "сложные слова переводи на русский в скобках."
    }
}
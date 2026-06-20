// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1book/A1BookRepository.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1book

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

// ── Модели контента (парсятся из assets/a1_book/*.json) ──

data class A1BookLessonMeta(
    val nummer: Int,
    val themaDe: String,
    val themaRu: String,
    val grammatikTitel: List<String>,
    val anzahlVoice: Int,
)

data class A1BookLesson(
    val nummer: Int,
    val themaDe: String,
    val themaRu: String,
    val folge: String,
    val lernziele: List<String>,
    val grammatik: List<A1BookGrammar>,
    val wortschatz: List<A1BookWord>,
    val voiceAufgaben: List<A1BookTask>,
)

data class A1BookGrammar(
    val titelDe: String,
    val titelRu: String,
    val erklaerungRu: String,
    val tableLines: List<String>,
    val beispiele: List<Pair<String, String>>,
    val merkeRu: String,
)

data class A1BookWord(
    val de: String,
    val ru: String,
    val artikel: String?,
    val plural: String?,
)

data class A1BookTask(
    val id: String,
    val typ: String,
    val anweisungRu: String,
    val promptDe: String?,
    val promptRu: String?,
    val erwartetDe: String,
    val akzeptiert: List<String>,
    val hinweisRu: String,
)

/**
 * Источник данных книжного курса A1.1. Читает JSON из assets/a1_book/,
 * хранит выбранный урок и собирает системный промпт тьютора.
 * Парсинг через org.json (входит в Android SDK) — без сериализационных плагинов.
 */
@Singleton
class A1BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** Выбранный урок (1..7). Экран выставляет его перед стартом сессии. */
    @Volatile
    var selectedLesson: Int = 1

    private val dir = "a1_book"

    private fun readAsset(name: String): String =
        context.assets.open("$dir/$name").bufferedReader().use { it.readText() }

    suspend fun listLessons(): List<A1BookLessonMeta> = withContext(Dispatchers.IO) {
        runCatching {
            val arr = JSONObject(readAsset("manifest.json")).getJSONArray("lektionen")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                A1BookLessonMeta(
                    nummer = o.getInt("nummer"),
                    themaDe = o.optString("thema_de"),
                    themaRu = o.optString("thema_ru"),
                    grammatikTitel = o.optJSONArray("grammatik_titel").toStringList(),
                    anzahlVoice = o.optJSONObject("anzahl")?.optInt("voice_aufgaben") ?: 0,
                )
            }
        }.getOrElse {
            // Фолбэк без манифеста: пробуем уроки 1..7 напрямую.
            (1..7).mapNotNull { n -> runCatching { loadLesson(n) }.getOrNull() }
                .map { l ->
                    A1BookLessonMeta(
                        l.nummer, l.themaDe, l.themaRu,
                        l.grammatik.map { g -> g.titelRu }, l.voiceAufgaben.size,
                    )
                }
        }
    }

    suspend fun loadLesson(nummer: Int): A1BookLesson = withContext(Dispatchers.IO) {
        val root = JSONObject(readAsset("lektion_%02d.json".format(nummer)))
        val l = root.getJSONObject("lektion")
        val lb = root.optJSONObject("lehrwerk_bezug")
        A1BookLesson(
            nummer = l.getInt("nummer"),
            themaDe = l.optString("thema_de"),
            themaRu = l.optString("thema_ru"),
            folge = lb?.optString("folge_titel_de") ?: l.optString("folge_titel_de"),
            lernziele = l.optJSONArray("lernziele_ru").toStringList(),
            grammatik = l.getJSONArray("grammatik").toGrammar(),
            wortschatz = l.getJSONArray("wortschatz").toWords(),
            voiceAufgaben = l.getJSONArray("voice_aufgaben").toTasks(),
        )
    }

    // ── Парсеры ──

    private fun JSONArray?.toStringList(): List<String> {
        val array = this ?: return emptyList()
        return (0 until array.length()).map { array.getString(it) }
    }

    private fun JSONArray.toGrammar(): List<A1BookGrammar> = (0 until length()).map { i ->
        val o = getJSONObject(i)
        val lines = mutableListOf<String>()
        o.optJSONObject("tabelle")?.optJSONArray("zeilen")?.let { rows ->
            for (r in 0 until rows.length()) {
                val row = rows.getJSONArray(r)
                lines += (0 until row.length()).joinToString(" — ") { row.getString(it) }
            }
        }
        o.optJSONArray("zahlen")?.let { z ->
            for (r in 0 until z.length()) {
                val row = z.getJSONArray(r)
                lines += row.getString(0) + " = " + row.getString(1)
            }
        }
        val bsp = mutableListOf<Pair<String, String>>()
        o.optJSONArray("beispiele")?.let { b ->
            for (r in 0 until b.length()) {
                val e = b.getJSONObject(r)
                bsp += e.optString("de") to e.optString("ru")
            }
        }
        A1BookGrammar(
            titelDe = o.optString("titel_de"),
            titelRu = o.optString("titel_ru"),
            erklaerungRu = o.optString("erklaerung_ru"),
            tableLines = lines,
            beispiele = bsp,
            merkeRu = o.optString("merke_ru"),
        )
    }

    private fun JSONArray.toWords(): List<A1BookWord> = (0 until length()).map { i ->
        val o = getJSONObject(i)
        A1BookWord(
            de = o.optString("de"),
            ru = o.optString("ru"),
            artikel = if (o.has("artikel")) o.optString("artikel") else null,
            plural = if (o.has("plural")) o.optString("plural") else null,
        )
    }

    private fun JSONArray.toTasks(): List<A1BookTask> = (0 until length()).map { i ->
        val o = getJSONObject(i)
        A1BookTask(
            id = o.optString("id"),
            typ = o.optString("typ"),
            anweisungRu = o.optString("anweisung_ru"),
            promptDe = if (o.has("prompt_de")) o.optString("prompt_de") else null,
            promptRu = if (o.has("prompt_ru")) o.optString("prompt_ru") else null,
            erwartetDe = o.optString("erwartet_de"),
            akzeptiert = o.optJSONArray("akzeptiert").toStringList(),
            hinweisRu = o.optString("hinweis_ru"),
        )
    }

    /** Системный промпт репетитора для выбранного урока. */
    fun buildPrompt(lesson: A1BookLesson): String {
        val grammar = lesson.grammatik.joinToString("\n\n") { g ->
            buildString {
                append("▸ ${g.titelRu} (${g.titelDe})\n")
                if (g.erklaerungRu.isNotBlank()) append("${g.erklaerungRu}\n")
                if (g.tableLines.isNotEmpty()) append(g.tableLines.joinToString("\n") { "   $it" } + "\n")
                g.beispiele.forEach { append("   • ${it.first} — ${it.second}\n") }
                if (g.merkeRu.isNotBlank() && g.merkeRu != "—") append("   ⚠ ${g.merkeRu}")
            }.trim()
        }
        val vocab = lesson.wortschatz.joinToString("\n") { w ->
            val art = w.artikel?.takeIf { it.isNotBlank() }?.let { "$it " } ?: ""
            val pl = w.plural?.takeIf { it.isNotBlank() && it != "—" }?.let { ", $it" } ?: ""
            "   • $art${w.de}$pl — ${w.ru}"
        }
        val tasks = lesson.voiceAufgaben.joinToString("\n") { t ->
            val q = t.promptDe ?: t.promptRu ?: ""
            val alt = if (t.akzeptiert.isNotEmpty()) " | также ок: ${t.akzeptiert.joinToString(" / ")}" else ""
            "   [${t.id}] (${t.anweisungRu}) Спроси: «$q» → ждём: «${t.erwartetDe}»$alt | подсказка: ${t.hinweisRu}"
        }
        return """
════════════════════════════════════════════════════════════
РОЛЬ: Русскоязычный репетитор немецкого языка, уровень A1.
УРОК ${lesson.nummer}: ${lesson.themaRu} (${lesson.themaDe}).
════════════════════════════════════════════════════════════

🎯 ЦЕЛИ УРОКА:
${lesson.lernziele.joinToString("\n") { "   • $it" }}

📐 ГРАММАТИКА УРОКА (объясняй ПРОСТО, по-русски, по одному пункту):
$grammar

📚 ЛЕКСИКА УРОКА:
$vocab

🗣 ЗАДАНИЯ ДЛЯ ОТРАБОТКИ (твой сценарий — иди по порядку):
$tasks

════════════════════════════════════════════════════════════
КАК ВЕСТИ УРОК:
1. СТАРТ: коротко поздоровайся по-русски, назови тему одним предложением и СРАЗУ объясни первый пункт грамматики (2–3 фразы).
2. Немецкий произноси МЕДЛЕННО и чётко. Объяснения и подсказки — по-русски.
3. После пункта дай 1–2 задания по нему: задай вопрос → дождись ответа ученика → коротко среагируй (Верно / Почти / правильный вариант) → при ошибке дай подсказку → следующий.
4. КОРОТКИЕ реплики. Один вопрос за раз. Не вываливай всё сразу. Темп живой.
5. Исправляй мягко, давай ученику шанс вспомнить. Хвали по делу, без лести.
6. Пройдя основные задания — подведи короткий итог (1–2 фразы) и скажи, что урок пройден.

ЖДИ СИСТЕМНОГО СИГНАЛА И НАЧНИ С ПРИВЕТСТВИЯ И ПЕРВОГО ПУНКТА.
        """.trimIndent()
    }
}

// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/data/grammar/A1GrammarCatalog.kt
//
// ФИЛОСОФИЯ:
// Правила НЕ изучаются отдельным блоком. Они сопровождают
// кластеры лемм. Каждое правило имеет:
//   - exposureThreshold: порог экспозиции (сколько раз паттерн
//     должен промелькнуть в речи Gemini до первого объяснения)
//   - shortExplanation: 1-2 фразы, которые Gemini произнесёт
//     ОДНИМ РАЗОМ по-русски, потом вернётся в ситуацию
//   - difficulty: порядок введения
//
// Алгоритм Gemini:
//   1. Использует правило в речи (Gemini слышит это в своей речи).
//     → timesHeardInContext++
//   2. Когда threshold достигнут → тест: "ты заметил что я говорю?"
//   3. Если ученик не объясняет сам → Gemini даёт shortExplanation
//   4. Отмечает wasIntroduced = true
//   5. Дальше корректирует ошибки ссылаясь на это правило
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.grammar

import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import kotlinx.serialization.json.Json

object A1GrammarCatalog {

    /**
     * Список всех правил A1 в порядке введения.
     * Это СТАТИЧНЫЕ данные, захардкожены в коде,
     * импортируются в БД при первом запуске.
     */
    val RULES: List<GrammarRuleA1Entity> = listOf(

        // ─── Фундамент (вводится очень рано) ───
        GrammarRuleA1Entity(
            id = "g01_personalpronomen_nom",
            nameDe = "Personalpronomen Nominativ",
            nameRu = "Личные местоимения",
            shortExplanation = "ich = я, du = ты (неформально), Sie = Вы (вежливо), er/sie/es = он/она/оно, wir = мы, ihr = вы (мн.), sie = они.",
            examplesJson = """["Ich bin Ruslan.", "Du bist nett.", "Er wohnt hier.", "Wir sind Freunde."]""",
            exposureThreshold = 5,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g02_sein_praesens",
            nameDe = "Verb 'sein' im Präsens",
            nameRu = "Глагол 'быть' в настоящем",
            shortExplanation = "Глагол sein (быть) особый: ich bin, du bist, er ist, wir sind, ihr seid, sie sind. Запомни формы — в немецком нет как в русском 'я есть = я' без sein не обойтись.",
            examplesJson = """["Ich bin müde.", "Du bist nett.", "Er ist mein Freund.", "Wir sind hier."]""",
            exposureThreshold = 8,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g03_haben_praesens",
            nameDe = "Verb 'haben' im Präsens",
            nameRu = "Глагол 'иметь' в настоящем",
            shortExplanation = "haben (иметь): ich habe, du hast, er hat, wir haben, ihr habt, sie haben. После haben — всегда Akkusativ: 'Ich habe einen Bruder'.",
            examplesJson = """["Ich habe Zeit.", "Du hast Hunger.", "Er hat ein Auto.", "Wir haben zwei Kinder."]""",
            exposureThreshold = 8,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g04_regulaere_verben",
            nameDe = "Regelmäßige Verben im Präsens",
            nameRu = "Правильные глаголы в настоящем",
            shortExplanation = "Правильные глаголы меняют окончание: ich -e, du -st, er -t, wir -en, ihr -t, sie -en. Пример 'kommen': ich komme, du kommst, er kommt.",
            examplesJson = """["Ich wohne in Köln.", "Du kommst aus Russland.", "Er arbeitet hier.", "Wir lernen Deutsch."]""",
            exposureThreshold = 10,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g05_w_fragen",
            nameDe = "W-Fragen",
            nameRu = "Вопросительные слова",
            shortExplanation = "Wer? = Кто, Was? = Что, Wo? = Где, Woher? = Откуда, Wohin? = Куда, Wann? = Когда, Wie? = Как, Warum? = Почему. В вопросе глагол идёт сразу после W-слова.",
            examplesJson = """["Wie heißt du?", "Wo wohnst du?", "Woher kommst du?", "Wann kommst du?"]""",
            exposureThreshold = 6,
            difficulty = 1,
        ),

        // ─── Числа, даты, время ───
        GrammarRuleA1Entity(
            id = "g06_zahlen_1_100",
            nameDe = "Zahlen 1-100",
            nameRu = "Числа 1-100",
            shortExplanation = "Числа 21-99 читаются наоборот: 21 = einundzwanzig (один-и-двадцать). Пишутся слитно. Запомни: hundert = 100.",
            examplesJson = """["21 = einundzwanzig", "35 = fünfunddreißig", "99 = neunundneunzig", "100 = hundert"]""",
            exposureThreshold = 5,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g07_artikel_nominativ",
            nameDe = "Bestimmter und unbestimmter Artikel (Nominativ)",
            nameRu = "Артикли: der/die/das и ein/eine",
            shortExplanation = "Каждое существительное имеет род. Определённый артикль: der (муж.), die (жен.), das (средн.). Неопределённый: ein (муж./ср.), eine (жен.). Учи слово ВМЕСТЕ с артиклем!",
            examplesJson = """["der Mann / ein Mann", "die Frau / eine Frau", "das Kind / ein Kind"]""",
            exposureThreshold = 10,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g08_akkusativ",
            nameDe = "Akkusativ",
            nameRu = "Винительный падеж (Akkusativ)",
            shortExplanation = "Akkusativ = прямой объект (кого? что?). Только МУЖСКОЙ артикль меняется: der → den, ein → einen. Die, das, eine НЕ меняются. 'Ich habe einen Bruder'.",
            examplesJson = """["Ich nehme den Kaffee.", "Ich habe einen Bruder.", "Ich sehe die Frau.", "Ich esse das Brot."]""",
            exposureThreshold = 12,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g09_negation",
            nameDe = "Negation mit 'nicht' und 'kein'",
            nameRu = "Отрицание: nicht и kein",
            shortExplanation = "nicht отрицает глагол или прилагательное: 'Ich komme nicht'. kein/keine отрицает существительное с артиклем: 'Ich habe keine Zeit' (НЕ 'nicht Zeit').",
            examplesJson = """["Ich komme nicht.", "Das ist nicht gut.", "Ich habe keine Zeit.", "Er hat kein Auto."]""",
            exposureThreshold = 8,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g10_possessiv",
            nameDe = "Possessivpronomen",
            nameRu = "Притяжательные местоимения",
            shortExplanation = "mein/meine = мой/моя, dein/deine = твой/твоя, sein/seine = его, ihr/ihre = её или их, unser/unsere = наш, euer/eure = ваш. Оканчиваются как ein/eine (по роду существительного).",
            examplesJson = """["Das ist mein Bruder.", "Meine Schwester heißt Anna.", "Sein Auto ist neu.", "Unsere Eltern wohnen hier."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g11_plural",
            nameDe = "Plural der Nomen",
            nameRu = "Множественное число",
            shortExplanation = "В немецком 5 способов образования множественного: -e, -er, -(e)n, -s, без изменения. Например: Kind → Kinder, Frau → Frauen, Auto → Autos, Lehrer → Lehrer. Учи с каждым словом!",
            examplesJson = """["das Kind → die Kinder", "die Frau → die Frauen", "das Auto → die Autos", "der Lehrer → die Lehrer"]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g12_modalverben",
            nameDe = "Modalverben: können, müssen, wollen, möchten",
            nameRu = "Модальные глаголы",
            shortExplanation = "Модальные меняют смысл действия: können (мочь), müssen (должен), wollen (хотеть), möchten (хотел бы — вежливо). Второй глагол идёт В КОНЕЦ в инфинитиве: 'Ich kann Deutsch sprechen'.",
            examplesJson = """["Ich kann gut schwimmen.", "Du musst jetzt gehen.", "Er will ein Auto kaufen.", "Ich möchte einen Kaffee."]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g13_trennbare_verben",
            nameDe = "Trennbare Verben",
            nameRu = "Отделяемые глаголы",
            shortExplanation = "У некоторых глаголов приставка отделяется: aufstehen (вставать) → 'Ich stehe um 7 auf' (auf уходит в конец предложения). Также: anrufen, abfahren, ankommen, einkaufen.",
            examplesJson = """["Ich stehe um 7 auf.", "Der Zug fährt um 8 ab.", "Ich rufe dich morgen an.", "Wir kaufen am Samstag ein."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g14_praeposition_dativ",
            nameDe = "Präpositionen + Dativ (mit, zu, bei, von, nach, aus)",
            nameRu = "Предлоги с Dativ",
            shortExplanation = "После mit, zu, bei, von, nach, aus всегда Dativ. Артикли в Dativ: der→dem, die→der, das→dem. 'Ich fahre mit dem Bus zu dem (= zum) Arzt'.",
            examplesJson = """["Ich fahre mit dem Bus.", "Ich gehe zu dem (zum) Arzt.", "Ich wohne bei meiner Mutter.", "Wir kommen aus der Ukraine."]""",
            exposureThreshold = 12,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g15_zeitangaben",
            nameDe = "Zeitangaben: am, im, um",
            nameRu = "Время: am, im, um",
            shortExplanation = "am + день: am Montag. im + месяц/сезон: im Januar, im Sommer. um + время: um 10 Uhr. Запомни формулу: am Tag, im Monat, um Uhrzeit.",
            examplesJson = """["Am Montag arbeite ich.", "Im Sommer fahren wir ans Meer.", "Um 8 Uhr beginnt der Kurs.", "Im Januar ist mein Geburtstag."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g16_satzbau",
            nameDe = "Satzbau: Verb auf Position 2",
            nameRu = "Порядок слов: глагол на 2-й позиции",
            shortExplanation = "Главное правило немецкого: глагол всегда на 2-м месте в обычном предложении. Если начинаешь с времени/места — глагол ВСЁ РАВНО 2-й. 'Heute gehe ich ins Kino', не 'Heute ich gehe'.",
            examplesJson = """["Ich gehe heute ins Kino.", "Heute gehe ich ins Kino.", "Morgen arbeite ich nicht.", "Um 8 Uhr stehe ich auf."]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g17_imperativ",
            nameDe = "Imperativ",
            nameRu = "Повелительное наклонение",
            shortExplanation = "Повеление: для du убери окончание -st ('Komm!', 'Geh!'), для Sie добавь местоимение ('Kommen Sie!', 'Gehen Sie!'). Для ihr: 'Kommt!', 'Geht!'.",
            examplesJson = """["Komm bitte!", "Sei leise!", "Kommen Sie bitte!", "Setzen Sie sich!"]""",
            exposureThreshold = 6,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g18_gern_lieber",
            nameDe = "gern, lieber, am liebsten",
            nameRu = "gern/lieber — выражение предпочтений",
            shortExplanation = "gern после глагола = 'охотно/люблю делать': 'Ich trinke gern Kaffee' = люблю кофе. lieber = 'больше люблю' (сравнение). am liebsten = 'больше всего'.",
            examplesJson = """["Ich trinke gern Kaffee.", "Ich esse lieber Fisch.", "Am liebsten esse ich Pizza.", "Sie schwimmt gern."]""",
            exposureThreshold = 6,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g19_weil",
            nameDe = "Nebensatz mit 'weil'",
            nameRu = "Придаточные с 'weil' (потому что)",
            shortExplanation = "После weil глагол УХОДИТ В КОНЕЦ: 'Ich komme nicht, weil ich krank BIN'. Не путай с denn (потому что), после denn нормальный порядок.",
            examplesJson = """["Ich komme nicht, weil ich krank bin.", "Er lernt Deutsch, weil er in Deutschland arbeitet.", "Sie ist müde, weil sie lange gearbeitet hat."]""",
            exposureThreshold = 8,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g20_perfekt_basics",
            nameDe = "Perfekt (Grundlagen)",
            nameRu = "Perfekt — прошедшее время",
            shortExplanation = "Perfekt = haben/sein (на 2-м месте) + Partizip II (в конце). Обычные глаголы: 'gelernt, gemacht, gesagt'. Движение/изменение состояния — с sein: 'bin gegangen, bin gefahren'.",
            examplesJson = """["Ich habe Deutsch gelernt.", "Du hast viel gearbeitet.", "Er ist nach Berlin gefahren.", "Wir sind zu Hause geblieben."]""",
            exposureThreshold = 10,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g21_praeposition_akkusativ",
            nameDe = "Präpositionen + Akkusativ (für, ohne, gegen, um)",
            nameRu = "Предлоги с Akkusativ",
            shortExplanation = "После für, ohne, gegen, um, durch — всегда Akkusativ. Артикли: der→den, ein→einen. 'Das ist für den Chef', 'Ich gehe ohne meinen Freund'.",
            examplesJson = """["Das ist für dich.", "Ich gehe ohne meinen Freund.", "Das Medikament ist gegen Kopfschmerzen.", "Wir laufen um den See."]""",
            exposureThreshold = 10,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g22_adjektiv_nach_sein",
            nameDe = "Adjektiv als Prädikat",
            nameRu = "Прилагательное после sein/werden",
            shortExplanation = "После sein, werden, bleiben прилагательное НЕ меняется (не склоняется): 'Das Haus ist GROSS' (не 'großes'). Склонение начинается только когда прилагательное перед существительным.",
            examplesJson = """["Das Haus ist groß.", "Der Kaffee ist heiß.", "Die Frau ist nett.", "Die Kinder sind müde."]""",
            exposureThreshold = 8,
            difficulty = 2,
        ),
    )

    /** Сериализованная версия для импорта в БД. */
    fun asJson(): String = Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(GrammarRuleA1Entity.serializer()),
        RULES
    )
}

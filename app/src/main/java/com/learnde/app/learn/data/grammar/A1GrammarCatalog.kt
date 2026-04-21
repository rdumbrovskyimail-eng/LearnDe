// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v3.1
// Путь: app/src/main/java/com/learnde/app/learn/data/grammar/A1GrammarCatalog.kt
//
// ИЗМЕНЕНИЯ ОТНОСИТЕЛЬНО v2:
//   + g00_alphabet (алфавит и произношение)
//   + g00_greetings (социальные формулы — обёртка)
//   + g15_uhrzeit (время: часы, минуты, halb, Viertel)
//   + g16_datum (дата: am 3. Mai)
//   + g23_pronomen_akk (mich/dich/ihn/sie в Akkusativ)
//   * g06_zahlen_1_100 → g06_zahlen_basis (расширено до 1 000 000)
//   * g15_zeitangaben → g15_uhrzeit (уточнено)
//   * g16_satzbau остался (не конфликтует с g16_datum, разные id)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.data.grammar

import com.learnde.app.learn.data.db.GrammarRuleA1Entity

object A1GrammarCatalog {

    val RULES: List<GrammarRuleA1Entity> = listOf(

        // ═══ Уровень 1: самые базовые ═══

        GrammarRuleA1Entity(
            id = "g00_alphabet",
            nameDe = "Das Alphabet + Umlaute + ß",
            nameRu = "Алфавит, умлауты и ß",
            shortExplanation = "В немецком 26 букв + 3 умлаута (ä, ö, ü) + ß (эсцет). Умлаут меняет звук: a→ä (а→э), o→ö (о→ё в 'мёд'), u→ü (у→ю в 'тюль'). ß = долгое 's' (Straße, Fuß). Важно уметь произнести своё имя по буквам.",
            examplesJson = """["A wie Anton", "B wie Berta", "Ä wie Ärger", "Ö wie Öl", "Ü wie Übel", "ß wie Straße", "Mein Name: M-Ü-L-L-E-R"]""",
            exposureThreshold = 3,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g00_greetings",
            nameDe = "Soziale Formeln",
            nameRu = "Социальные формулы",
            shortExplanation = "Приветствия и прощания зависят от времени суток и формальности: 'Hallo!' / 'Guten Morgen!' / 'Guten Tag!' / 'Guten Abend!' — для лица. 'Auf Wiedersehen' — лично прощаясь, 'Auf Wiederhören' — по телефону. 'Tschüss' — неформально.",
            examplesJson = """["Guten Morgen!", "Guten Tag!", "Tschüss!", "Auf Wiedersehen!", "Auf Wiederhören!"]""",
            exposureThreshold = 5,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g01_personalpronomen_nom",
            nameDe = "Personalpronomen Nominativ",
            nameRu = "Личные местоимения (именительный)",
            shortExplanation = "ich = я, du = ты (неформально), Sie = Вы (вежливо, заглавная!), er/sie/es = он/она/оно, wir = мы, ihr = вы (мн., неформально), sie = они. Sie и sie пишутся по-разному: Sie с большой = 'Вы', sie с малой = 'она' или 'они'.",
            examplesJson = """["Ich bin Ruslan.", "Du bist nett.", "Er wohnt hier.", "Wir sind Freunde.", "Sind Sie Herr Müller?"]""",
            exposureThreshold = 5,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g02_sein_praesens",
            nameDe = "Verb 'sein' im Präsens",
            nameRu = "Глагол 'быть' в настоящем",
            shortExplanation = "Sein (быть) — неправильный: ich bin, du bist, er/sie/es ist, wir sind, ihr seid, sie/Sie sind. В русском часто опускается ('я Иван'), в немецком — ОБЯЗАТЕЛЬНО: 'Ich BIN Iwan'.",
            examplesJson = """["Ich bin müde.", "Du bist nett.", "Er ist mein Freund.", "Wir sind hier.", "Seid ihr bereit?"]""",
            exposureThreshold = 8,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g03_haben_praesens",
            nameDe = "Verb 'haben' im Präsens",
            nameRu = "Глагол 'иметь' в настоящем",
            shortExplanation = "Haben (иметь): ich habe, du hast, er hat, wir haben, ihr habt, sie haben. После haben — всегда Akkusativ: 'Ich habe einen Bruder', 'Ich habe Zeit' (без артикля — тоже Akk).",
            examplesJson = """["Ich habe Zeit.", "Du hast Hunger.", "Er hat ein Auto.", "Wir haben zwei Kinder."]""",
            exposureThreshold = 8,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g04_regulaere_verben",
            nameDe = "Regelmäßige Verben im Präsens",
            nameRu = "Правильные глаголы в настоящем",
            shortExplanation = "Правильные глаголы: основа + окончание. ich -e, du -st, er/sie/es -t, wir -en, ihr -t, sie/Sie -en. Пример 'kommen': ich komme, du kommst, er kommt, wir kommen, ihr kommt, sie kommen. Сильные глаголы (fahren, sehen, essen) меняют корень в du/er: du fährst, er sieht, er isst.",
            examplesJson = """["Ich wohne in Köln.", "Du kommst aus Russland.", "Er arbeitet hier.", "Wir lernen Deutsch.", "Er fährt (NICHT fahrt!) nach Berlin."]""",
            exposureThreshold = 10,
            difficulty = 1,
        ),

        GrammarRuleA1Entity(
            id = "g05_w_fragen",
            nameDe = "W-Fragen",
            nameRu = "Вопросительные слова",
            shortExplanation = "W-слова: Wer? (кто) Was? (что) Wo? (где) Woher? (откуда) Wohin? (куда) Wann? (когда) Wie? (как) Warum? (почему) Wie viel? (сколько). В вопросе с W-словом: W-слово + глагол (2-е место) + подлежащее. 'Wo wohnst du?'",
            examplesJson = """["Wie heißt du?", "Wo wohnst du?", "Woher kommst du?", "Wann kommst du?", "Wie viel kostet das?"]""",
            exposureThreshold = 6,
            difficulty = 1,
        ),

        // ═══ Уровень 2: числа, артикли ═══

        GrammarRuleA1Entity(
            id = "g06_zahlen_basis",
            nameDe = "Zahlen: 1 bis 1 000 000",
            nameRu = "Числа от 1 до миллиона",
            shortExplanation = "1-12 — отдельные слова (eins, zwei… zwölf). 13-19 — число + zehn (dreizehn). Десятки: zwanzig, dreißig, vierzig… 21-99 ЧИТАЕТСЯ НАОБОРОТ: 21 = einundzwanzig (ОДИН-И-ДВАДЦАТЬ). Сотни: hundert, тысячи: tausend. В Германии запятая = наш десятичный знак: 10,5 Euro. Точка = разделитель тысяч: 10.000 = десять тысяч.",
            examplesJson = """["21 = einundzwanzig", "35 = fünfunddreißig", "99 = neunundneunzig", "100 = (ein)hundert", "2 534 = zweitausendfünfhundertvierunddreißig", "10,5 € = zehn Euro fünfzig"]""",
            exposureThreshold = 8,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g07_artikel_nominativ",
            nameDe = "Bestimmter und unbestimmter Artikel (Nominativ)",
            nameRu = "Артикли: der/die/das и ein/eine",
            shortExplanation = "Каждое существительное имеет род: мужской (der Mann), женский (die Frau), средний (das Kind). Определённый артикль (известная вещь): der / die / das. Неопределённый (любая, впервые): ein (м./ср.) / eine (ж.). Во мн. числе: die (все), без eine. ПРАВИЛО: учи слово ВМЕСТЕ с артиклем!",
            examplesJson = """["der Mann / ein Mann", "die Frau / eine Frau", "das Kind / ein Kind", "die Kinder (pl.)"]""",
            exposureThreshold = 10,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g08_akkusativ",
            nameDe = "Akkusativ",
            nameRu = "Винительный падеж (Akkusativ)",
            shortExplanation = "Akkusativ = прямой объект (кого? что?). Меняется ТОЛЬКО мужской артикль: der → den, ein → einen, mein → meinen. Женский, средний, множественный НЕ меняются. После haben, sehen, kaufen, nehmen, essen и др. — всегда Akkusativ.",
            examplesJson = """["Ich nehme DEN Kaffee.", "Ich habe EINEN Bruder.", "Ich sehe DIE Frau (не меняется).", "Ich esse DAS Brot (не меняется)."]""",
            exposureThreshold = 12,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g09_negation",
            nameDe = "Negation: nicht und kein",
            nameRu = "Отрицание: nicht и kein",
            shortExplanation = "NICHT отрицает глагол, прилагательное или наречие: 'Ich komme NICHT', 'Das ist NICHT gut'. KEIN отрицает существительное с неопределённым артиклем или без артикля: 'Ich habe KEIN Auto', 'Er hat KEINE Zeit'. НЕЛЬЗЯ сказать 'nicht Zeit' — только 'keine Zeit'.",
            examplesJson = """["Ich komme nicht.", "Das ist nicht gut.", "Ich habe keine Zeit.", "Er hat kein Auto."]""",
            exposureThreshold = 8,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g10_possessiv",
            nameDe = "Possessivpronomen",
            nameRu = "Притяжательные местоимения",
            shortExplanation = "mein = мой, dein = твой, sein = его, ihr = её/их, unser = наш, euer = ваш, Ihr = Ваш (вежл.). Окончания как у ein/eine: мужской/средний без окончания (mein Vater, mein Kind), женский и мн. с -e (meine Mutter, meine Eltern). В Akk. мужской получает -en: 'Ich sehe MEINEN Vater'.",
            examplesJson = """["Das ist mein Bruder.", "Meine Schwester heißt Anna.", "Sein Auto ist neu.", "Unsere Eltern wohnen hier.", "Ich sehe meinen Vater (Akk)."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g11_plural",
            nameDe = "Plural der Nomen",
            nameRu = "Множественное число существительных",
            shortExplanation = "В немецком 5 типов окончаний мн.ч.: -e (Tag→Tage), -er (Kind→Kinder, часто с умлаутом: Mann→Männer), -(e)n (Frau→Frauen), -s (Auto→Autos, иностранные), без изменений (Lehrer→Lehrer). Артикль мн.ч. ВСЕГДА die. Нет единого правила — учи форму с каждым словом.",
            examplesJson = """["das Kind → die Kinder", "die Frau → die Frauen", "das Auto → die Autos", "der Lehrer → die Lehrer", "der Mann → die Männer"]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g12_modalverben",
            nameDe = "Modalverben: können, müssen, wollen, möchten, dürfen, sollen, mögen",
            nameRu = "Модальные глаголы",
            shortExplanation = "Модальный глагол + инфинитив ОСНОВНОГО глагола в конец предложения. können (мочь/уметь), müssen (должен), wollen (хотеть — резко), möchten (хотел бы — вежливо), dürfen (можно/разрешено), sollen (следует/должен по поручению), mögen (любить что-то). Спряжение: ich kann, du kannst, er kann (БЕЗ -t в er!).",
            examplesJson = """["Ich kann gut schwimmen.", "Du musst jetzt gehen.", "Er will ein Auto kaufen.", "Ich möchte einen Kaffee.", "Darf ich rauchen?"]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g13_trennbare_verben",
            nameDe = "Trennbare Verben",
            nameRu = "Отделяемые глаголы",
            shortExplanation = "У некоторых глаголов приставка ОТДЕЛЯЕТСЯ в настоящем и уходит В КОНЕЦ предложения: aufstehen → 'Ich stehe um 7 AUF'. Приставки, которые всегда отделяются: auf-, an-, ab-, aus-, ein-, mit-, zu-, vor-, her-, hin-. Пример: anrufen (звонить) → 'Ich rufe dich morgen AN'.",
            examplesJson = """["Ich stehe um 7 auf.", "Der Zug fährt um 8 ab.", "Ich rufe dich morgen an.", "Wir kaufen am Samstag ein.", "Er zieht die Jacke aus."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        // ═══ Уровень 3: Dativ, время, синтаксис ═══

        GrammarRuleA1Entity(
            id = "g14_praeposition_dativ",
            nameDe = "Präpositionen + Dativ (mit, zu, bei, von, nach, aus, seit)",
            nameRu = "Предлоги с Dativ",
            shortExplanation = "После mit, zu, bei, von, nach, aus, seit ВСЕГДА Dativ. Артикли в Dativ: der → dem, die → der, das → dem, мн. → den (+ -n к слову). Слияния: zu+dem = zum, zu+der = zur, bei+dem = beim, von+dem = vom, in+dem = im, an+dem = am. 'Ich fahre MIT DEM Bus ZUM (=zu dem) Arzt'.",
            examplesJson = """["Ich fahre mit dem Bus.", "Ich gehe zum Arzt.", "Ich wohne bei meiner Mutter.", "Wir kommen aus der Ukraine.", "Seit zwei Jahren wohne ich hier."]""",
            exposureThreshold = 12,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g15_uhrzeit",
            nameDe = "Uhrzeit: offiziell und umgangssprachlich",
            nameRu = "Время: официально и разговорно",
            shortExplanation = "ОФИЦИАЛЬНО (вокзал, ТВ): 24-часовой формат. '14:30 Uhr' = 'vierzehn Uhr dreißig'. РАЗГОВОРНО: 12-часовой, + halb, Viertel. 'halb drei' = 2:30 (ПОЛОВИНА ТРЕТЬЕГО, не третьего!). 'Viertel nach drei' = 3:15. 'Viertel vor drei' = 2:45. Вопрос: 'Wie spät ist es?' или 'Wie viel Uhr ist es?'. Ответ: 'Es ist…' или 'Um … Uhr'.",
            examplesJson = """["Es ist acht Uhr.", "Um halb neun (8:30).", "Viertel nach drei (3:15).", "Viertel vor zehn (9:45).", "Offiziell: 15:30 Uhr.", "Von neun bis siebzehn Uhr arbeite ich."]""",
            exposureThreshold = 8,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g16_datum",
            nameDe = "Datum: Tag, Monat, Jahr",
            nameRu = "Дата: день, месяц, год",
            shortExplanation = "Формула: der + порядковое число (с -te/-ten) + Monat. 'Heute ist DER dritte Mai' (именит.), 'Am dritten Mai' (с AM — датив). Даты 1.-19. формируются с -te (dritte, vierte…), от 20. — с -ste (zwanzigste, einundzwanzigste). Годы читаются как в русском: 2024 = zweitausendvierundzwanzig. 19XX = neunzehnhundert… (1985 = neunzehnhundertfünfundachtzig).",
            examplesJson = """["Heute ist der 15. Oktober.", "Am dritten Mai habe ich Geburtstag.", "Ich bin am 12. Juni 1990 geboren.", "Vom 1. bis zum 15. August habe ich Urlaub."]""",
            exposureThreshold = 6,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g16_satzbau",
            nameDe = "Satzbau: Verb auf Position 2",
            nameRu = "Порядок слов: глагол на 2-й позиции",
            shortExplanation = "ГЛАВНОЕ правило немецкого: спрягаемый глагол ВСЕГДА на 2-м месте в обычном предложении. Если предложение начинается с времени/места, подлежащее перемещается на 3-е место. 'Ich gehe heute ins Kino' = 'Heute GEHE ich ins Kino' (но НЕ 'Heute ich gehe'!). Если есть модальный глагол + инфинитив, инфинитив идёт в конец: 'Ich muss heute viel arbeiten'.",
            examplesJson = """["Ich gehe heute ins Kino.", "Heute gehe ich ins Kino.", "Morgen arbeite ich nicht.", "Um 8 Uhr stehe ich auf.", "Ich muss heute arbeiten."]""",
            exposureThreshold = 10,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g17_imperativ",
            nameDe = "Imperativ",
            nameRu = "Повелительное наклонение",
            shortExplanation = "ПОВЕЛЕНИЕ разное для du/ihr/Sie: для du — основа БЕЗ окончания ('Komm!', 'Geh!', 'Iss!'), для ihr — основа + t ('Kommt!', 'Geht!'), для Sie — инфинитив + Sie ('Kommen Sie!', 'Gehen Sie!'). Исключение: sein → 'Sei!' (du), 'Seien Sie!' (Sie).",
            examplesJson = """["Komm bitte!", "Sei leise!", "Kommen Sie bitte!", "Setzen Sie sich!", "Gebt mir das Buch!"]""",
            exposureThreshold = 6,
            difficulty = 3,
        ),

        GrammarRuleA1Entity(
            id = "g18_gern_lieber",
            nameDe = "gern, lieber, am liebsten",
            nameRu = "gern / lieber / am liebsten — предпочтения",
            shortExplanation = "GERN после глагола = 'с удовольствием/люблю делать': 'Ich trinke gern Kaffee' = люблю кофе. LIEBER = 'предпочитаю/больше люблю' (сравнение): 'Ich trinke lieber Tee als Kaffee'. AM LIEBSTEN = 'больше всего люблю': 'Am liebsten trinke ich Wasser'. Важно: gern — НАРЕЧИЕ, не путать с прилагательным mögen ('Ich mag Kaffee').",
            examplesJson = """["Ich trinke gern Kaffee.", "Ich esse lieber Fisch als Fleisch.", "Am liebsten esse ich Pizza.", "Sie schwimmt gern."]""",
            exposureThreshold = 6,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g19_weil",
            nameDe = "Nebensatz mit 'weil'",
            nameRu = "Придаточные с 'weil' (потому что)",
            shortExplanation = "После WEIL спрягаемый глагол УХОДИТ В КОНЕЦ: 'Ich komme nicht, weil ich krank BIN'. НЕ путай с DENN ('потому что, ведь') — после denn НОРМАЛЬНЫЙ порядок: 'Ich komme nicht, denn ich BIN krank'. Weil — подчинительный союз, denn — сочинительный.",
            examplesJson = """["Ich komme nicht, weil ich krank bin.", "Er lernt Deutsch, weil er in Deutschland arbeitet.", "Sie ist müde, weil sie viel gearbeitet hat."]""",
            exposureThreshold = 8,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g20_perfekt_basics",
            nameDe = "Perfekt (Grundlagen)",
            nameRu = "Perfekt — прошедшее разговорное",
            shortExplanation = "Perfekt = вспомог.глагол haben/sein (2-е место) + Partizip II в конце. Регулярные: ge- + основа + -t (lernen → gelernt, machen → gemacht). С sein — глаголы движения и изменения состояния: gehen → ist gegangen, fahren → ist gefahren, kommen → ist gekommen, bleiben → ist geblieben. Всё остальное — с haben.",
            examplesJson = """["Ich habe Deutsch gelernt.", "Du hast viel gearbeitet.", "Er ist nach Berlin gefahren.", "Wir sind zu Hause geblieben."]""",
            exposureThreshold = 10,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g21_praeposition_akkusativ",
            nameDe = "Präpositionen + Akkusativ (für, ohne, gegen, um, durch)",
            nameRu = "Предлоги с Akkusativ",
            shortExplanation = "После FÜR (для), OHNE (без), GEGEN (против), UM (вокруг/в — о времени), DURCH (через) — ВСЕГДА Akkusativ. Артикли: der→den, ein→einen, mein→meinen. Женский и средний НЕ меняются.",
            examplesJson = """["Das ist für dich.", "Ich gehe ohne meinen Freund.", "Das Medikament ist gegen Kopfschmerzen.", "Wir laufen um den See.", "Durch den Park."]""",
            exposureThreshold = 10,
            difficulty = 4,
        ),

        GrammarRuleA1Entity(
            id = "g22_adjektiv_nach_sein",
            nameDe = "Adjektiv als Prädikat",
            nameRu = "Прилагательное после sein/werden/bleiben",
            shortExplanation = "После sein, werden, bleiben прилагательное НЕ СКЛОНЯЕТСЯ: 'Das Haus ist GROSS' (не 'großes'!), 'Die Frau ist nett', 'Die Kinder sind müde'. Склонение начинается ТОЛЬКО когда прилагательное стоит ПЕРЕД существительным ('das große Haus'). Для A1 этого достаточно.",
            examplesJson = """["Das Haus ist groß.", "Der Kaffee ist heiß.", "Die Frau ist nett.", "Die Kinder sind müde."]""",
            exposureThreshold = 8,
            difficulty = 2,
        ),

        GrammarRuleA1Entity(
            id = "g23_pronomen_akk",
            nameDe = "Personalpronomen Akkusativ",
            nameRu = "Личные местоимения в Akkusativ (меня, тебя, его…)",
            shortExplanation = "В Akkusativ меняются: ich→mich, du→dich, er→ihn, sie→sie (не меняется!), es→es, wir→uns, ihr→euch, sie/Sie→sie/Sie. В Dativ (после mit/zu и т.д.): mir, dir, ihm, ihr, ihm, uns, euch, ihnen/Ihnen. Рефлексив: 'Ich wasche MICH' (мою себя).",
            examplesJson = """["Ich sehe dich.", "Sie liebt ihn.", "Er hilft mir (Dat).", "Ich wasche mich.", "Kommst du mit uns?"]""",
            exposureThreshold = 10,
            difficulty = 4,
        ),
    )
}

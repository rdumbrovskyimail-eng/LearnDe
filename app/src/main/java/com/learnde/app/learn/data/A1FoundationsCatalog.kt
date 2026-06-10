package com.learnde.app.learn.data

import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.util.AppLogger

object A1FoundationsCatalog {

    private const val CATEGORY = "Фундамент"

    /**
     * Вставить отсутствующие фундаментальные кластеры.
     * Вызывать из A1DataImporter ПОСЛЕ импорта a1_clusters.json.
     */
    suspend fun ensureInserted(dao: A1ClusterDao, logger: AppLogger) {
        val existing = dao.getAllOrdered().map { it.id }.toSet()
        val missing = ALL.filter { it.id !in existing }
        if (missing.isEmpty()) {
            logger.d("Foundations: все ${ALL.size} кластеров уже на месте")
            return
        }
        dao.insertClusters(missing)
        logger.d("Foundations: добавлено ${missing.size} фундаментальных кластеров")
    }

    private fun cluster(
        id: String,
        titleDe: String,
        titleRu: String,
        lemmas: List<String>,
        anchor: String,
        grammarFocus: String,
        scenario: String,
        difficulty: Int = 1,
        prerequisites: List<String> = emptyList(),
    ) = ClusterA1Entity(
        id = id,
        titleDe = titleDe,
        titleRu = titleRu,
        lemmasJson = lemmas.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
        anchorLemma = anchor,
        grammarRuleId = null,
        grammarFocus = grammarFocus,
        scenarioHint = scenario,
        category = CATEGORY,
        difficulty = difficulty,
        prerequisitesJson = prerequisites.joinToString(prefix = "[", postfix = "]") { "\"$it\"" },
        isUnlocked = prerequisites.isEmpty(),
    )

    val ALL: List<ClusterA1Entity> = listOf(

        // ───────────────────────── АЛФАВИТ И ЗВУКИ ─────────────────────────

        cluster(
            id = "f01_alphabet",
            titleDe = "Das Alphabet",
            titleRu = "Алфавит A–Z",
            lemmas = listOf("das Alphabet", "der Buchstabe", "buchstabieren", "heißen", "schreiben"),
            anchor = "das Alphabet",
            grammarFocus = "Названия букв",
            scenario = "Тренировка алфавита. Произнеси буквы группами по 5-6 (A B C D E…), " +
                "ученик повторяет за тобой каждую группу. Затем выборочная проверка: называй " +
                "случайные буквы, ученик произносит. Особое внимание: V (фау), W (вэ), " +
                "J (йот), Y (юпсилон), Z (цэт). Финал: ученик произносит алфавит сам.",
        ),

        cluster(
            id = "f02_spelling",
            titleDe = "Buchstabieren",
            titleRu = "Диктовка по буквам (имя, e-mail)",
            lemmas = listOf("buchstabieren", "der Name", "der Vorname", "der Nachname", "die E-Mail", "wiederholen", "langsam"),
            anchor = "buchstabieren",
            grammarFocus = "Wie schreibt man das?",
            scenario = "Ролевая игра «регистрация»: ты сотрудник, ученик называет и диктует " +
                "по буквам своё имя и фамилию (можно вымышленные немецкие: Müller, Schmidt). " +
                "Затем наоборот: ты диктуешь немецкое слово по буквам, ученик его собирает " +
                "и произносит. 3-4 раунда.",
            prerequisites = listOf("f01_alphabet"),
        ),

        cluster(
            id = "f03_phonetics",
            titleDe = "Aussprache: ä ö ü ß, sch, ch, ei, eu",
            titleRu = "Особые звуки немецкого",
            lemmas = listOf("schön", "die Tür", "das Mädchen", "die Straße", "ich", "das Buch", "nein", "Deutschland", "heute"),
            anchor = "schön",
            grammarFocus = "Umlaute и диграфы",
            scenario = "Фонетическая тренировка. По одному звуку за раз: ä (Mädchen), " +
                "ö (schön), ü (Tür), ß (Straße), sch (Schule), мягкое ch (ich), твёрдое ch " +
                "(Buch), ei = «ай» (nein), eu = «ой» (heute). Для каждого: произнеси " +
                "медленно → ученик повторяет 2 раза → слово в коротком словосочетании. " +
                "Поправляй произношение через PRONUNCIATION_DRILL.",
            prerequisites = listOf("f01_alphabet"),
        ),

        // ───────────────────────── ЧИСЛА ─────────────────────────

        cluster(
            id = "f04_numbers_0_12",
            titleDe = "Zahlen 0–12",
            titleRu = "Числа 0–12",
            lemmas = listOf("null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn", "elf", "zwölf"),
            anchor = "eins",
            grammarFocus = "Количественные числительные",
            scenario = "Счёт 0-12: сначала хором по порядку (ты — ученик повторяет), " +
                "затем вразброс («Скажи: семь», «Какое число между vier и sechs?»), " +
                "затем мини-математика: «zwei plus drei?» — ученик отвечает по-немецки.",
        ),

        cluster(
            id = "f05_numbers_13_19",
            titleDe = "Zahlen 13–19",
            titleRu = "Числа 13–19",
            lemmas = listOf("dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn"),
            anchor = "dreizehn",
            grammarFocus = "Образование -zehn; sechzehn/siebzehn без -s/-en",
            scenario = "Покажи закономерность: drei+zehn=dreizehn. Особые формы: sechzehn " +
                "(не sechszehn!), siebzehn (не siebenzehn!). Дриллинг вразброс, затем " +
                "ученик называет возраст: «Ich bin … Jahre alt» с любым числом 13-19.",
            prerequisites = listOf("f04_numbers_0_12"),
        ),

        cluster(
            id = "f06_numbers_20_100",
            titleDe = "Zahlen 20–100",
            titleRu = "Числа 20–100 («задом наперёд»)",
            lemmas = listOf("zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig", "hundert", "und"),
            anchor = "zwanzig",
            grammarFocus = "einundzwanzig: единицы ПЕРЕД десятками через und",
            scenario = "Главная ловушка немецких чисел: 21 = ein-und-zwanzig (один-и-двадцать). " +
                "Объясни принцип на 21, 32, 45. Затем дриллинг: называй числа по-русски — " +
                "ученик по-немецки (25, 47, 63, 89, 99). Затем наоборот: ты по-немецки — " +
                "ученик переводит. Финал: ученик называет свой год рождения по частям.",
            prerequisites = listOf("f05_numbers_13_19"),
        ),

        cluster(
            id = "f07_ordinals",
            titleDe = "Ordnungszahlen und Datum",
            titleRu = "Порядковые числа и дата",
            lemmas = listOf("der erste", "der zweite", "der dritte", "das Datum", "der Geburtstag", "heute", "morgen"),
            anchor = "der erste",
            grammarFocus = "-te (до 19) / -ste (от 20); erste/dritte/siebte — исключения",
            scenario = "Порядковые: 1.-19. → -te (der vierte), от 20. → -ste (der zwanzigste). " +
                "Исключения: erste, dritte, siebte. Тренировка на датах: «Der wievielte ist " +
                "heute?» — «Heute ist der …». Ученик называет дату своего рождения: " +
                "«Mein Geburtstag ist am …».",
            prerequisites = listOf("f06_numbers_20_100"),
            difficulty = 2,
        ),

        // ───────────────────────── ВРЕМЯ И КАЛЕНДАРЬ ─────────────────────────

        cluster(
            id = "f08_weekdays",
            titleDe = "Wochentage",
            titleRu = "Дни недели",
            lemmas = listOf("der Montag", "der Dienstag", "der Mittwoch", "der Donnerstag", "der Freitag", "der Samstag", "der Sonntag", "die Woche", "das Wochenende"),
            anchor = "der Montag",
            grammarFocus = "am + день недели",
            scenario = "Дни недели по порядку, затем вразброс. Конструкция am Montag. " +
                "Вопросы: «Welcher Tag ist heute?», «Was machst du am Samstag?» — " +
                "ученик отвечает простыми фразами из выученной лексики.",
        ),

        cluster(
            id = "f09_months_seasons",
            titleDe = "Monate und Jahreszeiten",
            titleRu = "Месяцы и времена года",
            lemmas = listOf("der Januar", "der Februar", "der März", "der April", "der Mai", "der Juni", "der Juli", "der August", "der September", "der Oktober", "der November", "der Dezember", "der Frühling", "der Sommer", "der Herbst", "der Winter"),
            anchor = "der Januar",
            grammarFocus = "im + месяц/сезон",
            scenario = "Месяцы группами по сезонам: Winter (Dezember-Februar) и т.д. " +
                "Конструкция im Juli, im Sommer. Вопросы: «Wann hast du Geburtstag?» — " +
                "«Im …». «Welche Jahreszeit magst du?»",
            prerequisites = listOf("f08_weekdays"),
        ),

        cluster(
            id = "f10_clock",
            titleDe = "Die Uhrzeit",
            titleRu = "Который час",
            lemmas = listOf("die Uhr", "die Stunde", "die Minute", "halb", "das Viertel", "vor", "nach", "spät"),
            anchor = "die Uhr",
            grammarFocus = "Wie spät ist es? halb = ПОЛОВИНА СЛЕДУЮЩЕГО часа",
            scenario = "Время по часам. Сначала ровные часы (Es ist drei Uhr). Затем " +
                "ГЛАВНАЯ ловушка: halb vier = 3:30 (половина ЧЕТВЁРТОГО, а не половина " +
                "после трёх — как в русском, повезло!). Viertel nach drei = 3:15, " +
                "Viertel vor vier = 3:45. Дриллинг: называй время цифрами по-русски — " +
                "ученик по-немецки. 6-8 раундов с разным временем.",
            prerequisites = listOf("f06_numbers_20_100"),
            difficulty = 2,
        ),

        // ───────────────────────── ДЕНЬГИ И ДИКТОВКА ─────────────────────────

        cluster(
            id = "f11_money",
            titleDe = "Geld und Preise",
            titleRu = "Деньги и цены",
            lemmas = listOf("das Geld", "der Euro", "der Cent", "kosten", "der Preis", "teuer", "billig", "bezahlen"),
            anchor = "kosten",
            grammarFocus = "Was kostet …? Das kostet … Euro …",
            scenario = "Ролевая игра «магазин»: ты продавец, называешь цены " +
                "(zwei Euro fünfzig; vierzehn Euro neunzig). Ученик переспрашивает " +
                "«Wie bitte?», повторяет цену, спрашивает «Was kostet das?» про " +
                "разные предметы. Поменяйтесь ролями: ученик — продавец.",
            prerequisites = listOf("f06_numbers_20_100"),
            difficulty = 2,
        ),

        cluster(
            id = "f12_phone_address",
            titleDe = "Telefonnummer und Adresse",
            titleRu = "Телефон и адрес (диктовка)",
            lemmas = listOf("die Telefonnummer", "das Handy", "die Adresse", "die Straße", "die Hausnummer", "die Postleitzahl", "wohnen"),
            anchor = "die Telefonnummer",
            grammarFocus = "Числа в потоке речи; Wie ist deine Telefonnummer?",
            scenario = "Аудирование чисел в потоке: продиктуй телефонный номер по " +
                "2-3 цифры (null drei null — zwölf — fünfundvierzig…), ученик повторяет " +
                "и «записывает». Затем ученик диктует свой (вымышленный) номер. " +
                "Адрес: «Ich wohne in der Goethestraße 15». Komбинация диктовки букв " +
                "(улица) и чисел (дом, индекс) — это реальный экзаменационный формат A1.",
            prerequisites = listOf("f02_spelling", "f06_numbers_20_100"),
            difficulty = 2,
        ),
    )
}

// ────────────────────────────────────────────────────────────
//  ПРИМЕЧАНИЕ ПО DAO
// ────────────────────────────────────────────────────────────
// Требуется метод вставки списком. Если его нет в A1ClusterDao:
//
//   @Insert(onConflict = OnConflictStrategy.IGNORE)
//   suspend fun insertClusters(clusters: List<ClusterA1Entity>)
//
// OnConflictStrategy.IGNORE — двойная страховка идемпотентности.
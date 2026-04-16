package com.learnde.app.domain.avatar

/**
 * PhonemeData — Артикуляционные профили фонем для русского и немецкого.
 *
 * Каждая фонема описывается 6 артикуляционными параметрами [0..1]:
 *   jawOpen    — открытие челюсти (JawOpen)
 *   lipRound   — округление губ (MouthPucker + MouthFunnel)
 *   lipSpread  — растяжение губ (MouthStretchL/R)
 *   lipClose   — смыкание губ (MouthClose + MouthPressL/R)
 *   tongueUp   — подъём языка (MouthShrugLower — proxy)
 *   teethClose — сближение зубов (для С/З)
 *
 * + VisemeClass для маппинга в VisemeGroup (gate)
 * + durationMs для оценочного тайминга
 * + isVoiced для различия звонких/глухих
 */
object PhonemeData {

    enum class VisemeClass {
        // Гласные
        OPEN_VOWEL,      // А
        MID_VOWEL,       // Э, Е
        CLOSE_FRONT,     // И
        CLOSE_BACK,      // У
        MID_ROUND,       // О
        CLOSE_ROUND,     // Ü, Ö
        CENTRAL,         // Ы, schwa
        REDUCED,         // безударные

        // Согласные
        BILABIAL_STOP,   // П, Б
        BILABIAL_NASAL,  // М
        LABIODENTAL,     // Ф, В
        DENTAL_STOP,     // Т, Д, Н
        DENTAL_FRIC,     // С, З, Ц
        POSTALVEOLAR,    // Ш, Ж, Ч, Щ
        PALATAL,         // Й, ich-Laut
        VELAR_STOP,      // К, Г
        VELAR_FRIC,      // Х, ach-Laut
        UVULAR,          // Р
        LATERAL,         // Л
        GLOTTAL,         // H, Knacklaut
        SILENCE,
    }

    data class PhonemeProfile(
        val visemeClass: VisemeClass,
        val jawOpen: Float,
        val lipRound: Float,
        val lipSpread: Float,
        val lipClose: Float,
        val tongueUp: Float,
        val teethClose: Float,
        val durationMs: Int,
        val isVoiced: Boolean,
    )

    // ══════════════════════════════════════════════════════════════════════
    //  РУССКИЕ ФОНЕМЫ
    // ══════════════════════════════════════════════════════════════════════

    val RU = mapOf(
        // ── Гласные ────────────────────────────────────────────────────
        "а" to PhonemeProfile(VisemeClass.OPEN_VOWEL,     0.42f, 0.00f, 0.10f, 0.00f, 0.00f, 0.00f, 120, true),
        "о" to PhonemeProfile(VisemeClass.MID_ROUND,      0.32f, 0.45f, 0.00f, 0.00f, 0.00f, 0.00f, 110, true),
        "у" to PhonemeProfile(VisemeClass.CLOSE_BACK,     0.18f, 0.55f, 0.00f, 0.00f, 0.00f, 0.00f, 100, true),
        "э" to PhonemeProfile(VisemeClass.MID_VOWEL,      0.35f, 0.00f, 0.18f, 0.00f, 0.12f, 0.00f, 110, true),
        "и" to PhonemeProfile(VisemeClass.CLOSE_FRONT,    0.12f, 0.00f, 0.35f, 0.00f, 0.30f, 0.00f, 100, true),
        "ы" to PhonemeProfile(VisemeClass.CENTRAL,        0.16f, 0.00f, 0.15f, 0.00f, 0.22f, 0.00f, 100, true),

        // ── Взрывные ────────────────────────────────────────────────────
        "п" to PhonemeProfile(VisemeClass.BILABIAL_STOP,  0.08f, 0.00f, 0.00f, 0.82f, 0.00f, 0.00f,  25, false),
        "б" to PhonemeProfile(VisemeClass.BILABIAL_STOP,  0.10f, 0.00f, 0.00f, 0.78f, 0.00f, 0.00f,  30, true),
        "т" to PhonemeProfile(VisemeClass.DENTAL_STOP,    0.14f, 0.00f, 0.08f, 0.00f, 0.55f, 0.22f,  25, false),
        "д" to PhonemeProfile(VisemeClass.DENTAL_STOP,    0.16f, 0.00f, 0.06f, 0.00f, 0.50f, 0.18f,  30, true),
        "к" to PhonemeProfile(VisemeClass.VELAR_STOP,     0.18f, 0.00f, 0.00f, 0.00f, 0.10f, 0.00f,  25, false),
        "г" to PhonemeProfile(VisemeClass.VELAR_STOP,     0.20f, 0.00f, 0.00f, 0.00f, 0.08f, 0.00f,  30, true),

        // ── Фрикативные ────────────────────────────────────────────────
        "с" to PhonemeProfile(VisemeClass.DENTAL_FRIC,    0.06f, 0.00f, 0.28f, 0.00f, 0.40f, 0.55f,  80, false),
        "з" to PhonemeProfile(VisemeClass.DENTAL_FRIC,    0.08f, 0.00f, 0.25f, 0.00f, 0.38f, 0.50f,  70, true),
        "ш" to PhonemeProfile(VisemeClass.POSTALVEOLAR,   0.10f, 0.30f, 0.00f, 0.00f, 0.45f, 0.35f,  90, false),
        "ж" to PhonemeProfile(VisemeClass.POSTALVEOLAR,   0.12f, 0.28f, 0.00f, 0.00f, 0.42f, 0.30f,  80, true),
        "щ" to PhonemeProfile(VisemeClass.POSTALVEOLAR,   0.08f, 0.25f, 0.00f, 0.00f, 0.55f, 0.40f, 100, false),
        "ф" to PhonemeProfile(VisemeClass.LABIODENTAL,    0.08f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  70, false),
        "в" to PhonemeProfile(VisemeClass.LABIODENTAL,    0.10f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  60, true),
        "х" to PhonemeProfile(VisemeClass.VELAR_FRIC,     0.22f, 0.00f, 0.00f, 0.00f, 0.08f, 0.00f,  70, false),

        // ── Сонорные ──────────────────────────────────────────────────
        "м" to PhonemeProfile(VisemeClass.BILABIAL_NASAL, 0.06f, 0.00f, 0.00f, 0.70f, 0.00f, 0.00f,  70, true),
        "н" to PhonemeProfile(VisemeClass.DENTAL_STOP,    0.12f, 0.00f, 0.06f, 0.00f, 0.52f, 0.15f,  65, true),
        "л" to PhonemeProfile(VisemeClass.LATERAL,        0.16f, 0.00f, 0.10f, 0.00f, 0.48f, 0.00f,  60, true),
        "р" to PhonemeProfile(VisemeClass.UVULAR,         0.20f, 0.00f, 0.06f, 0.00f, 0.35f, 0.00f,  55, true),
        "й" to PhonemeProfile(VisemeClass.PALATAL,        0.10f, 0.00f, 0.22f, 0.00f, 0.40f, 0.00f,  40, true),

        // ── Аффрикаты ──────────────────────────────────────────────────
        "ц" to PhonemeProfile(VisemeClass.DENTAL_FRIC,    0.08f, 0.00f, 0.22f, 0.00f, 0.45f, 0.48f,  60, false),
        "ч" to PhonemeProfile(VisemeClass.POSTALVEOLAR,   0.10f, 0.22f, 0.00f, 0.00f, 0.50f, 0.38f,  55, false),

        // ── Пауза ──────────────────────────────────────────────────────
        " " to PhonemeProfile(VisemeClass.SILENCE,        0.02f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  80, false),
    )

    // ══════════════════════════════════════════════════════════════════════
    //  НЕМЕЦКИЕ ФОНЕМЫ
    // ══════════════════════════════════════════════════════════════════════

    val DE = mapOf(
        // ── Гласные (включая Umlaute) ─────────────────────────────────
        "a"  to PhonemeProfile(VisemeClass.OPEN_VOWEL,    0.40f, 0.00f, 0.08f, 0.00f, 0.00f, 0.00f, 120, true),
        "e"  to PhonemeProfile(VisemeClass.MID_VOWEL,     0.28f, 0.00f, 0.22f, 0.00f, 0.18f, 0.00f, 100, true),
        "i"  to PhonemeProfile(VisemeClass.CLOSE_FRONT,   0.10f, 0.00f, 0.38f, 0.00f, 0.32f, 0.00f,  90, true),
        "o"  to PhonemeProfile(VisemeClass.MID_ROUND,     0.30f, 0.42f, 0.00f, 0.00f, 0.00f, 0.00f, 110, true),
        "u"  to PhonemeProfile(VisemeClass.CLOSE_BACK,    0.16f, 0.52f, 0.00f, 0.00f, 0.00f, 0.00f, 100, true),
        "ä"  to PhonemeProfile(VisemeClass.MID_VOWEL,     0.36f, 0.00f, 0.20f, 0.00f, 0.10f, 0.00f, 110, true),
        "ö"  to PhonemeProfile(VisemeClass.CLOSE_ROUND,   0.24f, 0.40f, 0.00f, 0.00f, 0.22f, 0.00f, 100, true),
        "ü"  to PhonemeProfile(VisemeClass.CLOSE_ROUND,   0.12f, 0.50f, 0.00f, 0.00f, 0.35f, 0.00f,  95, true),
        "ə"  to PhonemeProfile(VisemeClass.REDUCED,       0.14f, 0.00f, 0.05f, 0.00f, 0.10f, 0.00f,  60, true),

        // ── Согласные ──────────────────────────────────────────────────
        "p"  to PhonemeProfile(VisemeClass.BILABIAL_STOP, 0.08f, 0.00f, 0.00f, 0.80f, 0.00f, 0.00f,  25, false),
        "b"  to PhonemeProfile(VisemeClass.BILABIAL_STOP, 0.10f, 0.00f, 0.00f, 0.76f, 0.00f, 0.00f,  30, true),
        "t"  to PhonemeProfile(VisemeClass.DENTAL_STOP,   0.12f, 0.00f, 0.06f, 0.00f, 0.50f, 0.20f,  25, false),
        "d"  to PhonemeProfile(VisemeClass.DENTAL_STOP,   0.14f, 0.00f, 0.05f, 0.00f, 0.48f, 0.16f,  30, true),
        "k"  to PhonemeProfile(VisemeClass.VELAR_STOP,    0.16f, 0.00f, 0.00f, 0.00f, 0.08f, 0.00f,  25, false),
        "g"  to PhonemeProfile(VisemeClass.VELAR_STOP,    0.18f, 0.00f, 0.00f, 0.00f, 0.06f, 0.00f,  30, true),
        "f"  to PhonemeProfile(VisemeClass.LABIODENTAL,   0.08f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  70, false),
        "v"  to PhonemeProfile(VisemeClass.LABIODENTAL,   0.10f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  60, true),
        "s"  to PhonemeProfile(VisemeClass.DENTAL_FRIC,   0.06f, 0.00f, 0.26f, 0.00f, 0.38f, 0.52f,  80, false),
        "z"  to PhonemeProfile(VisemeClass.DENTAL_FRIC,   0.08f, 0.00f, 0.24f, 0.00f, 0.36f, 0.48f,  70, true),
        "ʃ"  to PhonemeProfile(VisemeClass.POSTALVEOLAR,  0.10f, 0.32f, 0.00f, 0.00f, 0.44f, 0.34f,  85, false),
        "ç"  to PhonemeProfile(VisemeClass.PALATAL,       0.12f, 0.00f, 0.18f, 0.00f, 0.42f, 0.00f,  70, false),
        "x"  to PhonemeProfile(VisemeClass.VELAR_FRIC,    0.20f, 0.00f, 0.00f, 0.00f, 0.06f, 0.00f,  70, false),
        "m"  to PhonemeProfile(VisemeClass.BILABIAL_NASAL,0.06f, 0.00f, 0.00f, 0.68f, 0.00f, 0.00f,  70, true),
        "n"  to PhonemeProfile(VisemeClass.DENTAL_STOP,   0.10f, 0.00f, 0.05f, 0.00f, 0.50f, 0.12f,  65, true),
        "ŋ"  to PhonemeProfile(VisemeClass.VELAR_STOP,    0.14f, 0.00f, 0.00f, 0.00f, 0.06f, 0.00f,  60, true),
        "l"  to PhonemeProfile(VisemeClass.LATERAL,       0.14f, 0.00f, 0.08f, 0.00f, 0.46f, 0.00f,  55, true),
        "ʁ"  to PhonemeProfile(VisemeClass.UVULAR,        0.18f, 0.00f, 0.00f, 0.00f, 0.05f, 0.00f,  50, true),
        "j"  to PhonemeProfile(VisemeClass.PALATAL,       0.10f, 0.00f, 0.20f, 0.00f, 0.38f, 0.00f,  40, true),
        "h"  to PhonemeProfile(VisemeClass.GLOTTAL,       0.18f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  50, false),
        "ʔ"  to PhonemeProfile(VisemeClass.GLOTTAL,       0.04f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  15, false),
        "w"  to PhonemeProfile(VisemeClass.CLOSE_BACK,    0.08f, 0.40f, 0.00f, 0.00f, 0.00f, 0.00f,  40, true),
        " "  to PhonemeProfile(VisemeClass.SILENCE,       0.02f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f,  80, false),
    )

    val NEUTRAL = PhonemeProfile(
        VisemeClass.SILENCE, 0.02f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 40, false,
    )
}

/** Маппинг VisemeClass → VisemeGroup (gate) */
fun PhonemeData.VisemeClass.toVisemeGroup(): VisemeGroup = when (this) {
    PhonemeData.VisemeClass.OPEN_VOWEL    -> VisemeGroup.VOWEL_AA
    PhonemeData.VisemeClass.MID_VOWEL     -> VisemeGroup.VOWEL_EE
    PhonemeData.VisemeClass.CLOSE_FRONT   -> VisemeGroup.VOWEL_EE
    PhonemeData.VisemeClass.CLOSE_BACK    -> VisemeGroup.VOWEL_OO
    PhonemeData.VisemeClass.MID_ROUND     -> VisemeGroup.VOWEL_OO
    PhonemeData.VisemeClass.CLOSE_ROUND   -> VisemeGroup.VOWEL_OO
    PhonemeData.VisemeClass.CENTRAL       -> VisemeGroup.VOWEL_EE
    PhonemeData.VisemeClass.REDUCED       -> VisemeGroup.SILENCE
    PhonemeData.VisemeClass.BILABIAL_STOP -> VisemeGroup.BILABIAL
    PhonemeData.VisemeClass.BILABIAL_NASAL-> VisemeGroup.BILABIAL
    PhonemeData.VisemeClass.LABIODENTAL   -> VisemeGroup.LABIODENTAL
    PhonemeData.VisemeClass.DENTAL_STOP   -> VisemeGroup.DENTAL_ALV
    PhonemeData.VisemeClass.DENTAL_FRIC   -> VisemeGroup.DENTAL_ALV
    PhonemeData.VisemeClass.POSTALVEOLAR  -> VisemeGroup.PALATAL
    PhonemeData.VisemeClass.PALATAL       -> VisemeGroup.PALATAL
    PhonemeData.VisemeClass.VELAR_STOP    -> VisemeGroup.VELAR
    PhonemeData.VisemeClass.VELAR_FRIC    -> VisemeGroup.VELAR
    PhonemeData.VisemeClass.UVULAR        -> VisemeGroup.DENTAL_ALV
    PhonemeData.VisemeClass.LATERAL       -> VisemeGroup.DENTAL_ALV
    PhonemeData.VisemeClass.GLOTTAL       -> VisemeGroup.VELAR
    PhonemeData.VisemeClass.SILENCE       -> VisemeGroup.SILENCE
}

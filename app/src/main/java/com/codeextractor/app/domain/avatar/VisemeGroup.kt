package com.codeextractor.app.domain.avatar

/**
 * VisemeGroup — 9 артикуляционных классов для gate-системы.
 *
 * Gate определяет ДОПУСТИМЫЙ ДИАПАЗОН движений blendshapes.
 * Аудио НЕ МОЖЕТ выйти за пределы gate — это УБИЙЦА рыбьего рта.
 *
 * Пример: при BILABIAL (М, П, Б) jawOpen ограничен [0.02..0.10],
 * даже если RMS = 1.0. Губы ОБЯЗАНЫ быть сомкнуты.
 *
 * Группировка по месту артикуляции:
 *   ГУБНЫЕ:    BILABIAL, LABIODENTAL
 *   ЗУБНЫЕ:    DENTAL_ALV
 *   НЁБНЫЕ:    PALATAL
 *   ГОРЛОВЫЕ:  VELAR
 *   ГЛАСНЫЕ:   VOWEL_AA, VOWEL_EE, VOWEL_OO
 *   ТИШИНА:    SILENCE
 */
enum class VisemeGroup {
    SILENCE,      // Пауза — губы нейтральны
    BILABIAL,     // П, Б, М — жёсткое смыкание губ
    LABIODENTAL,  // Ф, В, W — нижняя губа к верхним зубам
    DENTAL_ALV,   // Т, Д, Н, С, З, Л, Р — язык к альвеолам
    PALATAL,      // Ш, Ж, Ч, Щ, Й — губы трубочкой + язык вверх
    VELAR,        // К, Г, Х — задний язык, губы нейтральны
    VOWEL_AA,     // А, Я — широкое открытие рта
    VOWEL_EE,     // Е, Э, И, Ы — растянутые губы
    VOWEL_OO,     // О, У, Ю, Ö, Ü — округлённые губы
}
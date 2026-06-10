package com.learnde.app.presentation.learn.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ────────────────────────────────────────────────────────────
//  1. СТАРЫЕ ИМЕНА ЦВЕТОВ → НОВЫЕ ПОЛЯ LearnColors (v7)
//     (extension-свойства: ноль дублирования значений)
// ────────────────────────────────────────────────────────────

val LearnColors.bg: Color            get() = background
val LearnColors.surfaceVar: Color    get() = surfaceRaised
val LearnColors.textHi: Color        get() = textPrimary
val LearnColors.textMid: Color       get() = textSecondary
val LearnColors.textLow: Color       get() = textTertiary
val LearnColors.stroke: Color        get() = divider
val LearnColors.strokeStrong: Color  get() = outline
val LearnColors.warn: Color          get() = warning
val LearnColors.warnSoft: Color      get() = warning.copy(alpha = 0.14f)
val LearnColors.error: Color         get() = danger
val LearnColors.errorSoft: Color     get() = dangerSoft
val LearnColors.voiceStart: Color    get() = accent
val LearnColors.voiceEnd: Color      get() = warning

// ────────────────────────────────────────────────────────────
//  2. LearnPalette — прямые ссылки на цвета (если где-то остались)
// ────────────────────────────────────────────────────────────

object LearnPalette {
    val BgLight        = StudioLight.background
    val BgDark         = StudioDark.background
    val SurfaceLight   = StudioLight.surface
    val SurfaceDark    = StudioDark.surface
    val SurfaceVarL    = StudioLight.surfaceRaised
    val SurfaceVarD    = StudioDark.surfaceRaised
    val TextHi_L       = StudioLight.textPrimary
    val TextMid_L      = StudioLight.textSecondary
    val TextLow_L      = StudioLight.textTertiary
    val TextHi_D       = StudioDark.textPrimary
    val TextMid_D      = StudioDark.textSecondary
    val TextLow_D      = StudioDark.textTertiary
    val Accent         = StudioDark.accent
    val AccentSoft_L   = StudioLight.accentSoft
    val AccentSoft_D   = StudioDark.accentSoft
    val VoiceStart     = StudioDark.accent
    val VoiceEnd       = StudioDark.warning
    val Success        = StudioDark.success
    val SuccessSoft    = StudioDark.successSoft
    val Warn           = StudioDark.warning
    val WarnSoft       = StudioDark.warning.copy(alpha = 0.14f)
    val Error          = StudioDark.danger
    val ErrorSoft      = StudioDark.dangerSoft
    val Stroke_L       = StudioLight.divider
    val Stroke_D       = StudioDark.divider
    val StrokeStrong_L = StudioLight.outline
    val StrokeStrong_D = StudioDark.outline
}

// ────────────────────────────────────────────────────────────
//  3. LearnTokens — все прежние размеры + короткие алиасы,
//     которые встречаются в новых экранах (Micro/Body/Title…)
// ────────────────────────────────────────────────────────────

object LearnTokens {
    // ── Радиусы ──
    val RadiusXxs = 4.dp
    val RadiusXs  = 8.dp
    val RadiusSm  = 12.dp
    val RadiusMd  = 14.dp
    val RadiusLg  = 18.dp
    val RadiusXl  = 24.dp

    // ── Отступы ──
    val PaddingXs = 4.dp
    val PaddingSm = 8.dp
    val PaddingMd = 12.dp
    val PaddingLg = 16.dp
    val PaddingXl = 24.dp

    // ── Линии ──
    val BorderThin   = 1.dp
    val BorderMedium = 1.5.dp

    // ── Кнопки ──
    val ButtonHeightSm = 40.dp
    val ButtonHeightMd = 48.dp
    val ButtonHeightLg = 56.dp

    // ── Типографика: полные имена (старый код) ──
    val FontSizeMicro     = 9.sp
    val FontSizeCaption   = 11.sp
    val FontSizeBody      = 13.sp
    val FontSizeBodyLarge = 15.sp
    val FontSizeTitle     = 17.sp
    val FontSizeTitleLg   = 22.sp
    val FontSizeDisplay   = 26.sp

    // ── Типографика: короткие алиасы (новые экраны) ──
    val Micro     = FontSizeMicro
    val Caption   = FontSizeCaption
    val Body      = FontSizeBody
    val BodyLarge = FontSizeBodyLarge
    val Title     = FontSizeTitle
    val TitleLg   = FontSizeTitleLg
    val Display   = FontSizeDisplay

    val CapsLetterSpacing = 1.4.sp
}

// ────────────────────────────────────────────────────────────
//  4. Plural — русская плюрализация (вернулась без изменений)
// ────────────────────────────────────────────────────────────

object Plural {
    private fun pick(n: Int, one: String, few: String, many: String): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> many
            mod10 == 1       -> one
            mod10 in 2..4    -> few
            else             -> many
        }
    }

    fun word(n: Int)     = pick(n, "слово", "слова", "слов")
    fun lesson(n: Int)   = pick(n, "урок", "урока", "уроков")
    fun rule(n: Int)     = pick(n, "правило", "правила", "правил")
    fun minute(n: Int)   = pick(n, "минута", "минуты", "минут")
    fun question(n: Int) = pick(n, "вопрос", "вопроса", "вопросов")
    fun attempt(n: Int)  = pick(n, "попытка", "попытки", "попыток")
    fun day(n: Int)      = pick(n, "день", "дня", "дней")
    fun card(n: Int)     = pick(n, "карточка", "карточки", "карточек")
    fun cluster(n: Int)  = pick(n, "тема", "темы", "тем")
}
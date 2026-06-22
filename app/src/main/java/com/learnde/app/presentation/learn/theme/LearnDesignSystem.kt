// Путь: app/src/main/java/com/learnde/app/presentation/learn/theme/LearnDesignSystem.kt
//
// РЕДИЗАЙН: монохромная палитра в стиле ChatGPT (серый + белый).
//   • Светлая тема: белый фон, почти-чёрный текст, тонкие серые границы.
//   • Тёмная тема: #212121 фон, #2F2F2F поверхности, светлый текст.
//   • Единственный цветовой акцент — зелёный (success) для «верно» и красный
//     (danger) для ошибок: в языковом тренажёре без них нельзя.
//   • Все имена свойств сохранены — экраны менять не нужно, цвета берутся
//     через learnColors() / LearnPalette.
//
// СОХРАНЁН фикс Platform declaration clash в LearnType (@get:JvmName на
// строчных свойствах display/title/body/caption/micro).
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ────────────────────────────────────────────────────────────
//  1. ЦВЕТА
// ────────────────────────────────────────────────────────────

class LearnColors(
    val background: Color,
    val surface: Color,
    val surfaceRaised: Color,
    val surfaceSunken: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val divider: Color,
    val outline: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val success: Color,
    val successSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val bubbleTutor: Color,
    val bubbleUser: Color,
    val bubbleSystem: Color,
) {
    val bg: Color            get() = background
    val surfaceVar: Color    get() = surfaceRaised
    val textHi: Color        get() = textPrimary
    val textMid: Color       get() = textSecondary
    val textLow: Color       get() = textTertiary
    val stroke: Color        get() = divider
    val strokeStrong: Color  get() = outline
    val warn: Color          get() = warning
    val warnSoft: Color      get() = warningSoft
    val error: Color         get() = danger
    val errorSoft: Color     get() = dangerSoft
    val voiceStart: Color    get() = accent
    val voiceEnd: Color      get() = textSecondary
}

object GeminiTokens {
    val Blue700 = Color(0xFF0D0D0D)
    val Blue600 = Color(0xFF2D2D2D)
    val Blue500 = Color(0xFF444444)
    val Blue400 = Color(0xFF6E6E80)
    val Blue300 = Color(0xFF8E8EA0)
    val Blue100 = Color(0xFFD9D9E3)
    val Blue050 = Color(0xFFF0F0F2)

    val White = Color(0xFFFFFFFF)
    val Surface1 = Color(0xFFFFFFFF)
    val Surface2 = Color(0xFFF7F7F8)
    val Sunken = Color(0xFFF0F0F2)

    val Ink900 = Color(0xFF212121)
    val Ink800 = Color(0xFF2F2F2F)
    val Ink700 = Color(0xFF333333)
    val Ink600 = Color(0xFF3D3D3D)

    val TextHiL = Color(0xFF0D0D0D)
    val TextMidL = Color(0xFF565869)
    val TextLowL = Color(0xFF8E8EA0)

    val TextHiD = Color(0xFFECECEC)
    val TextMidD = Color(0xFFB4B4B4)
    val TextLowD = Color(0xFF8E8EA0)

    val GreenL = Color(0xFF10A37F)
    val GreenSoftL = Color(0x1A10A37F)
    val GreenD = Color(0xFF19C37D)
    val GreenSoftD = Color(0x2619C37D)

    val RedL = Color(0xFFD92D20)
    val RedSoftL = Color(0x14D92D20)
    val RedD = Color(0xFFF97066)
    val RedSoftD = Color(0x26F97066)

    val AmberL = Color(0xFFB26B00)
    val AmberSoftL = Color(0x18B26B00)
    val AmberD = Color(0xFFF0A23B)
    val AmberSoftD = Color(0x26F0A23B)
}

val GeminiLight = LearnColors(
    background     = GeminiTokens.White,
    surface        = GeminiTokens.Surface1,
    surfaceRaised  = GeminiTokens.White,
    surfaceSunken  = GeminiTokens.Sunken,
    textPrimary    = GeminiTokens.TextHiL,
    textSecondary  = GeminiTokens.TextMidL,
    textTertiary   = GeminiTokens.TextLowL,
    divider        = Color(0xFFE5E5E5),
    outline        = Color(0xFFD9D9E3),
    accent         = GeminiTokens.Blue600,
    onAccent       = GeminiTokens.White,
    accentSoft     = GeminiTokens.Blue050,
    success        = GeminiTokens.GreenL,
    successSoft    = GeminiTokens.GreenSoftL,
    danger         = GeminiTokens.RedL,
    dangerSoft     = GeminiTokens.RedSoftL,
    warning        = GeminiTokens.AmberL,
    warningSoft    = GeminiTokens.AmberSoftL,
    bubbleTutor    = GeminiTokens.Surface2,
    bubbleUser     = Color(0xFFECECEC),
    bubbleSystem   = Color(0x00000000),
)

val GeminiDark = LearnColors(
    background     = GeminiTokens.Ink900,
    surface        = GeminiTokens.Ink800,
    surfaceRaised  = GeminiTokens.Ink700,
    surfaceSunken  = GeminiTokens.Ink900,
    textPrimary    = GeminiTokens.TextHiD,
    textSecondary  = GeminiTokens.TextMidD,
    textTertiary   = GeminiTokens.TextLowD,
    divider        = Color(0xFF3D3D3D),
    outline        = Color(0xFF4D4D4D),
    accent         = Color(0xFFECECEC),
    onAccent       = Color(0xFF0D0D0D),
    accentSoft     = Color(0xFF3A3A3A),
    success        = GeminiTokens.GreenD,
    successSoft    = GeminiTokens.GreenSoftD,
    danger         = GeminiTokens.RedD,
    dangerSoft     = GeminiTokens.RedSoftD,
    warning        = GeminiTokens.AmberD,
    warningSoft    = GeminiTokens.AmberSoftD,
    bubbleTutor    = GeminiTokens.Ink700,
    bubbleUser     = Color(0xFF3A3A3A),
    bubbleSystem   = Color(0x00000000),
)

@Composable
@ReadOnlyComposable
fun learnColors(): LearnColors =
    if (isSystemInDarkTheme()) GeminiDark else GeminiLight

object LearnPalette {
    val BgLight        = GeminiLight.background
    val BgDark         = GeminiDark.background
    val SurfaceLight   = GeminiLight.surface
    val SurfaceDark    = GeminiDark.surface
    val SurfaceVarL    = GeminiLight.surfaceRaised
    val SurfaceVarD    = GeminiDark.surfaceRaised
    val TextHi_L       = GeminiLight.textPrimary
    val TextMid_L      = GeminiLight.textSecondary
    val TextLow_L      = GeminiLight.textTertiary
    val TextHi_D       = GeminiDark.textPrimary
    val TextMid_D      = GeminiDark.textSecondary
    val TextLow_D      = GeminiDark.textTertiary
    val Accent         = GeminiDark.accent
    val AccentSoft_L   = GeminiLight.accentSoft
    val AccentSoft_D   = GeminiDark.accentSoft
    val VoiceStart     = GeminiDark.accent
    val VoiceEnd       = GeminiDark.textSecondary
    val Success        = GeminiDark.success
    val SuccessSoft    = GeminiDark.successSoft
    val Warn           = GeminiDark.warning
    val WarnSoft       = GeminiDark.warningSoft
    val Error          = GeminiDark.danger
    val ErrorSoft      = GeminiDark.dangerSoft
    val Stroke_L       = GeminiLight.divider
    val Stroke_D       = GeminiDark.divider
    val StrokeStrong_L = GeminiLight.outline
    val StrokeStrong_D = GeminiDark.outline
}

// ────────────────────────────────────────────────────────────
//  2. LearnTokens
// ────────────────────────────────────────────────────────────

object LearnTokens {
    val RadiusXxs = 4.dp
    val RadiusXs  = 8.dp
    val RadiusSm  = 12.dp
    val RadiusMd  = 14.dp
    val RadiusLg  = 18.dp
    val RadiusXl  = 24.dp

    val PaddingXs = 4.dp
    val PaddingSm = 8.dp
    val PaddingMd = 12.dp
    val PaddingLg = 16.dp
    val PaddingXl = 24.dp

    val BorderThin   = 1.dp
    val BorderMedium = 1.5.dp

    val ButtonHeightSm = 40.dp
    val ButtonHeightMd = 48.dp
    val ButtonHeightLg = 56.dp

    val FontSizeMicro     = 9.sp
    val FontSizeCaption   = 11.sp
    val FontSizeBody      = 13.sp
    val FontSizeBodyLarge = 15.sp
    val FontSizeTitle     = 17.sp
    val FontSizeTitleLg   = 22.sp
    val FontSizeDisplay   = 26.sp

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
//  3. Plural
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

// ────────────────────────────────────────────────────────────
//  4. LearnType
//     @get:JvmName на строчных свойствах, конфликтующих с заглавными алиасами.
// ────────────────────────────────────────────────────────────

object LearnType {
    // ── Строчные (новые экраны «Студии») ──
    @get:JvmName("displaySp")
    val display   = 26.sp
    @get:JvmName("titleSp")
    val title     = 17.sp
    val chat      = 15.sp
    val chatLine  = 22.sp
    @get:JvmName("bodySp")
    val body      = 13.sp
    val bodyLine  = 18.sp
    val label     = 12.sp
    @get:JvmName("captionSp")
    val caption   = 11.sp
    @get:JvmName("microSp")
    val micro     = 9.sp

    // ── Заглавные алиасы (LearnType.Micro, LearnType.Title и т.д.) ──
    val Micro             = LearnTokens.Micro
    val Caption           = LearnTokens.Caption
    val Body              = LearnTokens.Body
    val BodyLarge         = LearnTokens.BodyLarge
    val Title             = LearnTokens.Title
    val TitleLg           = LearnTokens.TitleLg
    val Display           = LearnTokens.Display
    val CapsLetterSpacing = LearnTokens.CapsLetterSpacing
}

// ────────────────────────────────────────────────────────────
//  5. LearnDim
// ────────────────────────────────────────────────────────────

object LearnDim {
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s5: Dp = 20.dp
    val s6: Dp = 24.dp

    val rChip: Dp   = 10.dp
    val rCard: Dp   = 14.dp
    val rPanel: Dp  = 20.dp
    val rBubble: Dp = 16.dp

    val statusBarH: Dp         = 44.dp
    val progressCollapsedH: Dp = 36.dp
    val hintChipH: Dp          = 36.dp
    val bottomBarH: Dp         = 76.dp
    val micButton: Dp          = 60.dp
    val quickChipH: Dp         = 32.dp

    val PaddingXs: Dp get() = LearnTokens.PaddingXs
    val PaddingSm: Dp get() = LearnTokens.PaddingSm
    val PaddingMd: Dp get() = LearnTokens.PaddingMd
    val PaddingLg: Dp get() = LearnTokens.PaddingLg
    val PaddingXl: Dp get() = LearnTokens.PaddingXl

    val RadiusXxs: Dp get() = LearnTokens.RadiusXxs
    val RadiusXs: Dp  get() = LearnTokens.RadiusXs
    val RadiusSm: Dp  get() = LearnTokens.RadiusSm
    val RadiusMd: Dp  get() = LearnTokens.RadiusMd
    val RadiusLg: Dp  get() = LearnTokens.RadiusLg
    val RadiusXl: Dp  get() = LearnTokens.RadiusXl

    val BorderThin: Dp   get() = LearnTokens.BorderThin
    val BorderMedium: Dp get() = LearnTokens.BorderMedium

    val ButtonHeightSm: Dp get() = LearnTokens.ButtonHeightSm
    val ButtonHeightMd: Dp get() = LearnTokens.ButtonHeightMd
    val ButtonHeightLg: Dp get() = LearnTokens.ButtonHeightLg
}

// ────────────────────────────────────────────────────────────
//  6. ДВИЖЕНИЕ
// ────────────────────────────────────────────────────────────

object LearnMotion {
    const val micro      = 140
    const val standard   = 220
    const val emphasized = 320
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

// ────────────────────────────────────────────────────────────
//  7. СТАТУСЫ СОЕДИНЕНИЯ
// ────────────────────────────────────────────────────────────

@Composable
fun linkColor(stateName: String): Color {
    val c = learnColors()
    return when (stateName) {
        "READY"      -> c.success
        "ROTATING"   -> c.success
        "CONNECTING" -> c.textSecondary
        "RECOVERING" -> c.textSecondary
        "FAILED"     -> c.danger
        else         -> c.textTertiary
    }
}

fun linkLabel(stateName: String): String = when (stateName) {
    "READY"      -> "В эфире"
    "ROTATING"   -> "В эфире"
    "CONNECTING" -> "Подключение…"
    "RECOVERING" -> "Восстановление…"
    "FAILED"     -> "Нет связи"
    else         -> ""
}

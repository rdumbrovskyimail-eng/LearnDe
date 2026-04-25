// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/theme/LearnDesignSystem.kt
//
// Единая дизайн-система Learn блока — Voice-First Minimalism.
// Тёплый монохром + один accent. Без эмодзи-флагов как UI.
// Weight-контраст вместо цветового.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Единая палитра Learn-блока.
 * Используем семантичные имена, а не Color1/Color2.
 */
object LearnPalette {
    // ── Background / Surface ──
    val BgLight        = Color(0xFFFAFAF7)   // warm off-white
    val BgDark         = Color(0xFF0F0F0F)
    val SurfaceLight   = Color(0xFFFFFFFF)
    val SurfaceDark    = Color(0xFF1A1A1A)
    val SurfaceVarL    = Color(0xFFF2F1EC)   // warm gray-cream
    val SurfaceVarD    = Color(0xFF222222)

    // ── Text ──
    val TextHi_L       = Color(0xFF1A1A1A)
    val TextMid_L      = Color(0xFF57534E)
    val TextLow_L      = Color(0xFFA8A29E)
    val TextHi_D       = Color(0xFFF5F5F0)
    val TextMid_D      = Color(0xFFB8B4AC)
    val TextLow_D      = Color(0xFF6B6862)

    // ── Accent (deep, premium) ──
    val Accent         = Color(0xFF1E40AF)   // deep blue — отсылка к DE
    val AccentSoft_L   = Color(0xFFE8EDFA)   // светлый бэкграунд для accent
    val AccentSoft_D   = Color(0xFF1A2447)

    // ── Voice Active (только когда есть голос) ──
    val VoiceStart     = Color(0xFFFF6B35)
    val VoiceEnd       = Color(0xFFF59E0B)

    // ── Semantic (используем редко, только для статусов) ──
    val Success        = Color(0xFF2D7D5B)   // приглушённый green
    val SuccessSoft    = Color(0xFFE6F0EC)
    val Warn           = Color(0xFFB85C00)   // приглушённый orange
    val WarnSoft       = Color(0xFFFAEFE0)
    val Error          = Color(0xFFB42318)   // приглушённый red
    val ErrorSoft      = Color(0xFFFAE8E5)

    // ── Strokes ──
    val Stroke_L       = Color(0x141A1A1A)   // 8% black
    val Stroke_D       = Color(0x14F5F5F0)   // 8% off-white
    val StrokeStrong_L = Color(0x331A1A1A)   // 20% black
    val StrokeStrong_D = Color(0x33F5F5F0)
}

/** Контекстно-зависимые цвета (light/dark). */
class LearnColors(
    val bg: Color,
    val surface: Color,
    val surfaceVar: Color,
    val textHi: Color,
    val textMid: Color,
    val textLow: Color,
    val accent: Color,
    val accentSoft: Color,
    val voiceStart: Color,
    val voiceEnd: Color,
    val success: Color,
    val successSoft: Color,
    val warn: Color,
    val warnSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val stroke: Color,
    val strokeStrong: Color,
)

@Composable
@ReadOnlyComposable
fun learnColors(): LearnColors {
    val dark = isSystemInDarkTheme()
    return if (dark) {
        LearnColors(
            bg = LearnPalette.BgDark,
            surface = LearnPalette.SurfaceDark,
            surfaceVar = LearnPalette.SurfaceVarD,
            textHi = LearnPalette.TextHi_D,
            textMid = LearnPalette.TextMid_D,
            textLow = LearnPalette.TextLow_D,
            accent = LearnPalette.Accent,
            accentSoft = LearnPalette.AccentSoft_D,
            voiceStart = LearnPalette.VoiceStart,
            voiceEnd = LearnPalette.VoiceEnd,
            success = LearnPalette.Success,
            successSoft = Color(0xFF1F2D26),
            warn = LearnPalette.Warn,
            warnSoft = Color(0xFF2E2418),
            error = LearnPalette.Error,
            errorSoft = Color(0xFF2E1B19),
            stroke = LearnPalette.Stroke_D,
            strokeStrong = LearnPalette.StrokeStrong_D,
        )
    } else {
        LearnColors(
            bg = LearnPalette.BgLight,
            surface = LearnPalette.SurfaceLight,
            surfaceVar = LearnPalette.SurfaceVarL,
            textHi = LearnPalette.TextHi_L,
            textMid = LearnPalette.TextMid_L,
            textLow = LearnPalette.TextLow_L,
            accent = LearnPalette.Accent,
            accentSoft = LearnPalette.AccentSoft_L,
            voiceStart = LearnPalette.VoiceStart,
            voiceEnd = LearnPalette.VoiceEnd,
            success = LearnPalette.Success,
            successSoft = LearnPalette.SuccessSoft,
            warn = LearnPalette.Warn,
            warnSoft = LearnPalette.WarnSoft,
            error = LearnPalette.Error,
            errorSoft = LearnPalette.ErrorSoft,
            stroke = LearnPalette.Stroke_L,
            strokeStrong = LearnPalette.StrokeStrong_L,
        )
    }
}

/** Единые радиусы / отступы. */
object LearnTokens {
    val RadiusXs    = 8.dp
    val RadiusSm    = 12.dp
    val RadiusMd    = 14.dp
    val RadiusLg    = 18.dp
    val RadiusXl    = 24.dp

    val PaddingXs   = 4.dp
    val PaddingSm   = 8.dp
    val PaddingMd   = 12.dp
    val PaddingLg   = 16.dp
    val PaddingXl   = 24.dp

    val BorderThin   = 1.dp
    val BorderMedium = 1.5.dp

    // ── Typography ──
    val FontSizeMicro     = 9.sp
    val FontSizeCaption   = 11.sp
    val FontSizeBody      = 13.sp
    val FontSizeBodyLarge = 15.sp
    val FontSizeTitle     = 17.sp
    val FontSizeTitleLg   = 22.sp
    val FontSizeDisplay   = 32.sp

    // German letter spacing для caption-меток (всё, что в caps lock)
    val CapsLetterSpacing = 1.4.sp
}

/** Утилиты для русской плюрализации (1 слово / 2 слова / 5 слов). */
object Plural {
    fun word(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "слов"
            mod10 == 1 -> "слово"
            mod10 in 2..4 -> "слова"
            else -> "слов"
        }
    }

    fun lesson(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "уроков"
            mod10 == 1 -> "урок"
            mod10 in 2..4 -> "урока"
            else -> "уроков"
        }
    }

    fun rule(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "правил"
            mod10 == 1 -> "правило"
            mod10 in 2..4 -> "правила"
            else -> "правил"
        }
    }

    fun minute(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "минут"
            mod10 == 1 -> "минута"
            mod10 in 2..4 -> "минуты"
            else -> "минут"
        }
    }

    fun question(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "вопросов"
            mod10 == 1 -> "вопрос"
            mod10 in 2..4 -> "вопроса"
            else -> "вопросов"
        }
    }

    fun attempt(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when {
            mod100 in 11..14 -> "попыток"
            mod10 == 1 -> "попытка"
            mod10 in 2..4 -> "попытки"
            else -> "попыток"
        }
    }
}

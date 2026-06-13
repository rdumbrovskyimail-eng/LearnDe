package com.learnde.app.presentation.learn.v2

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.learnde.app.presentation.learn.theme.LearnColors

// ─────────────────────────────────────────────────────────────────
//  Базовые цвета Gemini Blue
// ─────────────────────────────────────────────────────────────────

object GeminiTokens {
    // Бренд-синие
    val Blue700 = Color(0xFF0B57D0)
    val Blue600 = Color(0xFF1A73E8)
    val Blue500 = Color(0xFF4285F4)
    val Blue400 = Color(0xFF669DF6)
    val Blue300 = Color(0xFF8AB4F8)
    val Blue100 = Color(0xFFD3E3FD)
    val Blue050 = Color(0xFFEAF1FE)

    // Светлые поверхности (едва голубые)
    val White = Color(0xFFFFFFFF)
    val Surface1 = Color(0xFFF8FAFD)
    val Surface2 = Color(0xFFF3F7FC)
    val Sunken = Color(0xFFEEF3FA)

    // Тёмная версия (для системной тёмной темы — приглушённый сине-серый)
    val Ink900 = Color(0xFF0B0E14)
    val Ink800 = Color(0xFF131722)
    val Ink700 = Color(0xFF1A1F2B)
    val Ink600 = Color(0xFF222838)

    // Текст (светлая тема)
    val TextHiL = Color(0xFF1F1F1F)
    val TextMidL = Color(0xFF5F6368)
    val TextLowL = Color(0xFF9AA0A6)

    // Текст (тёмная тема)
    val TextHiD = Color(0xFFE8EAED)
    val TextMidD = Color(0xFF9AA0A6)
    val TextLowD = Color(0xFF5F6571)

    // Семантика
    val GreenL = Color(0xFF1E8E3E)
    val GreenSoftL = Color(0x141E8E3E)
    val GreenD = Color(0xFF81C995)
    val GreenSoftD = Color(0x2281C995)

    val RedL = Color(0xFFD93025)
    val RedSoftL = Color(0x14D93025)
    val RedD = Color(0xFFF28B82)
    val RedSoftD = Color(0x22F28B82)

    val AmberL = Color(0xFFE37400)
    val AmberSoftL = Color(0x18E37400)
    val AmberD = Color(0xFFFDD663)
    val AmberSoftD = Color(0x22FDD663)
}

// ─────────────────────────────────────────────────────────────────
//  LearnColors-наборы (light / dark) с синими токенами
// ─────────────────────────────────────────────────────────────────

val GeminiLight = LearnColors(
    background     = GeminiTokens.White,
    surface        = GeminiTokens.Surface1,
    surfaceRaised  = GeminiTokens.White,
    surfaceSunken  = GeminiTokens.Sunken,
    textPrimary    = GeminiTokens.TextHiL,
    textSecondary  = GeminiTokens.TextMidL,
    textTertiary   = GeminiTokens.TextLowL,
    divider        = Color(0xFFE3E8EF),
    outline        = Color(0xFFD2DBE6),
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
    bubbleUser     = GeminiTokens.Blue050,
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
    divider        = Color(0xFF2A3140),
    outline        = Color(0xFF38404F),
    accent         = GeminiTokens.Blue300,
    onAccent       = GeminiTokens.Ink900,
    accentSoft     = Color(0x1F8AB4F8),
    success        = GeminiTokens.GreenD,
    successSoft    = GeminiTokens.GreenSoftD,
    danger         = GeminiTokens.RedD,
    dangerSoft     = GeminiTokens.RedSoftD,
    warning        = GeminiTokens.AmberD,
    warningSoft    = GeminiTokens.AmberSoftD,
    bubbleTutor    = GeminiTokens.Ink700,
    bubbleUser     = Color(0xFF1B2740),
    bubbleSystem   = Color(0x00000000),
)

@Composable
@ReadOnlyComposable
fun geminiColors(): LearnColors =
    if (isSystemInDarkTheme()) GeminiDark else GeminiLight

// ─────────────────────────────────────────────────────────────────
//  Градиенты для orb и кнопок
// ─────────────────────────────────────────────────────────────────

object GeminiGradients {

    /** Основной градиент шара: голубой → синий → тёмно-синий. */
    val orb: Brush
        get() = Brush.linearGradient(
            colors = listOf(
                GeminiTokens.Blue500,
                GeminiTokens.Blue600,
                GeminiTokens.Blue700,
            )
        )

    /** Радиальный «дышащий» градиент для idle-состояния шара. */
    fun orbRadial(): Brush = Brush.radialGradient(
        colors = listOf(
            GeminiTokens.Blue400,
            GeminiTokens.Blue600,
            GeminiTokens.Blue700,
        )
    )

    /** Свечение вокруг шара, когда говорит ИИ. */
    fun speakingGlow(): Brush = Brush.radialGradient(
        colors = listOf(
            GeminiTokens.Blue300.copy(alpha = 0.55f),
            GeminiTokens.Blue500.copy(alpha = 0.18f),
            Color.Transparent,
        )
    )

    /** Свечение, когда активен микрофон (чуть холоднее/ярче). */
    fun listeningGlow(): Brush = Brush.radialGradient(
        colors = listOf(
            GeminiTokens.Blue100.copy(alpha = 0.7f),
            GeminiTokens.Blue400.copy(alpha = 0.22f),
            Color.Transparent,
        )
    )

    /** Заливка primary-кнопки (Старт). */
    val primaryButton: Brush
        get() = Brush.horizontalGradient(
            colors = listOf(GeminiTokens.Blue600, GeminiTokens.Blue700)
        )
}

// ─────────────────────────────────────────────────────────────────
//  Формы и размеры
// ─────────────────────────────────────────────────────────────────

object GeminiShape {
    val card = RoundedCornerShape(24.dp)
    val cardLarge = RoundedCornerShape(28.dp)
    val chip = RoundedCornerShape(50)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

object GeminiDims {
    val screenPadding = 20.dp
    val sectionGap = 18.dp
    val cardPadding = 18.dp
    val orbSize = 168.dp
    val orbSizeCompact = 120.dp
    val progressRing = 56.dp
    val pillHeight = 56.dp
    val timelineDot = 10.dp
    val timelineDotActive = 14.dp
}
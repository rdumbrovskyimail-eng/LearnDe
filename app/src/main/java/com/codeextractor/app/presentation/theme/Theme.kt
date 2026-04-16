// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/theme/Theme.kt
// Изменения:
//   + GeminiLiveTheme принимает ThemeMode (AUTO / LIGHT / DARK)
//   + Отключен dynamicColor (ломал брендинг Gemini)
//   + Кастомные ColorScheme с корректными onX, surfaceVariant, outline
//   + Поддержка status bar contrast
// ═══════════════════════════════════════════════════════════
package com.codeextractor.app.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.codeextractor.app.data.settings.ThemeMode

// ─── Light ───
private val LightColors = lightColorScheme(
    primary            = GeminiBlue,
    onPrimary          = Color.White,
    primaryContainer   = GeminiBlueSoft,
    onPrimaryContainer = Color(0xFF0B3A82),
    secondary          = GeminiViolet,
    onSecondary        = Color.White,
    tertiary           = GeminiAqua,
    onTertiary         = Color.White,
    error              = StatusRed,
    onError            = Color.White,
    background         = LightBg,
    onBackground       = LightTextPrimary,
    surface            = LightBg,
    onSurface          = LightTextPrimary,
    surfaceVariant     = LightSurface,
    onSurfaceVariant   = LightTextSecondary,
    surfaceContainer   = LightSurface,
    surfaceContainerHigh = LightSurfaceHigh,
    outline            = LightOutlineDim,
    outlineVariant     = LightOutline
)

// ─── Dark ───
private val DarkColors = darkColorScheme(
    primary            = GeminiBlueLight,
    onPrimary          = Color(0xFF002F6C),
    primaryContainer   = Color(0xFF0B3A82),
    onPrimaryContainer = GeminiBlueSoft,
    secondary          = GeminiViolet,
    onSecondary        = Color.White,
    tertiary           = GeminiAqua,
    onTertiary         = Color(0xFF003538),
    error              = StatusRedDark,
    onError            = Color(0xFF410002),
    background         = DarkBg,
    onBackground       = DarkTextPrimary,
    surface            = DarkBg,
    onSurface          = DarkTextPrimary,
    surfaceVariant     = DarkSurface,
    onSurfaceVariant   = DarkTextSecondary,
    surfaceContainer   = DarkSurface,
    surfaceContainerHigh = DarkSurfaceHigh,
    outline            = DarkOutlineDim,
    outlineVariant     = DarkOutline
)

@Composable
fun GeminiLiveTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) {
        ThemeMode.AUTO  -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK  -> true
    }
    val colorScheme = if (darkTheme) DarkColors else LightColors

    // StatusBar / NavBar — прозрачные с корректным контрастом иконок
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            val insets = WindowCompat.getInsetsController(window, view)
            val bgLight = colorScheme.background.luminance() > 0.5f
            insets.isAppearanceLightStatusBars = bgLight
            insets.isAppearanceLightNavigationBars = bgLight
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
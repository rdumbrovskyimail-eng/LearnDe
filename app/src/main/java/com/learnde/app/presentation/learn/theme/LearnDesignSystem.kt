// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v7.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/theme/LearnDesignSystem.kt
//
// ДИЗАЙН-СИСТЕМА «STUDIO» — премиальная среда для фокусной учёбы.
//
// Художественное направление:
//   Глубокий графит + тёплый янтарь. Не «приложение-игрушка», а
//   студия звукозаписи: тёмная, спокойная, ничего не отвлекает от
//   голоса. Один акцентный цвет несёт ВСЮ энергию интерфейса.
//   Светлая тема — «бумага и чернила» с тем же янтарём.
//
// Правила системы (соблюдать во всех экранах):
//   1. Цвет = смысл. Янтарь — только действие/внимание. Зелёный —
//      только успех/готовность. Коралл — только ошибка. Всё
//      остальное — нейтральные ступени.
//   2. Иерархия весом и размером шрифта, НЕ цветом радуги.
//   3. Сетка 4dp. Радиусы: 10 (chip) / 14 (card) / 20 (panel) / full.
//   4. Движение: 140мс micro / 220мс standard / 320мс emphasized,
//      easing FastOutSlowIn. Ничего не «прыгает».
//   5. Hairline-границы 1dp вместо теней — глубина через тон, не блюр.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ────────────────────────────────────────────────────────────
//  ПАЛИТРА
// ────────────────────────────────────────────────────────────

@Immutable
data class LearnColors(
    // Поверхности (4 ступени глубины)
    val background: Color,      // экран
    val surface: Color,         // карточка
    val surfaceRaised: Color,   // карточка над карточкой / диалог
    val surfaceSunken: Color,   // вдавленные зоны (поле чата)

    // Текст
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,    // подписи, таймштампы

    // Линии
    val divider: Color,         // hairline 1dp
    val outline: Color,         // активные рамки

    // Смысловые
    val accent: Color,          // янтарь — действие, внимание, ученик
    val onAccent: Color,
    val accentSoft: Color,      // янтарь 12% — фоны чипов/бейджей
    val success: Color,         // готовность, верный ответ
    val successSoft: Color,
    val danger: Color,          // ошибка, обрыв
    val dangerSoft: Color,
    val warning: Color,         // деградация, реконнект

    // Чат
    val bubbleTutor: Color,     // реплика репетитора
    val bubbleUser: Color,      // реплика ученика
    val bubbleSystem: Color,    // системные микро-события
)

/** ТЁМНАЯ — основная. «Студия». */
val StudioDark = LearnColors(
    background     = Color(0xFF0E1014),
    surface        = Color(0xFF161A21),
    surfaceRaised  = Color(0xFF1D222B),
    surfaceSunken  = Color(0xFF0A0C0F),

    textPrimary    = Color(0xFFEDEFF3),
    textSecondary  = Color(0xFF9AA3B2),
    textTertiary   = Color(0xFF5C6470),

    divider        = Color(0xFF232934),
    outline        = Color(0xFF323A48),

    accent         = Color(0xFFFFB454),
    onAccent       = Color(0xFF231503),
    accentSoft     = Color(0x1FFFB454),
    success        = Color(0xFF4ADE80),
    successSoft    = Color(0x1F4ADE80),
    danger         = Color(0xFFF87171),
    dangerSoft     = Color(0x1FF87171),
    warning        = Color(0xFFFBBF24),

    bubbleTutor    = Color(0xFF1A1F28),
    bubbleUser     = Color(0xFF2B2415),   // тёплый графит с янтарным подтоном
    bubbleSystem   = Color(0x00000000),   // прозрачно — только текст
)

/** СВЕТЛАЯ — «бумага и чернила». */
val StudioLight = LearnColors(
    background     = Color(0xFFF7F6F3),
    surface        = Color(0xFFFFFFFF),
    surfaceRaised  = Color(0xFFFFFFFF),
    surfaceSunken  = Color(0xFFEFEDE8),

    textPrimary    = Color(0xFF1A1D23),
    textSecondary  = Color(0xFF5B6470),
    textTertiary   = Color(0xFF9AA1AC),

    divider        = Color(0xFFE5E2DB),
    outline        = Color(0xFFCBC7BE),

    accent         = Color(0xFFE08700),
    onAccent       = Color(0xFFFFFFFF),
    accentSoft     = Color(0x1AE08700),
    success        = Color(0xFF15803D),
    successSoft    = Color(0x1A15803D),
    danger         = Color(0xFFDC2626),
    dangerSoft     = Color(0x14DC2626),
    warning        = Color(0xFFD97706),

    bubbleTutor    = Color(0xFFFFFFFF),
    bubbleUser     = Color(0xFFFFF3E0),
    bubbleSystem   = Color(0x00000000),
)

private val LocalLearnColors = staticCompositionLocalOf { StudioDark }

@Composable
fun learnColors(): LearnColors =
    if (isSystemInDarkTheme()) StudioDark else StudioLight

// ────────────────────────────────────────────────────────────
//  ТИПОГРАФИКА (масштаб 11→26, как в редакторских системах)
// ────────────────────────────────────────────────────────────

object LearnType {
    val display   = 26.sp   // заголовок экрана уровней
    val title     = 17.sp   // заголовки секций / диалогов
    val chat      = 15.sp   // ТЕЛО ЧАТА — главный размер приложения
    val chatLine  = 22.sp   // line-height чата
    val body      = 13.sp   // карточки-подсказки, описания
    val bodyLine  = 18.sp
    val label     = 12.sp   // чипы, кнопки-чипы
    val caption   = 11.sp   // таймштампы, подписи, системные события
    val micro     = 9.sp    // бейджи
}

// ────────────────────────────────────────────────────────────
//  СЕТКА / РАДИУСЫ / РАЗМЕРЫ
// ────────────────────────────────────────────────────────────

object LearnDim {
    // Spacing (сетка 4)
    val s1: Dp = 4.dp
    val s2: Dp = 8.dp
    val s3: Dp = 12.dp
    val s4: Dp = 16.dp
    val s5: Dp = 20.dp
    val s6: Dp = 24.dp

    // Радиусы
    val rChip: Dp = 10.dp
    val rCard: Dp = 14.dp
    val rPanel: Dp = 20.dp
    val rBubble: Dp = 16.dp

    // Ключевые высоты единого экрана урока
    val statusBarH: Dp = 44.dp      // статус-капсула сверху
    val progressCollapsedH: Dp = 36.dp
    val hintChipH: Dp = 36.dp
    val bottomBarH: Dp = 76.dp
    val micButton: Dp = 60.dp
    val quickChipH: Dp = 32.dp
}

// ────────────────────────────────────────────────────────────
//  ДВИЖЕНИЕ
// ────────────────────────────────────────────────────────────

object LearnMotion {
    const val micro = 140       // нажатия, переключатели
    const val standard = 220    // раскрытие панелей, появление пузырей
    const val emphasized = 320  // смена экранов, диалоги
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

// ────────────────────────────────────────────────────────────
//  СТАТУСЫ СОЕДИНЕНИЯ → ЦВЕТ/ТЕКСТ (единая точка истины для UI)
// ────────────────────────────────────────────────────────────

@Composable
fun linkColor(stateName: String): Color {
    val c = learnColors()
    return when (stateName) {
        "READY"      -> c.success
        "ROTATING"   -> c.success            // ротация незаметна — остаёмся зелёными
        "CONNECTING" -> c.warning
        "RECOVERING" -> c.warning
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
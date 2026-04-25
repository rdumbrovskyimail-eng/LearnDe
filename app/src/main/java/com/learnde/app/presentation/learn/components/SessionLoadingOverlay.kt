// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/SessionLoadingOverlay.kt
//
// ИЗМЕНЕНИЕ: компонент больше НЕ показывает fullscreen затемнение.
// Старые места его вызова заменены на InlineLoadingBar.
// Этот файл оставлен как deprecated stub, чтобы не сломать билды,
// если где-то ещё остались ссылки. Внутри — пустой Composable.
//
// Когда все места перейдут на InlineLoadingBar — этот файл можно удалить.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import androidx.compose.runtime.Composable

/**
 * @deprecated Используйте [InlineLoadingBar] вместо fullscreen overlay.
 *   Новая версия не затемняет экран и встраивается в layout как обычный
 *   композитный элемент.
 */
@Deprecated(
    message = "Use InlineLoadingBar instead — fullscreen overlay was removed.",
    replaceWith = ReplaceWith(
        expression = "InlineLoadingBar(modifier)",
        imports = ["com.learnde.app.presentation.learn.components.InlineLoadingBar"],
    ),
)
@Composable
fun SessionLoadingOverlay() {
    // intentionally empty — старый full-screen overlay убран.
}

package com.learnde.app.util

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalContext

/**
 * Ресурсно-безопасные строки для UI.
 * ViewModel отдаёт UiText, Compose резолвит в строку.
 * Это позволяет тестировать ViewModel без Context.
 */
@Immutable
sealed interface UiText {

    @Immutable
    data class Plain(val value: String) : UiText

    @Immutable
    data class Resource(
        @StringRes val resId: Int,
        val args: List<Any> = emptyList()
    ) : UiText

    fun resolve(context: Context): String = when (this) {
        is Plain -> value
        is Resource -> {
            if (args.isEmpty()) {
                context.getString(resId)
            } else {
                context.getString(resId, *args.toTypedArray())
            }
        }
    }
}

/** Extension для удобного использования в Compose */
@Composable
fun UiText.resolve(): String {
    val context = LocalContext.current
    return resolve(context)
}
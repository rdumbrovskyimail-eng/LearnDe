// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1book/A1BookViewModel.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class A1BookUiState(
    val lessons: List<A1BookLessonMeta> = emptyList(),
    val loading: Boolean = true,
)

@HiltViewModel
class A1BookViewModel @Inject constructor(
    private val repo: A1BookRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(A1BookUiState())
    val state: StateFlow<A1BookUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = runCatching { repo.listLessons() }.getOrDefault(emptyList())
            _state.update { it.copy(lessons = list.sortedBy { m -> m.nummer }, loading = false) }
        }
    }

    /** Зафиксировать выбранный урок для сессии "a1_book" (читается в onEnter). */
    fun select(nummer: Int) {
        repo.selectedLesson = nummer
    }
}

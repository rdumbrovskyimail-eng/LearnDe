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
    /** Открытый для чтения урок («учебник»). null — показываем список. */
    val openLesson: A1BookLesson? = null,
    val openLoading: Boolean = false,
    val openError: Boolean = false,
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

    /**
     * Открыть урок в режиме «учебника»: грузим полный контент (грамматика,
     * таблицы, лексика, примеры, задания) и сразу фиксируем его как выбранный,
     * чтобы голосовая сессия стартовала именно с него.
     */
    fun open(nummer: Int) {
        repo.selectedLesson = nummer
        _state.update { it.copy(openLoading = true, openError = false, openLesson = null) }
        viewModelScope.launch {
            val lesson = runCatching { repo.loadLesson(nummer) }.getOrNull()
            _state.update {
                it.copy(
                    openLesson = lesson,
                    openLoading = false,
                    openError = lesson == null,
                )
            }
        }
    }

    /** Вернуться к списку уроков. */
    fun closeLesson() {
        _state.update { it.copy(openLesson = null, openError = false, openLoading = false) }
    }

    /** Зафиксировать выбранный урок для сессии "a1_book" (читается в onEnter). */
    fun select(nummer: Int) {
        repo.selectedLesson = nummer
    }
}

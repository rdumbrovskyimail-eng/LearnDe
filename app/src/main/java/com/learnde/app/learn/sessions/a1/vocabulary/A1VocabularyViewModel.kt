package com.learnde.app.learn.sessions.a1.vocabulary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.LemmaA1Entity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class A1VocabState(
    val all: List<LemmaA1Entity> = emptyList(),
    val filtered: List<LemmaA1Entity> = emptyList(),
    val total: Int = 0,
    val query: String = "",
    val filter: VocabFilter = VocabFilter.ALL,
    val expandedLemma: String? = null,
    val loading: Boolean = true,
)

sealed class A1VocabIntent {
    data class UpdateQuery(val q: String) : A1VocabIntent()
    data class SetFilter(val f: VocabFilter) : A1VocabIntent()
    data class ToggleExpand(val lemma: String) : A1VocabIntent()
}

@HiltViewModel
class A1VocabularyViewModel @Inject constructor(
    private val lemmaDao: A1LemmaDao,
) : ViewModel() {
    
    private val _state = MutableStateFlow(A1VocabState())
    val state: StateFlow<A1VocabState> = _state.asStateFlow()
    
    init {
        viewModelScope.launch {
            loadAll()
        }
    }
    
    fun onIntent(intent: A1VocabIntent) {
        when (intent) {
            is A1VocabIntent.UpdateQuery -> {
                _state.update { it.copy(query = intent.q) }
                applyFilter()
            }
            is A1VocabIntent.SetFilter -> {
                _state.update { it.copy(filter = intent.f) }
                applyFilter()
            }
            is A1VocabIntent.ToggleExpand -> {
                _state.update { 
                    it.copy(expandedLemma = if (it.expandedLemma == intent.lemma) null else intent.lemma) 
                }
            }
        }
    }
    
    private suspend fun loadAll() = withContext(Dispatchers.IO) {
        // Загружаем все леммы. Если нужен метод getAll() — добавить в DAO.
        val total = lemmaDao.getTotalCount()
        // Используем getByLemmas с пустым списком? Лучше добавить getAll() в DAO.
        // Workaround: используем getDueForReview с большим limit.
        val all = lemmaDao.getDueForReview(limit = 5000)  // FIXME: заменить на getAll()
        _state.update { it.copy(all = all, total = total, loading = false) }
        applyFilter()
    }
    
    private fun applyFilter() {
        val s = _state.value
        val now = System.currentTimeMillis()
        val byFilter = when (s.filter) {
            VocabFilter.ALL -> s.all
            VocabFilter.MASTERED -> s.all.filter { it.masteryScore >= 0.7f }
            VocabFilter.IN_PROGRESS -> s.all.filter { it.masteryScore in 0.3f..0.7f }
            VocabFilter.WEAK -> s.all.filter { it.timesHeard > 0 && it.masteryScore < 0.3f }
            VocabFilter.NEW -> s.all.filter { it.timesHeard == 0 }
            VocabFilter.DUE -> s.all.filter { it.nextReviewAt != null && it.nextReviewAt!! <= now }
        }
        val byQuery = if (s.query.isBlank()) byFilter
            else byFilter.filter { it.lemma.contains(s.query, ignoreCase = true) }
        _state.update { it.copy(filtered = byQuery) }
    }
}
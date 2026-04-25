package com.learnde.app.learn.sessions.a1.grammar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.db.A1GrammarDao
import com.learnde.app.learn.data.db.GrammarRuleA1Entity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GrammarSheetState(
    val rules: List<GrammarRuleA1Entity> = emptyList(),
    val introducedCount: Int = 0,
    val totalCount: Int = 0,
)

@HiltViewModel
class GrammarSheetViewModel @Inject constructor(
    private val grammarDao: A1GrammarDao,
) : ViewModel() {
    
    private val _state = MutableStateFlow(GrammarSheetState())
    val state: StateFlow<GrammarSheetState> = _state.asStateFlow()
    
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = grammarDao.getAllOrdered()
                val introduced = all.count { it.wasIntroduced }
                _state.update {
                    it.copy(
                        rules = all,
                        introducedCount = introduced,
                        totalCount = all.size,
                    )
                }
            }
        }
    }
}
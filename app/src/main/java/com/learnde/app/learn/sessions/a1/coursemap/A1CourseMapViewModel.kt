package com.learnde.app.learn.sessions.a1.coursemap

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.domain.A1SessionPlanner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class A1CourseMapState(
    val byCategory: Map<String, List<ClusterA1Entity>> = emptyMap(),
    val totalCount: Int = 0,
    val masteredCount: Int = 0,
    val currentClusterId: String? = null,
    val loading: Boolean = true,
)

@HiltViewModel
class A1CourseMapViewModel @Inject constructor(
    private val clusterDao: A1ClusterDao,
    private val planner: A1SessionPlanner,
) : ViewModel() {
    
    private val _state = MutableStateFlow(A1CourseMapState())
    val state: StateFlow<A1CourseMapState> = _state.asStateFlow()
    
    init {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val all = clusterDao.getAllOrdered()
                val current = planner.pickNextCluster()
                val grouped = all.groupBy { it.category }
                _state.update {
                    it.copy(
                        byCategory = grouped,
                        totalCount = all.size,
                        masteredCount = all.count { c -> c.isMastered },
                        currentClusterId = current?.id,
                        loading = false,
                    )
                }
            }
        }
    }
}
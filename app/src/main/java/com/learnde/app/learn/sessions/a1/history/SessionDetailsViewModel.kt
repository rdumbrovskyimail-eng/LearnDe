// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 4)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/SessionDetailsViewModel.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.learn.data.db.A1SessionDao
import com.learnde.app.learn.data.db.A1SessionLogEntity
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.util.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

data class LemmaDetailItem(
    val lemma: String,
    val article: String?,
    val wasProduced: Boolean,
    val wasFailed: Boolean,
    val wasTargeted: Boolean,
    val diagnosis: ErrorDiagnosis?,
)

data class SessionDetailsState(
    val loading: Boolean = true,
    val session: A1SessionLogEntity? = null,
    val cluster: ClusterA1Entity? = null,
    val lemmas: List<LemmaDetailItem> = emptyList(),
    val error: String? = null,
)

@HiltViewModel
class SessionDetailsViewModel @Inject constructor(
    private val sessionDao: A1SessionDao,
    private val clusterDao: A1ClusterDao,
    private val lemmaDao: A1LemmaDao,
    private val logger: AppLogger,
) : ViewModel() {

    private val _state = MutableStateFlow(SessionDetailsState())
    val state: StateFlow<SessionDetailsState> = _state.asStateFlow()

    fun load(sessionId: Long) {
        viewModelScope.launch {
            try {
                val session = sessionDao.getById(sessionId)
                if (session == null) {
                    _state.update { it.copy(loading = false, error = "Сессия не найдена") }
                    return@launch
                }

                val cluster = clusterDao.getById(session.clusterId)

                val targeted = parseList(session.lemmasTargetedJson)
                val produced = parseList(session.lemmasProducedJson).toSet()
                val failed = parseList(session.lemmasFailedJson).toSet()
                val diagnosesMap = parseDiagnosesMap(session.errorDiagnosesJson)

                // Собираем все уникальные леммы из сессии
                val allLemmaKeys = (targeted + produced + failed).distinct()
                val lemmaEntities = if (allLemmaKeys.isNotEmpty()) {
                    lemmaDao.getByLemmas(allLemmaKeys)
                } else emptyList()
                val lemmaByKey = lemmaEntities.associateBy { it.lemma }

                val items = allLemmaKeys.map { key ->
                    val entity = lemmaByKey[key]
                    LemmaDetailItem(
                        lemma = key,
                        article = entity?.article,
                        wasProduced = key in produced,
                        wasFailed = key in failed,
                        wasTargeted = key in targeted,
                        diagnosis = diagnosesMap[key],
                    )
                }.sortedWith(compareByDescending<LemmaDetailItem> { it.wasFailed }
                    .thenByDescending { it.wasProduced })

                _state.update {
                    it.copy(
                        loading = false,
                        session = session,
                        cluster = cluster,
                        lemmas = items,
                    )
                }
            } catch (e: Exception) {
                logger.e("SessionDetails load failed: ${e.message}", e)
                _state.update { it.copy(loading = false, error = e.message) }
            }
        }
    }

    private fun parseList(json: String): List<String> = try {
        if (json.isBlank() || json == "[]") emptyList()
        else Json.decodeFromString(json)
    } catch (e: kotlinx.serialization.SerializationException) {
        emptyList()
    } catch (e: IllegalArgumentException) {
        emptyList()
    }

    private fun parseDiagnosesMap(json: String): Map<String, ErrorDiagnosis> {
        if (json.isBlank() || json == "{}") return emptyMap()
        return try {
            val obj = Json.parseToJsonElement(json).jsonObject
            obj.mapValues { (_, value) ->
                val o = (value as JsonObject)
                ErrorDiagnosis(
                    source = ErrorSource.fromString(o["source"]?.jsonPrimitive?.content),
                    depth = ErrorDepth.fromString(o["depth"]?.jsonPrimitive?.content),
                    category = ErrorCategory.fromString(o["category"]?.jsonPrimitive?.content),
                    specifics = o["specifics"]?.jsonPrimitive?.content ?: "",
                )
            }
        } catch (e: Exception) {
            logger.w("Failed to parse errorDiagnosesJson: ${e.message}")
            emptyMap()
        }
    }
}
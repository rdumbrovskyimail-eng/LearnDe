// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ (Patch 4)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/SessionDetailsScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailsScreen(
    sessionId: Long,
    onBack: () -> Unit,
    onRepeatCluster: (String) -> Unit,
    onStartNewReview: () -> Unit,
    vm: SessionDetailsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Детали урока", fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp)
        ) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Загрузка...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                state.error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Ошибка: ${state.error}", color = Color(0xFFE53935))
                }
                state.session != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item { SessionSummaryCard(state) }
                        item { StatsCard(state) }
                        if (state.lemmas.isNotEmpty()) {
                            item {
                                Text("Леммы этой сессии",
                                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            items(state.lemmas, key = { it.lemma }) { item ->
                                LemmaDetailCard(item)
                            }
                        }
                        if (!state.session!!.feedbackText.isBlank()) {
                            item { FeedbackCard(state.session!!.feedbackText) }
                        }
                        item {
                            val isReviewSession = state.session!!.clusterId == "review"
                            if (isReviewSession) {
                                Button(
                                    onClick = { onStartNewReview() },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF7B1FA2),
                                    ),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Начать новое повторение",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { onRepeatCluster(state.session!!.clusterId) },
                                    modifier = Modifier.fillMaxWidth().height(48.dp),
                                ) {
                                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Повторить этот урок",
                                        color = Color.White,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(state: SessionDetailsState) {
    val session = state.session ?: return
    val cluster = state.cluster

    val completeColor = if (session.isComplete) Color(0xFF43A047) else Color(0xFFFB8C00)
    val qualityColor = when (session.overallQuality) {
        in 6..7 -> Color(0xFF43A047)
        in 4..5 -> Color(0xFFFB8C00)
        else -> Color(0xFFE53935)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(qualityColor),
                contentAlignment = Alignment.Center
            ) {
                Text("${session.overallQuality}", color = Color.White,
                    fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(cluster?.titleRu ?: session.clusterId,
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                cluster?.titleDe?.let {
                    Text(it, fontSize = 12.sp, fontFamily = FontFamily.Serif,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatFullDate(session.startedAt)} · ${session.durationMinutes} мин",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row {
            StatusBadge(
                text = if (session.isComplete) "Завершена" else "Прервана",
                color = completeColor,
            )
            Spacer(Modifier.width(6.dp))
            StatusBadge(
                text = "Фаза: ${session.phaseReached}",
                color = MaterialTheme.colorScheme.primary,
            )
            if (session.avgQuality > 0) {
                Spacer(Modifier.width(6.dp))
                StatusBadge(
                    text = "Ø ${String.format(Locale.ROOT, "%.1f", session.avgQuality)}",
                    color = Color(0xFF7B1FA2),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text, fontSize = 10.sp, color = color, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun StatsCard(state: SessionDetailsState) {
    val session = state.session ?: return
    val produced = state.lemmas.count { it.wasProduced && !it.wasFailed }
    val failed = state.lemmas.count { it.wasFailed }
    val targeted = state.lemmas.count { it.wasTargeted }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        StatBox("Целевых", "$targeted", Color(0xFF64B5F6))
        StatBox("Освоено", "$produced", Color(0xFF43A047))
        StatBox("Ошибок", "$failed", Color(0xFFE53935))
        StatBox("Оценок", "${session.evaluateCallsCount}", Color(0xFFFB8C00))
    }
}

@Composable
private fun StatBox(label: String, value: String, color: Color) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 9.sp, color = color.copy(alpha = 0.8f),
            fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LemmaDetailCard(item: LemmaDetailItem) {
    val (bgColor, borderColor) = when {
        item.wasFailed -> Color(0xFFE53935).copy(alpha = 0.08f) to Color(0xFFE53935)
        item.wasProduced -> Color(0xFF43A047).copy(alpha = 0.08f) to Color(0xFF43A047)
        else -> MaterialTheme.colorScheme.surfaceVariant to Color.Transparent
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusIcon = when {
                item.wasProduced && !item.wasFailed -> Icons.Filled.CheckCircle to Color(0xFF43A047)
                item.wasFailed -> Icons.Filled.Close to Color(0xFFE53935)
                else -> null
            }
            statusIcon?.let { (icon, color) ->
                Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            val articlePrefix = item.article?.let { "$it " } ?: ""
            Text("$articlePrefix${item.lemma}",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        if (item.diagnosis != null && item.diagnosis.isError) {
            Spacer(Modifier.height(6.dp))
            DiagnosisRow(item.diagnosis)
            if (item.diagnosis.specifics.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("💡 ${item.diagnosis.specifics}",
                    fontSize = 11.sp, lineHeight = 15.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DiagnosisRow(diagnosis: ErrorDiagnosis) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sourceLabel(diagnosis.source)?.let { MiniChip(it, Color(0xFFFB8C00)) }
        depthLabel(diagnosis.depth)?.let { (label, color) -> MiniChip(label, color) }
        categoryLabel(diagnosis.category)?.let { MiniChip(it, Color(0xFF7B1FA2)) }
    }
}

@Composable
private fun MiniChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text, fontSize = 9.sp, color = color,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun FeedbackCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .padding(12.dp)
    ) {
        Text("Обратная связь", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(4.dp))
        Text(text, fontSize = 13.sp, lineHeight = 18.sp)
    }
}

private fun sourceLabel(s: ErrorSource): String? = when (s) {
    ErrorSource.L1_TRANSFER -> "Русский→немецкий"
    ErrorSource.OVERGENERALIZATION -> "Широкое правило"
    ErrorSource.SIMPLIFICATION -> "Упрощение"
    ErrorSource.COMMUNICATION_STRATEGY -> "Обход"
    ErrorSource.NONE -> null
}

private fun depthLabel(d: ErrorDepth): Pair<String, Color>? = when (d) {
    ErrorDepth.SLIP -> "оговорка" to Color(0xFF43A047)
    ErrorDepth.MISTAKE -> "неуверенность" to Color(0xFFFDD835)
    ErrorDepth.ERROR -> "не знал" to Color(0xFFE53935)
    ErrorDepth.NONE -> null
}

private fun categoryLabel(c: ErrorCategory): String? = when (c) {
    ErrorCategory.GENDER -> "артикль"
    ErrorCategory.CASE -> "падеж"
    ErrorCategory.WORD_ORDER -> "порядок"
    ErrorCategory.LEXICAL -> "слово"
    ErrorCategory.PHONOLOGY -> "звук"
    ErrorCategory.PRAGMATICS -> "регистр"
    ErrorCategory.CONJUGATION -> "спряжение"
    ErrorCategory.NEGATION -> "отрицание"
    ErrorCategory.PLURAL -> "мн. число"
    ErrorCategory.PREPOSITION -> "предлог"
    ErrorCategory.NONE -> null
}

private fun formatFullDate(ts: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(ts))
// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/history/SessionDetailsScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.domain.ErrorCategory
import com.learnde.app.learn.domain.ErrorDepth
import com.learnde.app.learn.domain.ErrorDiagnosis
import com.learnde.app.learn.domain.ErrorSource
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.Plural
import com.learnde.app.presentation.learn.theme.learnColors
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
    val colors = learnColors()

    LaunchedEffect(sessionId) { vm.load(sessionId) }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Детали урока",
                        fontSize = LearnTokens.FontSizeTitle,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textHi,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            "Назад",
                            tint = colors.textHi,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingMd),
        ) {
            when {
                state.loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Загрузка…", color = colors.textLow)
                    }
                state.error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Ошибка: ${state.error}", color = colors.error)
                    }
                state.session != null -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingMd),
                    ) {
                        item { SessionSummaryCard(state) }
                        item { StatsCard(state) }
                        if (state.lemmas.isNotEmpty()) {
                            item {
                                Text(
                                    "Леммы этой сессии",
                                    fontSize = LearnTokens.FontSizeBody,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colors.textMid,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                            items(state.lemmas, key = { it.lemma }) { item ->
                                LemmaDetailCard(item)
                            }
                        }
                        if (state.session?.feedbackText?.isNotBlank() == true) {
                            item { FeedbackCard(state.session!!.feedbackText) }
                        }
                        item {
                            val isReviewSession = state.session!!.clusterId == "review"
                            Button(
                                onClick = {
                                    if (isReviewSession) onStartNewReview()
                                    else onRepeatCluster(state.session!!.clusterId)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(LearnTokens.ButtonHeightMd),
                                shape = RoundedCornerShape(LearnTokens.RadiusSm),
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent),
                            ) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (isReviewSession) "Начать новое повторение"
                                    else "Повторить этот урок",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(LearnTokens.PaddingMd)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionSummaryCard(state: SessionDetailsState) {
    val colors = learnColors()
    val session = state.session ?: return
    val cluster = state.cluster
    val completeColor = if (session.isComplete) colors.success else colors.warn
    val qualityColor = when {
        session.overallQuality >= 6 -> colors.success
        session.overallQuality >= 4 -> colors.warn
        else -> colors.error
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusMd))
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(qualityColor),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${session.overallQuality}",
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = LearnTokens.FontSizeTitle,
                )
            }
            Spacer(Modifier.width(LearnTokens.PaddingMd))
            Column(Modifier.weight(1f)) {
                Text(
                    cluster?.titleRu ?: session.clusterId.ifBlank { "Сессия" },
                    fontSize = LearnTokens.FontSizeBodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textHi,
                )
                cluster?.titleDe?.let {
                    Text(
                        it,
                        fontSize = LearnTokens.FontSizeCaption,
                        color = colors.textMid,
                    )
                }
                Spacer(Modifier.height(4.dp))
                val mins = session.durationMinutes
                val dateStr = com.learnde.app.learn.sessions.a1.util.A1DateFormatters.formatFullDate(session.startedAt)
                val timeStr = java.text.SimpleDateFormat("HH:mm", java.util.Locale("ru")).format(java.util.Date(session.startedAt))
                Column {
                    Text(
                        "$dateStr · $timeStr",
                        fontSize = LearnTokens.FontSizeMicro,
                        color = colors.textLow,
                    )
                    Text(
                        if (mins == 0) "<1 мин" else "$mins ${Plural.minute(mins)}",
                        fontSize = LearnTokens.FontSizeMicro,
                        color = colors.textLow,
                    )
                }
            }
        }
        Spacer(Modifier.height(LearnTokens.PaddingSm))
        Row {
            StatusBadge(
                text = if (session.isComplete) "Завершена" else "Прервана",
                color = completeColor,
            )
            Spacer(Modifier.width(6.dp))
            StatusBadge(text = "Фаза: ${session.phaseReached}", color = colors.accent)
            if (session.avgQuality > 0) {
                Spacer(Modifier.width(6.dp))
                StatusBadge(
                    text = "Ø ${String.format(Locale.ROOT, "%.1f", session.avgQuality)}",
                    color = colors.textMid,
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = LearnTokens.FontSizeMicro,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StatsCard(state: SessionDetailsState) {
    val colors = learnColors()
    val session = state.session ?: return
    val produced = state.lemmas.count { it.wasProduced && !it.wasFailed }
    val failed = state.lemmas.count { it.wasFailed }
    val targeted = state.lemmas.count { it.wasTargeted }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LearnTokens.PaddingSm),
    ) {
        StatBox("Целевых", "$targeted", colors.accent, Modifier.weight(1f))
        StatBox("Освоено", "$produced", colors.success, Modifier.weight(1f))
        StatBox("Ошибок", "$failed", colors.error, Modifier.weight(1f))
        StatBox("Оценок", "${session.evaluateCallsCount}", colors.warn, Modifier.weight(1f))
    }
}

@Composable
private fun StatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    val colors = learnColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusXs))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(value, fontSize = LearnTokens.FontSizeBodyLarge, fontWeight = FontWeight.Bold, color = valueColor)
        Text(label, fontSize = 9.sp, color = colors.textLow, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun LemmaDetailCard(item: LemmaDetailItem) {
    val colors = learnColors()
    val (bgColor, borderColor) = when {
        item.wasFailed -> colors.errorSoft to colors.error.copy(alpha = 0.3f)
        item.wasProduced -> colors.successSoft to colors.success.copy(alpha = 0.3f)
        else -> colors.surface to colors.stroke
    }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(bgColor)
            .border(LearnTokens.BorderThin, borderColor, RoundedCornerShape(LearnTokens.RadiusXs))
            .padding(LearnTokens.PaddingSm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val statusIcon = when {
                item.wasProduced && !item.wasFailed -> Icons.Filled.CheckCircle to colors.success
                item.wasFailed -> Icons.Filled.Close to colors.error
                else -> null
            }
            statusIcon?.let { (icon, color) ->
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
            }
            val articlePrefix = item.article?.let { "$it " } ?: ""
            Text(
                "$articlePrefix${item.lemma}",
                fontSize = LearnTokens.FontSizeBody,
                fontWeight = FontWeight.SemiBold,
                color = colors.textHi,
            )
        }

        if (item.diagnosis != null && item.diagnosis.isError) {
            Spacer(Modifier.height(4.dp))
            DiagnosisRow(item.diagnosis)
            if (item.diagnosis.specifics.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    item.diagnosis.specifics,
                    fontSize = LearnTokens.FontSizeCaption,
                    lineHeight = 15.sp,
                    color = colors.textMid,
                )
            }
        }
    }
}

@Composable
private fun DiagnosisRow(diagnosis: ErrorDiagnosis) {
    val colors = learnColors()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        sourceLabel(diagnosis.source)?.let { MiniChip(it, colors.warn) }
        depthLabel(diagnosis.depth)?.let { (label, _) -> MiniChip(label, colors.textMid) }
        categoryLabel(diagnosis.category)?.let { MiniChip(it, colors.accent) }
    }
}

@Composable
private fun MiniChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(text, fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FeedbackCard(text: String) {
    val colors = learnColors()
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.accentSoft)
            .padding(LearnTokens.PaddingMd),
    ) {
        Text(
            "Обратная связь",
            fontSize = LearnTokens.FontSizeMicro,
            fontWeight = FontWeight.SemiBold,
            color = colors.accent,
            letterSpacing = LearnTokens.CapsLetterSpacing,
        )
        Spacer(Modifier.height(4.dp))
        Text(text, fontSize = LearnTokens.FontSizeBody, lineHeight = 18.sp, color = colors.textHi)
    }
}

private fun sourceLabel(s: ErrorSource): String? = when (s) {
    ErrorSource.L1_TRANSFER -> "русский → немецкий"
    ErrorSource.OVERGENERALIZATION -> "широкое правило"
    ErrorSource.SIMPLIFICATION -> "упрощение"
    ErrorSource.COMMUNICATION_STRATEGY -> "обход"
    ErrorSource.NONE -> null
}

private fun depthLabel(d: ErrorDepth): Pair<String, Color>? = when (d) {
    ErrorDepth.SLIP -> "оговорка" to Color.Unspecified
    ErrorDepth.MISTAKE -> "неуверенность" to Color.Unspecified
    ErrorDepth.ERROR -> "не знал" to Color.Unspecified
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

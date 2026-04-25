// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/vocabulary/A1VocabularyScreen.kt
//
// ИЗМЕНЕНИЯ v5.0:
//   1. Единая тема LearnColors.
//   2. LazyRow вместо вложенного LazyColumn (избавляемся от warning).
//   3. Артикль der/die/das цветным префиксом перед леммой (нем. традиция).
//   4. Фильтры всегда видны (горизонтальный скролл).
//   5. Раскрывающаяся карточка с FSRS-статами + индикатор статуса.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.vocabulary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.LemmaA1Entity
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

enum class VocabFilter(val label: String) {
    ALL("Все"),
    MASTERED("Освоенные"),
    IN_PROGRESS("В процессе"),
    WEAK("Слабые"),
    NEW("Новые"),
    DUE("На повтор"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1VocabularyScreen(
    onBack: () -> Unit,
    vm: A1VocabularyViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colors = learnColors()

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Мой словарь",
                            fontSize = LearnTokens.FontSizeTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textHi,
                        )
                        Text(
                            "${state.filtered.size} из ${state.total}",
                            fontSize = LearnTokens.FontSizeMicro,
                            color = colors.textLow,
                        )
                    }
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
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingMd),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = { vm.onIntent(A1VocabIntent.UpdateQuery(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text("Поиск слова…", color = colors.textLow)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, null, tint = colors.textMid)
                },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onIntent(A1VocabIntent.UpdateQuery("")) }) {
                            Icon(Icons.Filled.Clear, "Очистить", tint = colors.textMid)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.stroke,
                    focusedTextColor = colors.textHi,
                    unfocusedTextColor = colors.textHi,
                ),
            )

            Spacer(Modifier.height(LearnTokens.PaddingSm))

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(VocabFilter.values().toList()) { filter ->
                    FilterChip(
                        text = filter.label,
                        selected = state.filter == filter,
                        onClick = { vm.onIntent(A1VocabIntent.SetFilter(filter)) },
                    )
                }
            }

            Spacer(Modifier.height(LearnTokens.PaddingSm))

            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Загрузка…", color = colors.textLow, fontSize = LearnTokens.FontSizeBody)
                }
            } else if (state.filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.query.isNotBlank() -> "Ничего не найдено"
                            state.filter == VocabFilter.ALL -> "Словарь ещё не загружен"
                            else -> "Нет слов в этой категории"
                        },
                        color = colors.textLow,
                        fontSize = LearnTokens.FontSizeBody,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.filtered, key = { it.lemma }) { lemma ->
                        LemmaCard(
                            lemma = lemma,
                            isExpanded = state.expandedLemma == lemma.lemma,
                            onClick = { vm.onIntent(A1VocabIntent.ToggleExpand(lemma.lemma)) },
                        )
                    }
                    item { Spacer(Modifier.height(LearnTokens.PaddingMd)) }
                }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = learnColors()
    val bg = if (selected) colors.accentSoft else colors.surface
    val border = if (selected) colors.accent.copy(alpha = 0.4f) else colors.stroke
    val textColor = if (selected) colors.accent else colors.textMid
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(bg)
            .border(LearnTokens.BorderThin, border, RoundedCornerShape(LearnTokens.RadiusXs))
            .clickable { onClick() }
            .padding(horizontal = LearnTokens.PaddingMd, vertical = 6.dp),
    ) {
        Text(
            text,
            fontSize = LearnTokens.FontSizeCaption,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = textColor,
        )
    }
}

@Composable
private fun LemmaCard(
    lemma: LemmaA1Entity,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = learnColors()
    val mastery = lemma.masteryScore
    val (statusColor, statusText) = when {
        lemma.timesHeard == 0 -> colors.textLow to "новое"
        mastery >= 0.7f -> colors.success to "освоено"
        mastery >= 0.3f -> colors.warn to "в процессе"
        else -> colors.error to "слабое"
    }
    val articleColor = when (lemma.article) {
        "der" -> colors.accent           // m → синий
        "die" -> colors.error            // f → красный
        "das" -> colors.success          // n → зелёный
        else -> colors.textMid
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .border(
                LearnTokens.BorderThin,
                if (isExpanded) statusColor.copy(alpha = 0.4f) else colors.stroke,
                RoundedCornerShape(LearnTokens.RadiusSm),
            )
            .clickable { onClick() }
            .padding(LearnTokens.PaddingMd),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(LearnTokens.PaddingSm))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lemma.article != null) {
                        Text(
                            "${lemma.article}",
                            fontSize = LearnTokens.FontSizeBodyLarge,
                            fontWeight = FontWeight.Normal,
                            color = articleColor,
                        )
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        lemma.lemma,
                        fontSize = LearnTokens.FontSizeBodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textHi,
                    )
                }
                Text(
                    "${lemma.pos} · $statusText",
                    fontSize = 10.sp,
                    color = colors.textLow,
                )
            }
            Text(
                "${(mastery * 100).toInt()}%",
                fontSize = LearnTokens.FontSizeBody,
                fontWeight = FontWeight.Bold,
                color = statusColor,
            )
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.stroke),
                )
                Spacer(Modifier.height(LearnTokens.PaddingSm))
                StatRow("Слышал", "${lemma.timesHeard}")
                StatRow("Правильно", "${lemma.timesProduced}")
                StatRow("Ошибок", "${lemma.timesFailed}")
                StatRow("FSRS повторений", "${lemma.fsrsReps}")
                StatRow("FSRS пропусков", "${lemma.fsrsLapses}")
                lemma.nextReviewAt?.let { ts ->
                    val daysUntil = ((ts - System.currentTimeMillis()) / (24 * 3600 * 1000)).toInt()
                    val text = when {
                        daysUntil <= 0 -> "сейчас"
                        daysUntil == 1 -> "завтра"
                        else -> "через $daysUntil дн."
                    }
                    StatRow("Следующее повторение", text)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    val colors = learnColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = LearnTokens.FontSizeCaption,
            color = colors.textMid,
        )
        Text(
            value,
            fontSize = LearnTokens.FontSizeCaption,
            fontWeight = FontWeight.SemiBold,
            color = colors.textHi,
        )
    }
}

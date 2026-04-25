// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/vocabulary/A1VocabularyScreen.kt
//
// Экран "Мой словарь" — список всех 835 лемм A1 с:
//   - поиском по подстроке
//   - фильтром (Все / Освоенные / В процессе / Слабые / Новые / На повтор)
//   - цветовым индикатором masteryScore
//   - разворачиваемой карточкой с FSRS-статистикой
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.vocabulary

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.LemmaA1Entity

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
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Мой словарь", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${state.filtered.size} из ${state.total}", 
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp)
        ) {
            // ─── Поиск ───
            OutlinedTextField(
                value = state.query,
                onValueChange = { vm.onIntent(A1VocabIntent.UpdateQuery(it)) },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск слова...") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { vm.onIntent(A1VocabIntent.UpdateQuery("")) }) {
                            Icon(Icons.Filled.Clear, "Очистить")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            
            Spacer(Modifier.height(8.dp))
            
            // ─── Фильтры ───
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 60.dp),
            ) {
                item {
                    androidx.compose.foundation.lazy.LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(VocabFilter.values().toList()) { filter ->
                            FilterChip(
                                selected = state.filter == filter,
                                onClick = { vm.onIntent(A1VocabIntent.SetFilter(filter)) },
                                label = { Text(filter.label, fontSize = 11.sp) },
                            )
                        }
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            // ─── Список ───
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.filtered, key = { it.lemma }) { lemma ->
                    LemmaCard(
                        lemma = lemma,
                        isExpanded = state.expandedLemma == lemma.lemma,
                        onClick = { vm.onIntent(A1VocabIntent.ToggleExpand(lemma.lemma)) }
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LemmaCard(
    lemma: LemmaA1Entity,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val mastery = lemma.masteryScore
    val (statusColor, statusText) = when {
        lemma.timesHeard == 0 -> Color(0xFF9E9E9E) to "новое"
        mastery >= 0.7f -> Color(0xFF43A047) to "освоено"
        mastery >= 0.3f -> Color(0xFFFB8C00) to "в процессе"
        else -> Color(0xFFE53935) to "слабое"
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .border(
                1.dp, 
                statusColor.copy(alpha = 0.3f), 
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                val articlePrefix = lemma.article?.let { "$it " } ?: ""
                Text(
                    "$articlePrefix${lemma.lemma}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${lemma.pos} · $statusText",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Mastery percentage
            Text(
                "${(mastery * 100).toInt()}%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
            )
        }
        
        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                StatRow("Слышал", "${lemma.timesHeard}")
                StatRow("Правильно", "${lemma.timesProduced}")
                StatRow("Ошибок", "${lemma.timesFailed}")
                StatRow("FSRS повторений", "${lemma.fsrsReps}")
                StatRow("FSRS пропусков", "${lemma.fsrsLapses}")
                lemma.nextReviewAt?.let {
                    val daysUntil = ((it - System.currentTimeMillis()) / (24 * 3600 * 1000)).toInt()
                    val text = when {
                        daysUntil <= 0 -> "сейчас"
                        daysUntil == 1 -> "завтра"
                        else -> "через $daysUntil дней"
                    }
                    StatRow("Следующее повторение", text)
                }
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
        )
    }
}
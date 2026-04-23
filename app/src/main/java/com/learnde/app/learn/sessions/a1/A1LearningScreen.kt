package com.learnde.app.learn.sessions.a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.components.SessionLoadingOverlay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1LearningScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    vm: A1LearningViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val sessionId = if (state.isReviewMode) "a1_review" else "a1_situation"
            learnCoreViewModel.onIntent(LearnCoreIntent.Start(sessionId))
        } else {
            Toast.makeText(context, "Для обучения нужен микрофон", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        vm.effects.collect { effect ->
            when (effect) {
                is A1LearningEffect.RequestStartSession -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        learnCoreViewModel.onIntent(LearnCoreIntent.Start("a1_situation"))
                    } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                is A1LearningEffect.RequestStartReviewSession -> {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        learnCoreViewModel.onIntent(LearnCoreIntent.Start("a1_review"))
                    } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
                is A1LearningEffect.RequestStopSession -> learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
                is A1LearningEffect.ShowToast -> Toast.makeText(context, effect.msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val exitAndBack: () -> Unit = {
        if (state.sessionActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (state.isReviewMode) Icons.Filled.Refresh else Icons.Filled.School, null,
                            tint = if (state.isReviewMode) Color(0xFF7B1FA2) else MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isReviewMode) "Повторение слабых" else "Обучение A1", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                navigationIcon = { IconButton(onClick = exitAndBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    IconButton(onClick = onOpenDebugLogs) { Icon(Icons.Filled.BugReport, "Логи") }
                    IconButton(onClick = onOpenHistory) { Icon(Icons.Filled.History, "История") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = { Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { CurrentFunctionBar(status = fnStatus) } }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            ) {
                if (state.loading) return@Column
                if (state.error != null) { Text("Ошибка: ${state.error}", color = Color(0xFFE53935)); return@Column }

                ProgressSummary(state)
                Spacer(Modifier.height(12.dp))

                if (!state.isReviewMode) {
                    state.currentCluster?.let { cluster ->
                        CurrentClusterCard(cluster.titleRu, cluster.titleDe, cluster.scenarioHint, cluster.grammarFocus, cluster.difficulty, state.sessionActive)
                    } ?: Text("Все кластеры пройдены!", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                } else {
                    ReviewSessionCard(weakCount = state.weakLemmasCount)
                }

                Spacer(Modifier.height(12.dp))

                AnimatedVisibility(visible = state.sessionActive && !state.isReviewMode, enter = fadeIn() + slideInVertically { -it/2 }, exit = fadeOut() + slideOutVertically { -it/2 }) {
                    PhaseIndicator(state.currentPhase)
                }

                Spacer(Modifier.height(8.dp))

                AnimatedVisibility(visible = state.lastEvaluation != null, enter = fadeIn() + slideInVertically { it/2 }, exit = fadeOut()) {
                    state.lastEvaluation?.let { ev -> LemmaEvaluationCard(ev) { vm.onIntent(A1LearningIntent.DisputeEvaluation(ev.lemma)) } }
                }

                Spacer(Modifier.height(8.dp))

                if (state.sessionActive) {
                    SessionLiveStats(state.lemmasHeardThisSession.size, state.lemmasProducedThisSession.size, state.lemmasFailedThisSession.size, state.grammarIntroducedInSession)
                    Spacer(Modifier.height(8.dp))
                }

                if (!state.sessionActive && !state.isReviewMode) {
                    GrammarProgressRow(state.grammarIntroduced, state.grammarTotal)
                    Spacer(Modifier.height(8.dp))
                }

                if (state.sessionActive && learnState.transcript.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Субтитры:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)).padding(8.dp)) {
                        LazyColumn(modifier = Modifier.fillMaxSize(), reverseLayout = true) {
                            val messages = remember(learnState.transcript) { learnState.transcript.reversed().filter { it.role == com.learnde.app.domain.model.ConversationMessage.ROLE_MODEL } }
                            items(messages) { msg ->
                                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
                                    Text(msg.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).padding(8.dp))
                                }
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                BottomActionButton(state, vm, learnState.connectionStatus)
                Spacer(Modifier.height(8.dp))
            }

            AnimatedVisibility(
                visible = learnState.isPreparingSession,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                SessionLoadingOverlay()
            }
        }
    }

    if (state.sessionFinished) {
        SessionFinishedDialog(state.finalQuality ?: 5, state.finalFeedback ?: "", state.lemmasProducedThisSession.size, state.lemmasFailedThisSession.size, state.isReviewMode) { vm.onIntent(A1LearningIntent.DismissFinalDialog) }
    }
    if (state.isA1Completed && state.currentCluster == null) A1CompletedDialog(onClose = onBack)
}

@Composable private fun ProgressSummary(state: A1LearningState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CircularProgressItem("Леммы", state.lemmasMastered, state.totalLemmas, MaterialTheme.colorScheme.primary, if (state.lemmasInProgress > 0) "+${state.lemmasInProgress}" else null)
            CircularProgressItem("Кластеры", state.clustersMastered, state.totalClusters, Color(0xFF43A047))
            CircularProgressItem("Правила", state.grammarIntroduced, state.grammarTotal, Color(0xFFFB8C00))
        }
        if (state.lemmasInProgress > 0) {
            Spacer(Modifier.height(6.dp))
            Text("В процессе изучения: ${state.lemmasInProgress} лемм", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun CircularProgressItem(label: String, current: Int, total: Int, color: Color, subValue: String? = null) {
    val fraction by animateFloatAsState(targetValue = if (total == 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f), animationSpec = tween(900, easing = FastOutSlowInEasing), label = "progress")
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(92.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val stroke = with(density) { 9.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                drawArc(color = trackColor, startAngle = -90f, sweepAngle = 360f, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(brush = Brush.sweepGradient(0f to color.copy(alpha = 0.75f), 1f to color, center = this.center), startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$current", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                Text("/$total", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                subValue?.let { Text(it, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = color) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun CurrentClusterCard(titleRu: String, titleDe: String, scenario: String, grammarFocus: String, difficulty: Int, isActive: Boolean) {
    val bgColor = if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(bgColor).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(titleRu, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Box(Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("Уровень $difficulty", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(titleDe, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Serif)
        Spacer(Modifier.height(8.dp))
        Text(scenario, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Грамматика:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Text(grammarFocus, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable private fun ReviewSessionCard(weakCount: Int) {
    val purple = Color(0xFF7B1FA2)
    Column(Modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(purple.copy(alpha = 0.12f)).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Refresh, null, tint = purple, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Быстрое повторение", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = purple)
        }
        Spacer(Modifier.height(4.dp))
        Text("Сейчас у тебя $weakCount слов, которые стоит повторить.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
        Spacer(Modifier.height(4.dp))
        Text("Сессия займёт 5-7 минут. Только drill — без новых слов и грамматики.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
    }
}

@Composable private fun PhaseIndicator(phase: A1Phase) {
    val (label, color) = when (phase) {
        A1Phase.IDLE -> "Готовимся..." to Color.Gray
        A1Phase.WARM_UP -> "1/6 · Разминка" to Color(0xFF64B5F6)
        A1Phase.INTRODUCE -> "2/6 · Новые слова" to Color(0xFF1E88E5)
        A1Phase.DRILL -> "3/6 · Тренировка" to Color(0xFFFB8C00)
        A1Phase.APPLY -> "4/6 · Применение" to Color(0xFF7CB342)
        A1Phase.GRAMMAR -> "5/6 · Правило" to Color(0xFFAB47BC)
        A1Phase.COOL_DOWN -> "6/6 · Итог" to Color(0xFF43A047)
        A1Phase.FINISHED -> "Завершено!" to Color(0xFF2E7D32)
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable private fun LemmaEvaluationCard(ev: LastEvaluation, onDispute: () -> Unit) {
    val color = when (ev.quality) {
        7 -> Color(0xFF2E7D32); 6 -> Color(0xFF43A047); 5 -> Color(0xFF7CB342)
        4 -> Color(0xFFFDD835); 3 -> Color(0xFFFB8C00); 2 -> Color(0xFFF4511E)
        else -> Color(0xFFE53935)
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.12f)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text("${ev.quality}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(ev.lemma, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (ev.feedback.isNotBlank()) Text(ev.feedback, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
            }
            if (ev.wasCorrect) Icon(Icons.Filled.CheckCircle, null, tint = Color(0xFF43A047), modifier = Modifier.size(22.dp))
        }
        if (ev.diagnosis.isError) {
            Spacer(Modifier.height(8.dp))
            DiagnosisChips(ev)
            if (ev.diagnosis.specifics.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("💡 ${ev.diagnosis.specifics}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
            }
        }
        if (!ev.wasCorrect) {
            Spacer(Modifier.height(6.dp))
            TextButton(onClick = onDispute, modifier = Modifier.align(Alignment.End).height(28.dp), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Я сказал правильно!", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable private fun DiagnosisChips(ev: LastEvaluation) {
    val sourceLabel = when (ev.diagnosis.source) {
        com.learnde.app.learn.domain.ErrorSource.L1_TRANSFER -> "Влияние русского"
        com.learnde.app.learn.domain.ErrorSource.OVERGENERALIZATION -> "Широкое правило"
        com.learnde.app.learn.domain.ErrorSource.SIMPLIFICATION -> "Упрощение"
        com.learnde.app.learn.domain.ErrorSource.COMMUNICATION_STRATEGY -> "Обход"
        com.learnde.app.learn.domain.ErrorSource.NONE -> null
    }
    val depthLabel = when (ev.diagnosis.depth) {
        com.learnde.app.learn.domain.ErrorDepth.SLIP -> "оговорка"
        com.learnde.app.learn.domain.ErrorDepth.MISTAKE -> "неуверенность"
        com.learnde.app.learn.domain.ErrorDepth.ERROR -> "не знал"
        com.learnde.app.learn.domain.ErrorDepth.NONE -> null
    }
    val categoryLabel = when (ev.diagnosis.category) {
        com.learnde.app.learn.domain.ErrorCategory.GENDER -> "артикль"
        com.learnde.app.learn.domain.ErrorCategory.CASE -> "падеж"
        com.learnde.app.learn.domain.ErrorCategory.WORD_ORDER -> "порядок слов"
        com.learnde.app.learn.domain.ErrorCategory.LEXICAL -> "слово"
        com.learnde.app.learn.domain.ErrorCategory.PHONOLOGY -> "звук"
        com.learnde.app.learn.domain.ErrorCategory.PRAGMATICS -> "регистр"
        com.learnde.app.learn.domain.ErrorCategory.CONJUGATION -> "спряжение"
        com.learnde.app.learn.domain.ErrorCategory.NEGATION -> "отрицание"
        com.learnde.app.learn.domain.ErrorCategory.PLURAL -> "мн. число"
        com.learnde.app.learn.domain.ErrorCategory.PREPOSITION -> "предлог"
        com.learnde.app.learn.domain.ErrorCategory.NONE -> null
    }

    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        sourceLabel?.let { Chip(it, Color(0xFFFB8C00)) }
        depthLabel?.let {
            val c = when (ev.diagnosis.depth) {
                com.learnde.app.learn.domain.ErrorDepth.SLIP -> Color(0xFF43A047)
                com.learnde.app.learn.domain.ErrorDepth.MISTAKE -> Color(0xFFFDD835)
                com.learnde.app.learn.domain.ErrorDepth.ERROR -> Color(0xFFE53935)
                else -> Color.Gray
            }
            Chip(it, c)
        }
        categoryLabel?.let { Chip(it, Color(0xFF7B1FA2)) }
    }
}

@Composable private fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, fontSize = 9.sp, color = color, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

@Composable private fun SessionLiveStats(heard: Int, produced: Int, failed: Int, grammarIntroduced: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        StatChip("👂 $heard", "слышал", Color(0xFF64B5F6))
        StatChip("✓ $produced", "произнёс", Color(0xFF43A047))
        StatChip("✗ $failed", "ошибся", Color(0xFFE53935))
    }
    if (grammarIntroduced != null) {
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color(0xFFAB47BC).copy(alpha = 0.15f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📘 Новое правило: ", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFAB47BC))
            Text(grammarIntroduced, fontSize = 11.sp, color = Color(0xFFAB47BC))
        }
    }
}

@Composable private fun StatChip(main: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Text(main, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label, fontSize = 10.sp, color = color.copy(alpha = 0.8f))
    }
}

@Composable private fun GrammarProgressRow(introduced: Int, total: Int) {
    Column(Modifier.fillMaxWidth()) {
        Text("Правила грамматики: $introduced из $total", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0 until total) {
                val color = if (i < introduced) Color(0xFFAB47BC) else MaterialTheme.colorScheme.surfaceVariant
                Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
            }
        }
    }
}

@Composable private fun BottomActionButton(state: A1LearningState, vm: A1LearningViewModel, conn: LearnConnectionStatus) {
    when {
        state.sessionActive -> {
            Button(onClick = { vm.onIntent(A1LearningIntent.StopSession) }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))) {
                Icon(Icons.Filled.Stop, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text(if (state.isReviewMode) "Остановить повторение" else "Остановить сессию", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        state.currentCluster != null -> {
            Column(Modifier.fillMaxWidth()) {
                Button(onClick = { vm.onIntent(A1LearningIntent.StartNextCluster) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Начать урок", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                if (state.weakLemmasCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    val purple = Color(0xFF7B1FA2)
                    OutlinedButton(onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) }, modifier = Modifier.fillMaxWidth().height(46.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = purple), border = BorderStroke(1.5.dp, purple.copy(alpha = 0.6f))) {
                        Icon(Icons.Filled.Refresh, null, tint = purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Повторить слабые слова", color = purple, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.clip(CircleShape).background(purple).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text("${state.weakLemmasCount}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                    }
                }
            }
        }
        state.weakLemmasCount > 0 -> {
            val purple = Color(0xFF7B1FA2)
            Button(onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) }, modifier = Modifier.fillMaxWidth().height(50.dp), colors = ButtonDefaults.buttonColors(containerColor = purple)) {
                Icon(Icons.Filled.Refresh, null, tint = Color.White)
                Spacer(Modifier.width(8.dp))
                Text("Повторить ${state.weakLemmasCount} слов", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
        else -> {
            Text("Все кластеры пройдены — поздравляем!", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2E7D32), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun SessionFinishedDialog(quality: Int, feedback: String, lemmasProduced: Int, lemmasFailed: Int, isReviewMode: Boolean, onContinue: () -> Unit) {
    val color = if (quality >= 5) Color(0xFF43A047) else Color(0xFFFB8C00)
    AlertDialog(
        onDismissRequest = {}, title = null,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(80.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) { Text("$quality", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White) }
                Spacer(Modifier.height(12.dp))
                Text(if (isReviewMode) "Повторение завершено" else "Сессия завершена", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("✓ $lemmasProduced", color = Color(0xFF43A047), fontWeight = FontWeight.Bold)
                    Text("✗ $lemmasFailed", color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Text(feedback, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
            }
        },
        confirmButton = { Button(onClick = onContinue) { Text("Продолжить", color = Color.White) } },
        containerColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable private fun A1CompletedDialog(onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("🎉 A1 пройден!") }, text = { Text("Поздравляем! Все 835 слов и 22 правила A1 освоены. Ты готов к A2.") }, confirmButton = { Button(onClick = onClose) { Text("Отлично!") } })
}
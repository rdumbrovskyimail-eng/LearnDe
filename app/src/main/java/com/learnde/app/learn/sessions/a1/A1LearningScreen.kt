// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v4.0
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/A1LearningScreen.kt
//
// ИЗМЕНЕНИЯ v4.0 (premium-дизайн + критичный фикс):
//
//   🔴 ФИКС БАГА: В старой версии на строке 5274 был фильтр
//      `.filter { it.role == ConversationMessage.ROLE_MODEL }`
//      из-за которого реплики пользователя НЕ отображались.
//      Теперь чат показывает ОБЕ стороны — пользователь справа, Gemini слева,
//      с цветовой дифференциацией и флагами.
//
//   ✨ Новый дизайн:
//      - Hero-карточка кластера с градиентом и glow
//      - Фазовый Timeline (все 6 фаз видны сразу, текущая подсвечена)
//      - Chat-bubble'ы как в мессенджере (user справа, Gemini слева)
//      - Glow-прогресс-кольца для 3 метрик
//      - Live-статистика с пульсацией
//      - Карточка оценки лемм с цветовым кодом и chip'ами диагностики
//      - Премиум-кнопки с градиентами
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.learnde.app.domain.model.ConversationMessage
import com.learnde.app.learn.core.LearnConnectionStatus
import com.learnde.app.learn.core.LearnCoreIntent
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.components.SessionLoadingOverlay

// ═══════════════════════════════════════════════════════════
// Цвета темы обучения
// ═══════════════════════════════════════════════════════════
private object LearnTheme {
    val Primary = Color(0xFF43A047)           // зелёный — основной цвет обучения
    val PrimaryLight = Color(0xFF81C784)
    val Review = Color(0xFF7B1FA2)            // фиолетовый — режим повторения
    val Orange = Color(0xFFFB8C00)            // акценты, стат "слышал"
    val Blue = Color(0xFF1E88E5)              // акценты, стат "слышал"
    val Red = Color(0xFFE53935)               // ошибки
    val Purple = Color(0xFFAB47BC)            // грамматика
    // Чат
    val UserBubble = Color(0xFF1E88E5)
    val ModelBubble = Color(0xFF43A047)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1LearningScreen(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenVocabulary: () -> Unit,
    onOpenDebugLogs: () -> Unit,
    learnCoreViewModel: LearnCoreViewModel,
    vm: A1LearningViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val learnState by learnCoreViewModel.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val activity = context as? android.app.Activity
    var showRationaleDialog by remember { mutableStateOf(false) }
    var rationaleIsPermanent by remember { mutableStateOf(false) }

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val sessionId = if (state.isReviewMode) "a1_review" else "a1_situation"
            learnCoreViewModel.onIntent(LearnCoreIntent.Start(sessionId))
        } else {
            rationaleIsPermanent = activity == null ||
                !androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
                    activity, android.Manifest.permission.RECORD_AUDIO
                )
            showRationaleDialog = true
        }
    }

    if (showRationaleDialog) {
        com.learnde.app.presentation.learn.components.MicPermissionRationaleDialog(
            showSettingsButton = rationaleIsPermanent,
            onDismiss = { showRationaleDialog = false },
            onRequestAgain = {
                showRationaleDialog = false
                micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            },
            context = context,
        )
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
                is A1LearningEffect.SendSystemTextToGemini -> {
                    learnCoreViewModel.sendSystemText(effect.text)
                }
            }
        }
    }

    val exitAndBack: () -> Unit = {
        if (state.sessionActive) learnCoreViewModel.onIntent(LearnCoreIntent.Stop)
        onBack()
    }

    val topBarAccent = if (state.isReviewMode) LearnTheme.Review else LearnTheme.Primary

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(topBarAccent.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (state.isReviewMode) Icons.Filled.Refresh else Icons.Filled.School,
                                contentDescription = null,
                                tint = topBarAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                if (state.isReviewMode) "Повторение" else "Обучение A1",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (state.sessionActive) {
                                Text(
                                    "Сессия идёт",
                                    fontSize = 10.sp,
                                    color = topBarAccent,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = exitAndBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenDebugLogs) {
                        Icon(
                            Icons.Filled.BugReport,
                            "Логи",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenVocabulary) {
                        Icon(
                            Icons.Filled.MenuBook,
                            "Словарь",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenHistory) {
                        Icon(
                            Icons.Filled.History,
                            "История",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        },
        bottomBar = {
            Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                CurrentFunctionBar(status = fnStatus)
            }
        }
    ) { pad ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad)
                    .padding(horizontal = 16.dp),
            ) {
                if (state.loading) {
                    LoadingSection()
                    return@Column
                }
                if (state.error != null) {
                    ErrorSection(state.error ?: "Неизвестная ошибка")
                    return@Column
                }

                // ─── ВЕРХНЯЯ СЕКЦИЯ: ПРОГРЕСС ───
                ProgressSummary(state)

                Spacer(Modifier.height(14.dp))

                // ─── КАРТОЧКА ТЕКУЩЕГО УРОКА / РЕЖИМА ПОВТОРЕНИЯ ───
                if (!state.isReviewMode) {
                    state.currentCluster?.let { cluster ->
                        CurrentClusterCard(
                            titleRu = cluster.titleRu,
                            titleDe = cluster.titleDe,
                            scenario = cluster.scenarioHint,
                            grammarFocus = cluster.grammarFocus,
                            difficulty = cluster.difficulty,
                            isActive = state.sessionActive,
                        )
                    } ?: AllClustersDoneCard()
                } else {
                    ReviewSessionCard(weakCount = state.weakLemmasCount)
                }

                Spacer(Modifier.height(12.dp))

                // ─── PHASE TIMELINE (видны все 6 фаз сразу) ───
                AnimatedVisibility(
                    visible = state.sessionActive && !state.isReviewMode,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        PhaseTimeline(current = state.currentPhase)
                        Spacer(Modifier.height(10.dp))
                    }
                }

                // ─── КАРТОЧКА ПОСЛЕДНЕЙ ОЦЕНКИ ЛЕММЫ ───
                AnimatedVisibility(
                    visible = state.lastEvaluation != null,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    state.lastEvaluation?.let { ev ->
                        LemmaEvaluationCard(ev) { vm.onIntent(A1LearningIntent.DisputeEvaluation(ev.lemma)) }
                    }
                }

                AnimatedVisibility(
                    visible = state.sessionActive,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.height(10.dp))
                        SessionLiveStats(
                            heard = state.lemmasHeardThisSession.size,
                            produced = state.lemmasProducedThisSession.size,
                            failed = state.lemmasFailedThisSession.size,
                            grammarIntroduced = state.grammarIntroducedInSession,
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }

                // ─── КОГДА СЕССИЯ НЕ ИДЁТ: GRAMMAR PROGRESS ───
                var showGrammarSheet by remember { mutableStateOf(false) }
                AnimatedVisibility(
                    visible = !state.sessionActive && !state.isReviewMode,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    Column {
                        Spacer(Modifier.height(6.dp))
                        GrammarProgressRow(
                            state.grammarIntroduced,
                            state.grammarTotal,
                            onClick = { showGrammarSheet = true }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (showGrammarSheet) {
                    com.learnde.app.learn.sessions.a1.grammar.GrammarSheet(
                        onDismiss = { showGrammarSheet = false }
                    )
                }

                // ═══════════════════════════════════════════════════════
                // 🔴 ФИКС БАГА: ЧАТ (ПОКАЗЫВАЕМ ОБЕ СТОРОНЫ)
                // ═══════════════════════════════════════════════════════
                if (state.sessionActive && learnState.transcript.isNotEmpty()) {
                    ChatSection(
                        transcript = learnState.transcript,
                        isAiSpeaking = learnState.isAiSpeaking,
                        isMicActive = learnState.isMicActive,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(12.dp))
                } else if (state.sessionActive) {
                    // Сессия идёт, но пока нет сообщений — placeholder
                    EmptyChatPlaceholder(
                        isMicActive = learnState.isMicActive,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.height(12.dp))
                } else {
                    Spacer(Modifier.weight(1f))
                }

                // ─── ГЛАВНАЯ КНОПКА ─────
                BottomActionButton(state, vm, learnState.connectionStatus)
                Spacer(Modifier.height(8.dp))
            }

            // Loading overlay
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
        SessionFinishedDialog(
            quality = state.finalQuality ?: 5,
            feedback = state.finalFeedback ?: "",
            lemmasProduced = state.lemmasProducedThisSession.size,
            lemmasFailed = state.lemmasFailedThisSession.size,
            isReviewMode = state.isReviewMode,
        ) { vm.onIntent(A1LearningIntent.DismissFinalDialog) }
    }
    if (state.isA1Completed && state.currentCluster == null) A1CompletedDialog(onClose = onBack)
}

// ═══════════════════════════════════════════════════════════
// LOADING / ERROR
// ═══════════════════════════════════════════════════════════
@Composable
private fun LoadingSection() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = LearnTheme.Primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "Загружаем прогресс…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ErrorSection(msg: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.ErrorOutline,
                null,
                tint = LearnTheme.Red,
                modifier = Modifier.size(42.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text("Ошибка: $msg", color = LearnTheme.Red, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ═══════════════════════════════════════════════════════════
// PROGRESS SUMMARY — 3 кольца: Леммы / Кластеры / Правила
// ═══════════════════════════════════════════════════════════
@Composable
private fun ProgressSummary(state: A1LearningState) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            CircularProgressItem(
                label = "Леммы",
                current = state.lemmasMastered,
                total = state.totalLemmas,
                color = LearnTheme.Primary,
                subValue = if (state.lemmasInProgress > 0) "+${state.lemmasInProgress}" else null,
            )
            CircularProgressItem(
                label = "Уроки",
                current = state.clustersMastered,
                total = state.totalClusters,
                color = LearnTheme.Blue,
            )
            CircularProgressItem(
                label = "Правила",
                current = state.grammarIntroduced,
                total = state.grammarTotal,
                color = LearnTheme.Purple,
            )
        }
    }
}

@Composable
private fun CircularProgressItem(
    label: String,
    current: Int,
    total: Int,
    color: Color,
    subValue: String? = null,
) {
    val fraction by animateFloatAsState(
        targetValue = if (total == 0) 0f else (current.toFloat() / total).coerceIn(0f, 1f),
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(94.dp), contentAlignment = Alignment.Center) {
            val density = LocalDensity.current
            val stroke = with(density) { 8.dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                // Фоновый круг
                drawArc(
                    color = trackColor,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
                // Основная дуга
                drawArc(
                    brush = Brush.sweepGradient(
                        0f to color.copy(alpha = 0.55f),
                        0.7f to color,
                        1f to color.copy(alpha = 0.9f),
                        center = this.center
                    ),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    style = Stroke(stroke, cap = StrokeCap.Round)
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "$current",
                    fontSize = 21.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "/$total",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                subValue?.let {
                    Text(it, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = color)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// CURRENT CLUSTER CARD
// ═══════════════════════════════════════════════════════════
@Composable
private fun CurrentClusterCard(
    titleRu: String,
    titleDe: String,
    scenario: String,
    grammarFocus: String,
    difficulty: Int,
    isActive: Boolean,
) {
    val accent = LearnTheme.Primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (isActive) 6.dp else 3.dp,
                shape = RoundedCornerShape(18.dp),
                ambientColor = accent.copy(alpha = 0.25f),
                spotColor = accent.copy(alpha = 0.3f),
            )
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = if (isActive) 0.18f else 0.10f),
                        accent.copy(alpha = 0.04f),
                    )
                )
            )
            .border(
                1.dp,
                accent.copy(alpha = if (isActive) 0.4f else 0.2f),
                RoundedCornerShape(18.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "ТЕКУЩИЙ УРОК",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                letterSpacing = 1.5.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            DifficultyStars(difficulty)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            titleRu,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            titleDe,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            scenario,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LearnTheme.Purple.copy(alpha = 0.15f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    "ГРАММАТИКА",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = LearnTheme.Purple,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                grammarFocus,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun DifficultyStars(difficulty: Int) {
    Row {
        for (i in 1..4) {
            val filled = i <= difficulty
            Icon(
                if (filled) Icons.Filled.Star else Icons.Filled.StarBorder,
                contentDescription = null,
                tint = if (filled) Color(0xFFFFC107) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun AllClustersDoneCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LearnTheme.Primary.copy(alpha = 0.12f))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("🎉", fontSize = 32.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            "Все уроки A1 пройдены!",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = LearnTheme.Primary,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// REVIEW SESSION CARD
// ═══════════════════════════════════════════════════════════
@Composable
private fun ReviewSessionCard(weakCount: Int) {
    val purple = LearnTheme.Review
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, RoundedCornerShape(18.dp), ambientColor = purple.copy(alpha = 0.3f), spotColor = purple.copy(alpha = 0.3f))
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        purple.copy(alpha = 0.18f),
                        purple.copy(alpha = 0.05f),
                    )
                )
            )
            .border(1.dp, purple.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(purple.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Refresh, null, tint = purple, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Быстрое повторение",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = purple,
                )
                Text(
                    "Drill-режим · без новых слов",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(purple)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    "$weakCount",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "У вас $weakCount слов, которые стоит повторить. Сессия займёт 5-7 минут.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            lineHeight = 16.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// PHASE TIMELINE — 6 фаз как прогресс-бар
// ═══════════════════════════════════════════════════════════
@Composable
private fun PhaseTimeline(current: A1Phase) {
    val phases = listOf(
        A1Phase.WARM_UP to "Разминка",
        A1Phase.INTRODUCE to "Новое",
        A1Phase.DRILL to "Тренаж",
        A1Phase.APPLY to "Применяй",
        A1Phase.GRAMMAR to "Правило",
        A1Phase.COOL_DOWN to "Итог",
    )
    val currentIndex = phases.indexOfFirst { it.first == current }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            phases.forEachIndexed { index, (phase, _) ->
                val isDone = currentIndex > index
                val isActive = currentIndex == index
                val color = when {
                    isActive -> LearnTheme.Primary
                    isDone -> LearnTheme.Primary.copy(alpha = 0.6f)
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                val targetHeight = if (isActive) 6.dp else 4.dp
                val animHeight by animateFloatAsState(
                    targetValue = targetHeight.value,
                    animationSpec = tween(400),
                    label = "phaseHeight"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(animHeight.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            phases.forEachIndexed { index, (_, label) ->
                val isActive = currentIndex == index
                val color = if (isActive) LearnTheme.Primary else MaterialTheme.colorScheme.onSurfaceVariant
                Text(
                    label,
                    fontSize = 9.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                    color = color,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// LEMMA EVALUATION CARD
// ═══════════════════════════════════════════════════════════
@Composable
private fun LemmaEvaluationCard(ev: LastEvaluation, onDispute: () -> Unit) {
    val color = qualityColor(ev.quality)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        color.copy(alpha = 0.18f),
                        color.copy(alpha = 0.06f),
                    )
                )
            )
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Circular score badge
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .shadow(3.dp, CircleShape, ambientColor = color, spotColor = color)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${ev.quality}",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    ev.lemma,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif,
                )
                if (ev.feedback.isNotBlank()) {
                    Text(
                        ev.feedback,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp,
                    )
                }
            }
            if (ev.wasCorrect) {
                Icon(
                    Icons.Filled.CheckCircle,
                    null,
                    tint = LearnTheme.Primary,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        if (ev.diagnosis.isError) {
            Spacer(Modifier.height(10.dp))
            DiagnosisChips(ev)
            if (ev.diagnosis.specifics.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "💡 ${ev.diagnosis.specifics}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 15.sp,
                )
            }
        }
        if (!ev.wasCorrect) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = onDispute,
                modifier = Modifier
                    .align(Alignment.End)
                    .height(30.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            ) {
                Text(
                    "Я сказал правильно",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private fun qualityColor(q: Int): Color = when (q) {
    7 -> Color(0xFF2E7D32)
    6 -> Color(0xFF43A047)
    5 -> Color(0xFF7CB342)
    4 -> Color(0xFFFDD835)
    3 -> Color(0xFFFB8C00)
    2 -> Color(0xFFF4511E)
    else -> Color(0xFFE53935)
}

@Composable
private fun DiagnosisChips(ev: LastEvaluation) {
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
        sourceLabel?.let { DiagChip(it, LearnTheme.Orange) }
        depthLabel?.let {
            val c = when (ev.diagnosis.depth) {
                com.learnde.app.learn.domain.ErrorDepth.SLIP -> LearnTheme.Primary
                com.learnde.app.learn.domain.ErrorDepth.MISTAKE -> Color(0xFFFDD835)
                com.learnde.app.learn.domain.ErrorDepth.ERROR -> LearnTheme.Red
                else -> Color.Gray
            }
            DiagChip(it, c)
        }
        categoryLabel?.let { DiagChip(it, LearnTheme.Review) }
    }
}

@Composable
private fun DiagChip(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text,
            fontSize = 9.sp,
            color = color,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// SESSION LIVE STATS
// ═══════════════════════════════════════════════════════════
@Composable
private fun SessionLiveStats(heard: Int, produced: Int, failed: Int, grammarIntroduced: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatChip("👂", "$heard", "слышал", LearnTheme.Blue, Modifier.weight(1f))
        StatChip("✓", "$produced", "произнёс", LearnTheme.Primary, Modifier.weight(1f))
        StatChip("✗", "$failed", "ошибся", LearnTheme.Red, Modifier.weight(1f))
    }
    if (grammarIntroduced != null) {
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(LearnTheme.Purple.copy(alpha = 0.15f))
                .border(1.dp, LearnTheme.Purple.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("📘", fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    "НОВОЕ ПРАВИЛО",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = LearnTheme.Purple,
                    letterSpacing = 1.sp,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    grammarIntroduced,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun StatChip(emoji: String, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, fontSize = 13.sp)
            Spacer(Modifier.width(4.dp))
            Text(
                value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Text(
            label,
            fontSize = 10.sp,
            color = color.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// GRAMMAR PROGRESS ROW
// ═══════════════════════════════════════════════════════════
@Composable
private fun GrammarProgressRow(introduced: Int, total: Int, onClick: () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.MenuBook,
                null,
                tint = LearnTheme.Purple,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Правила грамматики",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$introduced / $total",
                fontSize = 11.sp,
                color = LearnTheme.Purple,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            for (i in 0 until total) {
                val done = i < introduced
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (done) LearnTheme.Purple
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// 🔴 CHAT SECTION — ФИКС БАГА: показываем ОБЕ стороны
// ═══════════════════════════════════════════════════════════
@Composable
private fun ChatSection(
    transcript: List<ConversationMessage>,
    isAiSpeaking: Boolean,
    isMicActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Автоскролл к последнему сообщению
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) {
            listState.animateScrollToItem(transcript.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // Заголовок чата с индикатором
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Forum,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Диалог",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            SpeakingIndicator(isAiSpeaking = isAiSpeaking, isMicActive = isMicActive)
        }
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.15f),
                    RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 🔴 ФИКС: НЕ фильтруем — показываем и ROLE_USER, и ROLE_MODEL
                items(transcript, key = { it.timestamp }) { msg ->
                    ChatBubble(msg)
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ConversationMessage) {
    val isUser = message.role == ConversationMessage.ROLE_USER
    val text = message.text.trim()
    if (text.isEmpty()) return

    val accent = if (isUser) LearnTheme.UserBubble else LearnTheme.ModelBubble
    val bgColor = accent.copy(alpha = 0.14f)
    val borderColor = accent.copy(alpha = 0.28f)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        if (!isUser) {
            // Аватар Gemini
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🤖", fontSize = 14.sp)
            }
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth(0.78f)
                .clip(
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp,
                    )
                )
                .background(bgColor)
                .border(
                    1.dp,
                    borderColor,
                    RoundedCornerShape(
                        topStart = 14.dp,
                        topEnd = 14.dp,
                        bottomStart = if (isUser) 14.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 14.dp,
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (isUser) "ВЫ" else "GEMINI",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    letterSpacing = 1.2.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 19.sp,
            )
        }

        if (isUser) {
            Spacer(Modifier.width(6.dp))
            // Аватар пользователя
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    null,
                    tint = accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun SpeakingIndicator(isAiSpeaking: Boolean, isMicActive: Boolean) {
    val (label, color, icon) = when {
        isAiSpeaking -> Triple("говорит Gemini", LearnTheme.ModelBubble, Icons.Filled.VolumeUp)
        isMicActive -> Triple("слушаю вас", LearnTheme.UserBubble, Icons.Filled.Mic)
        else -> Triple("пауза", MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.PauseCircleOutline)
    }
    val active = isAiSpeaking || isMicActive
    val alpha by animateFloatAsState(
        targetValue = if (active) 0.5f else 1f,
        animationSpec = if (active) {
            infiniteRepeatable(tween(700), RepeatMode.Reverse)
        } else {
            tween(300)
        },
        label = "sa"
    )
    val effAlpha = if (active) alpha else 1f

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.12f * effAlpha))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = color.copy(alpha = effAlpha), modifier = Modifier.size(11.dp))
        Spacer(Modifier.width(4.dp))
        Text(
            label,
            fontSize = 10.sp,
            color = color.copy(alpha = effAlpha),
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyChatPlaceholder(isMicActive: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (isMicActive) Icons.Filled.Mic else Icons.Filled.HourglassEmpty,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (isMicActive) "Gemini начнёт первым…" else "Ожидание…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════
// BOTTOM ACTION BUTTON
// ═══════════════════════════════════════════════════════════
@Composable
private fun BottomActionButton(state: A1LearningState, vm: A1LearningViewModel, conn: LearnConnectionStatus) {
    when {
        state.sessionActive -> {
            Button(
                onClick = { vm.onIntent(A1LearningIntent.StopSession) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(6.dp, RoundedCornerShape(14.dp), ambientColor = LearnTheme.Red, spotColor = LearnTheme.Red),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LearnTheme.Red),
            ) {
                Icon(Icons.Filled.Stop, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (state.isReviewMode) "Остановить повторение" else "Остановить сессию",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
        state.currentCluster != null -> {
            Column(Modifier.fillMaxWidth()) {
                Button(
                    onClick = { vm.onIntent(A1LearningIntent.StartNextCluster) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .shadow(6.dp, RoundedCornerShape(14.dp), ambientColor = LearnTheme.Primary, spotColor = LearnTheme.Primary),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = LearnTheme.Primary),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Начать урок",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                    )
                }
                if (state.weakLemmasCount > 0) {
                    Spacer(Modifier.height(10.dp))
                    val purple = LearnTheme.Review
                    OutlinedButton(
                        onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = purple),
                        border = BorderStroke(1.5.dp, purple.copy(alpha = 0.6f)),
                    ) {
                        Icon(Icons.Filled.Refresh, null, tint = purple, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Повторить слабые",
                            color = purple,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(purple)
                                .padding(horizontal = 8.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "${state.weakLemmasCount}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }
        state.weakLemmasCount > 0 -> {
            Button(
                onClick = { vm.onIntent(A1LearningIntent.StartReviewSession) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .shadow(6.dp, RoundedCornerShape(14.dp), ambientColor = LearnTheme.Review, spotColor = LearnTheme.Review),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LearnTheme.Review),
            ) {
                Icon(Icons.Filled.Refresh, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "Повторить ${state.weakLemmasCount} слов",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                )
            }
        }
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(LearnTheme.Primary.copy(alpha = 0.12f))
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Все кластеры пройдены — поздравляем!",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = LearnTheme.Primary,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
// DIALOGS
// ═══════════════════════════════════════════════════════════
@Composable
private fun SessionFinishedDialog(
    quality: Int,
    feedback: String,
    lemmasProduced: Int,
    lemmasFailed: Int,
    isReviewMode: Boolean,
    onContinue: () -> Unit,
) {
    val color = qualityColor(quality)
    AlertDialog(
        onDismissRequest = {},
        title = null,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(84.dp)
                        .shadow(8.dp, CircleShape, ambientColor = color, spotColor = color)
                        .clip(CircleShape)
                        .background(color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("$quality", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    if (isReviewMode) "Повторение завершено" else "Сессия завершена",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.CheckCircle, null, tint = LearnTheme.Primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$lemmasProduced", color = LearnTheme.Primary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Cancel, null, tint = LearnTheme.Red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("$lemmasFailed", color = LearnTheme.Red, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    feedback,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onContinue,
                colors = ButtonDefaults.buttonColors(containerColor = LearnTheme.Primary),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Продолжить", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun A1CompletedDialog(onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎉", fontSize = 28.sp)
                Spacer(Modifier.width(8.dp))
                Text("A1 пройден!", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(
                "Поздравляем! Все 835 слов и 22 правила A1 освоены. Вы готовы к A2.",
                lineHeight = 20.sp,
            )
        },
        confirmButton = {
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = LearnTheme.Primary),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("Отлично!", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        },
        shape = RoundedCornerShape(20.dp),
    )
}

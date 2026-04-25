// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v4.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubScreen.kt
//
// ИЗМЕНЕНИЯ v4.0 (premium-дизайн):
//   - Hero-карточки с градиентом и glow
//   - Hero-header с приветствием и иконкой
//   - Статистика на каждой карточке (3 цифры)
//   - Живая индикация текущей функции Gemini (CurrentFunctionBar)
//   - Удалена кнопка "Gl" из actions (загрязняла интерфейс)
//   - Анимация появления карточек
//   - Поддержка только 3 модулей (остальные удалены из контракта)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar

// ═══════════════════════════════════════════════════════════
// ЦВЕТА акцентов для карточек
// ═══════════════════════════════════════════════════════════
private object HubTheme {
    val AccentBlue = Color(0xFF1E88E5)     // Тест
    val AccentBlueLight = Color(0xFF64B5F6)
    val AccentGreen = Color(0xFF43A047)    // Обучение
    val AccentGreenLight = Color(0xFF81C784)
    val AccentOrange = Color(0xFFFB8C00)   // Переводчик
    val AccentOrangeLight = Color(0xFFFFB74D)

    fun accentPair(key: String): Pair<Color, Color> = when (key) {
        "Blue" -> AccentBlue to AccentBlueLight
        "Green" -> AccentGreen to AccentGreenLight
        "Orange" -> AccentOrange to AccentOrangeLight
        else -> AccentBlue to AccentBlueLight
    }

    fun icon(key: String): ImageVector = when (key) {
        "Quiz" -> Icons.Filled.Quiz
        "School" -> Icons.Filled.School
        "Translate" -> Icons.Filled.Translate
        else -> Icons.Filled.AutoAwesome
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnHubScreen(
    onBack: () -> Unit,
    onOpenTranslator: () -> Unit,
    onOpenA0a1Test: () -> Unit,
    onOpenA1Learning: () -> Unit,
    onOpenVoiceClient: () -> Unit,   // оставлен для обратной совместимости NavGraph — не используется в UI
    learnCoreViewModel: LearnCoreViewModel,
) {
    val hubVm: LearnHubViewModel = hiltViewModel()
    val state by hubVm.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        hubVm.effects.collect { effect ->
            when (effect) {
                is LearnHubEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                is LearnHubEffect.NavigateToItem -> when (effect.route) {
                    "learn/translator" -> onOpenTranslator()
                    "learn/a0a1" -> onOpenA0a1Test()
                    "learn/a1" -> onOpenA1Learning()
                    else -> { /* no-op */ }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LearnDE",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Default,
                        color = MaterialTheme.colorScheme.onBackground,
                        letterSpacing = 0.5.sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    // Стрик-индикатор (если > 0)
                    if (state.currentStreakDays > 0) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFFFB8C00).copy(alpha = 0.15f))
                                .border(1.dp, Color(0xFFFB8C00).copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text("🔥", fontSize = 14.sp)
                            Spacer(Modifier.width(3.dp))
                            Text(
                                "${state.currentStreakDays}",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFB8C00),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }

                    // ФИКС: Премиум-кнопка "Свободный диалог" в TopAppBar.
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(
                                        Color(0xFF8A7CFF),
                                        Color(0xFF2EE6D6),
                                    )
                                )
                            )
                            .clickable { onOpenVoiceClient() }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.GraphicEq,
                                contentDescription = "Свободный диалог",
                                tint = Color.White,
                                modifier = Modifier.size(14.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "ДИАЛОГ",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp,
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            CurrentFunctionBar(
                status = fnStatus,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            // ═══ HERO-HEADER ═══
            HeroHeader()

            Spacer(Modifier.height(16.dp))

            if (!state.apiKeySet) {
                ApiKeyMissingBanner()
                Spacer(Modifier.height(12.dp))
            }

            // ═══ Список модулей ═══
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(300 + index * 100)) +
                                slideInVertically(tween(400 + index * 100)) { it / 3 }
                    ) {
                        HeroCard(
                            item = item,
                            onClick = { hubVm.onIntent(LearnHubIntent.OpenItem(item.id)) }
                        )
                    }
                }

                // Подпись-дисклеймер
                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Прогресс сохраняется локально на устройстве",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  HERO-HEADER — большое приветствие сверху
// ═══════════════════════════════════════════════════════════
@Composable
private fun HeroHeader() {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.16f),
                        primary.copy(alpha = 0.04f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.25f),
                        primary.copy(alpha = 0.05f),
                    )
                ),
                shape = RoundedCornerShape(18.dp)
            )
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text("🇩🇪", fontSize = 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Изучение немецкого",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "От нуля до уверенного A1",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════
//  HERO-CARD — основная карточка модуля (premium-дизайн)
// ═══════════════════════════════════════════════════════════
@Composable
private fun HeroCard(
    item: LearnHubItem,
    onClick: () -> Unit,
) {
    val (accent, accentLight) = HubTheme.accentPair(item.accentKey)
    val icon = HubTheme.icon(item.iconKey)
    val enabled = item.implemented

    // Мягкая пульсация для LIVE-бейджа
    val pulse = rememberInfiniteTransition(label = "hubCardPulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (enabled) 4.dp else 0.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = accent.copy(alpha = 0.25f),
                spotColor = accent.copy(alpha = 0.25f),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = 0.12f),
                        accentLight.copy(alpha = 0.05f),
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(
                    colors = listOf(
                        accent.copy(alpha = if (enabled) 0.35f else 0.12f),
                        accent.copy(alpha = 0.08f),
                    )
                ),
                shape = RoundedCornerShape(20.dp)
            )
            .clickable(enabled = true) { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // ─── Верхняя строка: иконка + заголовок + стрелка ───
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Круг-иконка с glow
            Box(
                modifier = Modifier.size(52.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Внешнее свечение
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accent.copy(alpha = 0.35f),
                                    accent.copy(alpha = 0.0f),
                                )
                            )
                        )
                )
                // Иконка
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.2f))
                        .border(1.dp, accent.copy(alpha = 0.45f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    // Бейдж
                    BadgeChip(
                        text = item.badge,
                        accent = accent,
                        pulsingAlpha = if (item.badge == "LIVE") pulseAlpha else 1f,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.width(8.dp))

            if (enabled) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        // ─── Разделитель + статистика ───
        if (item.detailStats.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(accent.copy(alpha = 0.15f))
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                item.detailStats.forEach { (value, label) ->
                    StatPillar(value = value, label = label, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun StatPillar(value: String, label: String, accent: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = accent,
            fontFamily = FontFamily.Default,
        )
        if (label.isNotBlank()) {
            Spacer(Modifier.height(1.dp))
            Text(
                label,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
            )
        }
    }
}

@Composable
private fun BadgeChip(text: String, accent: Color, pulsingAlpha: Float) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.22f * pulsingAlpha))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.4f * pulsingAlpha),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = accent.copy(alpha = pulsingAlpha),
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun ApiKeyMissingBanner() {
    val orange = Color(0xFFFB8C00)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(orange.copy(alpha = 0.12f))
            .border(1.dp, orange.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = orange,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Задайте API-ключ в Настройках, чтобы запустить модули",
            fontSize = 12.sp,
            color = orange,
            fontWeight = FontWeight.Medium,
            lineHeight = 16.sp,
        )
    }
}

// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/presentation/learn/LearnHubScreen.kt
//
// ИЗМЕНЕНИЯ v5.0:
//   - Полный отказ от "детских" градиентов на карточках
//   - Монохромная схема + один accent (LearnPalette.Accent)
//   - Убрана кнопка "ДИАЛОГ" в TopBar (она дублировала функционал и была лишней)
//   - HeroHeader без эмодзи 🇩🇪 — заменён на минималистичный текстовый блок
//   - Карточки в едином стиле, weight-контраст вместо цвета
//   - REPLAY бадж для теста, если пройден
//   - Streak теперь действительно считается
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.learn.components.CurrentFunctionBar
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

private fun iconFor(key: String): ImageVector = when (key) {
    "Quiz" -> Icons.Filled.Quiz
    "School" -> Icons.Filled.School
    "Translate" -> Icons.Filled.Translate
    "Book" -> Icons.Filled.MenuBook
    else -> Icons.Filled.AutoAwesome
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearnHubScreen(
    onBack: () -> Unit,
    onOpenTranslator: () -> Unit,
    onOpenA0a1Test: () -> Unit,
    onOpenA1Learning: () -> Unit,
    onOpenVoiceClient: () -> Unit,
    onOpenGrammar: () -> Unit = {},
    onOpenDebugLogs: () -> Unit = {},
    learnCoreViewModel: LearnCoreViewModel,
) {
    val hubVm: LearnHubViewModel = hiltViewModel()
    val state by hubVm.state.collectAsStateWithLifecycle()
    val fnStatus by learnCoreViewModel.functionStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = learnColors()

    LaunchedEffect(Unit) {
        hubVm.effects.collect { effect ->
            when (effect) {
                is LearnHubEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
                is LearnHubEffect.NavigateToItem -> when (effect.route) {
                    "learn/translator" -> onOpenTranslator()
                    "learn/a0a1" -> onOpenA0a1Test()
                    "learn/a1" -> onOpenA1Learning()
                    "learn/a1/grammar" -> onOpenGrammar()
                }
            }
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "LearnDE",
                        fontSize = LearnTokens.FontSizeTitle,
                        fontWeight = FontWeight.Bold,
                        color = colors.textHi,
                        letterSpacing = (-0.2).sp,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = colors.textHi,
                        )
                    }
                },
                actions = {
                    if (state.currentStreakDays > 0) {
                        StreakChip(state.currentStreakDays)
                        Spacer(Modifier.width(LearnTokens.PaddingMd))
                    }
                    IconButton(onClick = onOpenDebugLogs) {
                        Icon(
                            Icons.Filled.BugReport,
                            contentDescription = "Журнал",
                            tint = colors.textMid,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
            )
        },
        bottomBar = {
            CurrentFunctionBar(
                status = fnStatus,
                modifier = Modifier.padding(
                    horizontal = LearnTokens.PaddingMd,
                    vertical = LearnTokens.PaddingSm,
                ),
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingLg),
        ) {
            Spacer(Modifier.height(LearnTokens.PaddingXs))
            HeroHeader()
            Spacer(Modifier.height(LearnTokens.PaddingLg))

            if (!state.apiKeySet) {
                ApiKeyMissingBanner()
                Spacer(Modifier.height(LearnTokens.PaddingMd))
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(LearnTokens.PaddingMd),
            ) {
                itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(tween(280 + index * 80)) +
                            slideInVertically(tween(360 + index * 80)) { it / 4 },
                    ) {
                        ModuleCard(
                            item = item,
                            onClick = { hubVm.onIntent(LearnHubIntent.OpenItem(item.id)) },
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(LearnTokens.PaddingXs))
                    Text(
                        "Прогресс сохраняется локально на устройстве",
                        fontSize = LearnTokens.FontSizeMicro,
                        color = colors.textLow,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(LearnTokens.PaddingSm))
                }
            }
        }
    }
}

@Composable
private fun StreakChip(days: Int) {
    val colors = learnColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.warnSoft)
            .border(
                LearnTokens.BorderThin,
                colors.warn.copy(alpha = 0.3f),
                RoundedCornerShape(LearnTokens.RadiusXs),
            )
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        // Минималистичный индикатор стрика без эмодзи
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(colors.warn),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "$days дн.",
            fontSize = LearnTokens.FontSizeCaption,
            fontWeight = FontWeight.SemiBold,
            color = colors.warn,
        )
    }
}

@Composable
private fun HeroHeader() {
    val colors = learnColors()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusLg))
            .background(colors.surface)
            .border(LearnTokens.BorderThin, colors.stroke, RoundedCornerShape(LearnTokens.RadiusLg))
            .padding(LearnTokens.PaddingLg),
    ) {
        Text(
            "Изучение немецкого",
            fontSize = LearnTokens.FontSizeTitleLg,
            fontWeight = FontWeight.Bold,
            color = colors.textHi,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "От нуля до уверенного A1",
            fontSize = LearnTokens.FontSizeBody,
            color = colors.textMid,
            fontWeight = FontWeight.Normal,
        )
    }
}

@Composable
private fun ModuleCard(
    item: LearnHubItem,
    onClick: () -> Unit,
) {
    val colors = learnColors()
    val enabled = item.implemented
    val icon = iconFor(item.iconKey)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusLg))
            .background(colors.surface)
            .border(
                LearnTokens.BorderThin,
                if (enabled) colors.stroke else colors.stroke.copy(alpha = 0.5f),
                RoundedCornerShape(LearnTokens.RadiusLg),
            )
            .clickable(enabled = enabled) { onClick() }
            .padding(LearnTokens.PaddingLg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(LearnTokens.RadiusSm))
                    .background(colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }

            Spacer(Modifier.width(LearnTokens.PaddingMd))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize = LearnTokens.FontSizeBodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textHi,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(LearnTokens.PaddingSm))
                    BadgeChip(item.badge)
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    item.subtitle,
                    fontSize = LearnTokens.FontSizeCaption,
                    color = colors.textMid,
                    fontWeight = FontWeight.Normal,
                )
            }

            Spacer(Modifier.width(LearnTokens.PaddingSm))

            if (enabled) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.textLow,
                    modifier = Modifier.size(18.dp),
                )
            } else {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = colors.textLow,
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        if (item.detailStats.isNotEmpty()) {
            Spacer(Modifier.height(LearnTokens.PaddingMd))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.stroke),
            )
            Spacer(Modifier.height(LearnTokens.PaddingMd))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                item.detailStats.forEach { (value, label) ->
                    StatPillar(value = value, label = label)
                }
            }
        }
    }
}

@Composable
private fun StatPillar(value: String, label: String) {
    val colors = learnColors()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value,
            fontSize = LearnTokens.FontSizeTitle,
            fontWeight = FontWeight.Bold,
            color = colors.textHi,
            letterSpacing = (-0.5).sp,
        )
        if (label.isNotBlank()) {
            Text(
                label,
                fontSize = LearnTokens.FontSizeMicro,
                color = colors.textLow,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun BadgeChip(text: String) {
    val colors = learnColors()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusXs))
            .background(colors.accentSoft)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = LearnTokens.FontSizeMicro,
            fontWeight = FontWeight.Bold,
            color = colors.accent,
            letterSpacing = LearnTokens.CapsLetterSpacing,
        )
    }
}

@Composable
private fun ApiKeyMissingBanner() {
    val colors = learnColors()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(LearnTokens.RadiusMd))
            .background(colors.warnSoft)
            .border(
                LearnTokens.BorderThin,
                colors.warn.copy(alpha = 0.3f),
                RoundedCornerShape(LearnTokens.RadiusMd),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.WarningAmber,
            contentDescription = null,
            tint = colors.warn,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(LearnTokens.PaddingSm))
        Text(
            "Задайте API-ключ в Настройках, чтобы запустить модули",
            fontSize = LearnTokens.FontSizeCaption,
            color = colors.warn,
            fontWeight = FontWeight.Medium,
        )
    }
}

package com.learnde.app.presentation.levelselect

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.A1ClusterDao
import com.learnde.app.learn.data.db.A1LemmaDao
import com.learnde.app.presentation.learn.theme.learnColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

// ────────────────────────────────────────────────────────────
//  STATE / VIEWMODEL
// ────────────────────────────────────────────────────────────

data class LevelSelectState(
    val a1Progress: Float = 0f,          // 0..1 — доля кластеров с mastery >= 0.7
    val a1LemmasMastered: Int = 0,
    val a1LemmasTotal: Int = 0,
    val a1Completed: Boolean = false,
)

@HiltViewModel
class LevelSelectViewModel @Inject constructor(
    clusterDao: A1ClusterDao,
    lemmaDao: A1LemmaDao,
) : ViewModel() {

    val state = combine(
        clusterDao.observeAll(),
        lemmaDao.observeAll(),
    ) { clusters, lemmas ->
        val mastered = clusters.count { it.masteryScore >= 0.7f }
        val total = clusters.size.coerceAtLeast(1)
        val progress = mastered.toFloat() / total
        LevelSelectState(
            a1Progress = progress,
            a1LemmasMastered = lemmas.count { it.masteryScore >= 0.7f },
            a1LemmasTotal = lemmas.size,
            a1Completed = progress >= 0.9f,
        )
    }.stateIn(
        scope = androidx.lifecycle.viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LevelSelectState(),
    )
}

// ────────────────────────────────────────────────────────────
//  SCREEN
// ────────────────────────────────────────────────────────────

@Composable
fun LevelSelectScreen(
    onOpenA1: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: LevelSelectViewModel = hiltViewModel(),
) {
    val colors = learnColors()
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 20.dp),
    ) {
        // ── Шапка ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Deutsch",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary,
                )
                Text(
                    text = "Выберите уровень",
                    fontSize = 13.sp,
                    color = colors.textSecondary,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Настройки",
                    tint = colors.textSecondary,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── A1 (активен) ──
        LevelCard(
            code = "A1",
            title = "Разговорный старт",
            subtitle = "Алфавит, числа, время, 800+ слов, базовая грамматика — живые голосовые ситуации",
            enabled = true,
            completed = state.a1Completed,
            progress = state.a1Progress,
            progressLabel = "${(state.a1Progress * 100).toInt()}% · слов освоено " +
                    "${state.a1LemmasMastered}/${state.a1LemmasTotal}",
            onClick = onOpenA1,
        )

        Spacer(Modifier.height(12.dp))

        // ── A2 (готов к подключению) ──
        LevelCard(
            code = "A2",
            title = "Уверенное общение",
            subtitle = if (state.a1Completed)
                "Скоро: модуль A2 в разработке"
            else
                "Откроется после завершения A1",
            enabled = false, // ← для подключения A2 поменять на state.a1Completed
            completed = false,
            progress = 0f,
            progressLabel = null,
            onClick = { },
        )

        Spacer(Modifier.height(12.dp))

        // ── B1 (плейсхолдер) ──
        LevelCard(
            code = "B1",
            title = "Самостоятельность",
            subtitle = "Откроется после завершения A2",
            enabled = false,
            completed = false,
            progress = 0f,
            progressLabel = null,
            onClick = { },
        )

        Spacer(Modifier.weight(1f))

        Text(
            text = "Вступительный тест пройден. Пройти его повторно можно из настроек.",
            fontSize = 11.sp,
            color = colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        )
    }
}

// ────────────────────────────────────────────────────────────
//  CARD
// ────────────────────────────────────────────────────────────

@Composable
private fun LevelCard(
    code: String,
    title: String,
    subtitle: String,
    enabled: Boolean,
    completed: Boolean,
    progress: Float,
    progressLabel: String?,
    onClick: () -> Unit,
) {
    val colors = learnColors()
    val shape = RoundedCornerShape(18.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(
                width = 1.dp,
                color = if (enabled) colors.accent.copy(alpha = 0.55f)
                        else colors.divider,
                shape = shape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.55f)
            .animateContentSize(tween(220))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Бейдж уровня
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (enabled) colors.accent.copy(alpha = 0.14f)
                        else colors.divider.copy(alpha = 0.4f)
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = code,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled) colors.accent else colors.textSecondary,
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.textPrimary,
                    )
                    if (completed) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.accent.copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = "ПРОЙДЕН",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = colors.accent,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = colors.textSecondary,
                )
            }

            Spacer(Modifier.width(8.dp))

            Icon(
                imageVector = if (enabled) Icons.AutoMirrored.Filled.KeyboardArrowRight
                              else Icons.Filled.Lock,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }

        // Прогресс — только для активного уровня
        if (enabled && progressLabel != null) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = colors.accent,
                trackColor = colors.divider,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = progressLabel,
                fontSize = 11.sp,
                color = colors.textSecondary,
            )
        }
    }
}

// ────────────────────────────────────────────────────────────
//  ПРИМЕЧАНИЕ ПО DAO
// ────────────────────────────────────────────────────────────
// ViewModel использует observeAll(): Flow<List<...>>.
// Если в A1ClusterDao/A1LemmaDao этих методов нет — добавьте:
//
//   @Query("SELECT * FROM a1_clusters")
//   fun observeAll(): Flow<List<ClusterA1Entity>>
//
//   @Query("SELECT * FROM a1_lemmas")
//   fun observeAll(): Flow<List<LemmaA1Entity>>
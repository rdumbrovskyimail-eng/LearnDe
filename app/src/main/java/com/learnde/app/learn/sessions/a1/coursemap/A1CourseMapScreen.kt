// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/coursemap/A1CourseMapScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.coursemap

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.ClusterA1Entity
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.Plural
import com.learnde.app.presentation.learn.theme.learnColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1CourseMapScreen(
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    vm: A1CourseMapViewModel = hiltViewModel(),
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
                            "Карта курса A1",
                            fontSize = LearnTokens.FontSizeTitle,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textHi,
                        )
                        Text(
                            "${state.masteredCount} из ${state.totalCount} ${Plural.lesson(state.totalCount)} пройдено",
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
        Column(modifier = Modifier.padding(pad)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LearnTokens.PaddingMd, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val total = state.clusters.size
                val completed = state.clusters.count { it.completed }
                val pct = if (total == 0) 0 else (completed * 100 / total)

                StatChip("Всего", total.toString(), Modifier.weight(1f))
                StatChip("Пройдено", completed.toString(), Modifier.weight(1f))
                StatChip("Прогресс", "$pct%", Modifier.weight(1f))
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = LearnTokens.PaddingMd),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingMd),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.byCategory.forEach { (category, clusters) ->
                item(key = "header_$category") {
                    Spacer(Modifier.height(LearnTokens.PaddingMd))
                    Text(
                        category,
                        fontSize = LearnTokens.FontSizeCaption,
                        fontWeight = FontWeight.Bold,
                        color = colors.textMid,
                        letterSpacing = LearnTokens.CapsLetterSpacing,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                items(clusters, key = { it.id }) { cluster ->
                    ClusterMapCard(
                        cluster = cluster,
                        isCurrent = state.currentClusterId == cluster.id,
                        onClick = { onClusterClick(cluster.id) },
                    )
                }
            }
            item { Spacer(Modifier.height(LearnTokens.PaddingLg)) }
        }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = learnColors()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = colors.textLow)
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = colors.textHi)
    }
}

private data class ClusterStatusStyle(
    val icon: ImageVector,
    val color: Color,
)

@Composable
private fun ClusterMapCard(
    cluster: ClusterA1Entity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val colors = learnColors()
    val mastery = cluster.masteryScore
    val status: ClusterStatusStyle = when {
        cluster.isMastered -> ClusterStatusStyle(Icons.Filled.CheckCircle, colors.success)
        isCurrent -> ClusterStatusStyle(Icons.Filled.PlayArrow, colors.accent)
        cluster.isUnlocked -> ClusterStatusStyle(Icons.Filled.PlayArrow, colors.textMid)
        else -> ClusterStatusStyle(Icons.Filled.Lock, colors.textLow)
    }

    // Анимация запускается ТОЛЬКО для текущего урока — для остальных 140 карточек
    // бесконечный transition не создаётся вообще, экономим ~140 RAF-таймеров.
    val pulseScale: Float = if (isCurrent) {
        val pulse = rememberInfiniteTransition(label = "currentPulse")
        val v by pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.02f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "pulse",
        )
        v
    } else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pulseScale)
            .clip(RoundedCornerShape(LearnTokens.RadiusSm))
            .background(colors.surface)
            .border(
                width = if (isCurrent) LearnTokens.BorderMedium else LearnTokens.BorderThin,
                color = if (isCurrent) colors.accent.copy(alpha = 0.4f) else colors.stroke,
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
            )
            .clickable(enabled = cluster.isUnlocked) { onClick() }
            .padding(LearnTokens.PaddingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(status.color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                status.icon,
                null,
                tint = status.color,
                modifier = Modifier.size(if (status.icon == Icons.Filled.Lock) 14.dp else 16.dp),
            )
        }
        Spacer(Modifier.width(LearnTokens.PaddingMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                cluster.titleRu,
                fontSize = LearnTokens.FontSizeBody,
                fontWeight = FontWeight.SemiBold,
                color = if (cluster.isUnlocked) colors.textHi else colors.textLow,
            )
            Text(
                cluster.titleDe,
                fontSize = 11.sp,
                color = colors.textLow,
                fontWeight = FontWeight.Normal,
            )
        }
        if (cluster.attempts > 0 || mastery > 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${(mastery * 100).toInt()}%",
                    fontSize = LearnTokens.FontSizeBody,
                    fontWeight = FontWeight.Bold,
                    color = status.color,
                )
                if (cluster.attempts > 0) {
                    Text(
                        "${cluster.attempts} ${Plural.attempt(cluster.attempts)}",
                        fontSize = 9.sp,
                        color = colors.textLow,
                    )
                }
            }
        }
    }
}

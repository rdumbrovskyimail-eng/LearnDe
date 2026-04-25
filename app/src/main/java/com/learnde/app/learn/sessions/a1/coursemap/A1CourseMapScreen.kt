// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/learn/sessions/a1/coursemap/A1CourseMapScreen.kt
//
// Карта всех 194 кластеров A1 с группировкой по category.
// Пройденные — зелёные с галочкой
// Текущий — пульсирует
// Заблокированные — серые с замком
// ═══════════════════════════════════════════════════════════
package com.learnde.app.learn.sessions.a1.coursemap

import androidx.compose.animation.core.*
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.data.db.ClusterA1Entity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A1CourseMapScreen(
    onBack: () -> Unit,
    onClusterClick: (String) -> Unit,
    vm: A1CourseMapViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Карта курса A1", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${state.masteredCount}/${state.totalCount} уроков пройдено",
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
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.byCategory.forEach { (category, clusters) ->
                item(key = "header_$category") {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        category,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.5.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                    )
                }
                items(clusters, key = { it.id }) { cluster ->
                    ClusterMapCard(
                        cluster = cluster,
                        isCurrent = state.currentClusterId == cluster.id,
                        onClick = { onClusterClick(cluster.id) }
                    )
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun ClusterMapCard(
    cluster: ClusterA1Entity,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val mastery = cluster.masteryScore
    val (status, color, iconType) = when {
        cluster.isMastered -> Triple("освоен", Color(0xFF43A047), 0)  // 0 = check
        isCurrent -> Triple("текущий", Color(0xFF1E88E5), 1)  // 1 = play
        cluster.isUnlocked -> Triple("доступен", Color(0xFFFB8C00), 1)
        else -> Triple("заблокирован", Color(0xFF9E9E9E), 2)  // 2 = lock
    }
    
    val pulse = rememberInfiniteTransition(label = "currentPulse")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (isCurrent) 1.05f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse"
    )
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(pulseScale)
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (cluster.isUnlocked) 0.1f else 0.05f))
            .border(
                width = if (isCurrent) 2.dp else 1.dp,
                color = color.copy(alpha = if (isCurrent) 0.6f else 0.25f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = cluster.isUnlocked) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.2f))
                .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when (iconType) {
                0 -> Icon(Icons.Filled.CheckCircle, null, tint = color, modifier = Modifier.size(20.dp))
                1 -> Icon(Icons.Filled.PlayArrow, null, tint = color, modifier = Modifier.size(20.dp))
                else -> Icon(Icons.Filled.Lock, null, tint = color, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                cluster.titleRu,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (cluster.isUnlocked) MaterialTheme.colorScheme.onSurface 
                       else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                cluster.titleDe,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Serif,
            )
        }
        if (cluster.attempts > 0 || mastery > 0) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${(mastery * 100).toInt()}%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                )
                if (cluster.attempts > 0) {
                    Text(
                        "${cluster.attempts}× попыток",
                        fontSize = 9.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
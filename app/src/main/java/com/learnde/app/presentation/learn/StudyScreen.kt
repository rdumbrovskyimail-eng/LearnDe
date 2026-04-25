// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v2.0
// Путь: app/src/main/java/com/learnde/app/presentation/learn/StudyScreen.kt
//
// ИЗМЕНЕНИЯ v2.0:
//   - Убрана плоская заглушка
//   - Premium-поздравление с уровнем
//   - 2 большие кнопки навигации: "Свободный диалог" и "В Хаб"
//   - Подготовка под будущий импорт A2/B1/B2 (структура унифицирована)
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    level: String,
    onBack: () -> Unit,
    onOpenTranslator: () -> Unit = {},   // навигация в Переводчик (A2-B2 могут использовать)
    onOpenFreeDialog: () -> Unit = {},   // навигация в Свободный диалог (когда появится)
) {
    val accentColor = when (level.uppercase()) {
        "A2" -> Color(0xFF1E88E5)
        "B1" -> Color(0xFFFB8C00)
        "B2" -> Color(0xFF7B1FA2)
        else -> Color(0xFF43A047)
    }
    val accentLight = accentColor.copy(alpha = 0.6f)
    
    // Pulsing animation for trophy
    val pulse = rememberInfiniteTransition(label = "trophy")
    val pulseScale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseScale"
    )
    
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Уровень $level", 
                        fontSize = 16.sp, 
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack, 
                            "Назад",
                            tint = MaterialTheme.colorScheme.onBackground,
                        )
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
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            
            // ═══ Hero: Trophy ═══
            Box(
                modifier = Modifier.size(120.dp).scale(pulseScale),
                contentAlignment = Alignment.Center,
            ) {
                // Glow
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    accentColor.copy(alpha = 0.3f),
                                    accentColor.copy(alpha = 0f),
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.2f))
                        .border(2.dp, accentColor, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            
            Spacer(Modifier.height(20.dp))
            
            // ═══ Заголовок ═══
            Text(
                text = "Поздравляем!",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            
            Spacer(Modifier.height(6.dp))
            
            Text(
                text = "Ваш уровень: $level",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = accentColor,
                textAlign = TextAlign.Center,
            )
            
            Spacer(Modifier.height(20.dp))
            
            // ═══ Описание ═══
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                    .border(
                        1.dp,
                        accentColor.copy(alpha = 0.2f),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "СТРУКТУРИРОВАННЫЕ УРОКИ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        letterSpacing = 1.5.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Уроки для уровня $level в активной разработке. " +
                    "Сейчас вы можете практиковаться в свободном диалоге или " +
                    "использовать переводчик в реальном времени.",
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            
            Spacer(Modifier.height(28.dp))
            
            // ═══ Кнопка 1: Переводчик (Free Dialog заменяем на доступный сейчас) ═══
            Button(
                onClick = onOpenTranslator,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentColor,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Translate, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Переводчик Live",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            
            Spacer(Modifier.height(12.dp))
            
            // ═══ Кнопка 2: В Хаб ═══
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(
                    1.5.dp, 
                    accentColor.copy(alpha = 0.5f)
                ),
            ) {
                Icon(
                    Icons.Filled.Home, 
                    null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Вернуться в Хаб",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                )
            }
            
            Spacer(Modifier.weight(1f))
        }
    }
}
// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.1 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/presentation/learn/StudyScreen.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(
    level: String,
    onBack: () -> Unit,
    onOpenTranslator: () -> Unit = {},
    onOpenFreeDialog: () -> Unit = {},
) {
    val colors = learnColors()
    
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
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "Уровень $level", 
                        fontSize = LearnTokens.FontSizeTitle, 
                        fontWeight = FontWeight.Bold,
                        color = colors.textHi,
                    ) 
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.bg
                )
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = LearnTokens.PaddingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.5f))
            
            // ═══ Hero: Trophy ═══
            Box(
                modifier = Modifier.size(180.dp).scale(pulseScale),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                )
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(colors.accent.copy(alpha = 0.1f))
                        .border(LearnTokens.BorderMedium, colors.accent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.EmojiEvents,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
            
            Spacer(Modifier.height(LearnTokens.PaddingXl))
            
            // ═══ Заголовок ═══
            Text(
                text = "Поздравляем!",
                fontSize = LearnTokens.FontSizeTitleLg,
                fontWeight = FontWeight.Bold,
                color = colors.textHi,
                textAlign = TextAlign.Center,
            )
            
            Spacer(Modifier.height(6.dp))
            
            Text(
                text = "Ваш уровень: $level",
                fontSize = LearnTokens.FontSizeTitle,
                fontWeight = FontWeight.SemiBold,
                color = colors.accent,
                textAlign = TextAlign.Center,
            )
            
            Spacer(Modifier.height(LearnTokens.PaddingXl))
            
            // ═══ Описание ═══
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(LearnTokens.RadiusMd))
                    .background(colors.surface)
                    .border(
                        LearnTokens.BorderThin,
                        colors.stroke,
                        RoundedCornerShape(LearnTokens.RadiusMd)
                    )
                    .padding(LearnTokens.PaddingLg),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        null,
                        tint = colors.accent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "СТРУКТУРИРОВАННЫЕ УРОКИ",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent,
                        letterSpacing = LearnTokens.CapsLetterSpacing,
                    )
                }
                Spacer(Modifier.height(LearnTokens.PaddingSm))
                Text(
                    "Уроки для уровня $level в активной разработке. " +
                    "Сейчас вы можете практиковаться в свободном диалоге или " +
                    "использовать переводчик в реальном времени.",
                    fontSize = LearnTokens.FontSizeBody,
                    lineHeight = 19.sp,
                    color = colors.textMid,
                    textAlign = TextAlign.Center,
                )
            }
            
            Spacer(Modifier.height(32.dp))
            
            // ═══ Кнопка 1: Переводчик ═══
            Button(
                onClick = onOpenTranslator,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LearnTokens.ButtonHeightMd),
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = Color.White,
                ),
            ) {
                Icon(Icons.Filled.Translate, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    "Переводчик Live",
                    fontSize = LearnTokens.FontSizeBodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            
            Spacer(Modifier.height(LearnTokens.PaddingMd))
            
            // ═══ Кнопка 2: В Хаб ═══
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LearnTokens.ButtonHeightMd),
                shape = RoundedCornerShape(LearnTokens.RadiusSm),
                border = BorderStroke(
                    LearnTokens.BorderMedium, 
                    colors.strokeStrong
                ),
            ) {
                Icon(
                    Icons.Filled.Home, 
                    null,
                    tint = colors.textHi,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Вернуться в Хаб",
                    fontSize = LearnTokens.FontSizeBodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.textHi,
                )
            }
            
            Spacer(Modifier.weight(1f))
        }
    }
}
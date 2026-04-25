// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА v5.0 (Voice-First Minimalism)
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/MicPermissionRationaleDialog.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.learnde.app.presentation.learn.theme.LearnTokens
import com.learnde.app.presentation.learn.theme.learnColors

@Composable
fun MicPermissionRationaleDialog(
    showSettingsButton: Boolean,
    onDismiss: () -> Unit,
    onRequestAgain: () -> Unit,
    context: Context,
) {
    val colors = learnColors()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Mic,
                    null,
                    tint = colors.accent,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(LearnTokens.PaddingSm))
                Text(
                    "Нужен микрофон",
                    fontWeight = FontWeight.Bold,
                    color = colors.textHi,
                )
            }
        },
        text = {
            Text(
                "LearnDE использует микрофон, чтобы вы могли разговаривать с AI-преподавателем на немецком в реальном времени. Без микрофона обучение, тестирование и переводчик не работают.\n\n" +
                    if (showSettingsButton) {
                        "Доступ заблокирован системно. Откройте настройки и включите вручную."
                    } else {
                        "Нажмите «Разрешить» в системном диалоге."
                    },
                fontSize = LearnTokens.FontSizeBody,
                lineHeight = 18.sp,
                color = colors.textMid,
            )
        },
        confirmButton = {
            if (showSettingsButton) {
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                        onDismiss()
                    },
                ) {
                    Text(
                        "Открыть настройки",
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
            } else {
                TextButton(onClick = onRequestAgain) {
                    Text(
                        "Разрешить",
                        fontWeight = FontWeight.SemiBold,
                        color = colors.accent,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена", color = colors.textMid)
            }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(LearnTokens.RadiusLg),
    )
}

fun shouldShowMicRationale(activity: Activity): Boolean {
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
        activity, android.Manifest.permission.RECORD_AUDIO,
    )
}

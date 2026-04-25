// ═══════════════════════════════════════════════════════════
// НОВЫЙ ФАЙЛ
// Путь: app/src/main/java/com/learnde/app/presentation/learn/components/MicPermissionRationaleDialog.kt
//
// Универсальный диалог объяснения, зачем нужен микрофон.
// Используется во ВСЕХ экранах, где запускается LiveClient.
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.learn.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Универсальный диалог-объяснение для прав на микрофон.
 * 
 * @param showSettingsButton — true если пользователь отказал navsegda 
 *                              (shouldShowRequestPermissionRationale == false и пермишен denied)
 * @param onDismiss — закрытие без действия
 * @param onRequestAgain — попытка запросить разрешение снова (если еще можно)
 */
@Composable
fun MicPermissionRationaleDialog(
    showSettingsButton: Boolean,
    onDismiss: () -> Unit,
    onRequestAgain: () -> Unit,
    context: Context,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Mic,
                    null,
                    tint = Color(0xFF43A047),
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text("Нужен доступ к микрофону", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Text(
                "LearnDE использует микрофон, чтобы вы могли разговаривать с " +
                "AI-преподавателем на немецком в реальном времени. Без микрофона " +
                "обучение, тестирование и переводчик не работают.\n\n" +
                if (showSettingsButton) {
                    "Доступ заблокирован системно. Откройте Настройки и включите его вручную."
                } else {
                    "Нажмите «Разрешить» в системном диалоге."
                },
                fontSize = 13.sp,
                lineHeight = 18.sp,
            )
        },
        confirmButton = {
            if (showSettingsButton) {
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        onDismiss()
                    }
                ) {
                    Text("Открыть настройки", fontWeight = FontWeight.SemiBold)
                }
            } else {
                TextButton(onClick = onRequestAgain) {
                    Text("Разрешить", fontWeight = FontWeight.SemiBold)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

/** Helper: проверяет нужно ли показывать rationale. */
fun shouldShowMicRationale(activity: Activity): Boolean {
    return androidx.core.app.ActivityCompat.shouldShowRequestPermissionRationale(
        activity, android.Manifest.permission.RECORD_AUDIO
    )
}
// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/codeextractor/app/presentation/navigation/NavGraph.kt
// Изменения:
//   + Routes.FUNCTIONS (экран тестирования 10 функций)
//   + FIX: launchSingleTop + saveState/restoreState в навигацию к SETTINGS
//   + FIX: VOICE тоже singleTop — не пересоздаётся при возврате
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.learnde.app.Learn.Test.A0a1.A0a1TestScreen
import com.learnde.app.presentation.editor.ModelEditorScreen
import com.learnde.app.presentation.functions.FunctionsTestScreen
import com.learnde.app.presentation.settings.SettingsScreen
import com.learnde.app.presentation.voice.VoiceScreen

object Routes {
    const val SETTINGS  = "settings"
    const val VOICE     = "voice"
    const val EDITOR    = "editor"
    const val FUNCTIONS = "functions"
    const val TEST_A0A1 = "test_a0a1"
}

object VoiceGender {
    private val MALE_VOICES = setOf("Puck", "Charon", "Fenrir", "Orus")
    fun avatarIndexForVoice(voiceId: String): Int =
        if (voiceId in MALE_VOICES) 1 else 2
}

@Composable
fun AppNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SETTINGS,
    ) {
        composable(
            route = Routes.SETTINGS,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            SettingsScreen(
                onStartSession = {
                    navController.navigate(Routes.VOICE) {
                        launchSingleTop = true
                        popUpTo(Routes.SETTINGS) { saveState = true }
                        restoreState = true
                    }
                }
            )
        }

        composable(
            route = Routes.VOICE,
            enterTransition = { slideInHorizontally(tween(300)) { -it } },
            exitTransition  = { slideOutHorizontally(tween(300)) { -it } },
        ) {
            VoiceScreen(
                onOpenEditor   = {
                    navController.navigate(Routes.EDITOR) { launchSingleTop = true }
                },
                onOpenSettings = {
                    // ═══ FIX КРАША ПРИ ПЕРЕХОДЕ В НАСТРОЙКИ ═══
                    // singleTop + popUpTo с сохранением состояния —
                    // гарантирует единственный инстанс Settings в back stack.
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                        popUpTo(Routes.SETTINGS) {
                            inclusive = false
                            saveState = true
                        }
                        restoreState = true
                    }
                },
                onOpenFunctions = {
                    navController.navigate(Routes.FUNCTIONS) { launchSingleTop = true }
                },
                onOpenA0a1Test   = {
                    navController.navigate(Routes.TEST_A0A1) { launchSingleTop = true }
                }
            )
        }

        composable(
            route = Routes.EDITOR,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            ModelEditorScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.FUNCTIONS,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            FunctionsTestScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.TEST_A0A1,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            A0a1TestScreen(onBack = { navController.popBackStack() })
        }
    }
}
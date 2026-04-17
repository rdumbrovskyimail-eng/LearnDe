// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/navigation/NavGraph.kt
//
// Изменения:
//   • Импорт A0a1TestScreen теперь из lowercase-пакета
//     com.learnde.app.learn.test.a0a1.A0a1TestScreen
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.presentation.editor.ModelEditorScreen
import com.learnde.app.presentation.functions.FunctionsTestScreen
import com.learnde.app.presentation.learn.LearnHubScreen
import com.learnde.app.presentation.settings.SettingsScreen
import com.learnde.app.presentation.voice.VoiceScreen

object Routes {
    const val SETTINGS   = "settings"
    const val VOICE      = "voice"
    const val EDITOR     = "editor"
    const val FUNCTIONS  = "functions"

    // ── Learn graph ──
    const val LEARN_GRAPH = "learn_graph"
    const val LEARN_HUB   = "learn/hub"
    const val LEARN_A0A1  = "learn/a0a1"
}

object VoiceGender {
    /**
     * Полный список мужских Gemini Live voices. Источник: ai.google.dev/gemini-api/docs
     * (на момент релиза). Если выбран голос не из списка — считаем женским.
     */
    private val MALE_VOICES = setOf(
        "Puck", "Charon", "Fenrir", "Orus",
        "Algenib", "Rasalgethi", "Alnilam", "Schedar",
        "Achird", "Iapetus", "Zubenelgenubi", "Sadachbia",
        "Sadaltager", "Enceladus", "Umbriel", "Algieba"
    )

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
                onOpenLearnHub = {
                    navController.navigate(Routes.LEARN_GRAPH) { launchSingleTop = true }
                },
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

        // ═══ Learn graph — общий NavBackStackEntry scope ═══
        // Все экраны Learn-блока разделяют один LearnCoreViewModel через
        // NavBackStackEntry родительского графа (LEARN_GRAPH).
        navigation(
            route = Routes.LEARN_GRAPH,
            startDestination = Routes.LEARN_HUB,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            composable(Routes.LEARN_HUB) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                LearnHubScreen(
                    onBack = { navController.popBackStack() },
                    onOpenA0a1Test = {
                        navController.navigate(Routes.LEARN_A0A1) { launchSingleTop = true }
                    },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            composable(Routes.LEARN_A0A1) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                com.learnde.app.learn.test.a0a1.A0a1TestScreen(
                    onBack = { navController.popBackStack() },
                    learnCoreViewModel = learnCoreVm,
                )
            }
        }
    }
}

/**
 * Возвращает LearnCoreViewModel, scoped к родительскому navigation graph
 * (Routes.LEARN_GRAPH). Таким образом все экраны внутри этого графа
 * разделяют ОДИН инстанс LearnCoreViewModel — это даёт единый источник
 * состояния Gemini-сессии, инфо-табло и т.д.
 *
 * При выходе из графа (pop back past LEARN_GRAPH) — VM уничтожается,
 * что триггерит onCleared → disconnect, release(Arbiter), release(audio).
 */
@Composable
private fun NavBackStackEntry.sharedLearnCoreViewModel(
    navController: androidx.navigation.NavHostController
): LearnCoreViewModel {
    val parentEntry = remember(this) {
        navController.getBackStackEntry(Routes.LEARN_GRAPH)
    }
    return hiltViewModel(parentEntry)
}

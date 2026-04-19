// ═══════════════════════════════════════════════════════════
// ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/navigation/NavGraph.kt
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
import com.learnde.app.presentation.onboarding.OnboardingScreen
import com.learnde.app.presentation.settings.SettingsScreen
import com.learnde.app.presentation.voice.VoiceScreen

object Routes {
    const val ONBOARDING = "onboarding"
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
        startDestination = Routes.ONBOARDING, // 👈 Изменен стартовый экран
    ) {
        composable(
            route = Routes.ONBOARDING,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            OnboardingScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.SETTINGS) {
                        launchSingleTop = true
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.SETTINGS,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            SettingsScreen(
                onStartSession = {
                    // 👈 При нажатии на кнопку внизу открывается Обучение (Hub), а не Voice
                    navController.navigate(Routes.LEARN_GRAPH) {
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
                    // Возвращаемся в хаб (или открываем его, если пришли из настроек)
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

        navigation(
            route = Routes.LEARN_GRAPH,
            startDestination = Routes.LEARN_HUB,
            enterTransition = { fadeIn(tween(250)) },
            exitTransition  = { fadeOut(tween(200)) },
        ) {
            composable(Routes.LEARN_HUB) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                LearnHubScreen(
                    onBack = { navController.navigate(Routes.SETTINGS) { popUpTo(Routes.SETTINGS) { inclusive = true } } },
                    onOpenA0a1Test = {
                        navController.navigate(Routes.LEARN_A0A1) { launchSingleTop = true }
                    },
                    onOpenVoiceClient = {
                        // 👈 Открытие чистого Gemini-клиента из правого верхнего угла Hub
                        navController.navigate(Routes.VOICE) { launchSingleTop = true }
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

@Composable
private fun NavBackStackEntry.sharedLearnCoreViewModel(
    navController: androidx.navigation.NavHostController
): LearnCoreViewModel {
    val parentEntry = remember(this) {
        navController.getBackStackEntry(Routes.LEARN_GRAPH)
    }
    return hiltViewModel(parentEntry)
}
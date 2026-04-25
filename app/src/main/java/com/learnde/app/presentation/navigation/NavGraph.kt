// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА (Patch 2.5)
// Путь: app/src/main/java/com/learnde/app/presentation/navigation/NavGraph.kt
//
// ИЗМЕНЕНИЯ:
//   - Добавлен маршрут learn/a1/history
//   - A1LearningScreen теперь принимает onOpenHistory
//   - Кнопка "Повторить урок" из History переводит в a1 с arg clusterId
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
    const val LEARN_GRAPH   = "learn_graph"
    const val LEARN_HUB     = "learn/hub"
    const val LEARN_TRANSLATOR = "learn/translator"
    const val LEARN_A0A1    = "learn/a0a1"
    const val LEARN_A1      = "learn/a1"
    const val LEARN_A1_WITH_CLUSTER = "learn/a1?clusterId={clusterId}"
    const val LEARN_A1_HISTORY = "learn/a1/history"
    const val LEARN_A1_VOCABULARY = "learn/a1/vocabulary"
    const val LEARN_A1_SESSION_DETAILS = "learn/a1/session/{sessionId}"
    const val DEBUG_LOGS = "debug/logs"
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
        startDestination = Routes.ONBOARDING,
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
                    onBack = {
                        navController.navigate(Routes.SETTINGS) {
                            popUpTo(Routes.SETTINGS) { inclusive = true }
                        }
                    },
                    onOpenTranslator = {
                        navController.navigate(Routes.LEARN_TRANSLATOR) { launchSingleTop = true }
                    },
                    onOpenA0a1Test = {
                        navController.navigate(Routes.LEARN_A0A1) { launchSingleTop = true }
                    },
                    onOpenA1Learning = {
                        navController.navigate(Routes.LEARN_A1) { launchSingleTop = true }
                    },
                    onOpenVoiceClient = {
                        navController.navigate(Routes.VOICE) { launchSingleTop = true }
                    },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            composable(Routes.LEARN_TRANSLATOR) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                com.learnde.app.learn.sessions.translator.TranslatorScreen(
                    onBack = { navController.popBackStack() },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            composable(Routes.LEARN_A0A1) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                com.learnde.app.learn.test.a0a1.A0a1TestScreen(
                    onBack = { navController.popBackStack() },
                    onNavigateToStudy = { level ->
                        navController.navigate("learn/study/$level") {
                            popUpTo(Routes.LEARN_HUB)
                        }
                    },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            // A1 learning main screen
            composable(
                route = Routes.LEARN_A1_WITH_CLUSTER,
                arguments = listOf(
                    navArgument("clusterId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                val clusterId = entry.arguments?.getString("clusterId")
                val a1Vm: com.learnde.app.learn.sessions.a1.A1LearningViewModel =
                    hiltViewModel(entry)

                // Если пришёл clusterId из History — автозапуск
                LaunchedEffect(clusterId) {
                    if (!clusterId.isNullOrBlank()) {
                        a1Vm.onIntent(
                            com.learnde.app.learn.sessions.a1.A1LearningIntent.StartCluster(clusterId)
                        )
                    }
                }

                com.learnde.app.learn.sessions.a1.A1LearningScreen(
                    onBack = { navController.popBackStack() },
                    onOpenHistory = {
                        navController.navigate(Routes.LEARN_A1_HISTORY) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDebugLogs = {
                        navController.navigate(Routes.DEBUG_LOGS) {
                            launchSingleTop = true
                        }
                    },
                    onOpenVocabulary = { navController.navigate("learn/a1/vocabulary") },
                    onOpenCourseMap = { navController.navigate("learn/a1/coursemap") },
                    learnCoreViewModel = learnCoreVm,
                    vm = a1Vm,
                )
            }

            // Простой alias без аргументов — перенаправляет в основной маршрут
            composable(Routes.LEARN_A1) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                com.learnde.app.learn.sessions.a1.A1LearningScreen(
                    onBack = { navController.popBackStack() },
                    onOpenHistory = {
                        navController.navigate(Routes.LEARN_A1_HISTORY) {
                            launchSingleTop = true
                        }
                    },
                    onOpenDebugLogs = {
                        navController.navigate(Routes.DEBUG_LOGS) {
                            launchSingleTop = true
                        }
                    },
                    onOpenVocabulary = { navController.navigate("learn/a1/vocabulary") },
                    onOpenCourseMap = { navController.navigate("learn/a1/coursemap") },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            composable(Routes.LEARN_A1_HISTORY) {
                com.learnde.app.learn.sessions.a1.history.A1HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onRepeatCluster = { clusterId ->
                        navController.navigate("learn/a1?clusterId=$clusterId") {
                            popUpTo(Routes.LEARN_A1_HISTORY) { inclusive = true }
                        }
                    },
                    onOpenDetails = { sessionId ->
                        navController.navigate("learn/a1/session/$sessionId") {
                            launchSingleTop = true
                        }
                    },
                )
            }

            composable(
                route = Routes.LEARN_A1_SESSION_DETAILS,
                arguments = listOf(
                    androidx.navigation.navArgument("sessionId") {
                        type = androidx.navigation.NavType.LongType
                    }
                )
            ) { entry ->
                val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
                com.learnde.app.learn.sessions.a1.history.SessionDetailsScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onRepeatCluster = { clusterId ->
                        navController.navigate("learn/a1?clusterId=$clusterId") {
                            popUpTo(Routes.LEARN_A1_HISTORY) { inclusive = true }
                        }
                    },
                    onStartNewReview = {
                        navController.navigate(Routes.LEARN_A1) {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(Routes.DEBUG_LOGS) {
                com.learnde.app.learn.sessions.a1.debug.DebugLogsScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable("learn/study/{level}") { entry ->
                val level = entry.arguments?.getString("level") ?: "A0"
                com.learnde.app.presentation.learn.StudyScreen(
                    level = level,
                    onBack = { navController.popBackStack(Routes.LEARN_HUB, inclusive = false) },
                    onOpenTranslator = {
                        navController.navigate(Routes.LEARN_TRANSLATOR) { launchSingleTop = true }
                    },
                    onOpenFreeDialog = {
                        navController.navigate(Routes.VOICE) { launchSingleTop = true }
                    }
                )
            }
        }
    }
}

@Composable
private fun NavBackStackEntry.sharedLearnCoreViewModel(
    navController: NavHostController
): LearnCoreViewModel {
    val parentEntry = remember(this) {
        navController.getBackStackEntry(Routes.LEARN_GRAPH)
    }
    return hiltViewModel(parentEntry)
}
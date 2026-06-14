// ═══════════════════════════════════════════════════════════
// ПОЛНАЯ ЗАМЕНА
// Путь: app/src/main/java/com/learnde/app/presentation/navigation/NavGraph.kt
// ═══════════════════════════════════════════════════════════
package com.learnde.app.presentation.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.learnde.app.learn.core.LearnCoreViewModel
import com.learnde.app.learn.test.a0a1.A0a1TestScreen
import com.learnde.app.presentation.functions.FunctionsTestScreen
import com.learnde.app.presentation.learn.LearnHubScreen
import com.learnde.app.presentation.levelselect.LevelSelectScreen
import com.learnde.app.presentation.onboarding.OnboardingScreen
import com.learnde.app.presentation.settings.SettingsScreen
import com.learnde.app.presentation.learn.theme.learnColors

object Routes {
    const val GATE          = "gate"            // решает: тест или выбор уровня
    const val ONBOARDING    = "onboarding"
    const val A0A1_TEST     = "test/a0a1"
    const val LEVEL_SELECT  = "levels"
    const val SETTINGS      = "settings"

    const val LEARN_GRAPH   = "learn_graph"
    const val LEARN_HUB     = "learn/hub"
    const val LEARN_A1      = "learn/a1"
    const val LEARN_A1_WITH_CLUSTER = "learn/a1?clusterId={clusterId}"
    const val LEARN_A1_HISTORY = "learn/a1/history"
    const val LEARN_A1_VOCABULARY = "learn/a1/vocabulary"
    const val LEARN_A1_SESSION_DETAILS = "learn/a1/session/{sessionId}"
    const val LEARN_A1_COURSE_MAP = "learn/a1/coursemap"
    const val LEARN_A1_GRAMMAR = "learn/a1/grammar"
    const val LEARN_STUDIO = "learn/studio"
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
        startDestination = Routes.GATE,
    ) {
        composable(Routes.GATE) {
            val settingsVm: GateViewModel = hiltViewModel()
            val userName by settingsVm.userName.collectAsStateWithLifecycle(initialValue = null)
            val testPassed by settingsVm.testPassed.collectAsStateWithLifecycle(initialValue = null)
            
            LaunchedEffect(userName, testPassed) {
                if (userName != null && testPassed != null) {
                    if (userName!!.isBlank()) {
                        navController.navigate(Routes.ONBOARDING) { popUpTo(Routes.GATE) { inclusive = true } }
                    } else if (testPassed == true) {
                        navController.navigate(Routes.LEVEL_SELECT) { popUpTo(Routes.GATE) { inclusive = true } }
                    } else {
                        navController.navigate(Routes.A0A1_TEST) { popUpTo(Routes.GATE) { inclusive = true } }
                    }
                }
            }
            Box(Modifier.fillMaxSize().background(learnColors().background))
        }

        composable(Routes.ONBOARDING) {
            com.learnde.app.presentation.onboarding.OnboardingScreen(
                onNavigateToSettings = {
                    navController.navigate(Routes.GATE) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.A0A1_TEST) {
            val learnCoreViewModel: LearnCoreViewModel = hiltViewModel()
            val goToLevels: () -> Unit = {
                navController.navigate(Routes.LEVEL_SELECT) {
                    popUpTo(Routes.A0A1_TEST) { inclusive = true }
                }
            }
            A0a1TestScreen(
                onBack = goToLevels,
                onNavigateToStudy = { _ -> goToLevels() },
                onNavigateToRoute = { route ->
                    // Кладём LEVEL_SELECT в backstack, чтобы «Назад» из A1 не закрывал приложение
                    navController.navigate(Routes.LEVEL_SELECT) {
                        popUpTo(Routes.A0A1_TEST) { inclusive = true }
                    }
                    navController.navigate(route) { launchSingleTop = true }
                },
                learnCoreViewModel = learnCoreViewModel,
            )
        }

        composable(Routes.LEVEL_SELECT) {
            LevelSelectScreen(
                onOpenA1 = { navController.navigate(Routes.LEARN_STUDIO) { launchSingleTop = true } }, // ИСПРАВЛЕНО: Открывает Студию
                onOpenSettings = { navController.navigate(Routes.SETTINGS) { launchSingleTop = true } },
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
                        navController.navigate(Routes.LEVEL_SELECT) {
                            popUpTo(Routes.LEVEL_SELECT) { inclusive = true }
                        }
                    },
                    onOpenA1Learning = {
                        navController.navigate(Routes.LEARN_STUDIO) { launchSingleTop = true } // ИСПРАВЛЕНО: Переход на Студию
                    },
                    onOpenGrammar = {
                        navController.navigate(Routes.LEARN_A1_GRAMMAR) { launchSingleTop = true }
                    },
                    onOpenStudio = {
                        navController.navigate(Routes.LEARN_STUDIO) { launchSingleTop = true }
                    },
                    onOpenDebugLogs = {
                        navController.navigate(Routes.DEBUG_LOGS) { launchSingleTop = true }
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

                val handle = entry.savedStateHandle
                LaunchedEffect(clusterId) {
                    val alreadyStarted = handle.get<String>("startedClusterId") == clusterId
                    if (!clusterId.isNullOrBlank() && !alreadyStarted) {
                        handle["startedClusterId"] = clusterId
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
                    onOpenVocabulary = { navController.navigate(Routes.LEARN_A1_VOCABULARY) },
                    onOpenCourseMap = { navController.navigate(Routes.LEARN_A1_COURSE_MAP) },
                    learnCoreViewModel = learnCoreVm,
                    vm = a1Vm,
                )
            }

            composable(Routes.LEARN_A1) {
                LaunchedEffect(Unit) {
                    navController.navigate("learn/a1?clusterId=") {
                        popUpTo(Routes.LEARN_A1) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            composable(Routes.LEARN_A1_HISTORY) {
                com.learnde.app.learn.sessions.a1.history.A1HistoryScreen(
                    onBack = { navController.popBackStack() },
                    onRepeatCluster = { clusterId ->
                        navController.navigate("learn/studio?clusterId=$clusterId") { // ИСПРАВЛЕНО
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

            composable(Routes.LEARN_A1_VOCABULARY) {
                com.learnde.app.learn.sessions.a1.vocabulary.A1VocabularyScreen(
                    onBack = { navController.popBackStack() }
                )
            }

            composable(Routes.LEARN_A1_GRAMMAR) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(learnColors().bg)
                ) {
                    com.learnde.app.learn.sessions.a1.grammar.GrammarSheet(
                        onDismiss = { navController.popBackStack() }
                    )
                }
            }

            // Добавлена поддержка опционального аргумента clusterId для Студии
            composable(
                route = "learn/studio?clusterId={clusterId}",
                arguments = listOf(
                    navArgument("clusterId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { entry ->
                val learnCoreVm = entry.sharedLearnCoreViewModel(navController)
                com.learnde.app.presentation.learn.v2.StudioScreen(
                    onBack = { navController.popBackStack() },
                    learnCoreViewModel = learnCoreVm,
                )
            }

            composable(Routes.LEARN_A1_COURSE_MAP) {
                com.learnde.app.learn.sessions.a1.coursemap.A1CourseMapScreen(
                    onBack = { navController.popBackStack() },
                    onClusterClick = { clusterId ->
                        navController.navigate("learn/studio?clusterId=$clusterId") {
                            launchSingleTop = true
                        }
                    }
                )
            }

            composable(
                route = Routes.LEARN_A1_SESSION_DETAILS,
                arguments = listOf(
                    navArgument("sessionId") {
                        type = NavType.LongType
                    }
                )
            ) { entry ->
                val sessionId = entry.arguments?.getLong("sessionId") ?: 0L
                com.learnde.app.learn.sessions.a1.history.SessionDetailsScreen(
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                    onRepeatCluster = { clusterId ->
                        navController.navigate("learn/studio?clusterId=$clusterId") { // ИСПРАВЛЕНО
                            popUpTo(Routes.LEARN_A1_HISTORY) { inclusive = true }
                        }
                    },
                    onStartNewReview = {
                        navController.navigate("learn/studio?clusterId=") { // ИСПРАВЛЕНО
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
                    onBack = { navController.popBackStack(Routes.LEARN_HUB, inclusive = false) }
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
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
            val testPassed by settingsVm.testPassed.collectAsStateWithLifecycle(initialValue = null)
            LaunchedEffect(testPassed) {
                when (testPassed) {
                    true  -> navController.navigate(Routes.LEVEL_SELECT) { popUpTo(Routes.GATE) { inclusive = true } }
                    false -> navController.navigate(Routes.A0A1_TEST)    { popUpTo(Routes.GATE) { inclusive = true } }
                    null  -> Unit // ждём чтения настроек
                }
            }
            Box(Modifier.fillMaxSize().background(learnColors().background))
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
                onNavigateToRoute = { _ -> goToLevels() },
                learnCoreViewModel = learnCoreViewModel,
            )
        }

        composable(Routes.LEVEL_SELECT) {
            LevelSelectScreen(
                onOpenA1 = { navController.navigate(Routes.LEARN_GRAPH) { launchSingleTop = true } },
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
                        navController.navigate(Routes.LEARN_A1) { launchSingleTop = true }
                    },
                    onOpenGrammar = {
                        navController.navigate(Routes.LEARN_A1_GRAMMAR) { launchSingleTop = true }
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

            composable(Routes.LEARN_A1_COURSE_MAP) {
                com.learnde.app.learn.sessions.a1.coursemap.A1CourseMapScreen(
                    onBack = { navController.popBackStack() },
                    onClusterClick = { clusterId ->
                        navController.navigate("learn/a1?clusterId=$clusterId") {
                            popUpTo(Routes.LEARN_A1) { inclusive = true }
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
package com.testplaybyte.loom.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.testplaybyte.loom.ui.LoomViewModel
import com.testplaybyte.loom.ui.components.LoomEmptyAction
import com.testplaybyte.loom.ui.components.LoomScreenHeader
import com.testplaybyte.loom.ui.icons.IconFolderOpen
import com.testplaybyte.loom.ui.screens.annotate.AnnotateScreen
import com.testplaybyte.loom.ui.screens.export.ExportScreen
import com.testplaybyte.loom.ui.screens.newproject.NewProjectScreen
import com.testplaybyte.loom.ui.screens.permissions.PermissionsScreen
import com.testplaybyte.loom.ui.screens.project.ProjectDetailScreen
import com.testplaybyte.loom.ui.screens.projects.ProjectsScreen
import com.testplaybyte.loom.ui.screens.settings.SettingsScreen
import com.testplaybyte.loom.ui.screens.splash.SplashScreen
import com.testplaybyte.loom.ui.theme.LoomMotion

/**
 * The navigation graph (docs/04 §1) with the prototype's screen-enter
 * motion (`lm-enter`: fade + 6dp up slide, 240ms).
 *
 * First-run gate: until `perms.unlocked` (all grants or the limited-access
 * skip), Permissions is the only reachable place after the splash — the
 * gate is evaluated against PERSISTED state (we wait for `hydrated`).
 */
@Composable
fun LoomNavHost(
    navController: NavHostController,
    vm: LoomViewModel,
) {
    val hydrated by vm.hydrated.collectAsState()
    val perms by vm.perms.collectAsState()

    NavHost(
        navController = navController,
        startDestination = Routes.SPLASH,
        enterTransition = {
            fadeIn(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)) +
                slideInVertically(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)) { it / 60 }
        },
        exitTransition = {
            fadeOut(tween(LoomMotion.DUR_2, easing = LoomMotion.ease))
        },
        popEnterTransition = {
            fadeIn(tween(LoomMotion.DUR_3, easing = LoomMotion.ease))
        },
        popExitTransition = {
            fadeOut(tween(LoomMotion.DUR_2, easing = LoomMotion.ease)) +
                slideOutVertically(tween(LoomMotion.DUR_3, easing = LoomMotion.ease)) { it / 60 }
        },
    ) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onDone = {
                    val target = if (hydrated && perms.unlocked) Routes.PROJECTS else Routes.PERMISSIONS
                    navController.navigate(target) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                hydrated = hydrated,
            )
        }

        composable(Routes.PERMISSIONS) {
            PermissionsScreen(
                vm = vm,
                onDone = {
                    navController.navigate(Routes.PROJECTS) {
                        popUpTo(Routes.PERMISSIONS) { inclusive = true }
                    }
                },
            )
        }

        composable(Routes.PROJECTS) {
            // Defensive gate: persisted state says the user must grant first.
            LaunchedEffect(hydrated, perms.unlocked) {
                if (hydrated && !perms.unlocked) {
                    navController.navigate(Routes.PERMISSIONS) {
                        popUpTo(Routes.PROJECTS) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }
            ProjectsScreen(
                vm = vm,
                onOpenProject = { id -> navController.navigate(Routes.project(id)) },
                onNewProject = { navController.navigate(Routes.NEW_PROJECT) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
            )
        }

        composable(Routes.NEW_PROJECT) {
            NewProjectScreen(
                vm = vm,
                onBack = { navController.popBackStack() },
                onCreated = { id ->
                    navController.navigate(Routes.project(id)) {
                        popUpTo(Routes.NEW_PROJECT) { inclusive = true }
                    }
                },
            )
        }

        composable(
            route = Routes.PROJECT,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_PROJECT_ID)
            ProjectDetailScreen(
                vm = vm,
                projectId = id,
                onBack = { navController.popBackStack() },
                onAnnotate = { index -> navController.navigate(Routes.annotate(id ?: "", index)) },
                onExport = { navController.navigate(Routes.export(id ?: "")) },
            )
        }

        composable(
            route = Routes.ANNOTATE,
            arguments = listOf(
                navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_INDEX) { type = NavType.IntType },
            ),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_PROJECT_ID)
            val index = entry.arguments?.getInt(Routes.ARG_INDEX) ?: 0
            AnnotateScreen(
                vm = vm,
                projectId = id,
                startIndex = index,
                onExit = { navController.popBackStack() },
            )
        }

        composable(
            route = Routes.EXPORT,
            arguments = listOf(navArgument(Routes.ARG_PROJECT_ID) { type = NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString(Routes.ARG_PROJECT_ID)
            ExportScreen(
                vm = vm,
                projectId = id,
                onBack = { navController.popBackStack() },
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                vm = vm,
                onBack = { navController.navigate(Routes.PROJECTS) { launchSingleTop = true } },
            )
        }
    }
}

/** "That project no longer exists." fallback (docs/04 §1). */
@Composable
fun MissingProjectScreen(onBackToProjects: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) {
        LoomScreenHeader(title = "Loom", onBack = onBackToProjects)
        LoomEmptyAction(
            title = "That project no longer exists.",
            sub = "It may have been deleted or the workspace folder changed.",
            actionLabel = "Back to projects",
            onAction = onBackToProjects,
            icon = IconFolderOpen,
            modifier = Modifier.padding(top = 120.dp),
        )
    }
}

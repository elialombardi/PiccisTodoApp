package com.elialombardi.piccistodo

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elialombardi.piccistodo.ui.screens.CreateCollectionScreen
import com.elialombardi.piccistodo.ui.screens.CreateScreen
import com.elialombardi.piccistodo.ui.screens.DetailScreen
import com.elialombardi.piccistodo.ui.screens.HomeScreen
import com.elialombardi.piccistodo.ui.screens.LoginScreen
import com.elialombardi.piccistodo.ui.screens.TasksScreen
import com.elialombardi.piccistodo.ui.screens.AccountScreen

@Composable
fun MainNavigation(startDestination: String = "login") {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tasksRoot by rememberSaveable { mutableStateOf(true) }
    val showNavigation = route == "home" || route == "account" || (route == "tasks" && tasksRoot)
    val signedOut = {
        navController.navigate("login") {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    val openSection: (String) -> Unit = { target ->
        if (route != target) navController.navigate(target) {
            popUpTo("home") { inclusive = false }
            launchSingleTop = true
        }
    }
    Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
        if (showNavigation) WorkspaceNavigation(route.orEmpty(), openSection)
    }) { padding ->
      NavHost(navController = navController, startDestination = startDestination,
          modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("home") {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable("home") {
            HomeScreen(
                onAccountClick = { openSection("account") },
                onCollectionClick = { collectionId ->
                    navController.navigate("detail/$collectionId")
                },
                onAddCollectionClick = {
                    navController.navigate("create_collection")
                }
            )
        }

        composable("tasks") {
            TasksScreen(
                onBack = { navController.popBackStack() },
                onAuthenticationRequired = signedOut,
                isTopLevel = true,
                onRootChanged = { tasksRoot = it }
            )
        }
        composable("account") { AccountScreen(onSignedOut = signedOut) }

        composable("create_collection") {
            CreateCollectionScreen(
                onBackClick = { navController.popBackStack() },
                onSaved = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = "detail/{collectionId}",
            arguments = listOf(navArgument("collectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val collectionIdString = backStackEntry.arguments?.getString("collectionId") ?: "0"
            val collectionId = collectionIdString.toUIntOrNull() ?: 0u

            DetailScreen(
                collectionId = collectionId,
                onAddItemClick = {
                    navController.navigate("create?collectionId=$collectionId")
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        composable(
            route = "create?collectionId={collectionId}",
            arguments = listOf(navArgument("collectionId") {
                type = NavType.StringType
                defaultValue = "0"
            })
        ) { backStackEntry ->
            val collectionIdString = backStackEntry.arguments?.getString("collectionId") ?: "0"
            val collectionId = collectionIdString.toUIntOrNull() ?: 0u

            CreateScreen(
                collectionId = collectionId,
                onSaved = { navController.popBackStack() },
                onBackClick = { navController.popBackStack() }
            )
        }
      }
    }
}

@Composable
internal fun WorkspaceNavigation(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        listOf(
            Triple("home", R.string.nav_collections, Icons.Outlined.FolderOpen),
            Triple("tasks", R.string.tasks_title, Icons.Outlined.WorkOutline),
            Triple("account", R.string.nav_account, Icons.Outlined.PersonOutline)
        ).forEach { (route, label, icon) ->
            NavigationBarItem(selected = selected == route, onClick = { onSelect(route) },
                icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
        }
    }
}

package com.elialombardi.piccistodo

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.LocalGasStation
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.elialombardi.piccistodo.ui.viewmodels.AuthViewModel
import com.elialombardi.piccistodo.ui.screens.NotesScreen
import com.elialombardi.piccistodo.ui.screens.LoginScreen
import com.elialombardi.piccistodo.ui.screens.TasksScreen
import com.elialombardi.piccistodo.ui.screens.AccountScreen
import com.elialombardi.piccistodo.ui.screens.FuelScreen

@Composable
fun MainNavigation(startDestination: String = "login") {
    val auth: AuthViewModel = hiltViewModel()
    val sessionChecked by auth.sessionChecked.collectAsState()
    val hasSession by auth.hasStoredSession.collectAsState()
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var tasksRoot by rememberSaveable { mutableStateOf(true) }
    var notesRoot by rememberSaveable { mutableStateOf(true) }
    val showNavigation = (route == "home" && notesRoot) || route == "account" || route == "fuel" || (route == "tasks" && tasksRoot)
    val signedOut = {
        navController.navigate("login") {
            popUpTo(navController.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }
    LaunchedEffect(sessionChecked, hasSession, route) {
        if (sessionChecked && !hasSession && route != null && route != "login") signedOut()
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
                viewModel = auth,
                onLoginSuccess = {
                    navController.navigate("home") {
                        popUpTo(navController.graph.id) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            )
        }
        composable("home") {
            NotesScreen(onAuthenticationRequired = signedOut, onRootChanged = { notesRoot = it })
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
        composable("fuel") { FuelScreen(onAuthenticationRequired = signedOut) }

        composable("create_collection") {
            NotesScreen(onAuthenticationRequired = signedOut, initialFolderForm = true)
        }

        composable(
            route = "detail/{collectionId}",
            arguments = listOf(navArgument("collectionId") { type = NavType.StringType })
        ) { backStackEntry ->
            val collectionIdString = backStackEntry.arguments?.getString("collectionId") ?: "0"
            NotesScreen(onAuthenticationRequired = signedOut, initialFolderId = collectionIdString.toLongOrNull()?.takeIf { it > 0 })
        }

        composable(
            route = "create?collectionId={collectionId}",
            arguments = listOf(navArgument("collectionId") {
                type = NavType.StringType
                defaultValue = "0"
            })
        ) { backStackEntry ->
            val collectionIdString = backStackEntry.arguments?.getString("collectionId") ?: "0"
            NotesScreen(onAuthenticationRequired = signedOut, initialFolderId = collectionIdString.toLongOrNull()?.takeIf { it > 0 }, initialCreate = true)
        }
      }
    }
}

@Composable
internal fun WorkspaceNavigation(selected: String, onSelect: (String) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        listOf(
            Triple("home", R.string.notes_title, Icons.Outlined.FolderOpen),
            Triple("tasks", R.string.tasks_title, Icons.Outlined.WorkOutline),
            Triple("fuel", R.string.fuel_title, Icons.Outlined.LocalGasStation),
            Triple("account", R.string.nav_account, Icons.Outlined.PersonOutline)
        ).forEach { (route, label, icon) ->
            NavigationBarItem(selected = selected == route, onClick = { onSelect(route) },
                icon = { Icon(icon, null) }, label = { Text(stringResource(label)) })
        }
    }
}

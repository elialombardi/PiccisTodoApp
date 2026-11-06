package com.elialombardi.piccistodo

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.elialombardi.piccistodo.ui.screens.CreateCollectionScreen
import com.elialombardi.piccistodo.ui.screens.CreateScreen
import com.elialombardi.piccistodo.ui.screens.DetailScreen
import com.elialombardi.piccistodo.ui.screens.HomeScreen
import com.elialombardi.piccistodo.ui.screens.LoginScreen

@Composable
fun MainNavigation(startDestination: String = "home") {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = startDestination) {
        // Route 0: Login Screen
        composable("login") {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate("home") {
                        // Clear backstack so user can't press back to return to Login
                        popUpTo("login") { inclusive = true }
                    }
                }
            )
        }
        // Route 1: Home Screen
        composable("home") {
            HomeScreen(
                onCollectionClick = { collectionId ->
                    navController.navigate("detail/$collectionId")
                },
                onAddCollectionClick = {
                    // This is where the app tries to go
                    navController.navigate("create_collection")
                }
            )
        }

        // Route 2: Create Collection Screen (NEW!)
        composable("create_collection") {
            CreateCollectionScreen(
                onBackClick = { navController.popBackStack() },
                onSaved = {
                    // When saved, go back to the home screen
                    navController.popBackStack()
                }
            )
        }

        // Route 3: Detail Screen
        composable(
            route = "detail/{collectionId}",
            arguments = listOf(navArgument("collectionId") { type = NavType.StringType }) // 1. Changed to StringType
        ) { backStackEntry ->
            // 2. Read it as a String, then convert to UInt
            val collectionIdString = backStackEntry.arguments?.getString("collectionId") ?: "0"
            val collectionId = collectionIdString.toUIntOrNull() ?: 0u

            DetailScreen(
                collectionId = collectionId, // Now it passes the UInt
                onAddItemClick = {
                    navController.navigate("create?collectionId=$collectionId")
                },
                onBackClick = { navController.popBackStack() }
            )
        }

        // Route 4: Create Todo Screen
        composable(
            route = "create?collectionId={collectionId}",
            arguments = listOf(navArgument("collectionId") {
                type = NavType.StringType // 1. Changed to StringType
                defaultValue = "0"
            })
        ) { backStackEntry ->
            // 2. Convert back to UInt
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
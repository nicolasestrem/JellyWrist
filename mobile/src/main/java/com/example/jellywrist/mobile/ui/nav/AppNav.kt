package com.example.jellywrist.mobile.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.jellywrist.mobile.ui.screens.browse.BrowseScreen
import com.example.jellywrist.mobile.ui.screens.home.HomeScreen
import com.example.jellywrist.mobile.ui.screens.onboarding.MobileOnboardingScreen

/** Navigation with the initial destination computed once, like the Wear navigation.  */
@Composable
fun AppNav(
    authenticated: Boolean,
    recoveredCorruptStore: Boolean = false,
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = if (authenticated) MobileRoutes.HOME else MobileRoutes.ONBOARDING,
    ) {
        composable(MobileRoutes.ONBOARDING) {
            MobileOnboardingScreen(
                recoveredCorruptStore = recoveredCorruptStore,
                onAuthenticated = {
                    navController.navigate(MobileRoutes.HOME) {
                        popUpTo(MobileRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }
        composable(MobileRoutes.HOME) {
            HomeScreen(
                onBrowse = { navController.navigate(MobileRoutes.BROWSE) },
                onSignedOut = {
                    navController.navigate(MobileRoutes.ONBOARDING) {
                        popUpTo(MobileRoutes.HOME) { inclusive = true }
                    }
                },
            )
        }
        composable(MobileRoutes.BROWSE) { BrowseScreen() }
    }
}

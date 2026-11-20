package com.example.jellywrist.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.example.jellywrist.ui.screens.onboarding.OnboardingContent
import com.example.jellywrist.ui.screens.onboarding.OnboardingViewModel
import com.example.jellywrist.ui.screens.settings.SettingsScreen
import com.example.jellywrist.ui.screens.settings.SettingsViewModel
import com.example.jellywrist.ui.screens.onboarding.OnboardingUiState
import com.example.jellywrist.ui.screens.downloads.DownloadsScreen
import com.example.jellywrist.ui.screens.downloads.DownloadsViewModel
import com.example.jellywrist.ui.screens.library.CollectionDetailScreen
import com.example.jellywrist.ui.screens.library.CollectionDetailViewModel
import com.example.jellywrist.ui.screens.library.LibraryScreen
import com.example.jellywrist.ui.screens.player.PlayerScreen
import com.example.jellywrist.ui.screens.player.PlayerViewModel
import com.example.jellywrist.ui.screens.library.LibraryViewModel
import com.example.jellywrist.ui.theme.JellyWristTheme

/**
 * Navigation routes.
 *
 * Centralized here so every screen is reachable without cross-screen dependencies:
 * a collection detail must be able to open the player without knowing the graph.
 */
object JellyRoutes {
    const val ONBOARDING = "onboarding"
    const val LIBRARY = "library"
    const val DOWNLOADS = "downloads"
    const val PLAYER = "player"
    const val SETTINGS = "settings"

    /** Collection detail; [ARG_COLLECTION_ID] is the Jellyfin id. */
    const val COLLECTION_DETAIL = "collection/{collectionId}"
    const val ARG_COLLECTION_ID = "collectionId"

    fun collectionDetail(collectionId: String) = "collection/$collectionId"
}

/**
 * Navigation host.
 *
 * The start destination depends on whether a session exists: [hasSession] is evaluated
 * once after the explicit authentication bootstrap state has completed.
 * After authentication, the home screen is removed from the back stack: a swipe must
 * not bring the user back to the sign-in screen.
 *
 * The "Aurora" theme ([JellyWristTheme]) wraps the whole graph. Each screen manages its
 * own `ScreenScaffold` + `TimeText` (except onboarding, whose scaffold stays here).
 */
@Composable
fun JellyNav(
    appName: String,
    hasSession: Boolean,
    recoveredCorruptStore: Boolean = false,
) {
    val navController = rememberSwipeDismissableNavController()
    val start = remember(hasSession) {
        if (hasSession) JellyRoutes.LIBRARY else JellyRoutes.ONBOARDING
    }

    JellyWristTheme {
        AppScaffold {
            SwipeDismissableNavHost(
                navController = navController,
                startDestination = start,
            ) {
                composable(JellyRoutes.ONBOARDING) {
                    val viewModel: OnboardingViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    LaunchedEffect(state) {
                        if (state is OnboardingUiState.Authenticated) {
                            navController.navigate(JellyRoutes.LIBRARY) {
                                popUpTo(JellyRoutes.ONBOARDING) { inclusive = true }
                            }
                        }
                    }

                    ScreenScaffold(timeText = { TimeText() }) {
                        OnboardingContent(
                            state = state,
                            recoveredCorruptStore = recoveredCorruptStore,
                            onServerInputChange = viewModel::onServerInputChange,
                            onSubmitServer = viewModel::submitServer,
                            onTrustCertificate = viewModel::trustCertificate,
                            onRejectCertificate = viewModel::rejectCertificate,
                            onRetry = viewModel::retry,
                        )
                    }
                }

                composable(JellyRoutes.LIBRARY) {
                    val viewModel: LibraryViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val selectedTab by viewModel.selectedTab.collectAsStateWithLifecycle()
                    val randomSyncState by viewModel.randomSyncState.collectAsStateWithLifecycle()

                    LibraryScreen(
                        state = state,
                        selectedTab = selectedTab,
                        randomSyncState = randomSyncState,
                        onTabChange = viewModel::selectTab,
                        onRefresh = viewModel::refresh,
                        onCollectionClick = { collectionId ->
                            navController.navigate(JellyRoutes.collectionDetail(collectionId))
                        },
                        onSyncRandomSongs = viewModel::syncRandomSongs,
                        onConfirmRandomMetered = viewModel::confirmRandomSyncOnMetered,
                        onDismissRandomPrompt = viewModel::dismissRandomSyncPrompt,
                        onConfirmBatteryWarning = viewModel::confirmBatteryWarning,
                        onDismissBatteryWarning = viewModel::dismissBatteryWarning,
                        onNavigateDownloads = { navController.navigate(JellyRoutes.DOWNLOADS) },
                        onNavigateSettings = { navController.navigate(JellyRoutes.SETTINGS) },
                    )
                }

                composable(JellyRoutes.DOWNLOADS) {
                    val viewModel: DownloadsViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val meteredPrompt by viewModel.meteredPrompt.collectAsStateWithLifecycle()
                    val batteryWarning by viewModel.batteryWarning.collectAsStateWithLifecycle()

                    DownloadsScreen(
                        state = state,
                        meteredPrompt = meteredPrompt,
                        batteryWarning = batteryWarning,
                        onRemoveTracks = viewModel::removeTracks,
                        onSyncNow = viewModel::syncNow,
                        onConfirmMeteredSync = viewModel::confirmMeteredSync,
                        onDismissMeteredPrompt = viewModel::dismissMeteredPrompt,
                        onConfirmBatterySync = viewModel::confirmBatterySync,
                        onDismissBatteryWarning = viewModel::dismissBatteryWarning,
                    )
                }

                composable(JellyRoutes.PLAYER) {
                    val viewModel: PlayerViewModel = hiltViewModel()
                    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()

                    PlayerScreen(
                        snapshot = snapshot,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onNext = viewModel::next,
                        onPrevious = viewModel::previous,
                        onToggleShuffle = viewModel::toggleShuffle,
                        onCycleRepeat = viewModel::cycleRepeat,
                        onRotaryDelta = viewModel::onRotaryDelta,
                        onSeekTo = viewModel::seekTo,
                    )
                }

                composable(JellyRoutes.SETTINGS) {
                    val viewModel: SettingsViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()

                    SettingsScreen(
                        state = state,
                        onQuotaChange = viewModel::setQuotaBytes,
                        onRequiresChargingChange = viewModel::setRequiresCharging,
                        onUnmeteredOnlyChange = viewModel::setUnmeteredOnly,
                        onPeriodicityChange = viewModel::setPeriodicityHours,
                        onSignOut = {
                            viewModel.signOut {
                                // Restart the activity to return to the home screen
                                // after the data purge
                                navController.navigate(JellyRoutes.ONBOARDING) {
                                    popUpTo(0) { inclusive = true }
                                }
                            }
                        },
                    )
                }

                composable(
                    route = JellyRoutes.COLLECTION_DETAIL,
                    arguments = listOf(
                        navArgument(JellyRoutes.ARG_COLLECTION_ID) {
                            type = androidx.navigation.NavType.StringType
                        },
                    ),
                ) {
                    val viewModel: CollectionDetailViewModel = hiltViewModel()
                    val playerViewModel: PlayerViewModel = hiltViewModel()
                    val collection by viewModel.collection.collectAsStateWithLifecycle()
                    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
                    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
                    val refreshError by viewModel.refreshError.collectAsStateWithLifecycle()
                    val consentPrompt by viewModel.consentPrompt.collectAsStateWithLifecycle()

                    CollectionDetailScreen(
                        collectionName = collection?.name.orEmpty(),
                        tracks = tracks,
                        baseUrl = baseUrl,
                        pinned = collection?.pinned ?: false,
                        estimatedBytes = collection?.estimatedBytes ?: 0L,
                        refreshError = refreshError,
                        consentPrompt = consentPrompt,
                        onPinnedChange = viewModel::setPinned,
                        onConsentConfirm = viewModel::confirmConsent,
                        onConsentDismiss = viewModel::dismissConsent,
                        onTrackClick = { index ->
                            playerViewModel.playCollection(viewModel.collectionId, index)
                            navController.navigate(JellyRoutes.PLAYER)
                        },
                    )
                }
            }
        }
    }
}

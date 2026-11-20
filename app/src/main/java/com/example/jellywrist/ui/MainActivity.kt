package com.example.jellywrist.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.TimeText
import com.example.jellywrist.data.auth.AuthBootstrapResult
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.di.BuildInfo
import com.example.jellywrist.download.DownloadResumer
import com.example.jellywrist.ui.nav.JellyNav
import com.example.jellywrist.ui.theme.JellyWristTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var buildInfo: BuildInfo

    @Inject lateinit var authRepository: AuthRepository

    @Inject lateinit var downloadResumer: DownloadResumer

    /**
     * POST_NOTIFICATIONS request (Android 13+). Denial is tolerated: downloads
     * still work, only the progress notification is missing (PLAN.md §8 task 14).
     */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* denial tolerated */ }

    /**
     * Recovery point for a queue Android refused to start in the background.
     *
     * A foreground-service start is only reliably permitted while the app is visible, so a
     * sync that ran as background work may have left downloads registered in Media3's index
     * with no service to move them. Being on screen is exactly the moment that is allowed,
     * so anything still pending gets its service here.
     */
    override fun onStart() {
        super.onStart()
        downloadResumer.resumeIfPending()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Install the Compose root synchronously. Encrypted storage is loaded from
        // within composition so a slow or recovered session can never leave a blank activity.
        setContent {
            val bootstrap by produceState<AuthBootstrapResult?>(initialValue = null) {
                value = authRepository.awaitBootstrap()
            }
            when (val current = bootstrap) {
                null -> AuthLoadingScreen()
                is AuthBootstrapResult.Authenticated -> {
                    LaunchedEffect(Unit) {
                        // Contextual rather than on first cold launch: an already signed-in user
                        // is about to sync, which is when the notification makes sense.
                        if (ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    JellyNav(appName = buildInfo.appName, hasSession = true)
                }

                is AuthBootstrapResult.SignedOut -> JellyNav(
                    appName = buildInfo.appName,
                    hasSession = false,
                    recoveredCorruptStore = current.recoveredCorruptStore,
                )
            }
        }
    }
}

@Composable
private fun AuthLoadingScreen() {
    JellyWristTheme {
        AppScaffold {
            ScreenScaffold(timeText = { TimeText() }) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

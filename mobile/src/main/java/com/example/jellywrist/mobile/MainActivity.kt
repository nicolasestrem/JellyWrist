package com.example.jellywrist.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.jellywrist.data.auth.AuthBootstrapResult
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.ui.nav.AppNav
import com.example.jellywrist.mobile.ui.theme.MobileTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Phone Compose host; the initial auth state is intentionally one-shot.  */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Before super/setContent: the screens draw edge to edge and each one applies its
        // Scaffold's inset padding, so nothing renders under the status or navigation bars.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Install the root immediately so session I/O cannot leave the activity blank.
        setContent {
            MobileTheme {
                val bootstrap by produceState<AuthBootstrapResult?>(initialValue = null) {
                    value = authRepository.awaitBootstrap()
                }
                when (val current = bootstrap) {
                    null -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }

                    is AuthBootstrapResult.Authenticated -> AppNav(authenticated = true)
                    is AuthBootstrapResult.SignedOut -> AppNav(
                        authenticated = false,
                        recoveredCorruptStore = current.recoveredCorruptStore,
                    )
                }
            }
        }
    }
}

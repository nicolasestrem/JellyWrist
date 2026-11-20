package com.example.jellywrist.mobile.ui.screens.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.jellywrist.mobile.R

/** Phone form for the Quick Connect flow, without password entry.  */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileOnboardingScreen(
    onAuthenticated: () -> Unit,
    recoveredCorruptStore: Boolean = false,
    viewModel: MobileOnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(state) {
        if (state is OnboardingUiState.Authenticated) onAuthenticated()
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
    ) { innerPadding ->
    Column(
        // The app name lives in the app bar now: as a headline inside this column it was
        // drawn under the status-bar clock.
        modifier = Modifier.fillMaxSize().padding(innerPadding).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (recoveredCorruptStore) {
            Text(
                text = stringResource(R.string.auth_store_recovered),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        when (val current = state) {
            is OnboardingUiState.ServerEntry -> {
                OutlinedTextField(
                    value = current.input,
                    onValueChange = viewModel::onServerInputChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.server_address)) },
                    placeholder = { Text(stringResource(R.string.server_address_hint)) },
                    isError = current.error != null,
                    supportingText = current.error?.let { error -> { Text(stringResource(error)) } },
                    singleLine = true,
                )
                Button(onClick = viewModel::submitServer, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.connect))
                }
            }

            OnboardingUiState.Validating -> CircularProgressIndicator()
            is OnboardingUiState.QuickConnect -> {
                Text(stringResource(R.string.quick_connect_code), style = MaterialTheme.typography.titleMedium)
                Text(current.code, style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.quick_connect_hint), style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(current.status), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.expired) {
                    Button(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
                }
            }

            OnboardingUiState.QuickConnectUnavailable -> {
                Text(stringResource(R.string.quick_connect_unavailable))
                Button(onClick = viewModel::retry) { Text(stringResource(R.string.retry)) }
            }

            is OnboardingUiState.CertificatePrompt -> CertificateDialog(
                prompt = current,
                onAccept = viewModel::trustCertificate,
                onReject = viewModel::rejectCertificate,
            )

            is OnboardingUiState.Authenticated -> CircularProgressIndicator()
        }
    }
    }
}

@Composable
private fun CertificateDialog(
    prompt: OnboardingUiState.CertificatePrompt,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(stringResource(R.string.certificate_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.certificate_message))
                Text(prompt.subject, style = MaterialTheme.typography.bodyMedium)
                Text(prompt.fingerprintSha256, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) { Text(stringResource(R.string.approve)) }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text(stringResource(R.string.reject)) }
        },
    )
}

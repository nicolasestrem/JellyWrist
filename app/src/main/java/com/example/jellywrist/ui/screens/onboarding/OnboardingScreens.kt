package com.example.jellywrist.ui.screens.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.example.jellywrist.R
import com.example.jellywrist.ui.components.AuroraButton
import com.example.jellywrist.ui.components.AuroraChip
import com.example.jellywrist.ui.components.AuroraChipVariant
import com.example.jellywrist.ui.components.GradientText
import com.example.jellywrist.ui.theme.JellyWristTheme
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Onboarding screens (PLAN.md §6) — **Quick Connect only** (DEC-010). Purely driven
 * by [OnboardingUiState]: no direct ViewModel access, all events bubble up
 * via lambdas — screens remain previewable and individually testable.
 *
 * "Aurora" styling: the Quick Connect code is painted in the signature purple ->
 * magenta gradient by [GradientText] (large, identifiable at first glance), the brand
 * is placed at the top of the input, main actions are [AuroraChip] with gradient fill.
 *
 * Wear Compose Material3 does not expose a text field: we use [BasicTextField] styled
 * with a rounded `surfaceContainer` background + `outline` border to stay readable on a round screen.
 *
 * The `ScreenScaffold` + `TimeText` is provided by the host (`JellyNav`): this composable
 * only produces the centered content — nesting a second `ScreenScaffold` would
 * display the time twice.
 */
@Composable
fun OnboardingContent(
    state: OnboardingUiState,
    recoveredCorruptStore: Boolean = false,
    onServerInputChange: (String) -> Unit,
    onSubmitServer: () -> Unit,
    onRetry: () -> Unit,
    onTrustCertificate: () -> Unit,
    onRejectCertificate: () -> Unit,
) {
    when (state) {
        is OnboardingUiState.ServerEntry -> ServerEntryScreen(
            state = state,
            recoveredCorruptStore = recoveredCorruptStore,
            onInputChange = onServerInputChange,
            onSubmit = onSubmitServer,
        )

        OnboardingUiState.Validating ->
            LoadingScreen(message = stringResource(R.string.onboarding_validating))

        is OnboardingUiState.QuickConnect -> QuickConnectScreen(
            state = state,
            onRetry = onRetry,
        )

        OnboardingUiState.QuickConnectUnavailable ->
            QuickConnectUnavailableScreen(onRetry = onRetry)

        is OnboardingUiState.CertificatePrompt -> CertificatePromptScreen(
            state = state,
            onTrust = onTrustCertificate,
            onReject = onRejectCertificate,
        )

        is OnboardingUiState.Authenticated -> AuthenticatedScreen(userName = state.userName)
    }
}

/**
 * Server address entry. Brand at the top (icon `Tv` + gradient-painted name), rounded
 * input field with placeholder, then main action as [AuroraChip] "Filled" — disabled
 * while the input is empty to avoid a valueless submission.
 */
@Composable
private fun ServerEntryScreen(
    state: OnboardingUiState.ServerEntry,
    recoveredCorruptStore: Boolean = false,
    onInputChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    ScrollableCenteredColumn {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        GradientText(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
        )
        if (recoveredCorruptStore) {
            Text(
                text = stringResource(R.string.onboarding_auth_store_recovered),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        InputField(
            value = state.input,
            onValueChange = onInputChange,
            onSubmit = onSubmit,
        )
        state.error?.let { error ->
            Text(
                text = stringResource(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        AuroraChip(
            label = stringResource(R.string.onboarding_connect),
            onClick = onSubmit,
            leadingIcon = Icons.Rounded.Send,
            variant = AuroraChipVariant.Filled,
            enabled = state.input.isNotBlank(),
        )
    }
}

/**
 * Quick Connect code awaiting approval. The 6-digit code is the visual hero:
 * [GradientText] in `displayMedium` paints it in the signature gradient. The status
 * (Jellyfin instruction / expiration) serves as the explanatory line. While the code
 * is valid, a small [CircularProgressIndicator] signals polling; once expired, the
 * "New code" action.
 */
@Composable
private fun QuickConnectScreen(
    state: OnboardingUiState.QuickConnect,
    onRetry: () -> Unit,
) {
    CenteredColumn {
        Icon(
            imageVector = Icons.Rounded.Link,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.size(28.dp),
        )
        GradientText(
            text = state.code,
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(state.status),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (state.expired) {
            AuroraChip(
                label = stringResource(R.string.onboarding_new_code),
                onClick = onRetry,
                leadingIcon = Icons.Rounded.Refresh,
                variant = AuroraChipVariant.Filled,
            )
        } else {
            CircularProgressIndicator(modifier = Modifier.size(24.dp))
        }
    }
}

/**
 * Quick Connect unavailable: with no password fallback (DEC-010), the user must
 * enable Quick Connect on the server side (Dashboard -> Settings -> Quick Connect). Error
 * icon, title, hint, then main [AuroraButton] in gradient.
 */
@Composable
private fun QuickConnectUnavailableScreen(onRetry: () -> Unit) {
    CenteredColumn {
        Icon(
            imageVector = Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_qc_unavailable_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = stringResource(R.string.onboarding_qc_unavailable_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AuroraButton(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_retry),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/**
 * Explicit approval of a self-signed certificate (PLAN.md §3.1, DEC-006).
 *
 * The SHA-256 fingerprint is shown in full: it is what the user compares with
 * the one given by their server — pinning without human verification has no value.
 * Two actions: "Trust" (Filled, primary) and "Refuse" (Outlined,
 * secondary) — clear visual hierarchy.
 */
@Composable
private fun CertificatePromptScreen(
    state: OnboardingUiState.CertificatePrompt,
    onTrust: () -> Unit,
    onReject: () -> Unit,
) {
    ScrollableCenteredColumn {
        Icon(
            imageVector = Icons.Rounded.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_certificate_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            text = state.subject,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            text = state.fingerprintSha256.chunked(2).joinToString(":"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AuroraChip(
            label = stringResource(R.string.onboarding_certificate_trust),
            onClick = onTrust,
            variant = AuroraChipVariant.Filled,
        )
        AuroraChip(
            label = stringResource(R.string.onboarding_certificate_reject),
            onClick = onReject,
            variant = AuroraChipVariant.Outlined,
        )
    }
}

/** Transient state: authenticated, navigation switches to the library. */
@Composable
private fun AuthenticatedScreen(userName: String) {
    CenteredColumn {
        Icon(
            imageVector = Icons.Rounded.CheckCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(40.dp),
        )
        Text(
            text = stringResource(R.string.onboarding_hello, userName),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/** Transient state: network validation in progress. */
@Composable
private fun LoadingScreen(message: String) {
    CenteredColumn {
        CircularProgressIndicator()
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Centered container for onboarding screens (short screens, no long list). Full-size
 * box centered, full-width column with 24 dp padding to stay within the circle on a
 * round screen, and `spacedBy(10.dp)` for uniform spacing.
 */
@Composable
private fun CenteredColumn(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            content()
        }
    }
}

/**
 * Scrollable variant of [CenteredColumn] for content that overflows a small Wear
 * screen. The column keeps its content centered when it fits, then makes it fully
 * scrollable when it overflows.
 */
@Composable
private fun ScrollableCenteredColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
    ) {
        content()
    }
}

/**
 * Server address input field.
 *
 * `KeyboardType.Uri` + auto-correct disabled + no capitalization: watch keyboards
 * aggressively "correct" (`Https://...`, suggestions) and a URL modified without the
 * user's knowledge fails silently — observed on emulator (GAP-010).
 * The IME action `Go` submits directly (and hides the keyboard). The cursor uses the
 * signature gradient for Aurora consistency.
 */
@Composable
private fun InputField(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = auroraGradient(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                keyboardController?.hide()
                onSubmit()
            },
        ),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.onboarding_server_address),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Preview(showBackground = true)
@Composable
private fun ServerEntryPreview() {
    JellyWristTheme {
        ServerEntryScreen(
            state = OnboardingUiState.ServerEntry(input = "192.0.2.10"),
            onInputChange = {},
            onSubmit = {},
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun QuickConnectPreview() {
    JellyWristTheme {
        QuickConnectScreen(
            state = OnboardingUiState.QuickConnect("123456", R.string.onboarding_status_approve_code),
            onRetry = {},
        )
    }
}

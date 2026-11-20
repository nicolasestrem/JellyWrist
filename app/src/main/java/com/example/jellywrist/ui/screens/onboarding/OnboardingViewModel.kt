package com.example.jellywrist.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.R
import com.example.jellywrist.data.auth.AuthResult
import com.example.jellywrist.companion.CompanionSetupCoordinator
import com.example.jellywrist.companion.RemoteSetupState
import com.example.jellywrist.data.auth.QuickConnectState
import com.example.jellywrist.data.net.CertificateFetcher
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.repo.ServerValidation
import com.example.jellywrist.sync.PeriodicSyncArmer
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Home screen state — **Quick Connect only** (owner decision 2026-08-08, DEC-010:
 * the credentials fallback is removed; no password ever passes through the watch
 * anymore).
 *
 * Sum type: each step carries only its own data, so inconsistent states are
 * unrepresentable.
 */
sealed interface OnboardingUiState {

    /** Server address entry. [error] is a string resource ID. */
    data class ServerEntry(val input: String = "", @StringRes val error: Int? = null) : OnboardingUiState

    /** Network validation of the URL in progress (probing https/http candidates). */
    data object Validating : OnboardingUiState

    /** Quick Connect code displayed, awaiting approval. */
    data class QuickConnect(val code: String, @StringRes val status: Int, val expired: Boolean = false) : OnboardingUiState

    /**
     * Quick Connect is disabled server-side or unreachable. Without a password fallback
     * (DEC-010), the way out is: retry, or enable Quick Connect on the server.
     */
    data object QuickConnectUnavailable : OnboardingUiState

    /**
     * The server presents a certificate the system doesn't recognize (self-signed).
     * The user sees the fingerprint and decides — opt-in pinning, PLAN.md §3.1 / DEC-006.
     */
    data class CertificatePrompt(
        val serverInput: String,
        val fingerprintSha256: String,
        val subject: String,
    ) : OnboardingUiState

    /** Authenticated: navigation switches to the library. */
    data class Authenticated(val userName: String) : OnboardingUiState
}

/**
 * Drives the onboarding flow: server validation (candidate probing,
 * PLAN.md §3.1) then **Quick Connect** authentication (PLAN.md §3.2, DEC-010).
 *
 * The token is never logged (REQ-005).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    private val certificateFetcher: CertificateFetcher,
    private val pinRegistry: PinRegistry,
    private val companionSetupCoordinator: CompanionSetupCoordinator,
    private val periodicSyncArmer: PeriodicSyncArmer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ServerEntry())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    /** Validated URL, kept for the subsequent authentication calls. */
    private var baseUrl: String? = null

    /** Quick Connect polling in progress: cancelled as soon as the user restarts the flow. */
    private var quickConnectJob: Job? = null

    init {
        viewModelScope.launch {
            companionSetupCoordinator.state.collect { remoteState ->
                if (_uiState.value is OnboardingUiState.Authenticated) return@collect
                _uiState.value = when (remoteState) {
                    is RemoteSetupState.AwaitingApproval -> OnboardingUiState.QuickConnect(
                        code = remoteState.code,
                        status = R.string.onboarding_status_phone_setup,
                    )

                    // A final outcome lingers in the coordinator (process scope): it may
                    // date from a provisioning that preceded a sign-out. Only honor it
                    // if a session actually exists — otherwise this screen would navigate
                    // to the library without a token.
                    is RemoteSetupState.Done ->
                        if (authRepository.currentSession() != null) {
                            OnboardingUiState.Authenticated(remoteState.userName)
                        } else {
                            _uiState.value
                        }

                    RemoteSetupState.Idle,
                    RemoteSetupState.Validating -> _uiState.value

                    // A remote provisioning failure is surfaced: without this, the screen
                    // stays silent and the user doesn't know the phone attempt failed.
                    is RemoteSetupState.Failed -> when (remoteState.error) {
                        WireErrors.INVALID_URL -> OnboardingUiState.ServerEntry(
                            input = (_uiState.value as? OnboardingUiState.ServerEntry)?.input.orEmpty(),
                            error = R.string.onboarding_error_invalid_address,
                        )
                        WireErrors.UNREACHABLE -> OnboardingUiState.ServerEntry(
                            input = (_uiState.value as? OnboardingUiState.ServerEntry)?.input.orEmpty(),
                            error = R.string.onboarding_error_server_unreachable,
                        )
                        WireErrors.QUICKCONNECT_UNAVAILABLE -> OnboardingUiState.QuickConnectUnavailable
                        WireErrors.EXPIRED -> OnboardingUiState.QuickConnect(
                            code = (_uiState.value as? OnboardingUiState.QuickConnect)?.code.orEmpty(),
                            status = R.string.onboarding_status_code_expired,
                            expired = true,
                        )
                        else -> OnboardingUiState.QuickConnect(
                            code = (_uiState.value as? OnboardingUiState.QuickConnect)?.code.orEmpty(),
                            status = R.string.onboarding_status_phone_failed,
                            expired = true,
                        )
                    }
                }
            }
        }
    }

    fun onServerInputChange(value: String) {
        val current = _uiState.value
        if (current is OnboardingUiState.ServerEntry) {
            _uiState.value = current.copy(input = value, error = null)
        }
    }

    /** Probes the input's candidates via `GET /System/Info/Public`, then Quick Connect. */
    fun submitServer() {
        val input = (_uiState.value as? OnboardingUiState.ServerEntry)?.input.orEmpty()
        if (input.isBlank()) {
            _uiState.value = OnboardingUiState.ServerEntry(input, R.string.onboarding_error_enter_address)
            return
        }

        _uiState.value = OnboardingUiState.Validating
        viewModelScope.launch {
            when (val validation = serverRepository.validateServer(input)) {
                is ServerValidation.Valid -> {
                    baseUrl = validation.server.baseUrl
                    startQuickConnect()
                }

                is ServerValidation.InvalidUrl ->
                    _uiState.value = OnboardingUiState.ServerEntry(input, R.string.onboarding_error_invalid_address)

                is ServerValidation.Unreachable -> {
                    // A TLS TRUST failure (CertificateException in the chain) on an
                    // HTTPS candidate signals a self-signed certificate: offer explicit
                    // pinning (PLAN.md §3.1) rather than a dead-end "unreachable".
                    // Other failures remain connection errors.
                    val trustFailure = generateSequence<Throwable>(validation.cause) { it.cause }
                        .any { it is java.security.cert.CertificateException }
                    if (trustFailure && validation.url.startsWith("https://")) {
                        offerCertificate(input, validation.url)
                    } else {
                        _uiState.value =
                            OnboardingUiState.ServerEntry(input, R.string.onboarding_error_server_unreachable)
                    }
                }
            }
        }
    }

    /** Fetches and presents the server's certificate for explicit approval. */
    private suspend fun offerCertificate(input: String, url: String) {
        certificateFetcher.fetch(url).fold(
            onSuccess = { cert ->
                _uiState.value = OnboardingUiState.CertificatePrompt(
                    serverInput = input,
                    fingerprintSha256 = cert.sha256Hex,
                    subject = cert.subject,
                )
            },
            onFailure = {
                _uiState.value = OnboardingUiState.ServerEntry(input, R.string.onboarding_error_server_unreachable)
            },
        )
    }

    /**
     * The user approves the presented certificate: the fingerprint is persisted
     * ([SettingsRepository]), applied immediately ([PinRegistry] — re-read on every
     * TLS handshake), then the server validation is replayed.
     */
    fun trustCertificate() {
        val prompt = _uiState.value as? OnboardingUiState.CertificatePrompt ?: return
        viewModelScope.launch {
            settingsRepository.setPinnedCertificate(prompt.fingerprintSha256)
            pinRegistry.update(prompt.fingerprintSha256)
            _uiState.value = OnboardingUiState.ServerEntry(prompt.serverInput)
            submitServer()
        }
    }

    /** The user rejects the certificate: back to entry, no trace persisted. */
    fun rejectCertificate() {
        val prompt = _uiState.value as? OnboardingUiState.CertificatePrompt ?: return
        _uiState.value = OnboardingUiState.ServerEntry(prompt.serverInput)
    }

    /**
     * Opens a Quick Connect request and polls its state until approval.
     *
     * Server without Quick Connect → [OnboardingUiState.QuickConnectUnavailable]: no
     * password fallback (DEC-010), the user must enable it server-side.
     *
     * Nothing may escape this `launch`: `viewModelScope` has no exception handler
     * and a leak would kill the process instead of offering a retry.
     */
    fun startQuickConnect() {
        val url = baseUrl ?: return
        quickConnectJob?.cancel()
        quickConnectJob = viewModelScope.launch {
            val handle = authRepository.initiateQuickConnect(url)
            if (handle == null) {
                _uiState.value = OnboardingUiState.QuickConnectUnavailable
                return@launch
            }

            _uiState.value = OnboardingUiState.QuickConnect(
                code = handle.code,
                status = R.string.onboarding_status_approve_code,
            )

            try {
                authRepository.pollQuickConnect(url, handle.secret).collect { state ->
                    when (state) {
                        QuickConnectState.Pending -> Unit // the code stays displayed

                        QuickConnectState.Approved ->
                            applyResult(
                                authRepository.authenticateWithQuickConnect(url, handle.secret),
                            )

                        QuickConnectState.Expired ->
                            _uiState.value = OnboardingUiState.QuickConnect(
                                code = handle.code,
                                status = R.string.onboarding_status_code_expired,
                                expired = true,
                            )

                        // The server went silent: the code may still be good, but the
                        // watch can no longer know. Retry, not "expired".
                        QuickConnectState.Unavailable ->
                            _uiState.value = OnboardingUiState.QuickConnect(
                                code = handle.code,
                                status = R.string.onboarding_status_connection_lost,
                                expired = true,
                            )
                    }
                }
            } catch (e: CancellationException) {
                throw e // restarting the flow cancels this polling: don't display it as a failure
            } catch (_: Exception) {
                _uiState.value = OnboardingUiState.QuickConnect(
                    code = handle.code,
                    status = R.string.onboarding_status_unexpected_failure,
                    expired = true,
                )
            }
        }
    }

    /** Restarts the flow: new Quick Connect request if the server is already validated. */
    fun retry() {
        quickConnectJob?.cancel()
        quickConnectJob = null
        if (baseUrl == null) {
            _uiState.value = OnboardingUiState.ServerEntry()
        } else {
            startQuickConnect()
        }
    }

    private fun applyResult(result: AuthResult) {
        if (result is AuthResult.Success) {
            // GAP-010, watch-local path: after a sign-out (factoryReset cancelled all sync
            // work), re-onboarding on the watch itself must re-arm the periodic sync —
            // JellyWristApp only arms at process start. Idempotent (UPDATE policy).
            viewModelScope.launch { periodicSyncArmer.arm() }
        }
        _uiState.value = when (result) {
            is AuthResult.Success -> OnboardingUiState.Authenticated(result.session.userName)

            is AuthResult.QuickConnectExpired -> OnboardingUiState.QuickConnect(
                code = "",
                status = R.string.onboarding_status_request_expired,
                expired = true,
            )

            is AuthResult.Failed -> OnboardingUiState.QuickConnectUnavailable
        }
    }
}

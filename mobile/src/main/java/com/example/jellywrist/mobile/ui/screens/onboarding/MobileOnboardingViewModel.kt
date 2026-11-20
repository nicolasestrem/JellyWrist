package com.example.jellywrist.mobile.ui.screens.onboarding

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.auth.AuthResult
import com.example.jellywrist.data.auth.QuickConnectState
import com.example.jellywrist.data.net.CertificateFetcher
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.repo.ServerValidation
import com.example.jellywrist.mobile.R
import com.example.jellywrist.mobile.data.MobilePrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Exclusive states of the phone Quick Connect flow. */

sealed interface OnboardingUiState {
    /** [error] is a string resource: the ViewModel never emits raw UI copy. */
    data class ServerEntry(val input: String = "", @StringRes val error: Int? = null) : OnboardingUiState
    data object Validating : OnboardingUiState
    data class QuickConnect(val code: String, @StringRes val status: Int, val expired: Boolean = false) : OnboardingUiState
    data object QuickConnectUnavailable : OnboardingUiState
    data class CertificatePrompt(
        val serverInput: String,
        val fingerprintSha256: String,
        val subject: String,
    ) : OnboardingUiState
    data class Authenticated(val userName: String) : OnboardingUiState
}

/** Resumes the watch flow without depending on its preferences or its Wear UI. */

@HiltViewModel
class MobileOnboardingViewModel @Inject constructor(
    private val serverRepository: ServerRepository,
    private val authRepository: AuthRepository,
    private val mobilePrefs: MobilePrefs,
    private val certificateFetcher: CertificateFetcher,
    private val pinRegistry: PinRegistry,
) : ViewModel() {
    private val _uiState = MutableStateFlow<OnboardingUiState>(OnboardingUiState.ServerEntry())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private var baseUrl: String? = null
    private var quickConnectJob: Job? = null

    fun onServerInputChange(value: String) {
        val current = _uiState.value
        if (current is OnboardingUiState.ServerEntry) {
            _uiState.value = current.copy(input = value, error = null)
        }
    }

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
                    _uiState.value = OnboardingUiState.ServerEntry(input, R.string.setup_error_invalid_url)

                is ServerValidation.Unreachable -> {
                    val trustFailure = generateSequence<Throwable>(validation.cause) { it.cause }
                        .any { it is java.security.cert.CertificateException }
                    if (trustFailure && validation.url.startsWith("https://")) {
                        offerCertificate(input, validation.url)
                    } else {
                        _uiState.value = OnboardingUiState.ServerEntry(input, R.string.setup_error_unreachable)
                    }
                }
            }
        }
    }

    private suspend fun offerCertificate(input: String, url: String) {
        certificateFetcher.fetch(url).fold(
            onSuccess = { certificate ->
                _uiState.value = OnboardingUiState.CertificatePrompt(
                    serverInput = input,
                    fingerprintSha256 = certificate.sha256Hex,
                    subject = certificate.subject,
                )
            },
            onFailure = {
                _uiState.value = OnboardingUiState.ServerEntry(input, R.string.setup_error_unreachable)
            },
        )
    }

    fun trustCertificate() {
        val prompt = _uiState.value as? OnboardingUiState.CertificatePrompt ?: return
        viewModelScope.launch {
            mobilePrefs.setPinnedCertificate(prompt.fingerprintSha256)
            pinRegistry.update(prompt.fingerprintSha256)
            _uiState.value = OnboardingUiState.ServerEntry(prompt.serverInput)
            submitServer()
        }
    }

    fun rejectCertificate() {
        val prompt = _uiState.value as? OnboardingUiState.CertificatePrompt ?: return
        _uiState.value = OnboardingUiState.ServerEntry(prompt.serverInput)
    }

    /**
     * Nothing must escape this `launch`: `viewModelScope` has no exception handler,
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
            _uiState.value = OnboardingUiState.QuickConnect(handle.code, R.string.onboarding_status_approve_code)
            try {
                authRepository.pollQuickConnect(url, handle.secret).collect { state ->
                    when (state) {
                        QuickConnectState.Pending -> Unit
                        QuickConnectState.Approved -> applyResult(authRepository.authenticateWithQuickConnect(url, handle.secret))
                        QuickConnectState.Expired -> _uiState.value = OnboardingUiState.QuickConnect(
                            code = handle.code,
                            status = R.string.onboarding_status_code_expired,
                            expired = true,
                        )

                        // The server went silent: the code may still be valid, but the
                        // phone can no longer tell. Retry, not "expired".
                        QuickConnectState.Unavailable -> _uiState.value = OnboardingUiState.QuickConnect(
                            code = handle.code,
                            status = R.string.onboarding_status_connection_lost,
                            expired = true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e // rethrowing the flow cancels this poll: do not display it as a failure
            } catch (_: Exception) {
                _uiState.value = OnboardingUiState.QuickConnect(
                    code = handle.code,
                    status = R.string.onboarding_status_unexpected_failure,
                    expired = true,
                )
            }
        }
    }

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
        _uiState.value = when (result) {
            is AuthResult.Success -> OnboardingUiState.Authenticated(result.session.userName)
            is AuthResult.QuickConnectExpired -> OnboardingUiState.QuickConnect("", R.string.onboarding_status_request_expired, expired = true)
            is AuthResult.Failed -> OnboardingUiState.QuickConnectUnavailable
        }
    }
}

package com.example.jellywrist.mobile.ui.screens.onboarding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.jellywrist.data.net.CertificateFetcher
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.mobile.R
import com.example.jellywrist.mobile.InMemoryTokenStore
import com.example.jellywrist.mobile.TestBackend
import com.example.jellywrist.mobile.data.MobilePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Phone-side Quick Connect flow.
 *
 * Covers the polling failure: `quickConnectState` rethrows any non-4xx `InvalidStatusException`
 * and does not catch any `IOException`. The exception escaped the `viewModelScope` `launch` —
 * with no exception handler, it would kill the process instead of letting the screen offer a
 * retry (PR #10, Codex P1).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MobileOnboardingViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * `viewModelScope` runs on `Dispatchers.Main`: the dispatcher is shared with
     * `runTest` (via `runTest(mainDispatcher)`) so that `advanceUntilIdle` properly drives
     * the ViewModel coroutines. Two distinct dispatchers would leave the state on
     * `Validating`.
     */
    private val mainDispatcher = StandardTestDispatcher()

    private lateinit var server: MockWebServer
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `a server that dies during the poll leaves a retryable state`() = runTest(mainDispatcher) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains(PATH_PUBLIC_INFO) -> jsonResponse(publicInfoJson())
                request.path.orEmpty().contains(PATH_QC_INITIATE) ->
                    jsonResponse(quickConnectJson(authenticated = false))
                else -> MockResponse().setResponseCode(503) // polling no longer responds
            }
        }
        val viewModel = viewModel()

        viewModel.onServerInputChange(baseUrl)
        viewModel.submitServer()

        // Wait for the state rather than `advanceUntilIdle`: MockWebServer calls are
        // real I/O, outside the virtual clock, which would yield back on `Validating`.
        // An exception escaping the `launch` would fail this test by `runTest` timeout —
        // exactly the regression being targeted.
        val state = viewModel.uiState.first {
            it is OnboardingUiState.QuickConnect && it.expired
        } as OnboardingUiState.QuickConnect

        // `expired` is what makes the "Retry" button appear (MobileOnboardingScreen).
        assertTrue("failure must be retryable", state.expired)
        // The code stays displayed: the Quick Connect request is not dead, the network is.
        assertEquals("123456", state.code)
        // The message distinguishes the two barriers: this one comes from the tolerant poll
        // (`QuickConnectState.Unavailable`), not the last-resort `catch` — which would also
        // catch the failure, but present it as an unexpected defect.
        assertEquals(R.string.onboarding_status_connection_lost, state.status)
    }

    @Test
    fun `an approved code still authenticates`() = runTest(mainDispatcher) {
        // Guard: fault tolerance must not have broken the nominal path.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains(PATH_PUBLIC_INFO) -> jsonResponse(publicInfoJson())
                    path.contains(PATH_QC_INITIATE) ->
                        jsonResponse(quickConnectJson(authenticated = false))
                    path.contains(PATH_QC_AUTHENTICATE) -> jsonResponse(authResultJson())
                    else -> jsonResponse(quickConnectJson(authenticated = true))
                }
            }
        }
        val viewModel = viewModel()

        viewModel.onServerInputChange(baseUrl)
        viewModel.submitServer()

        assertEquals(
            OnboardingUiState.Authenticated("Avery"),
            viewModel.uiState.first { it is OnboardingUiState.Authenticated },
        )
    }

    private fun viewModel(): MobileOnboardingViewModel {
        val backend = TestBackend()
        return MobileOnboardingViewModel(
            serverRepository = ServerRepository(backend),
            authRepository = AuthRepository(backend, InMemoryTokenStore()),
            mobilePrefs = MobilePrefs(
                PreferenceDataStoreFactory.create {
                    File(temporaryFolder.newFolder(), "prefs.preferences_pb")
                },
            ),
            certificateFetcher = CertificateFetcher(),
            pinRegistry = PinRegistry(),
        )
    }

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

    private fun publicInfoJson() = """
        {"ServerName":"Home Jellyfin","Version":"10.9.11","Id":"server-1","StartupWizardCompleted":true}
    """.trimIndent()

    private fun quickConnectJson(authenticated: Boolean) = """
        {
          "Authenticated": $authenticated,
          "Secret": "secret-abc",
          "Code": "123456",
          "DeviceId": "device-1",
          "DeviceName": "Phone",
          "AppName": "JellyWrist",
          "AppVersion": "0.1.0",
          "DateAdded": "2026-08-07T10:00:00.0000000Z"
        }
    """.trimIndent()

    private fun authResultJson() = """
        {
          "User": {
            "Id": "$USER_ID",
            "Name": "Avery",
            "HasPassword": true,
            "HasConfiguredPassword": true,
            "HasConfiguredEasyPassword": false
          },
          "AccessToken": "token-xyz",
          "ServerId": "server-1"
        }
    """.trimIndent()

    private companion object {
        const val USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

        /** SDK URL fragments, for a dispatcher independent of call ordering. */
        const val PATH_PUBLIC_INFO = "System/Info/Public"
        const val PATH_QC_INITIATE = "QuickConnect/Initiate"
        const val PATH_QC_AUTHENTICATE = "Users/AuthenticateWithQuickConnect"
    }
}

package com.example.jellywrist.ui.screens.onboarding

import com.example.jellywrist.companion.CompanionSetupCoordinator
import com.example.jellywrist.companion.RemoteSetupState
import com.example.jellywrist.data.net.CertificateFetcher
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.reset.ServerDataReset
import com.example.jellywrist.support.FakeSettingsRepository
import com.example.jellywrist.support.FakeTokenStore
import com.example.jellywrist.support.OkHttpJellyfinAuthBackend
import com.example.jellywrist.wire.SetupRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Persistence of remote provisioning state as seen from the onboarding screen.
 *
 * [CompanionSetupCoordinator] is a process-scoped singleton: its outcome outlives
 * the screen that observed it. A new onboarding screen — the one sign-out recreates —
 * receives it immediately and must not mistake itself for authenticated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")
        tokenStore = FakeTokenStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
        Dispatchers.resetMain()
    }

    @Test
    fun `a remote setup completed before sign out does not authenticate a new screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()

        // 1. The phone provisions the watch: the coordinator stays on `Done`.
        coordinator.handleSetup(SetupRequest(baseUrl)) { _, _ -> }
        advanceUntilIdle()
        assertEquals(RemoteSetupState.Done("Avery"), coordinator.state.value)

        // 2. Sign-out: the token disappears. The coordinator is deliberately NOT reset
        //    here — the screen's safety net is what is under test.
        tokenStore.clear()

        // 3. Navigation recreates the onboarding screen, which receives the residual `Done`.
        val viewModel = viewModel(coordinator)
        advanceUntilIdle()

        assertTrue(
            "A residual outcome must not authenticate: the library would open without a token",
            viewModel.uiState.value is OnboardingUiState.ServerEntry,
        )
    }

    @Test
    fun `a remote setup completed with a live session authenticates the screen`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()

        val viewModel = viewModel(coordinator)
        coordinator.handleSetup(SetupRequest(baseUrl)) { _, _ -> }
        advanceUntilIdle()

        assertEquals(OnboardingUiState.Authenticated("Avery"), viewModel.uiState.value)
    }

    @Test
    fun `a watch-local authentication re-arms the periodic sync`() = runTest {
        // GAP-010, second path: after a sign-out cancelled all sync work, re-onboarding on
        // the watch itself must re-arm — the app only arms at process start.
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val viewModel = viewModel(coordinator(backgroundScope))
        enqueueSuccessfulQuickConnect()

        viewModel.onServerInputChange(baseUrl)
        viewModel.submitServer()
        // first{} rather than advanceUntilIdle: validation runs real MockWebServer I/O
        // the virtual clock cannot drive.
        val state = viewModel.uiState.first { it is OnboardingUiState.Authenticated }
        advanceUntilIdle()

        assertEquals(OnboardingUiState.Authenticated("Avery"), state)
        assertEquals(1, viewModelArms)
    }

    /** Arms recorded by the ViewModel's own success path (GAP-010, watch-local). */
    private var viewModelArms = 0

    private fun viewModel(coordinator: CompanionSetupCoordinator) = OnboardingViewModel(
        serverRepository = ServerRepository(OkHttpJellyfinAuthBackend()),
        authRepository = AuthRepository(OkHttpJellyfinAuthBackend(), tokenStore),
        settingsRepository = FakeSettingsRepository(),
        certificateFetcher = CertificateFetcher(),
        pinRegistry = PinRegistry(),
        companionSetupCoordinator = coordinator,
        periodicSyncArmer = com.example.jellywrist.sync.PeriodicSyncArmer { viewModelArms++ },
    )

    private fun coordinator(scope: CoroutineScope) = CompanionSetupCoordinator(
        serverRepository = ServerRepository(OkHttpJellyfinAuthBackend()),
        authRepository = AuthRepository(OkHttpJellyfinAuthBackend(), tokenStore),
        settingsRepository = FakeSettingsRepository(),
        pinRegistry = PinRegistry(),
        serverDataReset = ServerDataReset { },
        periodicSyncArmer = com.example.jellywrist.sync.PeriodicSyncArmer { },
        appScope = scope,
    )

    private fun enqueueSuccessfulQuickConnect() {
        server.enqueue(jsonResponse(publicInfoJson()))
        server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))
        server.enqueue(jsonResponse(quickConnectJson(authenticated = true)))
        server.enqueue(jsonResponse(authResultJson()))
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
          "DeviceName": "Wear OS Watch",
          "AppName": "JellyWrist",
          "AppVersion": "0.1.0",
          "DateAdded": "2026-08-07T10:00:00.0000000Z"
        }
    """.trimIndent()

    private fun authResultJson() = """
        {
          "User": {
            "Id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
            "Name": "Avery",
            "HasPassword": true,
            "HasConfiguredPassword": true,
            "HasConfiguredEasyPassword": false
          },
          "AccessToken": "token-xyz",
          "ServerId": "server-1"
        }
    """.trimIndent()
}

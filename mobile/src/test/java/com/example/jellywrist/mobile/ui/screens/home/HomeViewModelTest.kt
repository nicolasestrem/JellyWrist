package com.example.jellywrist.mobile.ui.screens.home

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.InMemoryTokenStore
import com.example.jellywrist.mobile.TestBackend
import com.example.jellywrist.mobile.data.MobilePrefs
import com.example.jellywrist.mobile.testSession
import com.example.jellywrist.mobile.datalayer.FakeWatchLink
import com.example.jellywrist.mobile.datalayer.WatchDiscovery
import com.example.jellywrist.mobile.datalayer.WatchSetupController
import com.example.jellywrist.mobile.datalayer.WatchStatusRepository
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Diagnosis of the phone↔watch link.
 *
 * `refresh()` used to discard the repository's `Result`, so every failure mode collapsed to
 * `status == null` and the home screen printed one sentence — "check Bluetooth pairing" —
 * whether nothing was paired, the watch app was missing, or the watch simply did not answer.
 * These tests pin each outcome to its own state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** `viewModelScope` runs on `Dispatchers.Main`; sharing the scheduler lets it be driven. */
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a phone with no connected node reports no watch connected, not an absent pairing`() =
        runTest(mainDispatcher) {
            // `NodeClient.connectedNodes` lists what is connected *now*, never what is paired:
            // a paired watch that is off, asleep, or out of range lands here too, so the state
            // must not assert that nothing is paired.
            val viewModel = viewModel(FakeWatchLink(discovery = WatchDiscovery.NoWatchConnected), this)

            advanceUntilIdle()

            assertEquals(WatchAvailability.NoWatchConnected, viewModel.watchAvailability.value)
        }

    @Test
    fun `a connected watch without the app is named in the diagnosis`() = runTest(mainDispatcher) {
        val viewModel = viewModel(
            FakeWatchLink(discovery = WatchDiscovery.NodesWithoutApp(listOf("Test Watch"))),
            this,
        )

        advanceUntilIdle()

        assertEquals(WatchAvailability.AppMissing("Test Watch"), viewModel.watchAvailability.value)
    }

    @Test
    fun `a discovered watch that never answers is not reported as disconnected`() = runTest(mainDispatcher) {
        // The node is found, so the request is sent and the 15 s wait expires: the phone must
        // not blame the connection for a watch that is connected but silent.
        val viewModel = viewModel(FakeWatchLink(), this)

        advanceUntilIdle()

        assertEquals(WatchAvailability.NotAnswering, viewModel.watchAvailability.value)
    }

    @Test
    fun `a failure of the discovery step itself is not attributed to the watch`() = runTest(mainDispatcher) {
        val link = FakeWatchLink()
        link.discoveryFailure = IllegalStateException("capability query failed")

        val viewModel = viewModel(link, this)
        advanceUntilIdle()

        assertEquals(WatchAvailability.DiscoveryFailed, viewModel.watchAvailability.value)
    }

    @Test
    fun `an answering watch is reported as reachable`() = runTest(mainDispatcher) {
        val link = FakeWatchLink()
        link.onSend = { path ->
            if (path == WirePaths.STATUS_REQUEST) {
                link.reply(WirePaths.STATUS_REPLY, WatchStatus(configured = true).toWireBytes())
            }
        }

        val viewModel = viewModel(link, this)
        advanceUntilIdle()

        assertEquals(WatchAvailability.Reachable, viewModel.watchAvailability.value)
        assertEquals(true, viewModel.status.value?.configured)
    }

    @Test
    fun `a cached status is flagged stale once the watch stops answering`() = runTest(mainDispatcher) {
        val link = FakeWatchLink()
        link.onSend = { path ->
            if (path == WirePaths.STATUS_REQUEST) {
                link.reply(WirePaths.STATUS_REPLY, WatchStatus(configured = true).toWireBytes())
            }
        }
        val viewModel = viewModel(link, this)
        advanceUntilIdle()
        assertEquals(false, viewModel.statusIsStale.value)

        // The watch goes silent. The repository keeps the last status on purpose (the figures
        // stay useful), so nothing but this flag can stop the card claiming they are live.
        link.onSend = null
        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(WatchAvailability.NotAnswering, viewModel.watchAvailability.value)
        assertEquals(true, viewModel.status.value?.configured)
        assertEquals(true, viewModel.statusIsStale.value)
    }

    @Test
    fun `a live status is never flagged stale`() = runTest(mainDispatcher) {
        val link = FakeWatchLink()
        link.onSend = { path ->
            if (path == WirePaths.STATUS_REQUEST) {
                link.reply(WirePaths.STATUS_REPLY, WatchStatus(configured = true).toWireBytes())
            }
        }

        val viewModel = viewModel(link, this)
        advanceUntilIdle()

        assertEquals(false, viewModel.statusIsStale.value)
    }

    @Test
    fun `a setup attempt republishes the diagnosis its own discovery produced`() = runTest(mainDispatcher) {
        // The phone says the app is missing. The user installs it and taps Set up, but by then
        // the watch is off. Setup ran a discovery of its own and threw the answer away, so the
        // card went on naming the old cause for the whole new attempt — and `SetupButton`
        // suppressed the resulting link error as a duplicate of that very sentence.
        val link = FakeWatchLink(discovery = WatchDiscovery.NodesWithoutApp(listOf("Test Watch")))
        val viewModel = viewModel(link, this, session = testSession("http://localhost"))
        advanceUntilIdle()
        assertEquals(WatchAvailability.AppMissing("Test Watch"), viewModel.watchAvailability.value)

        link.discovery = WatchDiscovery.NoWatchConnected
        viewModel.setupWatch()
        advanceUntilIdle()

        assertEquals(WatchAvailability.NoWatchConnected, viewModel.watchAvailability.value)
    }

    @Test
    fun `a setup failure the watch itself reported leaves the diagnosis alone`() = runTest(mainDispatcher) {
        // No session: setup fails with NO_SESSION without ever reaching discovery, so it has
        // learned nothing about the link and must not overwrite what the refresh established.
        val link = FakeWatchLink(discovery = WatchDiscovery.NodesWithoutApp(listOf("Test Watch")))
        val viewModel = viewModel(link, this)
        advanceUntilIdle()

        viewModel.setupWatch()
        advanceUntilIdle()

        assertEquals(WatchAvailability.AppMissing("Test Watch"), viewModel.watchAvailability.value)
    }

    private fun viewModel(
        link: FakeWatchLink,
        scope: CoroutineScope,
        session: AuthSession? = null,
    ): HomeViewModel {
        val backend = TestBackend()
        val authRepository = AuthRepository(backend, InMemoryTokenStore(session))
        val mobilePrefs = MobilePrefs(
            PreferenceDataStoreFactory.create {
                File(temporaryFolder.newFolder(), "prefs.preferences_pb")
            },
        )
        val statusRepository = WatchStatusRepository(link)
        return HomeViewModel(
            watchStatusRepository = statusRepository,
            watchSetupController = WatchSetupController(
                watchLink = link,
                authRepository = authRepository,
                backend = backend,
                mobilePrefs = mobilePrefs,
                watchStatusRepository = statusRepository,
                appScope = scope,
            ),
            authRepository = authRepository,
            mobilePrefs = mobilePrefs,
        )
    }
}

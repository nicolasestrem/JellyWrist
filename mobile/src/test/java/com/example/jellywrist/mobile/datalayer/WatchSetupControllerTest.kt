package com.example.jellywrist.mobile.datalayer

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.InMemoryTokenStore
import com.example.jellywrist.mobile.TestBackend
import com.example.jellywrist.mobile.data.MobilePrefs
import com.example.jellywrist.mobile.testSession
import com.example.jellywrist.wire.SetupQcCode
import com.example.jellywrist.wire.SetupResult
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.toWireBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Full provisioning driven by a fake transport.
 *
 * The central test is the subscription invariant: `WatchLink.incoming()` does not replay,
 * so the exchange must keep **one** listener from the `SETUP` send through to the result. A
 * per-phase subscription (the bug fixed here) loses the `SETUP_RESULT` that the watch emits
 * while the phone's Quick Connect approval is still in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WatchSetupControllerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `a single subscription spans code approval and result`() = runTest {
        val link = FakeWatchLink()
        // The watch publishes the code then concludes immediately: the result arrives while
        // no await is armed, exactly the window where it was lost.
        link.onSend = { path ->
            if (path == WirePaths.SETUP) {
                link.reply(WirePaths.SETUP_QC, SetupQcCode("123456").toWireBytes())
                link.reply(
                    WirePaths.SETUP_RESULT,
                    SetupResult(ok = false, error = WireErrors.EXPIRED).toWireBytes(),
                )
            }
        }
        server.enqueue(MockResponse().setBody("true").addHeader("Content-Type", "application/json"))

        val controller = controller(link, this)
        controller.startSetup()
        val terminal = controller.state.first { it is WatchSetupState.Failure }
        advanceUntilIdle() // the listener is closed in the `finally`, after the publication

        assertEquals(WatchSetupState.Failure(WireErrors.EXPIRED), terminal)
        assertEquals(listOf("+", "-"), link.subscriptionLog)
        assertEquals(listOf(WirePaths.SETUP), link.sentPaths)
    }

    @Test
    fun `a successful setup refreshes the watch status`() = runTest {
        val link = FakeWatchLink()
        val statusRepository = WatchStatusRepository(link)
        link.onSend = { path ->
            when (path) {
                // Watch already configured: terminal result without a Quick Connect code.
                WirePaths.SETUP -> link.reply(
                    WirePaths.SETUP_RESULT,
                    SetupResult(ok = true, alreadyConfigured = true, userName = "Avery").toWireBytes(),
                )

                WirePaths.STATUS_REQUEST -> link.reply(
                    WirePaths.STATUS_REPLY,
                    WatchStatus(configured = true, userName = "Avery").toWireBytes(),
                )
            }
        }

        val controller = controller(link, this, statusRepository)
        controller.startSetup()
        val terminal = controller.state.first { it is WatchSetupState.Success }
        advanceUntilIdle() // the status refresh follows the state publication

        assertEquals(WatchSetupState.Success("Avery", alreadyConfigured = true), terminal)
        // Without this refresh, the home screen would stay on the "not configured" layout.
        assertTrue(WirePaths.STATUS_REQUEST in link.sentPaths)
        assertEquals(true, statusRepository.status.value?.configured)
    }

    @Test
    fun `an unreachable watch is reported instead of crashing`() = runTest {
        val link = FakeWatchLink(node = null)
        val statusRepository = WatchStatusRepository(link)

        val controller = controller(link, this, statusRepository)
        controller.startSetup()
        val terminal = controller.state.first { it is WatchSetupState.Failure }

        assertEquals(WatchSetupState.Failure(WireErrors.WATCH_UNREACHABLE), terminal)
        // The attempt's own discovery reaches the repository, which is what the home card
        // reads: setup used to call the transport directly and discard this answer.
        assertEquals(WatchDiscovery.NoWatchConnected, statusRepository.discovery.value)
    }

    @Test
    fun `a transport failure during the exchange is reported as internal`() = runTest {
        val link = FakeWatchLink()
        // The watch was discovered, so the failure is NOT a discovery failure: mapping it to
        // WATCH_UNREACHABLE told the user to check Bluetooth pairing for a bug that has
        // nothing to do with pairing.
        link.onSend = { throw IllegalStateException("data layer send failed") }

        val controller = controller(link, this)
        controller.startSetup()
        val terminal = controller.state.first { it is WatchSetupState.Failure }

        assertEquals(WatchSetupState.Failure(WireErrors.INTERNAL), terminal)
    }

    @Test
    fun `a failure of the discovery step itself is reported as an unreachable watch`() = runTest {
        val link = FakeWatchLink()
        link.discoveryFailure = IllegalStateException("capability query failed")
        val statusRepository = WatchStatusRepository(link)

        val controller = controller(link, this, statusRepository)
        controller.startSetup()
        val terminal = controller.state.first { it is WatchSetupState.Failure }

        assertEquals(WatchSetupState.Failure(WireErrors.WATCH_UNREACHABLE), terminal)
        // A query that threw leaves no diagnosis to publish, and must not leave a stale one.
        assertEquals(null, statusRepository.discovery.value)
    }

    private fun controller(
        link: FakeWatchLink,
        scope: CoroutineScope,
        statusRepository: WatchStatusRepository = WatchStatusRepository(link),
    ): WatchSetupController {
        val baseUrl = server.url("/").toString().trimEnd('/')
        val backend = TestBackend()
        return WatchSetupController(
            watchLink = link,
            authRepository = AuthRepository(backend, InMemoryTokenStore(testSession(baseUrl))),
            backend = backend,
            mobilePrefs = MobilePrefs(
                PreferenceDataStoreFactory.create {
                    File(temporaryFolder.newFolder(), "prefs.preferences_pb")
                },
            ),
            watchStatusRepository = statusRepository,
            appScope = scope,
        )
    }
}

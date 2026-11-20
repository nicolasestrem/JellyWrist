package com.example.jellywrist.companion

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.ServerRepository
import com.example.jellywrist.data.reset.ServerDataReset
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.sync.PeriodicSyncArmer
import com.example.jellywrist.support.FakeSettingsRepository
import com.example.jellywrist.support.FakeTokenStore
import com.example.jellywrist.support.OkHttpJellyfinAuthBackend
import com.example.jellywrist.wire.SetupQcCode
import com.example.jellywrist.wire.SetupRequest
import com.example.jellywrist.wire.SetupResult
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WirePaths
import com.example.jellywrist.wire.fromWire
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Verifies the Quick Connect flow triggered by the phone. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionSetupCoordinatorTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var settingsRepository: FakeSettingsRepository
    private lateinit var pinRegistry: PinRegistry
    private lateinit var coordinator: CompanionSetupCoordinator
    private lateinit var baseUrl: String

    /** Number of catalog purges requested by the coordinator. */
    private var purges = 0

    /** Number of periodic-sync re-arms requested by the coordinator (GAP-010). */
    private var syncArms = 0

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")
        tokenStore = FakeTokenStore()
        settingsRepository = FakeSettingsRepository()
        pinRegistry = PinRegistry()
        purges = 0
        syncArms = 0
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `setup sends code then successful result and persists session`() = runTest {
        coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(SetupRequest(baseUrl)) { path, payload -> replies += path to payload }
        advanceUntilIdle()

        assertEquals(listOf(WirePaths.SETUP_QC, WirePaths.SETUP_RESULT), replies.map { it.first })
        assertEquals("123456", replies[0].second.fromWire<SetupQcCode>().code)
        val result = replies[1].second.fromWire<SetupResult>()
        assertTrue(result.ok)
        assertEquals("Avery", result.userName)
        assertNotNull(tokenStore.load().sessionOrNull)
        // Clean watch: nothing to purge, purging is reserved for server changes.
        assertEquals(0, purges)
        // GAP-010: a fresh setup (e.g. after a factory reset) is not a reconfiguration, so
        // `clearServerData()` never runs — the periodic sync must be re-armed here, or the
        // watch looks configured but silently never syncs again.
        assertEquals(1, syncArms)
    }

    @Test
    fun `a failed setup does not arm the periodic sync`() = runTest {
        coordinator = coordinator(backgroundScope)
        server.enqueue(jsonResponse(publicInfoJson()))
        server.enqueue(MockResponse().setResponseCode(404))

        coordinator.handleSetup(SetupRequest(baseUrl)) { _, _ -> }
        advanceUntilIdle()

        assertEquals(0, syncArms)
    }

    @Test
    fun `setup reports unavailable quick connect`() = runTest {
        coordinator = coordinator(backgroundScope)
        server.enqueue(jsonResponse(publicInfoJson()))
        server.enqueue(MockResponse().setResponseCode(404))
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(SetupRequest(baseUrl)) { path, payload -> replies += path to payload }

        assertEquals(listOf(WirePaths.SETUP_RESULT), replies.map { it.first })
        val result = replies.single().second.fromWire<SetupResult>()
        assertEquals(false, result.ok)
        assertEquals(WireErrors.QUICKCONNECT_UNAVAILABLE, result.error)
    }

    @Test
    fun `setup acknowledges an already configured watch without network`() = runTest {
        tokenStore.save(session(baseUrl))
        coordinator = coordinator(backgroundScope)
        val replies = mutableListOf<Pair<String, ByteArray>>()

        // Request without serverId or userId: a phone older than these fields, whose
        // comparison falls back to the URL — the acknowledgment must stay offline.
        coordinator.handleSetup(SetupRequest(baseUrl)) { path, payload -> replies += path to payload }

        assertEquals(listOf(WirePaths.SETUP_RESULT), replies.map { it.first })
        val result = replies.single().second.fromWire<SetupResult>()
        assertTrue(result.ok)
        assertTrue(result.alreadyConfigured)
        assertEquals("Avery", result.userName)
        assertEquals(0, server.requestCount)
        assertEquals(0, purges)
    }

    @Test
    fun `a watch configured on another server is reconfigured and purged`() = runTest {
        // The watch is paired to another server: acknowledging "already configured"
        // would leave the phone's "Reconfigure" button without effect, and pinned
        // collections would then target a foreign server's identifiers.
        tokenStore.save(session(baseUrl = "https://old.example", serverId = "old-server"))
        coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(
            SetupRequest(baseUrl, serverId = "server-1", userId = USER_ID),
        ) { path, payload -> replies += path to payload }
        advanceUntilIdle()

        assertEquals(listOf(WirePaths.SETUP_QC, WirePaths.SETUP_RESULT), replies.map { it.first })
        val result = replies[1].second.fromWire<SetupResult>()
        assertTrue(result.ok)
        assertFalse(result.alreadyConfigured)
        assertEquals(baseUrl, tokenStore.load().sessionOrNull?.baseUrl)
        assertEquals(1, purges)
    }

    @Test
    fun `a watch configured with another account is reconfigured`() = runTest {
        // Same server, different account: the local catalog belongs to the previous user
        // (different libraries and permissions).
        tokenStore.save(session(baseUrl, userId = OTHER_USER_ID))
        coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(
            SetupRequest(baseUrl, serverId = "server-1", userId = USER_ID),
        ) { path, payload -> replies += path to payload }
        advanceUntilIdle()

        assertEquals(listOf(WirePaths.SETUP_QC, WirePaths.SETUP_RESULT), replies.map { it.first })
        assertEquals(USER_ID, tokenStore.load().sessionOrNull?.userId)
        assertEquals(1, purges)
    }

    @Test
    fun `a failed reconfiguration leaves the existing session and data intact`() = runTest {
        tokenStore.save(session(baseUrl = "https://old.example", serverId = "old-server"))
        coordinator = coordinator(backgroundScope)
        server.enqueue(jsonResponse(publicInfoJson()))
        server.enqueue(MockResponse().setResponseCode(404)) // Quick Connect unavailable
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(
            SetupRequest(baseUrl, serverId = "server-1", userId = USER_ID),
        ) { path, payload -> replies += path to payload }
        advanceUntilIdle()

        val result = replies.single().second.fromWire<SetupResult>()
        assertEquals(WireErrors.QUICKCONNECT_UNAVAILABLE, result.error)
        // The purge follows authentication only when authentication succeeds.
        assertEquals(0, purges)
        assertEquals("https://old.example", tokenStore.load().sessionOrNull?.baseUrl)
    }

    @Test
    fun `a failed reconfiguration restores the previous certificate pin`() = runTest {
        // The watch talks to a self-signed server; the phone offers another. Persisting
        // the new pin before authentication made the old server — the one of the
        // kept session — unreachable forever (PR #10, Codex P2).
        settingsRepository.setPinnedCertificate(OLD_PIN)
        pinRegistry.update(OLD_PIN)
        tokenStore.save(session(baseUrl = "https://old.example", serverId = "old-server"))
        coordinator = coordinator(backgroundScope)
        server.enqueue(jsonResponse(publicInfoJson()))
        server.enqueue(MockResponse().setResponseCode(404)) // Quick Connect unavailable

        coordinator.handleSetup(
            SetupRequest(baseUrl, pinnedCertSha256 = NEW_PIN, serverId = "server-1", userId = USER_ID),
        ) { _, _ -> }
        advanceUntilIdle()

        assertEquals(OLD_PIN, settingsRepository.settings.first().pinnedCertificateSha256)
        assertEquals(setOf(OLD_PIN), pinRegistry.pins)
    }

    @Test
    fun `a successful setup persists the requested certificate pin`() = runTest {
        settingsRepository.setPinnedCertificate(OLD_PIN)
        pinRegistry.update(OLD_PIN)
        coordinator = coordinator(backgroundScope)
        enqueueSuccessfulQuickConnect()

        coordinator.handleSetup(SetupRequest(baseUrl, pinnedCertSha256 = NEW_PIN)) { _, _ -> }
        advanceUntilIdle()

        assertEquals(NEW_PIN, settingsRepository.settings.first().pinnedCertificateSha256)
        assertEquals(setOf(NEW_PIN), pinRegistry.pins)
    }

    @Test
    fun `reset clears a terminal state so a later observer is not authenticated`() = runTest {
        tokenStore.save(session(baseUrl))
        coordinator = coordinator(backgroundScope)

        coordinator.handleSetup(SetupRequest(baseUrl)) { _, _ -> }
        assertEquals(RemoteSetupState.Done("Avery"), coordinator.state.value)

        // Without this reset, the lingering state survives sign-out and the next
        // home screen believes it's authenticated (PR #10, Codex P1).
        coordinator.reset()

        assertEquals(RemoteSetupState.Idle, coordinator.state.value)
    }

    /**
     * Cancellation during **validation**, before the Quick Connect poll exists.
     *
     * This test runs in real time on real dispatchers: the window to cover is
     * precisely the one where provisioning is suspended on network I/O, out of reach
     * of the virtual clock. Following only the poll let this coroutine run to
     * completion and persist a session well after sign-out (PR #10, Codex P1).
     */
    @Test
    fun `reset cancels a setup still validating and no session is persisted`() = runBlocking {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            coordinator = coordinator(realScope)
            // Validation drags on enough that the reset lands while it's running;
            // the rest of the flow would then respond instantly. All four responses are
            // needed: one was missing, and the uncancelled flow never reached
            // token persistence — the main assertion couldn't fail.
            server.enqueue(jsonResponse(publicInfoJson()).setBodyDelay(300, TimeUnit.MILLISECONDS))
            server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))
            server.enqueue(jsonResponse(quickConnectJson(authenticated = true)))
            server.enqueue(jsonResponse(authResultJson()))
            val replies = mutableListOf<String>()

            val caller = realScope.launch {
                coordinator.handleSetup(SetupRequest(baseUrl)) { path, _ -> replies += path }
            }
            withTimeout(SETUP_TIMEOUT_MS) {
                while (server.requestCount == 0) delay(10)
            }

            coordinator.reset()

            assertEquals(RemoteSetupState.Idle, coordinator.state.value)
            // Plenty of time for an uncancelled flow to complete.
            delay(SETTLE_MS)
            assertNull(
                "An authentication in flight must not survive the reset",
                tokenStore.load().sessionOrNull,
            )
            assertTrue("No response must go out after cancellation", replies.isEmpty())
            caller.cancel()
        } finally {
            realScope.cancel()
        }
    }

    /**
     * Server failure **after** code emission: the poll must not throw.
     *
     * `quickConnectState` rethrows any non-4xx `InvalidStatusException`. The exception
     * traversed the cold flow, escaped the `launch` of `appScope` — a `SupervisorJob` without
     * an exception handler — and killed the watch's process: the phone never received
     * `SETUP_RESULT`, only its own timeout (PR #10, Codex P1).
     */
    @Test
    fun `a server that dies during the poll yields a result instead of an exception`() = runTest {
        coordinator = coordinator(backgroundScope)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().contains(PATH_PUBLIC_INFO) -> jsonResponse(publicInfoJson())
                request.path.orEmpty().contains(PATH_QC_INITIATE) ->
                    jsonResponse(quickConnectJson(authenticated = false))
                else -> MockResponse().setResponseCode(503) // the poll no longer responds
            }
        }
        val replies = mutableListOf<Pair<String, ByteArray>>()

        coordinator.handleSetup(SetupRequest(baseUrl)) { path, payload -> replies += path to payload }
        advanceUntilIdle()

        assertEquals(listOf(WirePaths.SETUP_QC, WirePaths.SETUP_RESULT), replies.map { it.first })
        val result = replies[1].second.fromWire<SetupResult>()
        assertFalse(result.ok)
        assertEquals(WireErrors.UNREACHABLE, result.error)
        assertEquals(RemoteSetupState.Failed(WireErrors.UNREACHABLE), coordinator.state.value)
        assertNull(tokenStore.load().sessionOrNull)
    }

    /**
     * Write failure **after** authentication: the trial pin must not survive.
     *
     * `configured` holds back the `finally` from undoing the pin; placed before the writes,
     * it covered them by anticipation. A failing `setPinnedCertificate` — now intercepted
     * instead of killing the process — then left `staged` in place: `PinRegistry` makes
     * the trial prevail over the accepted and `update()` cannot erase it (by design), so the
     * watch would trust, for the lifetime of the process, a certificate that no
     * setting records.
     */
    @Test
    fun `a write failure after authentication still restores the previous certificate pin`() =
        runTest {
            settingsRepository.setPinnedCertificate(OLD_PIN)
            pinRegistry.update(OLD_PIN)
            coordinator = coordinator(
                backgroundScope,
                settings = SettingsRepositoryFailingOnPin(settingsRepository),
            )
            enqueueSuccessfulQuickConnect()

            coordinator.handleSetup(SetupRequest(baseUrl, pinnedCertSha256 = NEW_PIN)) { _, _ -> }
            advanceUntilIdle()

            assertEquals(setOf(OLD_PIN), pinRegistry.pins)
            // The token, however, is indeed persisted: the failure is subsequent.
            assertNotNull(tokenStore.load().sessionOrNull)
            assertEquals(RemoteSetupState.Done("Avery"), coordinator.state.value)
        }

    /** Settings whose only TLS write fails — the rest behaves normally. */
    private class SettingsRepositoryFailingOnPin(
        delegate: FakeSettingsRepository,
    ) : SettingsRepository by delegate {
        override suspend fun setPinnedCertificate(sha256: String?): Unit =
            throw IllegalStateException("DataStore write impossible")
    }

    /**
     * Transport failure **after** authentication: the watch is configured, not failed.
     *
     * The last-resort catch must not turn a lost announcement into a provisioning
     * failure: the session is already persisted, and publishing `Failed` would send the
     * home screen to an error while the watch is connected.
     */
    @Test
    fun `a transport failure after authentication still reports the watch as configured`() =
        runTest {
            coordinator = coordinator(backgroundScope)
            enqueueSuccessfulQuickConnect()

            coordinator.handleSetup(SetupRequest(baseUrl)) { path, _ ->
                // Play Services unavailable at the time of announcing the result.
                if (path == WirePaths.SETUP_RESULT) error("transport unavailable")
            }
            advanceUntilIdle()

            assertEquals(RemoteSetupState.Done("Avery"), coordinator.state.value)
            assertNotNull(tokenStore.load().sessionOrNull)
        }

    /**
     * A SETUP request arriving **during** the factory reset must wait, not run.
     *
     * `reset()` released the lock before the purge started: a re-provisioning
     * launched in that interval could authenticate after the token was erased — erased
     * last — and leave the watch connected to the just-deleted account (PR #10,
     * Codex P1). Real-time test: the target window is a suspended purge, out of reach
     * of the virtual clock.
     */
    @Test
    fun `a SETUP arriving during the factory reset waits for it to finish`() = runBlocking {
        val realScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            tokenStore.save(session(baseUrl = "https://old.example", serverId = "old-server"))
            coordinator = coordinator(realScope)

            val purgeDone = AtomicBoolean(false)
            val purgeWasDoneAtFirstRequest = AtomicBoolean(false)
            val firstRequest = AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    // Does the first network call of provisioning come after the purge?
                    if (firstRequest.compareAndSet(true, false)) {
                        purgeWasDoneAtFirstRequest.set(purgeDone.get())
                    }
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

            val purgeStarted = CompletableDeferred<Unit>()
            val reset = realScope.launch {
                coordinator.reset {
                    // Simulated factory reset: it drags on, and the token goes LAST, as
                    // in `LocalDataReset.factoryReset()`.
                    purgeStarted.complete(Unit)
                    delay(PURGE_MS)
                    tokenStore.clear()
                    purgeDone.set(true)
                }
            }
            purgeStarted.await()

            val setup = realScope.launch {
                coordinator.handleSetup(SetupRequest(baseUrl)) { _, _ -> }
            }
            withTimeout(SETUP_TIMEOUT_MS) {
                reset.join()
                setup.join()
            }

            assertTrue(
                "provisionning must not touch the network before the purge finishes",
                purgeWasDoneAtFirstRequest.get(),
            )
            // The watch is clean when provisioning starts: it's a
            // new configuration, not a reconfiguration, and nothing was purged for it.
            assertEquals(0, purges)
            // The session written afterward is legitimate — it follows the sign-out.
            assertEquals(baseUrl, tokenStore.load().sessionOrNull?.baseUrl)
        } finally {
            realScope.cancel()
        }
    }

    private fun coordinator(
        scope: CoroutineScope,
        settings: SettingsRepository = settingsRepository,
    ) = CompanionSetupCoordinator(
        serverRepository = ServerRepository(OkHttpJellyfinAuthBackend()),
        authRepository = AuthRepository(OkHttpJellyfinAuthBackend(), tokenStore),
        settingsRepository = settings,
        pinRegistry = pinRegistry,
        serverDataReset = ServerDataReset { purges++ },
        periodicSyncArmer = PeriodicSyncArmer { syncArms++ },
        appScope = scope,
    )

    private fun session(
        baseUrl: String,
        serverId: String? = "server-1",
        userId: String = USER_ID,
    ) = AuthSession(
        baseUrl = baseUrl,
        accessToken = "token",
        userId = userId,
        userName = "Avery",
        serverId = serverId,
    )

    /** Server validation, Quick Connect opening, immediate approval, then token. */
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
        const val OTHER_USER_ID = "11111111-2222-3333-4444-555555555555"

        /** Lowercase hex fingerprints without separator — the normalized format of [PinRegistry]. */
        const val OLD_PIN = "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899"
        const val NEW_PIN = "99887766554433221100ffeeddccbbaa99887766554433221100ffeeddccbbaa"

        const val SETUP_TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 1_500L

        /** Simulated purge: long enough for a concurrent request to land inside it. */
        const val PURGE_MS = 400L

        /** SDK URL fragments, for a dispatcher independent of call order. */
        const val PATH_PUBLIC_INFO = "System/Info/Public"
        const val PATH_QC_INITIATE = "QuickConnect/Initiate"
        const val PATH_QC_AUTHENTICATE = "Users/AuthenticateWithQuickConnect"
    }
}

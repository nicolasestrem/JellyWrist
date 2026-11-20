package com.example.jellywrist.data.repo

import com.example.jellywrist.data.auth.AuthResult
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.QuickConnectState
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * JVM tests of [AuthRepository] via MockWebServer (PLAN.md §8 task 3, "TDD: Quick Connect
 * + AuthenticateByName").
 *
 * JSON bodies are in **PascalCase**: this is what the SDK's `@SerialName` annotations generate
 * (`Secret`, `Code`, `Authenticated`, `AccessToken`, `User`...). In camelCase, deserialization
 * would silently fall back to default values.
 *
 * As in [ServerRepositoryTest], the [ApiClient] is built via [OkHttpFactory] without
 * instantiating the SDK (the Android variant would require a `Context`).
 */
class AuthRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var tokenStore: FakeTokenStore
    private lateinit var repo: AuthRepository
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        baseUrl = server.url("/").toString().removeSuffix("/")
        tokenStore = FakeTokenStore()
        repo = AuthRepository(OkHttpJellyfinAuthBackend(), tokenStore)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Quick Connect: initiation ---------------------------------------------------

    @Test
    fun `initiateQuickConnect returns secret and code`() = runBlocking {
        server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))

        val handle = repo.initiateQuickConnect(baseUrl)

        assertEquals("secret-abc", handle?.secret)
        assertEquals("123456", handle?.code)
        assertEquals("/QuickConnect/Initiate", server.takeRequest().path)
    }

    @Test
    fun `initiateQuickConnect returns null when quick connect is disabled`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertNull(repo.initiateQuickConnect(baseUrl))
    }

    // --- Quick Connect: state ---------------------------------------------------------

    @Test
    fun `quickConnectState maps unauthenticated to Pending`() = runBlocking {
        server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))

        assertEquals(QuickConnectState.Pending, repo.quickConnectState(baseUrl, "secret-abc"))
    }

    @Test
    fun `quickConnectState maps authenticated to Approved`() = runBlocking {
        server.enqueue(jsonResponse(quickConnectJson(authenticated = true)))

        assertEquals(QuickConnectState.Approved, repo.quickConnectState(baseUrl, "secret-abc"))
    }

    @Test
    fun `quickConnectState maps unknown secret to Expired`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals(QuickConnectState.Expired, repo.quickConnectState(baseUrl, "stale"))
    }

    // --- Quick Connect: polling ------------------------------------------------------

    @Test
    fun `pollQuickConnect emits Pending then Approved and completes`() = runTest {
        server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))
        server.enqueue(jsonResponse(quickConnectJson(authenticated = true)))

        val states = repo.pollQuickConnect(baseUrl, "secret-abc").toList()

        assertEquals(listOf(QuickConnectState.Pending, QuickConnectState.Approved), states)
        // MF-1: the POLL_INTERVAL = 2 s cadence must be respected;
        // no reason to exceed this delay for two polls.
        assertEquals(
            "virtual time must exactly match POLL_INTERVAL",
            2.seconds.inWholeMilliseconds, testScheduler.currentTime,
        )
    }

    @Test
    fun `pollQuickConnect gives up with Expired after the timeout budget`() = runTest {
        // Still "pending": the poll must stop on its own.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse(quickConnectJson(authenticated = false))
        }

        val states = repo.pollQuickConnect(baseUrl, "secret-abc").toList()

        assertEquals(QuickConnectState.Expired, states.last())
        assertTrue("the poll must emit Pending states before giving up", states.size > 2)
        assertTrue(states.dropLast(1).all { it == QuickConnectState.Pending })
        // MF-1: the POLL_TIMEOUT = 5 min budget must be exhausted exactly.
        assertEquals(
            "virtual time must exactly match POLL_TIMEOUT",
            5.minutes.inWholeMilliseconds, testScheduler.currentTime,
        )
    }


    @Test
    fun `pollQuickConnect reports Unavailable instead of throwing when the server dies`() =
        runTest {
            // PR #10, Codex P1: `quickConnectState` rethrows any non-4xx InvalidStatusException
            // and does not catch any IOException. The exception traversed the cold flow and
            // escaped its collectors' `launch` — including `appScope`, a SupervisorJob
            // without an exception handler: the process would die instead of responding.
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    MockResponse().setResponseCode(503)
            }

            val states = repo.pollQuickConnect(baseUrl, "secret-abc").toList()

            assertEquals(listOf(QuickConnectState.Unavailable), states)
            // Nothing is emitted during the grace period: the code stays displayed as is.
            assertEquals(
                "the give-up must occur exactly at FAILURE_GRACE",
                20.seconds.inWholeMilliseconds, testScheduler.currentTime,
            )
        }

    @Test
    fun `pollQuickConnect survives a transient failure and still completes`() = runTest {
        // A few seconds of Wi-Fi handoff must not condemn a wait that can last five minutes:
        // the grace counter resets to zero as soon as a response gets through.
        var call = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                call++
                return when {
                    call <= 5 -> MockResponse().setResponseCode(503)
                    call == 6 -> jsonResponse(quickConnectJson(authenticated = false))
                    call <= 11 -> MockResponse().setResponseCode(503)
                    else -> jsonResponse(quickConnectJson(authenticated = true))
                }
            }
        }

        val states = repo.pollQuickConnect(baseUrl, "secret-abc").toList()

        // Failures are not displayed; only server responses are states.
        assertEquals(
            listOf(QuickConnectState.Pending, QuickConnectState.Approved),
            states,
        )
    }

    // --- Quick Connect: exchange for a token --------------------------------------

    @Test
    fun `authenticateWithQuickConnect succeeds and persists the session`() = runBlocking {
        server.enqueue(jsonResponse(authResultJson()))

        val result = repo.authenticateWithQuickConnect(baseUrl, "secret-abc")

        val session = result.successOrFail().session
        assertEquals("token-xyz", session.accessToken)
        assertEquals("Avery", session.userName)
        assertEquals("server-1", session.serverId)
        assertEquals(USER_ID, session.userId)
        // MF-6: the baseUrl passed by the caller must be preserved verbatim.
        assertEquals(baseUrl, session.baseUrl)
        assertEquals(session, tokenStore.load().sessionOrNull)
        assertEquals("/Users/AuthenticateWithQuickConnect", server.takeRequest().path)
    }

    @Test
    fun `authenticateWithQuickConnect maps an expired secret to QuickConnectExpired`() =
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(400))

            assertEquals(
                AuthResult.QuickConnectExpired,
                repo.authenticateWithQuickConnect(baseUrl, "stale"),
            )
            assertNull(tokenStore.load().sessionOrNull)
        }

    // --- Fallback by credentials -------------------------------------------------------



    // --- Degraded responses -----------------------------------------------------------

    @Test
    fun `a response without access token fails and persists nothing`() = runBlocking {
        server.enqueue(
            jsonResponse(
                """
                {
                  "User": $USER_JSON,
                  "AccessToken": "",
                }
                """.trimIndent(),
            ),
        )

        assertTrue(repo.authenticateWithQuickConnect(baseUrl, "secret-abc") is AuthResult.Failed)
        assertNull(tokenStore.load().sessionOrNull)
    }

    @Test
    fun `connection failure is Failed`() = runBlocking {
        val deadUrl = server.url("/").toString().removeSuffix("/")
        server.shutdown() // nobody listening anymore

        assertTrue(repo.authenticateWithQuickConnect(deadUrl, "secret-abc") is AuthResult.Failed)
    }

    // --- Persisted session ------------------------------------------------------------

    @Test
    fun `currentSession reflects the store and signOut clears it`() = runBlocking {
        server.enqueue(jsonResponse(authResultJson()))
        repo.authenticateWithQuickConnect(baseUrl, "secret-abc")

        assertEquals("token-xyz", repo.currentSession()?.accessToken)

        repo.signOut()

        assertNull(repo.currentSession())
    }

    // --- Coroutine cancellation (MF-2, MF-4) -----------------------------------------

    @Test
    fun `pollQuickConnect stops polling when the collector is cancelled`() = runTest {
        // MF-2: cancellation must stop the poll (AuthRepository KDoc: "cold flow:
        // cancellation stops the poll").
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                jsonResponse(quickConnectJson(authenticated = false))
        }

        val collected = mutableListOf<QuickConnectState>()
        val job = launch {
            repo.pollQuickConnect(baseUrl, "secret-abc").collect { collected.add(it) }
        }
        // Allow time for a few polls.
        advanceTimeBy(10.seconds)

        job.cancelAndJoin()
        val countAfterCancel = server.requestCount

        // Advance more: no new request must be sent.
        advanceTimeBy(30.seconds)
        assertEquals(countAfterCancel, server.requestCount)

        // No Expired should have been emitted: cancellation precedes the timeout.
        assertTrue(
            "no Expired must be emitted before cancellation",
            QuickConnectState.Expired !in collected,
        )
    }


    @Test
    fun `authenticateWithQuickConnect propagates CancellationException`() = runBlocking {
        // MF-4: same check for the second suspend entry point.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                Thread.sleep(500)
                return jsonResponse(authResultJson())
            }
        }

        try {
            kotlinx.coroutines.withTimeout(50) {
                repo.authenticateWithQuickConnect(baseUrl, "secret-abc")
            }
            fail("withTimeout should have propagated CancellationException")
        } catch (_: CancellationException) {
            // OK: cancellation propagates correctly.
        }
    }

    // --- Server expiration during polling (MF-3) --------------------------------

    @Test
    fun `pollQuickConnect exits early on server-driven Expired`() = runTest {
        // MF-3: a secret that becomes invalid during polling must terminate the flow
        // before the POLL_TIMEOUT budget (AuthRepository.kt:90-92: return@flow if !Pending).
        server.enqueue(jsonResponse(quickConnectJson(authenticated = false)))
        server.enqueue(MockResponse().setResponseCode(404)) // secret expired

        val states = repo.pollQuickConnect(baseUrl, "secret-abc").toList()

        assertEquals(listOf(QuickConnectState.Pending, QuickConnectState.Expired), states)
        // The flow must terminate well before the 5 min budget.
        assertTrue(
            "the flow must terminate before POLL_TIMEOUT",
            testScheduler.currentTime < 5.minutes.inWholeMilliseconds,
        )
    }

    // --- Missing user (MF-5) ----------------------------------------------------

    @Test
    fun `a response with null User fails and persists nothing`() = runBlocking {
        // MF-5: a valid token but without a User is a failure
        // (AuthRepository.kt:150-151: user ?: return Failed).
        server.enqueue(
            jsonResponse(
                """
                {
                  "AccessToken": "token-xyz",
                  "ServerId": "server-1",
                  "User": null
                }
                """.trimIndent(),
            ),
        )

        assertTrue(repo.authenticateWithQuickConnect(baseUrl, "secret-abc") is AuthResult.Failed)
        assertNull(tokenStore.load().sessionOrNull)
    }

    // --- Trailing slash baseUrl (MF-6) ---------------------------------------------


    // --- Fixtures ---------------------------------------------------------------------

    private fun jsonResponse(body: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(body)

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
          "User": $USER_JSON,
          "AccessToken": "token-xyz",
          "ServerId": "server-1"
        }
    """.trimIndent()

    private companion object {
        /** `UserDto.Id` is a UUID on the SDK side: an arbitrary string would fail parsing. */
        const val USER_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6"

        /**
         * `UserDto` requires `Id` (UUID) plus the three `Has*Password` booleans, without
         * default values: omitting them makes SDK deserialization fail.
         */
        val USER_JSON = """
            {
              "Id": "$USER_ID",
              "Name": "Avery",
              "HasPassword": true,
              "HasConfiguredPassword": true,
              "HasConfiguredEasyPassword": false
            }
        """.trimIndent()
    }
}

/**
 * Unwraps an [AuthResult.Success] by re-raising the real cause on failure:
 * a bare `as Success` would hide the exception behind an opaque `ClassCastException`.
 */
private fun AuthResult.successOrFail(): AuthResult.Success = when (this) {
    is AuthResult.Success -> this
    is AuthResult.Failed -> throw AssertionError("expected Success, got Failed", cause)
    else -> throw AssertionError("expected Success, got $this")
}

/** In-memory [TokenStore]: verifies persistence without Keystore (unavailable on JVM). */
private class FakeTokenStore : TokenStore {
    private var session: AuthSession? = null

    override fun save(session: AuthSession) {
        this.session = session
    }

    override fun load(): SessionLoadResult = session
        ?.let { SessionLoadResult.Restored(it) }
        ?: SessionLoadResult.Empty

    override fun clear() {
        session = null
    }
}

/**
 * Test backend: builds the [ApiClient] via [OkHttpFactory] without going through
 * `Jellyfin.createApi` (avoids the `Context` requirement of the Android variant of the SDK).
 */
private class OkHttpJellyfinAuthBackend : JellyfinBackend {
    private val factory = OkHttpFactory()

    override fun discoverServers(): Flow<ServerDiscoveryInfo> = emptyFlow()

    override fun apiClient(baseUrl: String, accessToken: String?): ApiClient = factory.create(
        baseUrl = baseUrl,
        accessToken = null,
        clientInfo = ClientInfo("JellyWrist-test", "0.1.0"),
        deviceInfo = DeviceInfo("test", "test"),
        httpClientOptions = HttpClientOptions(),
        socketConnectionFactory = factory,
    )
}

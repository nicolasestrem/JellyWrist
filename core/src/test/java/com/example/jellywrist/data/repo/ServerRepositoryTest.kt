package com.example.jellywrist.data.repo

import com.example.jellywrist.data.jellyfin.JellyfinBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * JVM tests of [ServerRepository.validateServer] via MockWebServer (PLAN.md §8 task 2,
 * "TDD: MockWebServer on validation").
 *
 * UDP discovery (broadcast 7359) is not tested here: real network flow, unreliable on a
 * JVM runner — verified on emulator/watch.
 *
 * Uses [OkHttpJellyfinBackend] which builds the ApiClient via `OkHttpFactory` directly,
 * without instantiating the SDK (the Android variant would require a `Context`).
 */
class ServerRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ServerRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = ServerRepository(OkHttpJellyfinBackend())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `valid server returns ServerInfo from System Info Public`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {"ServerName":"Home Jellyfin","Version":"10.9.11",
                     "Id":"abc123","StartupWizardCompleted":true}
                    """.trimIndent(),
                ),
        )

        val result = repo.validateServer(server.url("/").toString())

        assertTrue("expected Valid but was $result", result is ServerValidation.Valid)
        val serverInfo = (result as ServerValidation.Valid).server
        assertEquals("Home Jellyfin", serverInfo.name)
        assertEquals("10.9.11", serverInfo.version)
        assertEquals("abc123", serverInfo.id)
        assertTrue(serverInfo.startupWizardCompleted)

        // The request indeed hit the canonical validation endpoint (no auth required).
        val recorded = server.takeRequest()
        assertEquals("/System/Info/Public", recorded.path)
    }

    @Test
    fun `missing optional fields still validate`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ServerName":"Bare"}"""),
        )

        val result = repo.validateServer(server.url("/").toString())
        assertTrue(result is ServerValidation.Valid)
        val serverInfo = (result as ServerValidation.Valid).server
        assertEquals("Bare", serverInfo.name)
        assertTrue(serverInfo.startupWizardCompleted) // defaults to true if absent
    }

    @Test
    fun `normalization is applied before validation`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"ServerName":"S"}"""),
        )

        // Input without scheme: http://localhost:PORT must be inferred before the call.
        val hostPort = "${server.hostName}:${server.port}"
        val result = repo.validateServer(hostPort)

        assertTrue("expected Valid but was $result", result is ServerValidation.Valid)
        assertEquals("http://$hostPort", (result as ServerValidation.Valid).server.baseUrl)
    }

    @Test
    fun `garbage input is InvalidUrl`() = runBlocking {
        assertEquals(ServerValidation.InvalidUrl("   "), repo.validateServer("   "))
        assertTrue(repo.validateServer("ftp://x") is ServerValidation.InvalidUrl)
    }

    @Test
    fun `http error is Unreachable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = repo.validateServer(server.url("/").toString())

        assertTrue("expected Unreachable but was $result", result is ServerValidation.Unreachable)
    }

    @Test
    fun `non-json body is Unreachable`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html")
                .setBody("<html>not a jellyfin server</html>"),
        )

        val result = repo.validateServer(server.url("/").toString())

        assertTrue(result is ServerValidation.Unreachable)
    }

    @Test
    fun `connection failure is Unreachable`() = runBlocking {
        // Nothing listening on this port anymore -> connection refused.
        val url = server.url("/").toString()
        server.shutdown()

        val result = repo.validateServer(url)

        assertTrue(result is ServerValidation.Unreachable)
    }
}

/**
 * Test backend: builds the [ApiClient] via [OkHttpFactory] without going through
 * `Jellyfin.createApi` (avoids the `Context` requirement of the Android variant of the SDK).
 * `discoverServers()` is not called by the validation tests.
 */
private class OkHttpJellyfinBackend : JellyfinBackend {
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

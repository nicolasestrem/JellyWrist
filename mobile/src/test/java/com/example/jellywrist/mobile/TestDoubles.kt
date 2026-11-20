package com.example.jellywrist.mobile

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

/** Shared test doubles for the phone module JVM tests. */
fun testSession(baseUrl: String) = AuthSession(
    baseUrl = baseUrl,
    accessToken = "token",
    userId = "00000000-0000-0000-0000-000000000002",
    userName = "Avery",
    serverId = null,
)

class InMemoryTokenStore(initial: AuthSession? = null) : TokenStore {
    private var session: AuthSession? = initial

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
 * Builds the [ApiClient] directly through `OkHttpFactory`.
 *
 * AGP resolves the **Android** variant of the KMP SDK even for `testDebugUnitTest`: going through
 * `createJellyfin {}` would require a `Context` (see docs/claude-code-reference.md).
 */
class TestBackend : JellyfinBackend {
    private val factory = OkHttpFactory()

    override fun discoverServers(): Flow<ServerDiscoveryInfo> = emptyFlow()

    override fun apiClient(baseUrl: String, accessToken: String?): ApiClient = factory.create(
        baseUrl = baseUrl,
        accessToken = accessToken,
        clientInfo = ClientInfo("JellyWrist-test", "0.1.0"),
        deviceInfo = DeviceInfo("test", "test"),
        httpClientOptions = HttpClientOptions(),
        socketConnectionFactory = factory,
    )
}

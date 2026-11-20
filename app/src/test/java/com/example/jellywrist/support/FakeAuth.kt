package com.example.jellywrist.support

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.auth.SessionLoadResult
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.settings.SyncSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.HttpClientOptions
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo

/**
 * Authentication fakes shared by `:app` JVM tests.
 *
 * Common to the companion coordinator and the onboarding screen: both observe the same
 * session, and a state persistence test must be able to wire them to the same [TokenStore].
 */

/** In-memory token store to test persistence without Android Keystore. */
class FakeTokenStore : TokenStore {
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

/** Minimal in-memory settings required for provisioning. */
class FakeSettingsRepository : SettingsRepository {
    private val current = MutableStateFlow(SyncSettings.DEFAULT)

    override val settings: Flow<SyncSettings> = current

    override suspend fun setQuotaBytes(bytes: Long) {
        current.value = current.value.copy(quotaBytes = bytes)
    }

    override suspend fun setRequiresCharging(required: Boolean) {
        current.value = current.value.copy(requiresCharging = required)
    }

    override suspend fun setUnmeteredOnly(unmetered: Boolean) {
        current.value = current.value.copy(unmeteredOnly = unmetered)
    }

    override suspend fun setPeriodicityHours(hours: Int) {
        current.value = current.value.copy(periodicityHours = hours)
    }

    override suspend fun setPinnedCertificate(sha256: String?) {
        current.value = current.value.copy(pinnedCertificateSha256 = sha256)
    }

    override suspend fun clear() {
        current.value = SyncSettings.DEFAULT
    }
}

/** JVM backend: builds an [ApiClient] without depending on an [android.content.Context]. */
class OkHttpJellyfinAuthBackend : JellyfinBackend {
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

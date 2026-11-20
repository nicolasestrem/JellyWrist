package com.example.jellywrist.data.jellyfin

import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.ServerDiscoveryInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access port to the Jellyfin SDK: local discovery (UDP 7359 broadcast) and creation of
 * typed HTTP clients ([ApiClient]) bound to a base URL.
 *
 * Deliberately abstracted: makes it possible to test
 * [com.example.jellywrist.data.repo.ServerRepository] on the JVM without building the
 * [Jellyfin] instance — the Android variant of the SDK requires a `Context` in its builder,
 * unavailable in a pure unit test (and Robolectric would be risky on AGP 9 / compileSdk 37).
 * Tests provide an impl building the [ApiClient] via `OkHttpFactory` directly.
 *
 * PLAN.md §3.1 + §11 (`data/jellyfin/` = wrapper for the official SDK).
 */
interface JellyfinBackend {
    /** Servers detected on the LAN (cold Flow ending at the SDK's timeout/limit). */
    fun discoverServers(): Flow<ServerDiscoveryInfo>

    /**
     * Creates an [ApiClient] pointing to [baseUrl] (URL already normalized by [UrlNormalizer]).
     *
     * [accessToken] is required by any authenticated call (library, playlists, images);
     * `null` is only suitable for public endpoints — `/System/Info/Public` and the Quick
     * Connect flow, which precisely precede obtaining a token.
     */
    fun apiClient(baseUrl: String, accessToken: String? = null): ApiClient
}

/**
 * Production implementation: delegates to the [Jellyfin] instance provided by Hilt.
 *
 * [dagger.Lazy]: building [Jellyfin] builds the shared `OkHttpClient`, whose TLS
 * trust-index construction alone costs hundreds of milliseconds (StrictMode-verified).
 * Repositories inject this backend from Activities and the Application — the SDK must
 * only materialize on the first actual discovery or API call, always in a coroutine.
 */
@Singleton
class JellyfinBackendImpl @Inject constructor(
    private val jellyfin: dagger.Lazy<Jellyfin>,
) : JellyfinBackend {
    override fun discoverServers(): Flow<ServerDiscoveryInfo> =
        jellyfin.get().discovery.discoverLocalServers()

    override fun apiClient(baseUrl: String, accessToken: String?): ApiClient =
        jellyfin.get().createApi(baseUrl = baseUrl, accessToken = accessToken)
}

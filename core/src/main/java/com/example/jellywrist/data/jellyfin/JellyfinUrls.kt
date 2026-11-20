package com.example.jellywrist.data.jellyfin

import com.example.jellywrist.data.auth.AuthSession

/**
 * Construction of the Jellyfin URLs consumed outside the typed SDK.
 *
 * The SDK covers JSON calls; two binary streams deliberately bypass it because they are
 * consumed by components that require a `Uri`: the Media3 download (`DownloadRequest`)
 * and artwork loading by Coil. These paths are therefore the only places where a Jellyfin
 * URL is assembled by hand — hence their centralization here.
 */
object JellyfinUrls {

    /**
     * Original audio file, **resumable via Range** (HTTP 206) and requiring no `Download`
     * permission — DEC-003 / REQ-003. Never replace with `/Audio/{id}/universal`: the
     * transcoded output returns `Accept-Ranges: none`, which breaks resuming.
     */
    fun trackFile(baseUrl: String, trackId: String): String =
        "${baseUrl.trimEnd('/')}/Items/$trackId/File"

    /**
     * Artwork resized server-side.
     *
     * The watch screen is ~466 px: requesting the full-resolution source would waste
     * Wi-Fi bandwidth and disk cache (PLAN.md §3.3). [tag] is included to invalidate
     * the cache when the artwork changes server-side.
     */
    fun primaryImage(baseUrl: String, itemId: String, tag: String? = null, fillWidth: Int = 200): String {
        val query = buildString {
            append("?fillWidth=").append(fillWidth)
            if (tag != null) append("&tag=").append(tag)
        }
        return "${baseUrl.trimEnd('/')}/Items/$itemId/Images/Primary$query"
    }

    /**
     * Jellyfin authentication header.
     *
     * The `MediaBrowser` scheme is the canonical form accepted by all supported versions;
     * the token never appears as a URL parameter, which prevents it from leaking into the
     * server's access logs or into a cache key (REQ-005).
     *
     * [device] distinguishes the two hosts in the Jellyfin sessions dashboard: the watch
     * and the phone companion share the same token but not the same device.
     */
    fun authorizationHeader(
        session: AuthSession,
        clientVersion: String,
        device: String = DEVICE_WATCH,
    ): String =
        """MediaBrowser Token="${session.accessToken}", Client="JellyWrist", Device="$device", DeviceId="${session.userId}", Version="$clientVersion""""

    const val HEADER_AUTHORIZATION = "Authorization"

    const val DEVICE_WATCH = "Wear OS"

    const val DEVICE_PHONE = "Android Phone"
}

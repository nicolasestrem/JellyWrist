package com.example.jellywrist.download

import androidx.core.net.toUri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadRequest.Builder
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.jellyfin.JellyfinUrls

/**
 * Construction of [DownloadRequest]s for the Media3 engine.
 *
 * Each request points to `/Items/{id}/File` (DEC-003 / REQ-003) with
 * `customCacheKey = trackId` so the cache is addressable independently of the URL
 * (PLAN.md §8, task 6). The token is **never** placed in URL parameters — it is injected
 * as a header by [com.example.jellywrist.data.net.JellyDataSource] on each connection
 * open (REQ-005).
 *
 * This class does not depend on the Media3 [DownloadManager]: it only builds requests.
 * It still requires an instrumented test (`androidTest`) because of `android.net.Uri`
 * (the `android.jar` stub is not runnable on pure JVM).
 */
@javax.inject.Singleton
class DownloadEnqueuer @javax.inject.Inject constructor() {

    /**
     * Builds a [DownloadRequest] for a given track.
     *
     * @param track Entity of the track to download.
     * @param baseUrl Base URL of the Jellyfin server (from the current session).
     * @return The request ready to be passed to [DownloadManager].
     */
    fun buildRequest(track: TrackEntity, baseUrl: String): DownloadRequest =
        Builder(track.id, JellyfinUrls.trackFile(baseUrl, track.id).toUri())
            .setCustomCacheKey(track.id)
            .build()
}

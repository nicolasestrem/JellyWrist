package com.example.jellywrist.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.jellyfin.JellyfinUrls

/**
 * Conversion [TrackEntity] → [MediaItem] (PLAN.md §8 task 9).
 *
 * **Only `READY` tracks are convertible**: the playback source is cache-only
 * (no streaming in v1, PLAN.md §1.2) and any playback of a non-downloaded track would
 * fail by construction. [playableQueue] applies this filter — it is the sole
 * playback-queue factory in the app.
 *
 * `customCacheKey = trackId` must be identical to the key used at download time
 * ([com.example.jellywrist.download.DownloadEnqueuer]): it is what addresses the bytes
 * in the shared `SimpleCache`, independently of the URL.
 */
object MediaItems {

    /**
     * Pure filter: playable tracks (`READY`), order preserved. Separated from [MediaItem]
     * construction (which touches `android.net.Uri`, not runnable in JVM tests) to stay
     * unit-testable (REQ-011).
     */
    fun playableTracks(tracks: List<TrackEntity>): List<TrackEntity> =
        tracks.filter { it.state == TrackState.READY }

    /** Playable playback queue: filters to `READY` tracks, order preserved. */
    fun playableQueue(tracks: List<TrackEntity>, baseUrl: String): List<MediaItem> =
        playableTracks(tracks).map { toMediaItem(it, baseUrl) }

    /** Converts a `READY` track. The caller guarantees the state — see [playableQueue]. */
    fun toMediaItem(track: TrackEntity, baseUrl: String): MediaItem {
        val artworkUri = track.imageTag?.let {
            JellyfinUrls.primaryImage(baseUrl, track.albumId ?: track.id, it).toUri()
        }
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(JellyfinUrls.trackFile(baseUrl, track.id).toUri())
            .setCustomCacheKey(track.id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(artworkUri)
                    .setTrackNumber(track.indexNumber)
                    .build(),
            )
            .build()
    }
}

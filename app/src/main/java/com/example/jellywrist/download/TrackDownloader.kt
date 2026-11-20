package com.example.jellywrist.download

import com.example.jellywrist.data.db.TrackState
import kotlinx.coroutines.flow.Flow

/**
 * Progress of a download, as observed by the UI.
 *
 * [percent] is `-1f` when the total size is not yet known: Media3 reports indeterminate
 * progress this way, and inventing a value would cause a progress bar to move backwards.
 */
data class DownloadProgress(
    val trackId: String,
    val percent: Float,
    val bytesDownloaded: Long,
    val state: TrackState,
)

/**
 * Download queue port.
 *
 * The sync engine decides **what** to download; this port knows **how**. The boundary
 * exists so the diff and budget logic (pure functions, tested on JVM) has no dependency
 * on Media3 or on a `Context`.
 *
 * Implementation contract: bytes are held by the Media3 cache and addressed by
 * `customCacheKey = trackId`; state is reflected in
 * [com.example.jellywrist.data.db.TrackEntity.state]. Calls are idempotent — re-enqueuing
 * an already-ready track must not re-download anything, and removing an absent track must not
 * fail (the sync engine replays its decisions on each pass).
 */
interface TrackDownloader {

    /** Enqueues [trackIds] against `/Items/{id}/File`, Range-resumable (REQ-003). */
    suspend fun enqueue(trackIds: List<String>)

    /** Removes from the queue and releases cached bytes for tracks that have become orphans. */
    suspend fun remove(trackIds: List<String>)

    /** Progress of all non-terminal tracks. */
    fun observeProgress(): Flow<List<DownloadProgress>>

    /** Bytes actually occupied by the download cache (storage gauge). */
    suspend fun usedBytes(): Long

    /** `true` while tracks remain to download — a queue a manual run still owns. */
    suspend fun hasOutstandingWork(): Boolean

    /**
     * Pauses the queue without losing state: used when the network becomes metered or
     * battery requires it. Resumption resumes from the last obtained byte (DEC-005).
     */
    suspend fun pauseAll()

    suspend fun resumeAll()
}

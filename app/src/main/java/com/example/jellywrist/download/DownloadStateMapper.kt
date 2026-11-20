package com.example.jellywrist.download

import androidx.media3.exoplayer.offline.Download
import com.example.jellywrist.data.db.TrackState

/**
 * Mapping of Media3 [Download.STATE_*] states to our [TrackState] values (PLAN.md §8, task 6).
 *
 * The mapping:
 * - `STATE_DOWNLOADING` / `STATE_RESTARTING` → [TrackState.DOWNLOADING]
 * - `STATE_COMPLETED` → **never** passes through here because [CodecSupport] is applied first
 * - `STATE_FAILED` → [TrackState.ERROR]
 * - Any other state (notably `STATE_STOPPED` after a `remove`) is ignored — the track
 *   retains its database state until an explicit sync-engine action.
 *
 * This mapper is a pure function: it can be tested on JVM without a Media3 mock.
 */
object DownloadStateMapper {

    /**
     * Translates a Media3 [Download] state into a [TrackState].
     *
     * @param media3State [Download.state] (one of the `Download.STATE_*` constants).
     * @param downloadedBytes Bytes already received (from [Download.getBytesDownloaded]).
     * @param errorMessage Optional error message (from [Download.failureReason]).
     * @return The corresponding [TrackState], or `null` if the Media3 state does not
     *   justify a database update (e.g. `STATE_STOPPED` following a remove).
     */
    fun toTrackState(
        media3State: Int,
        downloadedBytes: Long = 0L,
        errorMessage: String? = null,
    ): TrackState? = when (media3State) {
        Download.STATE_DOWNLOADING,
        Download.STATE_RESTARTING,
        -> TrackState.DOWNLOADING

        Download.STATE_FAILED -> TrackState.ERROR

        // STATE_COMPLETED is not mapped here: completion goes through
        // CodecSupport.finalStateForCodec to decide READY vs UNSUPPORTED.
        // STATE_STOPPED / STATE_QUEUED: no database change.
        else -> null
    }

    /**
     * Progress percentage, or `-1f` if the total size is unknown.
     *
     * Conforms to the [DownloadProgress.percent] contract (PLAN.md §8).
     */
    fun toPercent(downloadedBytes: Long, totalBytes: Long): Float =
        if (totalBytes <= 0L) -1f else (downloadedBytes.toFloat() / totalBytes.toFloat()) * 100f
}

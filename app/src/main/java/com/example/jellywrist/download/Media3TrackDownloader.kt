package com.example.jellywrist.download

import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.util.SyncTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Media3 implementation of [TrackDownloader] (PLAN.md §8 task 6).
 *
 * This class owns **observation**: it holds the [DownloadManager] listener, mirrors each
 * transition into Room and publishes progress. Queue *mutations* are delegated to
 * [DownloadQueueController], whose policy is JVM-tested — they used to run through
 * [androidx.media3.exoplayer.offline.DownloadService]'s static intents, every one of which
 * starts the service and can therefore be refused outright by Android when the call comes
 * from the sync worker.
 *
 * State is reflected into Room ([TrackDao]) on each transition: it is Room that the UI and
 * the sync engine consult, never Media3 directly. At the end of a download, the codec decides
 * the final state: `READY` if decodable, `UNSUPPORTED` otherwise (DEC-003/DEC-004).
 */
@Singleton
class Media3TrackDownloader @Inject constructor(
    private val downloadManager: DownloadManager,
    private val cache: Cache,
    private val trackDao: TrackDao,
    private val controller: DownloadQueueController,
    private val codecSupport: CodecSupport,
) : TrackDownloader {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Progress visible to the UI, updated by the [DownloadManager] listener. */
    private val _progress = MutableStateFlow<List<DownloadProgress>>(emptyList())
    override fun observeProgress(): StateFlow<List<DownloadProgress>> = _progress.asStateFlow()

    override suspend fun hasOutstandingWork(): Boolean = trackDao.outstandingCount() > 0

    init {
        // Listener registered at singleton creation: also catches downloads restored
        // after process death (DownloadManager reloads its persistent index).
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(
                downloadManager: DownloadManager,
                download: Download,
                finalException: Exception?,
            ) = handleDownloadUpdate(download, finalException)

            override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) =
                updateProgressSnapshot()

            override fun onIdle(downloadManager: DownloadManager) = updateProgressSnapshot()

            override fun onInitialized(downloadManager: DownloadManager) = updateProgressSnapshot()
        })
    }

    override suspend fun enqueue(trackIds: List<String>) {
        controller.enqueue(trackIds)
    }

    override suspend fun remove(trackIds: List<String>) {
        controller.remove(trackIds)
    }

    /** Bytes actually occupied by the cache (partials included) — the gauge must tell the truth. */
    override suspend fun usedBytes(): Long = withContext(Dispatchers.IO) { cache.cacheSpace }

    override suspend fun pauseAll() {
        // GAP-008: NetworkAwareness emits its first network decision before the UI comes to
        // the foreground on a cold start, and the pause used to travel as a service intent —
        // a background `startService` that Android 12+ rejects, killing the process before
        // the first frame. Pausing the manager directly cannot be refused at all.
        controller.pause()
    }

    override suspend fun resumeAll() {
        controller.resume()
    }

    /**
     * Reflects a Media3 transition into Room.
     *
     * Media3 callback (manager's main thread): Room work is dispatched to the IO scope.
     */
    private fun handleDownloadUpdate(download: Download, finalException: Exception?) {
        // Snapshot on the caller's thread — the manager's application thread — before
        // dispatching the Room work; see updateProgressSnapshot.
        updateProgressSnapshot()
        SyncTrace.i(
            "media3 track=${download.request.id.take(8)} state=${download.state} " +
                "bytes=${download.bytesDownloaded}" +
                (finalException?.let { " error=${it.javaClass.simpleName}" } ?: ""),
        )
        scope.launch {
            when (download.state) {
                Download.STATE_COMPLETED -> {
                    val track = trackDao.byId(download.request.id) ?: return@launch
                    // Size check: if Content-Length does not match, the file is truncated
                    // (server dropped, Jellyfin bug, etc.). Marking READY on an incomplete
                    // track would make it unplayable and the diff would skip it forever
                    // (bot review bug #9, round 2).
                    val expectedBytes = track.sizeBytes
                    if (expectedBytes > 0L && download.bytesDownloaded < expectedBytes) {
                        trackDao.updateProgress(
                            id = track.id,
                            state = TrackState.ERROR,
                            downloadedBytes = download.bytesDownloaded,
                            error = "Received size ${download.bytesDownloaded}/${expectedBytes}",
                        )
                    } else {
                        trackDao.updateProgress(
                            id = track.id,
                            state = codecSupport.finalStateForCodec(track.codec),
                            downloadedBytes = download.bytesDownloaded,
                            error = null,
                        )
                    }
                }
                Download.STATE_FAILED -> trackDao.updateProgress(
                    id = download.request.id,
                    state = TrackState.ERROR,
                    downloadedBytes = download.bytesDownloaded,
                    error = finalException?.message ?: "Download failed",
                )

                Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> trackDao.updateProgress(
                    id = download.request.id,
                    state = TrackState.DOWNLOADING,
                    downloadedBytes = download.bytesDownloaded,
                    error = null,
                )

                else -> Unit // QUEUED/REMOVING/STOPPED: no Room transition to reflect.
            }
        }
    }

    /**
     * Rebuilds the progress snapshot for the UI from the manager's current state.
     *
     * Must run on the [DownloadManager]'s application thread — all call sites are its
     * listener callbacks, which Media3 delivers there. `currentDownloads` is
     * thread-confined: it used to be read from a [Dispatchers.IO] coroutine, an
     * unsynchronized cross-thread access to the manager's internal list. Publishing the
     * mapped snapshot through [MutableStateFlow.value] is thread-safe.
     */
    private fun updateProgressSnapshot() {
        _progress.value = downloadManager.currentDownloads
            .filter { it.state != Download.STATE_COMPLETED }
            .map { download ->
                DownloadProgress(
                    trackId = download.request.id,
                    percent = DownloadStateMapper.toPercent(
                        downloadedBytes = download.bytesDownloaded,
                        totalBytes = download.contentLength,
                    ),
                    bytesDownloaded = download.bytesDownloaded,
                    state = DownloadStateMapper.toTrackState(download.state)
                        ?: TrackState.TO_DOWNLOAD,
                )
            }
    }
}

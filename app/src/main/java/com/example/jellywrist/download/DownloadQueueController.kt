package com.example.jellywrist.download

import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.util.SyncTrace
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of an enqueue request.
 *
 * [serviceRunning] is `false` when the tracks are safely in the download index but Android
 * refused to start the foreground service. That is the difference between "nothing was
 * queued" and "everything is queued and waiting for the app to come forward" — a distinction
 * the UI must be able to state, instead of showing a queue that never moves.
 */
data class EnqueueResult(val enqueued: Int, val serviceRunning: Boolean)

/**
 * Enqueue policy for the download queue.
 *
 * Split out of [Media3TrackDownloader] so it can be exercised on the JVM. What it protects is
 * the failure that stopped the watch downloading at all: the queue mutation used to *be* a
 * foreground-service start, so a refusal inside the sync worker threw, aborted
 * [com.example.jellywrist.sync.SyncEngine]'s pass before a single request was registered, and
 * left WorkManager retrying on an hour-long backoff with nothing to show for it.
 *
 * Here the durable write happens first and unconditionally; the service start is best effort
 * and reported, never fatal.
 */
@Singleton
class DownloadQueueController @Inject constructor(
    private val queue: DownloadQueue,
    private val trackDao: TrackDao,
    private val authRepository: AuthRepository,
) {

    suspend fun enqueue(trackIds: List<String>): EnqueueResult {
        val session = authRepository.currentSession() ?: run {
            SyncTrace.w("enqueue aborted: no authenticated session (requested=${trackIds.size})")
            return EnqueueResult(enqueued = 0, serviceRunning = true)
        }

        var enqueued = 0
        for (track in trackDao.byIds(trackIds)) {
            // Idempotence: an already-downloaded track (playable or not) is not re-enqueued.
            if (track.state == TrackState.READY || track.state == TrackState.UNSUPPORTED) continue
            queue.add(track, session.baseUrl)
            trackDao.updateProgress(
                id = track.id,
                state = TrackState.TO_DOWNLOAD,
                downloadedBytes = track.downloadedBytes,
                error = null,
            )
            enqueued++
        }

        if (enqueued == 0) return EnqueueResult(enqueued = 0, serviceRunning = true)

        val serviceRunning = runCatching { queue.startForegroundService() }
            .onFailure { SyncTrace.w("download service refused to start from this process state", it) }
            .isSuccess
        SyncTrace.i(
            "enqueue done requested=${trackIds.size} added=$enqueued serviceRunning=$serviceRunning",
        )
        return EnqueueResult(enqueued = enqueued, serviceRunning = serviceRunning)
    }

    suspend fun remove(trackIds: List<String>) {
        for (trackId in trackIds) {
            runCatching { queue.remove(trackId) }
                .onFailure { SyncTrace.w("removal refused for track=${trackId.take(8)}", it) }
            // Reset Room state immediately — without this the row stays READY and the next
            // diff jumps over it. The removal event only arrives after async processing, so
            // skipping this would leave a track shown as ready pointing at absent bytes.
            trackDao.updateProgress(trackId, TrackState.TO_DOWNLOAD, downloadedBytes = 0, error = null)
        }
    }

    suspend fun pause() {
        runCatching { queue.pause() }
            .onSuccess { SyncTrace.i("queue paused") }
            .onFailure { SyncTrace.w("pause failed", it) }
    }

    /**
     * Resumes the queue and asks for the service back.
     *
     * Resuming only clears the manager's paused flag; with no running service nothing
     * progresses, and the service may well be gone (FGS cap, process death). The start is best
     * effort for the same reason as in [enqueue].
     */
    suspend fun resume(): Boolean {
        runCatching { queue.resume() }
            .onFailure { SyncTrace.w("resume failed", it) }
        return runCatching { queue.startForegroundService() }
            .onFailure { SyncTrace.w("download service refused to start on resume", it) }
            .isSuccess
    }
}

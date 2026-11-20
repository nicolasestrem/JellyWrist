package com.example.jellywrist.download

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import com.example.jellywrist.data.db.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The Media3/Android side of the download queue.
 *
 * Separated from the enqueue *policy* for the same reason as [TrackDownloader]: the policy
 * decides which tracks belong in the queue and how a platform refusal is handled, and that is
 * worth testing on the JVM — but building a `DownloadRequest` needs `android.net.Uri` and a
 * real [DownloadManager], neither of which runs there (see [DownloadEnqueuer]).
 *
 * The split also encodes Media3's own contract, which the previous code inverted:
 * **adding to the download index is the durable act, starting the service is best effort.**
 * [add] and [remove] go straight to the [DownloadManager], which cannot be refused by the
 * platform; [startForegroundService] is the one call Android may reject, and it is allowed to
 * throw so the caller can decide what that means.
 *
 * Every method is `suspend` because the manager-backed ones must run on the manager's own
 * looper (see [Media3DownloadQueue]). [startForegroundService] needs no such hop; it keeps the
 * same shape so a caller can sequence it after an enqueue inside one coroutine.
 */
interface DownloadQueue {

    /** Registers [track] in the persistent Media3 download index. Never refused. */
    suspend fun add(track: TrackEntity, baseUrl: String)

    /** Drops [trackId] from the index and releases its cached bytes. */
    suspend fun remove(trackId: String)

    /**
     * Brings the download service to the foreground so queued work actually progresses.
     *
     * Throws `IllegalStateException` when Android forbids a foreground-service start from the
     * current process state — the caller must treat that as a reportable condition, not as a
     * failure of the enqueue.
     */
    suspend fun startForegroundService()

    /** Pauses the queue without losing progress; resumption resumes by Range (DEC-005). */
    suspend fun pause()

    suspend fun resume()

    /** `true` while the persistent index still holds downloads that have not completed. */
    suspend fun hasPendingDownloads(): Boolean
}

/**
 * Media3 implementation of [DownloadQueue].
 *
 * Every mutation goes through the injected [DownloadManager] — the same singleton
 * [JellyDownloadService] uses — rather than through the service's static intents. Those
 * intents were the problem: each one starts the service, and `sendAddDownload(foreground =
 * true)` is an unguarded `startForegroundService()`. Called from the sync worker, Android 12+
 * answers with `ForegroundServiceStartNotAllowedException`. Media3 itself catches that
 * exception in `DownloadManagerHelper.restartService()` and merely logs it, precisely because
 * the download survives in the index and its `Scheduler` — here [JellyDownloadService]'s
 * `WorkManagerScheduler` — brings the service back once requirements are met.
 *
 * **Thread confinement.** `DownloadManager`'s class documentation states that an instance
 * "must be accessed only from the thread that created it", exposed as
 * [DownloadManager.getApplicationLooper]. Nothing enforces that at runtime: there is no
 * thread assertion on any public method, `pendingMessages` is incremented without
 * synchronisation, and `currentDownloads` hands back its live list field. So an off-thread
 * call does not fail — it races, and a read answers with whatever list was last published to
 * that thread. Our callers are the sync worker and the application scope, WorkManager and
 * `Dispatchers.Default` threads the manager never saw, so every manager call here hops onto
 * its looper first via [onApplicationLooper]. That silence is why this went unnoticed: the
 * pending-download count was read from the wrong thread and its answer was simply trusted.
 */
@Singleton
class Media3DownloadQueue @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloadManager: DownloadManager,
    private val enqueuer: DownloadEnqueuer,
) : DownloadQueue {

    private val applicationHandler: Handler by lazy { Handler(downloadManager.applicationLooper) }

    override suspend fun add(track: TrackEntity, baseUrl: String) = onApplicationLooper {
        downloadManager.addDownload(enqueuer.buildRequest(track, baseUrl))
    }

    override suspend fun remove(trackId: String) = onApplicationLooper {
        // Idempotent by construction: an unknown id is a no-op for the manager.
        downloadManager.removeDownload(trackId)
    }

    override suspend fun startForegroundService() {
        // A plain Context call: no manager state is touched, so no looper hop is needed.
        DownloadService.startForeground(context, JellyDownloadService::class.java)
    }

    override suspend fun pause() = onApplicationLooper { downloadManager.pauseDownloads() }

    override suspend fun resume() = onApplicationLooper { downloadManager.resumeDownloads() }

    override suspend fun hasPendingDownloads(): Boolean = onApplicationLooper {
        downloadManager.currentDownloads.any { it.state != Download.STATE_COMPLETED }
    }

    /**
     * Runs [block] on the manager's application looper and returns its result.
     *
     * Runs inline when already on that thread (Media3 listener callbacks; in practice the main
     * thread), otherwise posts and suspends until the block has run. A failure inside the block
     * is rethrown to the caller, exactly as a direct call would have.
     */
    private suspend fun <T> onApplicationLooper(block: () -> T): T {
        if (Looper.myLooper() == downloadManager.applicationLooper) return block()
        return suspendCancellableCoroutine { continuation ->
            val posted = applicationHandler.post { continuation.resumeWith(runCatching(block)) }
            if (!posted) {
                continuation.resumeWithException(
                    IllegalStateException("DownloadManager application looper has quit"),
                )
            }
        }
    }
}

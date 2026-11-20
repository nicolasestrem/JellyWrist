package com.example.jellywrist.download

import com.example.jellywrist.util.SyncTrace
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings a stalled download queue back to life when the app becomes visible.
 *
 * Android only reliably permits a foreground-service start while the app is on screen, so a
 * sync that ran as background work can legitimately end with downloads registered in Media3's
 * index and no service running to move them — Media3's own `DownloadManagerHelper` gives up
 * the same way, logging the refusal and waiting. Nothing else would then restart the queue
 * until requirements changed and the `Scheduler` fired, which on a watch can be hours.
 *
 * Opening the app is precisely the moment the platform allows, so that is when this checks.
 *
 * The queue is injected as [dagger.Lazy] on purpose. This object is a field of `MainActivity`,
 * so Hilt resolves its constructor during activity creation on the main thread; a direct
 * [DownloadQueue] would drag `DownloadManager`, `SimpleCache`, its database and the TLS-aware
 * upstream into that moment on every launch, signed-out users included — the cold-start cost
 * the application class deliberately keeps off the main thread. `get()` runs inside the
 * launched coroutine instead.
 */
@Singleton
class DownloadResumer @Inject constructor(
    private val queue: dagger.Lazy<DownloadQueue>,
    private val appScope: CoroutineScope,
) {

    /** Starts the download service if, and only if, work is still outstanding. */
    fun resumeIfPending() {
        appScope.launch {
            val queue = queue.get()
            val pending = runCatching { queue.hasPendingDownloads() }
                .onFailure { SyncTrace.w("could not read the download index", it) }
                .getOrDefault(false)
            if (!pending) return@launch
            val started = runCatching { queue.startForegroundService() }
                .onFailure { SyncTrace.w("foreground resume refused even from the activity", it) }
                .isSuccess
            SyncTrace.i("foreground resume pending=true serviceStarted=$started")
        }
    }
}

package com.example.jellywrist.download

import com.example.jellywrist.data.db.TrackEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [DownloadResumer] — the foreground recovery of a stalled queue.
 *
 * The pending-work check used to read `DownloadManager.currentDownloads` from a
 * `Dispatchers.Default` coroutine. Media3 documents that list as confined to the manager's
 * application thread and enforces nothing, so the read did not throw — it returned whatever
 * list had last been published to that thread, and the recovery decided whether to start the
 * service from an answer it had no right to trust. Reading now goes through the
 * [DownloadQueue] port, whose Media3 implementation marshals onto the right looper.
 *
 * The port is also what makes the decision testable at all: `DownloadManager` cannot be built
 * on the JVM, so before this split there was nothing here to assert against.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadResumerTest {

    private class RecordingQueue(private val pending: Boolean) : DownloadQueue {
        var startCalls = 0
        var serviceStartFailure: Throwable? = null

        override suspend fun add(track: TrackEntity, baseUrl: String) = Unit
        override suspend fun remove(trackId: String) = Unit
        override suspend fun pause() = Unit
        override suspend fun resume() = Unit
        override suspend fun hasPendingDownloads(): Boolean = pending

        override suspend fun startForegroundService() {
            startCalls++
            serviceStartFailure?.let { throw it }
        }
    }

    private fun resumer(queue: DownloadQueue, scope: TestScope) =
        DownloadResumer(queue = dagger.Lazy { queue }, appScope = scope)

    @Test
    fun `pending work brings the service back when the app opens`() = runTest(StandardTestDispatcher()) {
        val queue = RecordingQueue(pending = true)

        resumer(queue, this).resumeIfPending()
        advanceUntilIdle()

        assertEquals(1, queue.startCalls)
    }

    @Test
    fun `an empty queue does not start a foreground service`() = runTest(StandardTestDispatcher()) {
        val queue = RecordingQueue(pending = false)

        resumer(queue, this).resumeIfPending()
        advanceUntilIdle()

        assertEquals(0, queue.startCalls)
    }

    @Test
    fun `a refused start is logged, not thrown`() = runTest(StandardTestDispatcher()) {
        val queue = RecordingQueue(pending = true).apply {
            serviceStartFailure = IllegalStateException("not allowed from background")
        }

        resumer(queue, this).resumeIfPending()
        advanceUntilIdle()

        assertEquals("the attempt was made and did not crash the caller", 1, queue.startCalls)
    }
}

package com.example.jellywrist.download

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.support.FakeTokenStore
import com.example.jellywrist.support.FakeTrackDao
import com.example.jellywrist.support.OkHttpJellyfinAuthBackend
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for [DownloadQueueController] — the enqueue policy, isolated from Media3.
 *
 * The behaviour under test is the one that silently stopped the watch downloading: the
 * queue mutation used to *be* a foreground-service start, so when Android refused that
 * start from the WorkManager worker the exception escaped, aborted the whole sync pass and
 * left nothing in the download index. Media3's own `DownloadManagerHelper.restartService`
 * catches exactly this `IllegalStateException` and carries on — the durable act is adding
 * to the index, and starting the service is best effort.
 */
class DownloadQueueControllerTest {

    private lateinit var trackDao: FakeTrackDao
    private lateinit var queue: RecordingQueue
    private lateinit var controller: DownloadQueueController

    private val session = AuthSession(
        baseUrl = "http://server.example:8096",
        accessToken = "token",
        userId = "u1",
        userName = "tester",
        serverId = "s1",
    )

    @Before
    fun setUp() {
        trackDao = FakeTrackDao()
        queue = RecordingQueue()
        val tokenStore = FakeTokenStore().apply { save(session) }
        controller = DownloadQueueController(
            queue = queue,
            trackDao = trackDao,
            authRepository = AuthRepository(OkHttpJellyfinAuthBackend(), tokenStore),
        )
    }

    private fun track(id: String, state: TrackState) =
        TrackEntity(id = id, title = id, state = state, sizeBytes = 10L)

    @Test
    fun `a refused foreground service start still leaves the tracks in the download index`() = runTest {
        trackDao.upsertAll(listOf(track("t1", TrackState.TO_DOWNLOAD), track("t2", TrackState.TO_DOWNLOAD)))
        queue.serviceStartFailure = IllegalStateException("Not allowed to start service: app is in background")

        val result = controller.enqueue(listOf("t1", "t2"))

        assertEquals("both tracks reach the durable index", listOf("t1", "t2"), queue.added)
        assertEquals(2, result.enqueued)
        assertFalse("the platform refusal is reported, not thrown", result.serviceRunning)
        assertEquals(TrackState.TO_DOWNLOAD, trackDao.rows["t1"]?.state)
    }

    @Test
    fun `already downloaded tracks are not re-enqueued`() = runTest {
        trackDao.upsertAll(
            listOf(
                track("ready", TrackState.READY),
                track("unsupported", TrackState.UNSUPPORTED),
                track("pending", TrackState.TO_DOWNLOAD),
            ),
        )

        val result = controller.enqueue(listOf("ready", "unsupported", "pending"))

        assertEquals(listOf("pending"), queue.added)
        assertEquals(1, result.enqueued)
    }

    @Test
    fun `a successful pass reports the service as running`() = runTest {
        trackDao.upsertAll(listOf(track("t1", TrackState.TO_DOWNLOAD)))

        val result = controller.enqueue(listOf("t1"))

        assertTrue(result.serviceRunning)
        assertEquals(1, queue.startServiceCalls)
    }

    @Test
    fun `nothing to enqueue does not start the service`() = runTest {
        trackDao.upsertAll(listOf(track("ready", TrackState.READY)))

        val result = controller.enqueue(listOf("ready"))

        assertEquals(0, result.enqueued)
        assertEquals("no queue, no foreground service", 0, queue.startServiceCalls)
        assertTrue(result.serviceRunning)
    }

    @Test
    fun `a refused removal does not throw and still resets Room state`() = runTest {
        trackDao.upsertAll(listOf(track("t1", TrackState.READY)))
        queue.removeFailure = IllegalStateException("background start not allowed")

        controller.remove(listOf("t1"))

        assertEquals(TrackState.TO_DOWNLOAD, trackDao.rows["t1"]?.state)
    }

    /** In-memory stand-in for the Media3 side of the queue. */
    private class RecordingQueue : DownloadQueue {
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var startServiceCalls = 0
        var serviceStartFailure: Throwable? = null
        var removeFailure: Throwable? = null

        override suspend fun add(track: TrackEntity, baseUrl: String) {
            added.add(track.id)
        }

        override suspend fun remove(trackId: String) {
            removeFailure?.let { throw it }
            removed.add(trackId)
        }

        override suspend fun startForegroundService() {
            startServiceCalls++
            serviceStartFailure?.let { throw it }
        }

        override suspend fun pause() = Unit

        override suspend fun resume() = Unit

        override suspend fun hasPendingDownloads(): Boolean = added.isNotEmpty()
    }
}

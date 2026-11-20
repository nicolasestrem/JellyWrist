package com.example.jellywrist.sync

import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.SyncOutcome
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.settings.SyncSettings
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.DownloadProgress
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.support.FakeCollectionDao
import com.example.jellywrist.support.FakeCollectionTrackDao
import com.example.jellywrist.support.FakeSyncLogDao
import com.example.jellywrist.support.FakeTrackDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * Orchestration tests for [SyncEngine] (PLAN.md §4.2) over in-memory doubles.
 *
 * Verifies pipeline order, budget → PENDING_BUDGET + PARTIAL translation, orphan
 * purge (bytes removed from the downloader, catalog kept for offline browsing),
 * and cancellation discipline (PAUSED logged then exception propagated — DEC-005).
 */
class SyncEngineTest {

    private lateinit var collectionDao: FakeCollectionDao
    private lateinit var trackDao: FakeTrackDao
    private lateinit var collectionTrackDao: FakeCollectionTrackDao
    private lateinit var syncLogDao: FakeSyncLogDao
    private lateinit var downloader: RecordingDownloader
    private lateinit var metadata: FakeMetadataSource

    private val session = AuthSession(
        baseUrl = "http://server:8096",
        accessToken = "token",
        userId = "u1",
        userName = "Nico",
        serverId = "s1",
    )

    @Before
    fun setUp() {
        collectionDao = FakeCollectionDao()
        trackDao = FakeTrackDao()
        collectionTrackDao = FakeCollectionTrackDao(collectionDao, trackDao)
        syncLogDao = FakeSyncLogDao()
        downloader = RecordingDownloader()
        metadata = FakeMetadataSource()
    }

    private fun engine(
        quota: Long = Long.MAX_VALUE / 4,
        disk: Long = Long.MAX_VALUE / 4,
    ) = SyncEngine(
        downloader = downloader,
        deviceStorage = object : DeviceStorage {
            override suspend fun availableBytes(): Long = disk

            override suspend fun totalBytes(): Long = disk
        },
        settingsRepository = fakeSettings(quota),
        metadataSource = metadata,
        collectionDao = collectionDao,
        trackDao = trackDao,
        collectionTrackDao = collectionTrackDao,
        syncLogDao = syncLogDao,
    )

    private suspend fun pinCollection(id: String, vararg tracks: SyncTrackMeta) {
        collectionDao.upsertAll(
            listOf(CollectionEntity(id = id, type = CollectionType.PLAYLIST, name = id, pinned = true)),
        )
        metadata.content[id] = tracks.toList()
    }

    @Test
    fun `a nominal pass enqueues new tracks and logs SUCCESS`() = runTest {
        pinCollection("c1", meta("t1"), meta("t2"))

        val outcome = engine().syncPass(session)

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("t1", "t2"), downloader.enqueued.flatten())
        assertEquals(SyncOutcome.SUCCESS, syncLogDao.entries.single().outcome)
        assertEquals(2, syncLogDao.entries.single().added)
    }

    @Test
    fun `insufficient budget produces PENDING_BUDGET and a PARTIAL log`() = runTest {
        pinCollection("c1", meta("t1", size = 100), meta("t2", size = 100))

        // Quota 150: t1 passes, t2 is deferred — never a silent failure (REQ-008).
        val outcome = engine(quota = 150, disk = 10L * 1024 * 1024 * 1024).syncPass(session)
        assertEquals(SyncOutcome.PARTIAL, outcome)
        assertEquals(listOf("t1"), downloader.enqueued.flatten())
        assertEquals(TrackState.PENDING_BUDGET, trackDao.rows["t2"]?.state)
        val log = syncLogDao.entries.single()
        assertEquals(SyncOutcome.PARTIAL, log.outcome)
        assertEquals(1, log.deferredForBudget)
    }

    @Test
    fun `an orphan is removed from the downloader but stays in the catalog`() = runTest {
        // t-orphan was being downloaded for c1; the server removed it from the playlist.
        pinCollection("c1", meta("t1"))
        trackDao.upsertAll(
            listOf(
                track("t-orphan", TrackState.READY, downloadedBytes = 999),
                track("t1", TrackState.READY),
            ),
        )
        collectionTrackDao.insertAll(
            listOf(
                com.example.jellywrist.data.db.CollectionTrackEntity("c1", "t-orphan", 0),
                com.example.jellywrist.data.db.CollectionTrackEntity("c1", "t1", 1),
            ),
        )

        engine().syncPass(session)

        assertEquals(listOf("t-orphan"), downloader.removed.flatten())
        // Byte purge, not catalog purge: offline browsing survives (REQ-006 + task 5).
        val purged = trackDao.rows["t-orphan"]
        assertTrue("the catalog row survives", purged != null)
        assertEquals(TrackState.TO_DOWNLOAD, purged?.state)
        assertEquals(0L, purged?.downloadedBytes)
    }

    @Test
    fun `one failing collection does not block the others`() = runTest {
        // A single unreachable or deleted collection used to abort the whole pass: the
        // fetch threw, the outer catch logged FAILED, and NOTHING was enqueued for any
        // other pinned collection. One bad playlist stopped the watch downloading at all.
        pinCollection("c-good", meta("t-good"))
        pinCollection("c-bad", meta("t-bad"))
        metadata.failures["c-bad"] = java.io.IOException("server said no")
        trackDao.upsertAll(listOf(track("t-known-bad", TrackState.READY, downloadedBytes = 999)))
        collectionTrackDao.insertAll(
            listOf(com.example.jellywrist.data.db.CollectionTrackEntity("c-bad", "t-known-bad", 0)),
        )

        val outcome = engine().syncPass(session)

        assertEquals("t-good is enqueued despite c-bad failing", listOf("t-good"), downloader.enqueued.flatten())
        assertEquals(SyncOutcome.PARTIAL, outcome)
        assertEquals(1, syncLogDao.entries.single().failed)
        assertTrue(
            "a collection we could not read is never purged",
            downloader.removed.flatten().none { it == "t-known-bad" },
        )
        assertEquals(TrackState.READY, trackDao.rows["t-known-bad"]?.state)
    }

    @Test
    fun `a READY track is never re-enqueued by a pass`() = runTest {
        pinCollection("c1", meta("t1"))
        trackDao.upsertAll(listOf(track("t1", TrackState.READY)))
        collectionTrackDao.insertAll(
            listOf(com.example.jellywrist.data.db.CollectionTrackEntity("c1", "t1", 0)),
        )

        engine().syncPass(session)

        assertTrue(downloader.enqueued.flatten().isEmpty())
    }

    @Test
    fun `no pinned collection logs SUCCESS without enqueueing anything`() = runTest {
        val outcome = engine().syncPass(session)
        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertTrue(downloader.enqueued.isEmpty())
    }

    @Test
    fun `a network error logs FAILED without throwing`() = runTest {
        pinCollection("c1", meta("t1"))
        metadata.failure = RuntimeException("network unreachable")

        val outcome = engine().syncPass(session)

        assertEquals(SyncOutcome.FAILED, outcome)
        assertEquals(SyncOutcome.FAILED, syncLogDao.entries.single().outcome)
    }

    @Test
    fun `cancellation logs PAUSED then propagates the exception`() = runTest {
        // DEC-005: the 6-hour FGS cap is not a failure — but the coroutine cancellation must
        // propagate, otherwise structured cancellation is broken.
        pinCollection("c1", meta("t1"))
        metadata.failure = CancellationException("FGS cap")

        try {
            engine().syncPass(session)
            fail("CancellationException must be propagated")
        } catch (_: CancellationException) {
            // expected
        }

        assertEquals(SyncOutcome.PAUSED, syncLogDao.entries.single().outcome)
    }

    @Test
    fun `synced collections receive a watermark`() = runTest {
        pinCollection("c1", meta("t1"), meta("t2"))

        engine().syncPass(session)

        assertEquals("t2", collectionDao.rows["c1"]?.reconWatermark)
        assertTrue((collectionDao.rows["c1"]?.lastSyncAtEpochMs ?: 0L) > 0L)
    }

    @Test
    fun `a local collection is reconciled by identity without a server fetch`() = runTest {
        // "500 random songs": no server counterpart. The engine must enqueue its
        // missing tracks WITHOUT calling the metadata port, and purge nothing.
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = "local-random",
                    type = CollectionType.PLAYLIST,
                    name = "500 random songs",
                    pinned = true,
                    localOnly = true,
                ),
            ),
        )
        trackDao.upsertAll(
            listOf(
                track("t-new", TrackState.TO_DOWNLOAD),
                track("t-done", TrackState.READY),
            ),
        )
        collectionTrackDao.insertAll(
            listOf(
                com.example.jellywrist.data.db.CollectionTrackEntity("local-random", "t-new", 0),
                com.example.jellywrist.data.db.CollectionTrackEntity("local-random", "t-done", 1),
            ),
        )
        metadata.failure = AssertionError("the server port must NOT be called for a local collection")

        val outcome = engine().syncPass(session)

        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(listOf("t-new"), downloader.enqueued.flatten())
        assertTrue("nothing is purged", downloader.removed.isEmpty())
    }

    @Test
    fun `a random re-draw frees the previous draw's bytes`() = runTest {
        // Track downloaded during a previous draw, whose link was REPLACED
        // (replaceContent): no collection references it anymore. The pure diff doesn't
        // see it (unlinked) — the SQL sweep must catch it, otherwise each
        // re-draw leaks its bytes until the quota is saturated.
        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = "local-random",
                    type = CollectionType.PLAYLIST,
                    name = "500 random songs",
                    pinned = true,
                    localOnly = true,
                ),
            ),
        )
        trackDao.upsertAll(
            listOf(
                track("t-old", TrackState.READY, downloadedBytes = 999), // previous draw, unlinked
                track("t-new", TrackState.TO_DOWNLOAD),
            ),
        )
        collectionTrackDao.insertAll(
            listOf(com.example.jellywrist.data.db.CollectionTrackEntity("local-random", "t-new", 0)),
        )

        engine().syncPass(session)

        assertTrue("the previous draw's bytes are freed", downloader.removed.flatten().contains("t-old"))
        assertEquals(TrackState.TO_DOWNLOAD, trackDao.rows["t-old"]?.state)
        assertEquals(0L, trackDao.rows["t-old"]?.downloadedBytes)
        assertEquals(listOf("t-new"), downloader.enqueued.flatten())
    }

    // ── Doubles ──────────────────────────────────────────────────────────

    private fun meta(id: String, size: Long = 100, codec: String = "flac") = SyncTrackMeta(
        id = id,
        title = "Titre $id",
        sizeBytes = size,
        codec = codec,
    )

    private fun track(id: String, state: TrackState, downloadedBytes: Long = 0) = TrackEntity(
        id = id,
        title = "Titre $id",
        sizeBytes = 100,
        state = state,
        downloadedBytes = downloadedBytes,
    )

    private class RecordingDownloader : TrackDownloader {
        val enqueued = mutableListOf<List<String>>()
        val removed = mutableListOf<List<String>>()

        override suspend fun enqueue(trackIds: List<String>) {
            enqueued.add(trackIds)
        }

        override suspend fun remove(trackIds: List<String>) {
            removed.add(trackIds)
        }

        override fun observeProgress(): Flow<List<DownloadProgress>> = MutableStateFlow(emptyList())

        override suspend fun usedBytes(): Long = 0L

        override suspend fun hasOutstandingWork(): Boolean = enqueued.isNotEmpty()

        override suspend fun pauseAll() = Unit

        override suspend fun resumeAll() = Unit
    }

    private class FakeMetadataSource : SyncMetadataSource {
        val content = mutableMapOf<String, List<SyncTrackMeta>>()
        val failures = mutableMapOf<String, Throwable>()
        var failure: Throwable? = null

        override suspend fun fetchCollectionTracks(
            collectionId: String,
            type: CollectionType,
            accessToken: String,
            baseUrl: String,
        ): List<SyncTrackMeta> {
            failures[collectionId]?.let { throw it }
            failure?.let { throw it }
            return content[collectionId].orEmpty()
        }
    }

    /** Fake of the [SettingsRepository] port — the engine only reads `settings.first()`. */
    private fun fakeSettings(quota: Long): SettingsRepository = object : SettingsRepository {
        override val settings: Flow<SyncSettings> = MutableStateFlow(
            SyncSettings.DEFAULT.copy(quotaBytes = quota),
        )

        override suspend fun setQuotaBytes(bytes: Long) = Unit

        override suspend fun setRequiresCharging(required: Boolean) = Unit

        override suspend fun setUnmeteredOnly(unmetered: Boolean) = Unit

        override suspend fun setPeriodicityHours(hours: Int) = Unit

        override suspend fun setPinnedCertificate(sha256: String?) = Unit

        override suspend fun clear() = Unit
    }
}

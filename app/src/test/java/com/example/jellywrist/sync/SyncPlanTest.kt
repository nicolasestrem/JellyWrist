package com.example.jellywrist.sync

import com.example.jellywrist.data.db.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the pure [diff] function (REQ-006, PLAN.md §8 task 11).
 *
 * Each test defends a rule stated in the KDoc of [diff]: server addition, refcounted
 * purge, idempotence, retry on error, undecodable codecs (DEC-003), budget
 * (REQ-008), and PENDING_BUDGET promotion.
 */
class SyncPlanTest {

    private val bigBudget = BudgetCapacity(freeBytes = Long.MAX_VALUE / 2, quotaBytes = 0, alreadyUsedBytes = 0)

    private fun local(
        tracks: Map<String, LocalTrack> = emptyMap(),
        collectionTracks: Map<String, Set<String>> = emptyMap(),
        pinned: Set<String> = collectionTracks.keys,
    ) = LocalState(tracks, collectionTracks, pinned)

    private fun serverColl(id: String, vararg tracks: ServerTrack) = ServerCollectionState(
        collectionId = id,
        trackIds = tracks.map { it.id },
        tracks = tracks.associateBy { it.id },
    )

    private fun st(id: String, size: Long = 100, codec: String? = "flac") = ServerTrack(id, size, codec)

    private fun lt(id: String, state: TrackState, size: Long = 100, codec: String? = "flac") =
        LocalTrack(id, state, size, codec)

    @Test
    fun `track added server-side is enqueued`() {
        val decision = diff(local(), listOf(serverColl("c1", st("t1"))), bigBudget)
        assertEquals(listOf("t1"), decision.toEnqueue)
        assertTrue(decision.toRemove.isEmpty())
    }

    @Test
    fun `track removed server-side with no other reference is purged`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.READY)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1")), // t1 removed server-side
            bigBudget,
        )
        assertEquals(listOf("t1"), decision.toRemove)
    }

    @Test
    fun `track still referenced by another pinned collection is not purged`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.READY)),
                collectionTracks = mapOf("c1" to setOf("t1"), "c2" to setOf("t1")),
            ),
            listOf(
                serverColl("c1"), // removed from c1…
                serverColl("c2", st("t1")), // …but still in c2
            ),
            bigBudget,
        )
        assertFalse("refcount: t1 is still in c2", "t1" in decision.toRemove)
    }

    @Test
    fun `unpinning a collection only orphans its exclusive tracks`() {
        // c1 unpinned (absent from server state); t-shared also lives in c2.
        val decision = diff(
            local(
                tracks = mapOf(
                    "t-exclusive" to lt("t-exclusive", TrackState.READY),
                    "t-shared" to lt("t-shared", TrackState.READY),
                ),
                collectionTracks = mapOf(
                    "c1" to setOf("t-exclusive", "t-shared"),
                    "c2" to setOf("t-shared"),
                ),
                pinned = setOf("c2"),
            ),
            listOf(serverColl("c2", st("t-shared"))),
            bigBudget,
        )
        assertTrue("t-exclusive" in decision.toRemove)
        assertFalse("t-shared" in decision.toRemove)
    }

    @Test
    fun `READY track is not re-enqueued`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.READY)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1"))),
            bigBudget,
        )
        assertTrue("idempotence: READY never re-enqueued", decision.toEnqueue.isEmpty())
    }

    @Test
    fun `track in error is re-enqueued`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.ERROR)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1"))),
            bigBudget,
        )
        assertEquals(listOf("t1"), decision.toEnqueue)
    }

    @Test
    fun `undecodable codec is reported AND enqueued`() {
        // DEC-003 (rank 1): the original file is downloaded even if unreadable;
        // the downloader will mark it UNSUPPORTED afterwards (DEC-004).
        val decision = diff(local(), listOf(serverColl("c1", st("t1", codec = "alac"))), bigBudget)
        assertTrue("t1" in decision.unsupported)
        assertTrue("DEC-003: downloaded anyway", "t1" in decision.toEnqueue)
    }

    @Test
    fun `UNSUPPORTED track already downloaded is not re-enqueued`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.UNSUPPORTED, codec = "alac")),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1", codec = "alac"))),
            bigBudget,
        )
        assertTrue(decision.toEnqueue.isEmpty())
    }

    @Test
    fun `tracks beyond budget are deferred not enqueued`() {
        val budget = BudgetCapacity(freeBytes = 150, quotaBytes = 0, alreadyUsedBytes = 0)
        val decision = diff(
            local(),
            listOf(serverColl("c1", st("t1", size = 100), st("t2", size = 100))),
            budget,
        )
        assertEquals(listOf("t1"), decision.toEnqueue)
        assertEquals(listOf("t2"), decision.toDeferForBudget)
    }

    @Test
    fun `PENDING_BUDGET is promoted when budget allows`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.PENDING_BUDGET)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1"))),
            bigBudget,
        )
        assertEquals(listOf("t1"), decision.toEnqueue)
    }

    @Test
    fun `PENDING_BUDGET stays deferred as long as budget is insufficient`() {
        val budget = BudgetCapacity(freeBytes = 10, quotaBytes = 0, alreadyUsedBytes = 0)
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.PENDING_BUDGET, size = 100)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1", size = 100))),
            budget,
        )
        assertTrue(decision.toEnqueue.isEmpty())
        assertEquals(listOf("t1"), decision.toDeferForBudget)
    }

    @Test
    fun `collection emptied server-side purges its tracks without touching others`() {
        val decision = diff(
            local(
                tracks = mapOf(
                    "t1" to lt("t1", TrackState.READY),
                    "t2" to lt("t2", TrackState.READY),
                ),
                collectionTracks = mapOf("c1" to setOf("t1"), "c2" to setOf("t2")),
            ),
            listOf(serverColl("c1"), serverColl("c2", st("t2"))),
            bigBudget,
        )
        assertEquals(listOf("t1"), decision.toRemove)
        assertTrue(decision.toEnqueue.isEmpty())
    }

    @Test
    fun `DOWNLOADING track is neither re-enqueued nor purged`() {
        val decision = diff(
            local(
                tracks = mapOf("t1" to lt("t1", TrackState.DOWNLOADING)),
                collectionTracks = mapOf("c1" to setOf("t1")),
            ),
            listOf(serverColl("c1", st("t1"))),
            bigBudget,
        )
        assertTrue(decision.toEnqueue.isEmpty())
        assertTrue(decision.toRemove.isEmpty())
    }

    @Test
    fun `recon watermark is the last server id of each collection`() {
        val decision = diff(
            local(),
            listOf(serverColl("c1", st("a"), st("b")), serverColl("c2")),
            bigBudget,
        )
        assertEquals("b", decision.reconWatermark["c1"])
        assertEquals(null, decision.reconWatermark["c2"])
    }
}

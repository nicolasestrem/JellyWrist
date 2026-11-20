package com.example.jellywrist.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the pure capacity planner [plan] (REQ-008, PLAN.md §8 task 8).
 *
 * The planner must respect **both** the user quota and real disk space
 * (minus a reserve), accept greedily in the given order without ever partially
 * accepting, and return an explicit verdict — never a silent failure.
 */
class BudgetPlannerTest {

    private fun c(id: String, size: Long) = Candidate(id, size)

    @Test
    fun `accepts everything when capacity suffices`() {
        val result = plan(
            candidates = listOf(c("a", 100), c("b", 200)),
            quotaBytes = 10_000,
            alreadyUsedBytes = 0,
            availableDiskBytes = 10_000,
            reserveBytes = 0,
        )
        assertEquals(listOf("a", "b"), result.accepted.map { it.trackId })
        assertTrue(result.deferred.isEmpty())
        assertEquals(BudgetVerdict.OK, result.verdict)
    }

    @Test
    fun `user quota bounds even when disk is vast`() {
        val result = plan(
            candidates = listOf(c("a", 100), c("b", 100)),
            quotaBytes = 150,
            alreadyUsedBytes = 0,
            availableDiskBytes = 1_000_000,
            reserveBytes = 0,
        )
        assertEquals(listOf("a"), result.accepted.map { it.trackId })
        assertEquals(listOf("b"), result.deferred.map { it.trackId })
        assertEquals(BudgetVerdict.PARTIAL, result.verdict)
    }

    @Test
    fun `real disk bounds even when quota is generous`() {
        // An 8 GB watch can be full well before a 6 GiB quota (REQ-008).
        val result = plan(
            candidates = listOf(c("a", 100), c("b", 100)),
            quotaBytes = 1_000_000,
            alreadyUsedBytes = 0,
            availableDiskBytes = 150,
            reserveBytes = 0,
        )
        assertEquals(listOf("a"), result.accepted.map { it.trackId })
        assertEquals(BudgetVerdict.PARTIAL, result.verdict)
    }

    @Test
    fun `safety reserve is subtracted from disk`() {
        val result = plan(
            candidates = listOf(c("a", 100)),
            quotaBytes = 1_000,
            alreadyUsedBytes = 0,
            availableDiskBytes = 550,
            reserveBytes = 512,
        )
        // 550 - 512 = 38 < 100: nothing is accepted.
        assertTrue(result.accepted.isEmpty())
        assertEquals(BudgetVerdict.NO_CAPACITY, result.verdict)
    }

    @Test
    fun `no partial acceptance of a candidate`() {
        val result = plan(
            candidates = listOf(c("gros-album-flac", 5_000)),
            quotaBytes = 1_000,
            alreadyUsedBytes = 0,
            availableDiskBytes = 100_000,
            reserveBytes = 0,
        )
        assertTrue(result.accepted.isEmpty())
        assertEquals(listOf("gros-album-flac"), result.deferred.map { it.trackId })
    }

    @Test
    fun `zero or negative capacity accepts nothing with explicit verdict`() {
        val result = plan(
            candidates = listOf(c("a", 1)),
            quotaBytes = 100,
            alreadyUsedBytes = 200, // overflow: negative margin
            availableDiskBytes = 100_000,
            reserveBytes = 0,
        )
        assertTrue(result.accepted.isEmpty())
        assertEquals("the UI must be able to warn, never a silent failure", BudgetVerdict.NO_CAPACITY, result.verdict)
    }

    @Test
    fun `freeing space promotes previously deferred candidates`() {
        val candidates = listOf(c("a", 100), c("b", 100))
        val before = plan(candidates, quotaBytes = 200, alreadyUsedBytes = 40, availableDiskBytes = 10_000, reserveBytes = 0)
        assertEquals(listOf("a"), before.accepted.map { it.trackId })

        // 40 bytes freed (deletion): b becomes acceptable under the same quota.
        val after = plan(candidates, quotaBytes = 200, alreadyUsedBytes = 0, availableDiskBytes = 10_000, reserveBytes = 0)
        assertEquals(listOf("a", "b"), after.accepted.map { it.trackId })
    }

    @Test
    fun `caller order is preserved never reordered`() {
        // The caller provides playlists first, albums second (PLAN.md §4.2 step 4):
        // the planner must not take it upon itself to sort by size.
        val result = plan(
            candidates = listOf(c("playlist-1", 300), c("album-1", 100), c("playlist-2", 200)),
            quotaBytes = 10_000,
            alreadyUsedBytes = 0,
            availableDiskBytes = 10_000,
            reserveBytes = 0,
        )
        assertEquals(listOf("playlist-1", "album-1", "playlist-2"), result.accepted.map { it.trackId })
    }

    @Test
    fun `greedy skips a large candidate but accepts the next one that fits`() {
        val result = plan(
            candidates = listOf(c("gros", 900), c("petit", 50)),
            quotaBytes = 100,
            alreadyUsedBytes = 0,
            availableDiskBytes = 10_000,
            reserveBytes = 0,
        )
        assertEquals(listOf("petit"), result.accepted.map { it.trackId })
        assertEquals(listOf("gros"), result.deferred.map { it.trackId })
    }

    @Test
    fun `no candidates returns OK with no capacity consumed`() {
        val result = plan(
            candidates = emptyList(),
            quotaBytes = 0,
            alreadyUsedBytes = 0,
            availableDiskBytes = 0,
            reserveBytes = 0,
        )
        assertEquals(BudgetVerdict.OK, result.verdict)
    }
}

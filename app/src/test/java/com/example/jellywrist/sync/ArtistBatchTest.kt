package com.example.jellywrist.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the pure artist batching [artistBatch] (PLAN.md §4.4).
 *
 * "Download all" for an artist enqueues greedily up to the cap and leaves the rest
 * in PENDING_BUDGET — "6 of 14 albums enqueued" rather than a total refusal.
 */
class ArtistBatchTest {

    private fun album(id: String, size: Long) = ArtistAlbum(id, "Album $id", size)

    @Test
    fun `all albums fit`() {
        val result = artistBatch(listOf(album("a", 100), album("b", 200)), budgetBytes = 1_000)
        assertEquals(listOf("a", "b"), result.toEnqueue.map { it.albumId })
        assertTrue(result.pendingBudget.isEmpty())
    }

    @Test
    fun `partial fill up to the cap leaves the rest pending`() {
        val result = artistBatch(
            listOf(album("a", 400), album("b", 400), album("c", 400)),
            budgetBytes = 900,
        )
        assertEquals(listOf("a", "b"), result.toEnqueue.map { it.albumId })
        assertEquals(listOf("c"), result.pendingBudget.map { it.albumId })
    }

    @Test
    fun `nothing fits with zero or negative budget`() {
        val albums = listOf(album("a", 1))
        assertEquals(albums, artistBatch(albums, budgetBytes = 0).pendingBudget)
        assertEquals(albums, artistBatch(albums, budgetBytes = -5).pendingBudget)
    }

    @Test
    fun `an album larger than the budget is skipped never truncated`() {
        val result = artistBatch(
            listOf(album("enorme-flac", 10_000), album("petit", 100)),
            budgetBytes = 500,
        )
        assertEquals(listOf("petit"), result.toEnqueue.map { it.albumId })
        assertEquals(listOf("enorme-flac"), result.pendingBudget.map { it.albumId })
    }

    @Test
    fun `caller-provided order is deterministic and preserved`() {
        val albums = listOf(album("z", 10), album("a", 10), album("m", 10))
        val result = artistBatch(albums, budgetBytes = 1_000)
        assertEquals(listOf("z", "a", "m"), result.toEnqueue.map { it.albumId })
    }
}

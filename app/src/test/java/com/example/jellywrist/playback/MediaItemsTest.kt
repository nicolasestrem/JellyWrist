package com.example.jellywrist.playback

import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests of the pure playback queue filter (PLAN.md §8 task 9).
 *
 * The [MediaItems.toMediaItem] part touches `android.net.Uri` (not executable in pure JVM);
 * it is covered by manual verification on an emulator. The **critical contract** —
 * only `READY` tracks are playable, in the given order — is here (the playback
 * source being cache-only, any other track would fail at playback).
 */
class MediaItemsTest {

    private fun track(id: String, state: TrackState) = TrackEntity(
        id = id,
        title = "Track $id",
        state = state,
    )

    @Test
    fun `only READY tracks are playable`() {
        val tracks = listOf(
            track("ready-1", TrackState.READY),
            track("dl", TrackState.DOWNLOADING),
            track("todo", TrackState.TO_DOWNLOAD),
            track("unsupported", TrackState.UNSUPPORTED),
            track("error", TrackState.ERROR),
            track("budget", TrackState.PENDING_BUDGET),
            track("ready-2", TrackState.READY),
        )
        val playable = MediaItems.playableTracks(tracks)
        assertEquals(listOf("ready-1", "ready-2"), playable.map { it.id })
    }

    @Test
    fun `UNSUPPORTED is never playable even when downloaded`() {
        // DEC-003: the file is cached, but ExoPlayer can't decode it — offering
        // it would produce a playback error instead of a clear report (DEC-004).
        val playable = MediaItems.playableTracks(listOf(track("t", TrackState.UNSUPPORTED)))
        assertTrue(playable.isEmpty())
    }

    @Test
    fun `collection order is preserved`() {
        val tracks = listOf(
            track("c", TrackState.READY),
            track("a", TrackState.READY),
            track("b", TrackState.READY),
        )
        assertEquals(listOf("c", "a", "b"), MediaItems.playableTracks(tracks).map { it.id })
    }

    @Test
    fun `empty list stays empty`() {
        assertTrue(MediaItems.playableTracks(emptyList()).isEmpty())
    }
}

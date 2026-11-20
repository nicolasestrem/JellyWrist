package com.example.jellywrist.wire

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `MessageClient.sendMessage` rejects payloads above ~100 KB and the sender-side failure
 * used to be swallowed — [sizeCapped] must keep any status under the limit.
 */
class WatchStatusSizeCapTest {

    private fun collection(i: Int) = WirePinnedCollection(
        id = "collection-$i",
        name = "A reasonably long album name to inflate the payload $i",
        type = "ALBUM",
        trackCount = i,
        estimatedBytes = i * 1_000L,
        lastSyncAtEpochMs = 1_700_000_000_000L + i,
    )

    private fun status(count: Int) = WatchStatus(
        configured = true,
        serverName = "Home Jellyfin",
        userName = "Avery",
        collections = (1..count).map(::collection),
    )

    @Test
    fun `a small status is returned unchanged`() {
        val status = status(count = 10)

        val capped = status.sizeCapped()

        assertEquals(status, capped)
        assertFalse(capped.collectionsTruncated)
    }

    @Test
    fun `an oversized status is trimmed under the limit and flagged`() {
        val status = status(count = 2_000)
        assertTrue("fixture must exceed the cap", status.toWireBytes().size > MAX_STATUS_PAYLOAD_BYTES)

        val capped = status.sizeCapped()

        assertTrue(capped.toWireBytes().size <= MAX_STATUS_PAYLOAD_BYTES)
        assertTrue(capped.collectionsTruncated)
        assertTrue(capped.collections.isNotEmpty())
        // The kept entries are a prefix — the phone still sees the head of the list.
        assertEquals(status.collections.take(capped.collections.size), capped.collections)
    }

    @Test
    fun `the flag survives the wire round trip and an empty list always fits`() {
        val capped = status(count = 2_000).sizeCapped(maxBytes = 500)

        val decoded = capped.toWireBytes().fromWire<WatchStatus>()

        assertTrue(decoded.collectionsTruncated)
        assertTrue(capped.toWireBytes().size <= 500 || capped.collections.isEmpty())
    }
}

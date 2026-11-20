package com.example.jellywrist.ui.components

import com.example.jellywrist.R
import com.example.jellywrist.data.db.TrackState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that each track state exposes a localized description for TalkBack.
 */
class StatusIconTest {
    @Test
    fun `each state returns its description resource`() {
        val expectedDescriptions = mapOf(
            TrackState.READY to R.string.track_status_ready,
            TrackState.TO_DOWNLOAD to R.string.track_status_to_download,
            TrackState.DOWNLOADING to R.string.track_status_downloading,
            TrackState.UNSUPPORTED to R.string.track_status_unsupported,
            TrackState.ERROR to R.string.track_status_error,
            TrackState.PENDING_BUDGET to R.string.track_status_pending_budget,
        )

        assertEquals(expectedDescriptions, TrackState.entries.associateWith { it.statusDescriptionRes() })
    }
}

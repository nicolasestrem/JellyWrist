package com.example.jellywrist.download

import androidx.media3.exoplayer.offline.Download
import com.example.jellywrist.data.db.TrackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests of [DownloadStateMapper] — the `Download.STATE_*` constants are
 * integer literals in the Media3 JAR (not `android.jar` stubs), so they run
 * in the JVM without an emulator. Verifies the `Download.STATE_*` → [TrackState]
 * mapping and percentage calculation (PLAN.md §8, task 6).
 */
class DownloadStateMapperTest {

    @Test
    fun `STATE_DOWNLOADING maps to DOWNLOADING`() {
        assertEquals(
            TrackState.DOWNLOADING,
            DownloadStateMapper.toTrackState(Download.STATE_DOWNLOADING),
        )
    }

    @Test
    fun `STATE_RESTARTING maps to DOWNLOADING`() {
        assertEquals(
            TrackState.DOWNLOADING,
            DownloadStateMapper.toTrackState(Download.STATE_RESTARTING),
        )
    }

    @Test
    fun `STATE_FAILED maps to ERROR`() {
        assertEquals(
            TrackState.ERROR,
            DownloadStateMapper.toTrackState(
                media3State = Download.STATE_FAILED,
                errorMessage = "Network error",
            ),
        )
    }

    @Test
    fun `STATE_COMPLETED returns null — handled by CodecSupport`() {
        // STATE_COMPLETED never goes through this mapper (DEC-003):
        // completion is handled by CodecSupport.finalStateForCodec().
        assertNull(DownloadStateMapper.toTrackState(Download.STATE_COMPLETED))
    }

    @Test
    fun `STATE_STOPPED returns null — no DB update`() {
        assertNull(DownloadStateMapper.toTrackState(Download.STATE_STOPPED))
    }

    @Test
    fun `STATE_QUEUED returns null`() {
        assertNull(DownloadStateMapper.toTrackState(Download.STATE_QUEUED))
    }

    @Test
    fun `unknown state returns null`() {
        assertNull(DownloadStateMapper.toTrackState(999))
    }

    @Test
    fun `toPercent with known total returns correct percentage`() {
        val result = DownloadStateMapper.toPercent(downloadedBytes = 50L, totalBytes = 200L)
        assertEquals(25f, result, 0.01f)
    }

    @Test
    fun `toPercent at 100 percent`() {
        val result = DownloadStateMapper.toPercent(downloadedBytes = 100L, totalBytes = 100L)
        assertEquals(100f, result, 0.01f)
    }

    @Test
    fun `toPercent with zero total returns negative one`() {
        assertEquals(-1f, DownloadStateMapper.toPercent(downloadedBytes = 50L, totalBytes = 0L), 0f)
    }

    @Test
    fun `toPercent with negative total returns negative one`() {
        assertEquals(-1f, DownloadStateMapper.toPercent(downloadedBytes = 50L, totalBytes = -1L), 0f)
    }

    @Test
    fun `toPercent with zero downloaded returns zero percent`() {
        val result = DownloadStateMapper.toPercent(downloadedBytes = 0L, totalBytes = 100L)
        assertEquals(0f, result, 0.01f)
    }
}

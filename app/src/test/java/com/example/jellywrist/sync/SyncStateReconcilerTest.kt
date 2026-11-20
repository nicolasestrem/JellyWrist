package com.example.jellywrist.sync

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The in-memory [SyncState] dies with the process while WorkManager keeps running — the
 * observable state must reconcile both (a mid-sync process death used to show IDLE).
 */
class SyncStateReconcilerTest {

    @Test
    fun `a running worker reports SYNCING whatever the local state says`() {
        for (local in SyncState.entries) {
            assertEquals(
                SyncState.SYNCING,
                reconcileSyncState(local, listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING)),
            )
        }
    }

    @Test
    fun `a stale SYNCING with nothing running falls back to IDLE`() {
        assertEquals(SyncState.IDLE, reconcileSyncState(SyncState.SYNCING, emptyList()))
        assertEquals(
            SyncState.IDLE,
            reconcileSyncState(SyncState.SYNCING, listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.SUCCEEDED)),
        )
    }

    @Test
    fun `queued state persists only while WorkManager is waiting`() {
        assertEquals(
            SyncState.QUEUED,
            reconcileSyncState(SyncState.QUEUED, listOf(WorkInfo.State.ENQUEUED)),
        )
        assertEquals(SyncState.IDLE, reconcileSyncState(SyncState.QUEUED, emptyList()))
    }

    @Test
    fun `outcome nuance is preserved when nothing is running`() {
        assertEquals(SyncState.IDLE, reconcileSyncState(SyncState.IDLE, listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(SyncState.PAUSED, reconcileSyncState(SyncState.PAUSED, listOf(WorkInfo.State.ENQUEUED)))
        assertEquals(SyncState.FAILED, reconcileSyncState(SyncState.FAILED, emptyList()))
    }
}

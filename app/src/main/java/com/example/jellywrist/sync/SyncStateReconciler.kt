package com.example.jellywrist.sync

import androidx.work.WorkInfo

/**
 * Reconciles the in-memory [SyncState] with what WorkManager actually reports.
 *
 * The in-memory state is process-scoped: after a process death mid-sync it restarts at
 * [SyncState.IDLE] while the worker may still be running (WorkManager survives the
 * process), and conversely a stale [SyncState.SYNCING] can linger if the worker died
 * without reporting. WorkManager is the source of truth for "running right now"; the
 * local state keeps the outcome nuance ([SyncState.PAUSED], [SyncState.FAILED]) that
 * work info cannot express.
 *
 * Pure function — JVM-testable without WorkManager.
 */
internal fun reconcileSyncState(local: SyncState, workStates: List<WorkInfo.State>): SyncState =
    when {
        workStates.any { it == WorkInfo.State.RUNNING } -> SyncState.SYNCING
        // Nothing is running: a lingering SYNCING is stale by definition.
        local == SyncState.SYNCING -> SyncState.IDLE
        local == SyncState.QUEUED && workStates.none { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.BLOCKED } ->
            SyncState.IDLE
        else -> local
    }

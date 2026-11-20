package com.example.jellywrist.sync

/**
 * Port for arming the periodic sync — same rationale as
 * [com.example.jellywrist.data.reset.ServerDataReset]: [SyncScheduler] needs WorkManager and
 * an Android `Context`, so callers that must stay JVM-testable depend on this instead.
 *
 * Arming is idempotent ([SyncScheduler.schedulePeriodic] enqueues with the `UPDATE` policy).
 */
fun interface PeriodicSyncArmer {
    suspend fun arm()
}

/**
 * Port for the sync commands the companion handler triggers — split from [PeriodicSyncArmer]
 * because callers need one or the other, never both, and a `fun interface` stays trivially
 * fakeable.
 */
interface SyncControl {
    /** See [SyncScheduler.syncNow]. */
    suspend fun syncNow(allowMetered: Boolean = false, allowLowBattery: Boolean = false)

    /** See [SyncScheduler.scheduleAutomaticOneShot]. */
    suspend fun scheduleAutomaticOneShot()

    /** Process-local state included in companion status replies. */
    fun currentSyncState(): SyncState = SyncState.IDLE

    /** Stable wire blocker, or `null` when no user action is required. */
    fun currentSyncBlocker(): String? = null
}

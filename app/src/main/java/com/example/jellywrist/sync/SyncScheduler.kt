package com.example.jellywrist.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.db.SyncOutcome
import androidx.work.workDataOf
import com.example.jellywrist.network.NetworkAwareness
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.settings.SyncSettings
import com.example.jellywrist.util.SyncTrace
import com.example.jellywrist.wire.WireSyncBlockers
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Unique tag for sync work (WorkManager). */
const val SYNC_WORK_TAG = "jellywatch_sync"

/** Name of the periodic sync work. */
const val ONETIME_AUTO_NAME = "jellywatch_auto_sync"
const val PERIODIC_SYNC_NAME = "periodic_sync"

/** Name of the one-shot sync work (manually triggered). */
const val ONETIME_SYNC_NAME = "onetime_sync"

/** Input keys for [ReconWorker]: manual path and per-request consent. */
const val KEY_MANUAL_SYNC = "manual_sync"
const val KEY_ALLOW_METERED = "allow_metered"
const val KEY_ALLOW_LOW_BATTERY = "allow_low_battery"

/**
 * Sync scheduler (PLAN.md §8 task 7 / §8 task 10).
 *
 * Manages the sync lifecycle via WorkManager:
 * - A **periodic** job armed at the frequency configured in [SyncSettings.periodicityHours]
 * - A **one-shot** job triggered by the user ("Sync" button)
 *
 * Startup constraints respect user settings:
 * - [NetworkType.UNMETERED] if [SyncSettings.unmeteredOnly]
 * - Charging required if [SyncSettings.requiresCharging]
 *
 * The FGS 6-hour cap is handled by the [SyncEngine]: a truncated pass → [SyncOutcome.PAUSED],
 * WorkManager re-arms on the next cycle (DEC-005).
 *
 * REQ-010 is [DEFERRED]: no PlaybackReport upload is scheduled.
 */
@Singleton
class SyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
    private val syncEngine: SyncEngine,
) : SyncControl {

    private val _state = MutableStateFlow(SyncState.IDLE)
    private val _blocker = MutableStateFlow<String?>(null)
    private val workManager = WorkManager.getInstance(context)

    /**
     * Observable sync state, consumed by the Downloads screen.
     *
     * Possible values: [SyncState.IDLE], [SyncState.SYNCING], [SyncState.PAUSED],
     * [SyncState.FAILED]. Reconciled against WorkManager (see [reconcileSyncState]) so a
     * sync surviving a process death is still reported as SYNCING instead of resetting
     * to IDLE with the in-memory state.
     */
    fun observeSyncState(): Flow<SyncState> = combine(
        _state,
        workManager.getWorkInfosByTagFlow(SYNC_WORK_TAG),
    ) { local, infos -> reconcileSyncState(local, infos.map { it.state }) }

    /**
     * Triggers an immediate (one-shot) sync.
     *
     * **User-initiated** path (PLAN.md §4.3 / REQ-007): unlike the overnight cycle, the
     * charging constraint is lifted — the battery warning is the UI's responsibility —
     * and the request is expedited (`setExpedited`) to start without waiting for a
     * maintenance window. The network constraint remains: without network, a sync is
     * pointless. If a sync is already running, no-op (WorkManager deduplicates by name).
     */
    override suspend fun syncNow(allowMetered: Boolean, allowLowBattery: Boolean) {
        _blocker.value = null
        _state.value = SyncState.QUEUED
        // CONNECTED constraint (not UNMETERED): on Wear OS, Wi-Fi is asleep unless
        // someone asks for it — an UNMETERED constraint would never be satisfied and
        // the work would never start. It is the worker that **acquires** Wi-Fi via
        // Horologist (NetworkAwareness.withHighBandwidth) once launched (PLAN.md §4.3).
        val request = OneTimeWorkRequestBuilder<ReconWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(
                workDataOf(
                    KEY_MANUAL_SYNC to true,
                    KEY_ALLOW_METERED to allowMetered,
                    KEY_ALLOW_LOW_BATTERY to allowLowBattery,
                ),
            )
            .addTag(SYNC_WORK_TAG)
            .build()

        workManager.enqueueUniqueWork(
            ONETIME_SYNC_NAME,
            // APPEND_OR_REPLACE, not KEEP: with KEEP, a request still sitting in ENQUEUED —
            // waiting on constraints, or serving an hour-long retry backoff — silently
            // swallowed every later tap, consent flags included. Appending runs the new
            // request after the current one instead of discarding it.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        SyncTrace.i(
            "syncNow enqueued allowMetered=$allowMetered allowLowBattery=$allowLowBattery",
        )
    }

    /**
     * Schedules the periodic sync according to user settings.
     *
     * Called on app startup and on every settings change.
     * If the periodicity changed, the existing job is replaced.
     *
     * Suspends because reading DataStore settings is asynchronous.
     */
    suspend fun schedulePeriodic() {
        val settings = getSettingsSnapshot()

        val repeatInterval = settings.periodicityHours.toLong().coerceIn(6, 168) // 6 h to 7 days
        val constraints = buildConstraints(settings)

        val request = PeriodicWorkRequestBuilder<ReconWorker>(
            repeatInterval, TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(SYNC_WORK_TAG)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.HOURS)
            .build()

        workManager.enqueueUniquePeriodicWork(
            PERIODIC_SYNC_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Schedules a one-shot automatic sync (pinning, etc.) with the user constraints
     * (charging, Wi-Fi) — REQ-007 (bot review bug #7).
     * Uses a distinct name from syncNow() so as not to overwrite a manual sync in progress. */
    override suspend fun scheduleAutomaticOneShot() {
        val settings = getSettingsSnapshot()
        val request = OneTimeWorkRequestBuilder<ReconWorker>()
            .setConstraints(buildConstraints(settings))
            .setInputData(workDataOf())
            .addTag(SYNC_WORK_TAG)
            .build()
        workManager.enqueueUniqueWork(
            ONETIME_AUTO_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
        SyncTrace.i(
            "automaticOneShot enqueued unmeteredOnly=${settings.unmeteredOnly} " +
                "requiresCharging=${settings.requiresCharging}",
        )
        _state.value = SyncState.QUEUED
    }
    /**
     * Cancels all sync work (user logout).
     */
    fun cancelAll() {
        workManager.cancelAllWorkByTag(SYNC_WORK_TAG)
        _state.value = SyncState.IDLE
        _blocker.value = null
    }

    /**
     * Updates the sync state — called by [ReconWorker] via the singleton.
     *
     * Exposed internally so the worker can report its progress without instantiating a
     * new [SyncScheduler].
     */
    internal fun setState(newState: SyncState) {
        _state.value = newState
    }

    internal fun setBlocker(blocker: String?) {
        _blocker.value = blocker
    }

    override fun currentSyncState(): SyncState = _state.value

    override fun currentSyncBlocker(): String? = _blocker.value

    /**
     * Executes a sync pass — delegated to the [SyncEngine].
     *
     * First checks that a Jellyfin session exists; otherwise returns [SyncOutcome.FAILED].
     */
    internal suspend fun executeSync(): SyncOutcome {
        val session = authRepository.currentSession()
            ?: run {
                SyncTrace.w("executeSync aborted: no authenticated session")
                return SyncOutcome.FAILED
            }

        return syncEngine.syncPass(session)
    }

    /** Builds WorkManager constraints from user settings. */
    private fun buildConstraints(
        settings: SyncSettings,
    ): Constraints = automaticSyncConstraints(settings)

    /** Reads current settings in a suspending manner (DataStore). */
    private suspend fun getSettingsSnapshot(): SyncSettings =
        settingsRepository.settings.first()
}

/** Automatic work keeps both user-selected constraints. */
internal fun automaticSyncConstraints(settings: SyncSettings): Constraints {
    val networkType = if (settings.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
    return Constraints.Builder()
        .setRequiredNetworkType(networkType)
        .setRequiresCharging(settings.requiresCharging)
        .build()
}

/**
 * Runs an authorized manual pass. The Media3 queue is resumed for every authorized
 * request, including ordinary Wi-Fi, because the charging observer may have paused it.
 */
internal suspend fun runManualSync(
    batteryWarningRequired: Boolean,
    highBandwidthGranted: Boolean,
    unmeteredAlready: Boolean,
    allowMetered: Boolean,
    allowLowBattery: Boolean,
    onBatteryConsentRequired: () -> Unit,
    onConsentRequired: () -> Unit,
    resumeDownloads: suspend () -> Unit,
    executeSync: suspend () -> SyncOutcome,
): SyncOutcome? {
    if (batteryWarningRequired && !allowLowBattery) {
        onBatteryConsentRequired()
        return null
    }
    if (!highBandwidthGranted && !unmeteredAlready && !allowMetered) {
        onConsentRequired()
        return null
    }
    resumeDownloads()
    return executeSync()
}

/** What [ReconWorker] should tell WorkManager about a finished pass. */
enum class WorkOutcome { SUCCESS, RETRY, FAILURE }

/**
 * Maps a pass outcome onto a WorkManager result.
 *
 * A **manual** failure is terminal on purpose. Returning `retry()` handed the pass to the
 * one-hour exponential backoff, so a user who had just tapped sync saw nothing happen, no
 * reason, and no second chance until the following hour. Automatic passes keep the retry —
 * nobody is watching them, and backoff is exactly right there.
 *
 * `null` means the pass stopped to ask for consent, which is a completed job, not a failure.
 * [SyncOutcome.PAUSED] is the FGS cap (DEC-005): it resumes by itself and is never presented
 * as a failure.
 */
internal fun workOutcomeFor(outcome: SyncOutcome?, manual: Boolean): WorkOutcome = when (outcome) {
    null -> WorkOutcome.SUCCESS
    SyncOutcome.FAILED -> if (manual) WorkOutcome.FAILURE else WorkOutcome.RETRY
    SyncOutcome.PAUSED, SyncOutcome.SUCCESS, SyncOutcome.PARTIAL -> WorkOutcome.SUCCESS
}

/**
 * WorkManager worker for sync (PLAN.md §8 task 10).
 *
 * [HiltWorker] with [AssistedInject]: WorkManager instantiates this worker via
 * [HiltWorkerFactory] (already configured in [com.example.jellywrist.JellyWristApp]).
 *
 * This worker is the WorkManager → SyncEngine entry point. It:
 * 1. Checks that a session exists
 * 2. Signals [SyncState.SYNCING]
 * 3. Delegates to [SyncScheduler.executeSync]
 * 4. Signals the result ([SyncState.PAUSED] or [SyncState.FAILED])
 * 5. Returns [androidx.work.ListenableWorker.Result]
 *
 * REQ-010 is [DEFERRED]: no PlaybackReport upload.
 */
@HiltWorker
class ReconWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val syncScheduler: SyncScheduler,
    private val networkAwareness: NetworkAwareness,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val manual = inputData.getBoolean(KEY_MANUAL_SYNC, false)
        val allowMetered = inputData.getBoolean(KEY_ALLOW_METERED, false)
        val allowLowBattery = inputData.getBoolean(KEY_ALLOW_LOW_BATTERY, false)

        SyncTrace.i(
            "worker start manual=$manual allowMetered=$allowMetered allowLowBattery=$allowLowBattery",
        )
        syncScheduler.setState(SyncState.SYNCING)

        return try {
            val outcome = if (manual) {
                // Manual path (PLAN.md §4.3): **explicit** Wi-Fi acquisition via
                // Horologist — Wear OS does not turn it on spontaneously and would let
                // sync crawl over the Bluetooth proxy. The lease serves to WAKE UP Wi-Fi,
                // not to block a sync when the active network is already unmetered
                // (emulator, watch already on Wi-Fi). We only stop if: lease denied AND
                // active network metered AND no explicit consent — never silent data
                // consumption (REQ-007).
                try {
                    runManualPass(allowMetered, allowLowBattery)
                } finally {
                    // Releases the queue only if the pass enqueued nothing; a pass that did
                    // enqueue is still downloading, and stays owned until it drains.
                    networkAwareness.manualRunFinished()
                }
            } else {
                syncScheduler.executeSync()
            }

            SyncTrace.i("worker outcome=${outcome?.name ?: "CONSENT_REQUIRED"}")
            when (outcome) {
                null -> Unit // explicit consent is required; the state is already PAUSED
                SyncOutcome.PAUSED -> syncScheduler.setState(SyncState.PAUSED)
                SyncOutcome.FAILED -> syncScheduler.setState(SyncState.FAILED)
                SyncOutcome.SUCCESS, SyncOutcome.PARTIAL -> syncScheduler.setState(SyncState.IDLE)
            }
            when (workOutcomeFor(outcome, manual)) {
                WorkOutcome.SUCCESS -> Result.success()
                WorkOutcome.RETRY -> Result.retry()
                WorkOutcome.FAILURE -> Result.failure()
            }
        } catch (e: CancellationException) {
            // System stop (constraints lost, FGS cap): signal pause then propagate —
            // WorkManager handles re-scheduling itself (DEC-005).
            SyncTrace.i("worker cancelled by the system")
            syncScheduler.setState(SyncState.PAUSED)
            throw e
        } catch (e: Exception) {
            SyncTrace.e("worker threw", e)
            syncScheduler.setState(SyncState.FAILED)
            if (manual) Result.failure() else Result.retry()
        }
    }

    /** The consent-gated manual pass under a Horologist Wi-Fi lease; `null` = consent needed. */
    private suspend fun runManualPass(allowMetered: Boolean, allowLowBattery: Boolean): SyncOutcome? =
        networkAwareness.withHighBandwidth { granted ->
            val unmeteredAlready = networkAwareness.isUnmetered.value
            SyncTrace.i(
                "worker wifiLeaseGranted=$granted unmeteredAlready=$unmeteredAlready",
            )
            runManualSync(
                batteryWarningRequired = networkAwareness.shouldWarnBeforeManualSync(),
                highBandwidthGranted = granted,
                unmeteredAlready = unmeteredAlready,
                allowMetered = allowMetered,
                allowLowBattery = allowLowBattery,
                onBatteryConsentRequired = {
                    SyncTrace.i("worker gate=battery-consent-required")
                    syncScheduler.setBlocker(WireSyncBlockers.LOW_BATTERY_CONFIRMATION_REQUIRED)
                    syncScheduler.setState(SyncState.PAUSED)
                },
                onConsentRequired = {
                    SyncTrace.i("worker gate=metered-consent-required")
                    syncScheduler.setBlocker(WireSyncBlockers.METERED_CONFIRMATION_REQUIRED)
                    syncScheduler.setState(SyncState.PAUSED)
                },
                resumeDownloads = {
                    SyncTrace.i("worker resuming Media3 queue before pass")
                    syncScheduler.setBlocker(null)
                    networkAwareness.resumeDownloads()
                },
                executeSync = syncScheduler::executeSync,
            )
        }
}

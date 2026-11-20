package com.example.jellywrist.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.companion.CompanionSetupCoordinator
import com.example.jellywrist.data.db.ServerDao
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.db.SyncLogEntity
import com.example.jellywrist.data.db.SyncOutcome
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.reset.LocalDataReset
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.data.settings.SyncSettings
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * State of the settings screen.
 *
 * Aggregates data from [SettingsRepository], [DeviceStorage], [TrackDownloader],
 * [SyncLogDao] and [AuthRepository] into a single immutable object consumed by the screen
 * (PLAN.md §8 task 13).
 */
data class SettingsUiState(
    val settings: SyncSettings = SyncSettings.DEFAULT,
    /** Bytes already used by cached tracks (READY + UNSUPPORTED). */
    val usedBytes: Long = 0L,
    /** Bytes available on the storage volume. */
    val availableDiskBytes: Long = 0L,
    /** Total size of the storage volume. */
    val totalDiskBytes: Long = 0L,
    /** Name of the connected Jellyfin server. */
    val serverName: String? = null,
    /** Name of the logged-in user. */
    val userName: String? = null,
    /** Log of recent syncs, most recent first. */
    val syncLog: List<SyncLogEntry> = emptyList(),
    /** Sign-out is in progress. */
    val signingOut: Boolean = false,
)

/**
 * Sync log entry for display (PLAN.md §4.2 point 7).
 *
 * [outcomeLabel] is a short icon; the full localized text is in res
 * (e.g. `sync_outcome_paused` -> "paused — will resume", DEC-005).
 */
data class SyncLogEntry(
    val id: Long,
    val startedAtEpochMs: Long,
    val finishedAtEpochMs: Long?,
    val added: Int,
    val removed: Int,
    val failed: Int,
    val deferredForBudget: Int,
    val outcome: SyncOutcome,
    val message: String?,
) {
    /** Short unicode icon for compact Wear OS UI. */
    val outcomeLabel: String get() = when (outcome) {
        SyncOutcome.SUCCESS -> "✓"
        SyncOutcome.PARTIAL -> "◐"
        SyncOutcome.PAUSED -> "⏸"
        SyncOutcome.FAILED -> "✗"
    }
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val deviceStorage: DeviceStorage,
    private val trackDownloader: TrackDownloader,
    private val syncLogDao: SyncLogDao,
    private val serverDao: ServerDao,
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler,
    private val localDataReset: LocalDataReset,
    private val companionSetupCoordinator: CompanionSetupCoordinator,
) : ViewModel() {

    private val _signingOut = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        syncLogDao.observeRecent(limit = 10),
        serverDao.observeCurrent(),
    ) { settings, logEntities, server ->
        SettingsUiState(
            settings = settings,
            usedBytes = trackDownloader.usedBytes(),
            availableDiskBytes = deviceStorage.availableBytes(),
            totalDiskBytes = deviceStorage.totalBytes(),
            serverName = server?.name,
            userName = authRepository.currentSession()?.userName,
            syncLog = logEntities.map { it.toUi() },
            signingOut = _signingOut.value,
        )
        // Off Main.immediate: the transform mixes cache-size I/O, StatFs and list mapping.
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SettingsUiState(),
    )

    /**
     * Adjusts the storage quota (PLAN.md §4.4).
     *
     * The quota is bounded between 512 MB and the available disk space + used space.
     */
    fun setQuotaBytes(bytes: Long) = viewModelScope.launch {
        val minQuota = 512L * 1024 * 1024 // 512 MB minimum
        val maxQuota = deviceStorage.availableBytes() + uiState.value.usedBytes
        val clamped = bytes.coerceIn(minQuota, maxQuota.coerceAtLeast(minQuota))
        settingsRepository.setQuotaBytes(clamped)
    }

    fun setRequiresCharging(required: Boolean) = viewModelScope.launch {
        settingsRepository.setRequiresCharging(required)
        // Periodic work constraints must follow settings (REQ-007).
        syncScheduler.schedulePeriodic()
    }

    fun setUnmeteredOnly(unmetered: Boolean) = viewModelScope.launch {
        settingsRepository.setUnmeteredOnly(unmetered)
        syncScheduler.schedulePeriodic()
    }

    fun setPeriodicityHours(hours: Int) = viewModelScope.launch {
        settingsRepository.setPeriodicityHours(hours.coerceIn(6, 168))
        syncScheduler.schedulePeriodic()
    }

    /**
     * Full sign-out = "factory reset" state (PLAN.md §8 task 13):
     * token, settings, Room catalog, Media3 cache bytes, persisted playback queue
     * and scheduled work — nothing must survive, including the **in-memory** state of
     * phone-driven provisioning: a residual `Done` would re-authenticate the
     * next onboarding screen without a token.
     */
    fun signOut(onComplete: () -> Unit) {
        viewModelScope.launch {
            _signingOut.value = true
            try {
                // Non-cancellable: the screen reports "signed out" in its `finally`, and a
                // ViewModel destroyed mid-wait would leave that message showing
                // even though the token had survived.
                //
                // The factory reset runs INSIDE the reset, so under the provisioning
                // lock. Cancelling was not enough: `reset()` returned control before the
                // purge, and a SETUP message arriving in that interval would start a
                // Quick Connect that could authenticate after the token was erased —
                // erased last — and resurrect the just-deleted account. The
                // coordinator no longer accepts any request before everything is cleared.
                withContext(NonCancellable) {
                    companionSetupCoordinator.reset { localDataReset.factoryReset() }
                }
            } finally {
                _signingOut.value = false
                onComplete()
            }
        }
    }
}

private fun SyncLogEntity.toUi() = SyncLogEntry(
    id = id,
    startedAtEpochMs = startedAtEpochMs,
    finishedAtEpochMs = finishedAtEpochMs,
    added = added,
    removed = removed,
    failed = failed,
    deferredForBudget = deferredForBudget,
    outcome = outcome,
    message = message,
)

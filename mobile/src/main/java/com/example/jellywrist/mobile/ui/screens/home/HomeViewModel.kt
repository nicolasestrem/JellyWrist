package com.example.jellywrist.mobile.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.data.MobilePrefs
import com.example.jellywrist.mobile.datalayer.WatchDiscovery
import com.example.jellywrist.mobile.datalayer.WatchSetupController
import com.example.jellywrist.mobile.datalayer.WatchSetupState
import com.example.jellywrist.mobile.datalayer.WatchStatusRepository
import com.example.jellywrist.mobile.ui.OpState
import com.example.jellywrist.mobile.ui.toOpState
import com.example.jellywrist.wire.PinCommand
import com.example.jellywrist.wire.WireErrors
import com.example.jellywrist.wire.WireSyncBlockers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * What the phone can currently say about its link to a watch.
 *
 * Every failure used to collapse into `status == null`, which the screen rendered as a single
 * sentence blaming Bluetooth pairing. Each variant here maps to one actionable sentence, and
 * only one is ever shown.
 */
sealed interface WatchAvailability {
    /** No discovery has completed yet — the first refresh is still in flight. */
    data object Unknown : WatchAvailability

    /**
     * No Wear OS node is connected to this phone.
     *
     * Not "unpaired": the Data Layer cannot distinguish a phone that never had a watch from
     * one whose watch is off, asleep, or out of range — see [WatchDiscovery.NoWatchConnected].
     */
    data object NoWatchConnected : WatchAvailability

    /** A watch is connected but does not run JellyWrist. */
    data class AppMissing(val watchName: String) : WatchAvailability

    /** The watch was found and the request was sent, but no reply arrived. */
    data object NotAnswering : WatchAvailability

    /** The discovery call itself failed — the fault is on this phone, not on the watch. */
    data object DiscoveryFailed : WatchAvailability

    /** The watch answered. */
    data object Reachable : WatchAvailability
}

/**
 * Whether this state describes a link the user cannot currently rely on.
 *
 * [WatchAvailability.Unknown] is excluded on purpose: an in-flight first refresh is not yet
 * evidence of anything, and flagging it would make the card cry wolf on every cold start.
 */
fun WatchAvailability.isFault(): Boolean =
    this !is WatchAvailability.Reachable && this !is WatchAvailability.Unknown

/**
 * Whether a setup failure says something about the phone↔watch link rather than about the
 * server, the account or this app.
 *
 * These are the two errors the availability line already states in its own words, which is why
 * the watch card suppresses them underneath it — and the two that a completed setup attempt can
 * therefore re-diagnose.
 */
fun String.isLinkError(): Boolean =
    this == WireErrors.WATCH_UNREACHABLE || this == WireErrors.TIMEOUT

/**
 * The availability a completed status exchange proves.
 *
 * A `null` discovery means the discovery call threw: the repository stores the outcome before
 * rethrowing, so the only way it stays unset after a completed refresh is a failure of the
 * query itself.
 */
internal fun availabilityOf(discovery: WatchDiscovery?, result: Result<Unit>): WatchAvailability = when {
    result.isSuccess -> WatchAvailability.Reachable
    discovery is WatchDiscovery.NoWatchConnected -> WatchAvailability.NoWatchConnected
    discovery is WatchDiscovery.NodesWithoutApp -> discovery.names
        .takeIf { it.isNotEmpty() }
        ?.let { WatchAvailability.AppMissing(it.joinToString(", ")) }
        ?: WatchAvailability.NoWatchConnected
    discovery is WatchDiscovery.Found -> WatchAvailability.NotAnswering
    else -> WatchAvailability.DiscoveryFailed
}

/**
 * The availability a *failed* setup attempt proves, or `null` to keep the current one.
 *
 * The distinction is what the failure happened after. Every error but the two link ones is
 * raised once the watch has already replied, so the watch is demonstrably connected and running
 * JellyWrist; leaving an earlier "app missing" on screen would be the stale diagnosis this path
 * exists to remove. `NO_SESSION` is the one failure raised before discovery runs, so it proves
 * nothing about the link and must change nothing.
 */
internal fun availabilityAfterFailedSetup(error: String, discovery: WatchDiscovery?): WatchAvailability? = when {
    error == WireErrors.NO_SESSION -> null
    discovery is WatchDiscovery.Found && !error.isLinkError() -> WatchAvailability.Reachable
    else -> availabilityOf(discovery, Result.failure(IllegalStateException(error)))
}

/** Carries the remote actions of the phone's main screen.  */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val watchStatusRepository: WatchStatusRepository,
    private val watchSetupController: WatchSetupController,
    private val authRepository: AuthRepository,
    private val mobilePrefs: MobilePrefs,
) : ViewModel() {
    private data class SyncConsent(
        val allowMetered: Boolean = false,
        val allowLowBattery: Boolean = false,
    )

    val status = watchStatusRepository.status
    val setupState: StateFlow<WatchSetupState> = watchSetupController.state
    private val _refreshing = MutableStateFlow(false)
    val refreshing = _refreshing.asStateFlow()

    /**
     * Diagnosis of the last refresh.
     *
     * `refresh()` used to discard the repository's `Result` entirely, so the screen could only
     * observe "no status" and had nothing to say about why.
     */
    private val _watchAvailability = MutableStateFlow<WatchAvailability>(WatchAvailability.Unknown)
    val watchAvailability: StateFlow<WatchAvailability> = _watchAvailability.asStateFlow()

    /**
     * True when [status] holds figures the watch is no longer confirming.
     *
     * [WatchStatusRepository] keeps the last answer across later failures — deliberately, the
     * storage and sync numbers stay worth showing — but the card used to render the diagnosis
     * only while `status` was still null, so after one successful reply a watch that went
     * silent left the screen displaying stale data as though it were live.
     */
    val statusIsStale: StateFlow<Boolean> = combine(status, watchAvailability) { status, availability ->
        status != null && availability.isFault()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Lifecycle of the "sync now" command — [OpState.Success] means **queued**. */
    private val _syncOp = MutableStateFlow<OpState>(OpState.Idle)
    val syncOp: StateFlow<OpState> = _syncOp.asStateFlow()

    private val _meteredConfirmationRequired = MutableStateFlow(false)
    val meteredConfirmationRequired = _meteredConfirmationRequired.asStateFlow()
    private val _lowBatteryConfirmationRequired = MutableStateFlow(false)
    val lowBatteryConfirmationRequired = _lowBatteryConfirmationRequired.asStateFlow()
    private var pendingSyncConsent = SyncConsent()

    /** Outcome of the last unpin; in-flight ids are in [pendingCollectionIds]. */
    private val _unpinOp = MutableStateFlow<OpState>(OpState.Idle)
    val unpinOp: StateFlow<OpState> = _unpinOp.asStateFlow()

    /** Collections with an unpin round trip in flight — their row control is disabled. */
    private val _pendingCollectionIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingCollectionIds: StateFlow<Set<String>> = _pendingCollectionIds.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val result = watchStatusRepository.refresh()
                _watchAvailability.value = availabilityOf(watchStatusRepository.discovery.value, result)
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Runs provisioning and republishes the diagnosis it actually observed.
     *
     * `watchAvailability` used to be written only by [refresh], while setup ran a discovery of
     * its own and threw the answer away. So a user told "JellyWrist is not installed on your
     * watch", who installed it and tapped Set up, kept reading that sentence for the whole new
     * exchange — and if the watch then failed to answer, [SetupButton] suppressed the timeout
     * as a duplicate of the very line that was now wrong.
     *
     * Only the two link-level outcomes republish. `AUTH_FAILED`, `EXPIRED` and a watch-reported
     * error all arrive *after* the watch replied, so the link is demonstrably fine and the
     * availability the refresh established still holds; `NO_SESSION` never reaches discovery.
     * Awaiting the [Job] rather than a terminal [WatchSetupState] is deliberate: `state` is a
     * `StateFlow` still holding the previous attempt's terminal value.
     */
    fun setupWatch() {
        viewModelScope.launch {
            watchSetupController.startSetup().join()
            when (val state = watchSetupController.state.value) {
                // A successful exchange is *not* proof the card is showing live figures:
                // `publish()` fires a status refresh and ignores whether it answered, so
                // asserting `Reachable` here would clear `statusIsStale` over values the watch
                // never confirmed. Our own refresh settles the status and the availability
                // together, from the same `Result`.
                is WatchSetupState.Success -> refresh()
                is WatchSetupState.Failure -> republishAfterFailedSetup(state.error)
                else -> Unit // cancelled by a newer request: its own join() will publish
            }
        }
    }

    private fun republishAfterFailedSetup(error: String) {
        availabilityAfterFailedSetup(error, watchStatusRepository.discovery.value)
            ?.let { _watchAvailability.value = it }
    }

    fun syncNow() = requestSync(SyncConsent())

    fun confirmMeteredSync() {
        _meteredConfirmationRequired.value = false
        requestSync(pendingSyncConsent.copy(allowMetered = true))
    }

    fun dismissMeteredConfirmation() {
        _meteredConfirmationRequired.value = false
    }

    fun confirmLowBatterySync() {
        _lowBatteryConfirmationRequired.value = false
        requestSync(pendingSyncConsent.copy(allowLowBattery = true))
    }

    fun dismissLowBatteryConfirmation() {
        _lowBatteryConfirmationRequired.value = false
    }

    private fun requestSync(consent: SyncConsent) {
        if (_syncOp.value is OpState.InFlight) return
        // Marked synchronously: a double tap must not launch two round trips.
        _syncOp.value = OpState.InFlight
        viewModelScope.launch {
            val previousLastSync = status.value?.lastSyncEpochMs
            _syncOp.value = watchStatusRepository.syncNow(
                allowMetered = consent.allowMetered,
                allowLowBattery = consent.allowLowBattery,
            ).toOpState()
            if (_syncOp.value is OpState.Success) {
                watchStatusRepository.pollSyncStatus(previousLastSync)
                when (status.value?.syncBlocker) {
                    WireSyncBlockers.METERED_CONFIRMATION_REQUIRED -> {
                        pendingSyncConsent = consent
                        _meteredConfirmationRequired.value = true
                        _lowBatteryConfirmationRequired.value = false
                    }
                    WireSyncBlockers.LOW_BATTERY_CONFIRMATION_REQUIRED -> {
                        pendingSyncConsent = consent
                        _meteredConfirmationRequired.value = false
                        _lowBatteryConfirmationRequired.value = true
                    }
                    else -> {
                        _meteredConfirmationRequired.value = false
                        _lowBatteryConfirmationRequired.value = false
                    }
                }
                if (_syncOp.value is OpState.Success) _syncOp.value = OpState.Idle
            }
        }
    }

    fun unpin(id: String, type: String, name: String) {
        if (id in _pendingCollectionIds.value) return
        // Guard and mark synchronously: a double tap must not launch two round trips.
        _pendingCollectionIds.update { it + id }
        _unpinOp.value = OpState.InFlight
        viewModelScope.launch {
            try {
                _unpinOp.value = watchStatusRepository.pin(PinCommand(id, type, name, pin = false)).toOpState()
            } finally {
                _pendingCollectionIds.update { it - id }
            }
        }
    }

    fun signOut(onComplete: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            mobilePrefs.setPinnedCertificate(null)
            onComplete()
        }
    }
}

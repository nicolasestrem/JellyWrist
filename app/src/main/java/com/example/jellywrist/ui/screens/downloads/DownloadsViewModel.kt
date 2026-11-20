package com.example.jellywrist.ui.screens.downloads

import androidx.lifecycle.ViewModel
import com.example.jellywrist.network.NetworkAwareness
import com.example.jellywrist.network.NetworkPolicy
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.sync.SyncScheduler
import com.example.jellywrist.sync.SyncState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * State of the Downloads screen (PLAN.md §8, task 6).
 *
 * Aggregates in-progress / completed / failed tracks, the storage gauge, and
 * the sync state. The screen depends only on this immutable object + lambda
 * callbacks (Wear Compose pattern, same as [SettingsUiState]).
 */
data class DownloadsUiState(
    /** Tracks pending or in progress (TO_DOWNLOAD, DOWNLOADING, PENDING_BUDGET). */
    val activeTracks: List<TrackUiItem> = emptyList(),
    /** Tracks ready for offline playback (READY + UNSUPPORTED). */
    val readyTracks: List<TrackUiItem> = emptyList(),
    /** Failed tracks (download failed). */
    val errorTracks: List<TrackUiItem> = emptyList(),
    /** Bytes used by the cache (READY + UNSUPPORTED). */
    val usedBytes: Long = 0L,
    /** Bytes available on the storage volume. */
    val availableDiskBytes: Long = 0L,
    /** Total size of the volume. */
    val totalDiskBytes: Long = 0L,
    /** Current sync state (IDLE, SYNCING, PAUSED, FAILED). */
    val syncState: SyncState = SyncState.IDLE,
)

/**
 * Displayable track item, with computed progress.
 *
 * The percentage progress is computed here for active tracks;
 * it is `-1f` if the total size is unknown (per the contract
 * of [com.example.jellywrist.download.DownloadProgress]).
 *
 * [artist] and [album] may be `null` (Jellyfin does not always populate them).
 */
data class TrackUiItem(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val state: TrackState,
    val sizeBytes: Long,
    val downloadedBytes: Long,
    /** Progress percentage, or `-1f` if unknown. */
    val percent: Float,
)

/**
 * Downloads screen ViewModel (PLAN.md §8, task 6).
 *
 * Observes track states via Room ([TrackDao]), Media3 progress
 * (via [TrackDownloader]), storage (via [DeviceStorage]) and sync
 * state (via [SyncScheduler]).
 */
@HiltViewModel
class DownloadsViewModel @Inject constructor(
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val deviceStorage: DeviceStorage,
    private val syncScheduler: SyncScheduler,
    private val networkAwareness: NetworkAwareness,
) : ViewModel() {

    /** `true` when a manual sync awaits "metered network" consent (REQ-007). */
    private val _meteredPrompt = MutableStateFlow(false)
    val meteredPrompt: StateFlow<Boolean> = _meteredPrompt.asStateFlow()
    private val _batteryWarning = MutableStateFlow(false)
    val batteryWarning: StateFlow<Boolean> = _batteryWarning.asStateFlow()
    private var lowBatteryConsentForMeteredPrompt = false
    /** Scratch signal to force a re-fetch after deletion. */
    private val _refreshSignal = MutableStateFlow(0L)

    /**
     * Main screen state, built by combining several sources.
     *
     * [SharingStarted.WhileSubscribed(5_000)] keeps Flows active for 5 s after
     * the last observation to avoid re-subscriptions on Wear OS scroll.
     */
    val uiState: StateFlow<DownloadsUiState> = combine(
        trackDao.observeByState(TrackState.TO_DOWNLOAD),
        trackDao.observeByState(TrackState.DOWNLOADING),
        trackDao.observeByState(TrackState.PENDING_BUDGET),
        trackDao.observeByState(TrackState.READY),
        trackDao.observeByState(TrackState.UNSUPPORTED),
        trackDao.observeByState(TrackState.ERROR),
        trackDao.observeStoredBytes(),
        trackDownloader.observeProgress(),
        _refreshSignal,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val toDownload = args[0] as List<com.example.jellywrist.data.db.TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val downloading = args[1] as List<com.example.jellywrist.data.db.TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val pendingBudget = args[2] as List<com.example.jellywrist.data.db.TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val ready = args[3] as List<com.example.jellywrist.data.db.TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val unsupported = args[4] as List<com.example.jellywrist.data.db.TrackEntity>
        @Suppress("UNCHECKED_CAST")
        val error = args[5] as List<com.example.jellywrist.data.db.TrackEntity>
        val usedBytes = args[6] as Long
        @Suppress("UNCHECKED_CAST")
        val progress = args[7] as List<com.example.jellywrist.download.DownloadProgress>
        // _refreshSignal (args[8]) consumed to trigger recomposition

        val progressMap = progress.associateBy { it.trackId }

        // Active tracks = TO_DOWNLOAD + DOWNLOADING + PENDING_BUDGET
        val activeTracks = (toDownload + downloading + pendingBudget).map { track ->
            val dl = progressMap[track.id]
            TrackUiItem(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                state = track.state,
                sizeBytes = track.sizeBytes,
                downloadedBytes = dl?.bytesDownloaded ?: track.downloadedBytes,
                percent = dl?.percent
                    ?: if (track.sizeBytes > 0) (track.downloadedBytes.toFloat() / track.sizeBytes.toFloat()) * 100f
                    else -1f,
            )
        }

        // Ready tracks = READY + UNSUPPORTED
        val readyTracks = (ready + unsupported).map { track ->
            TrackUiItem(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                state = track.state,
                sizeBytes = track.sizeBytes,
                downloadedBytes = track.sizeBytes,
                percent = 100f,
            )
        }

        // Failed tracks
        val errorTracks = error.map { track ->
            TrackUiItem(
                id = track.id,
                title = track.title,
                artist = track.artist,
                album = track.album,
                state = track.state,
                sizeBytes = track.sizeBytes,
                downloadedBytes = track.downloadedBytes,
                percent = -1f,
            )
        }

        DownloadsUiState(
            activeTracks = activeTracks,
            readyTracks = readyTracks,
            errorTracks = errorTracks,
            usedBytes = usedBytes,
            availableDiskBytes = deviceStorage.availableBytes(),
            totalDiskBytes = deviceStorage.totalBytes(),
        )
    }.combine(syncScheduler.observeSyncState()) { downloadsUiState, syncState ->
        downloadsUiState.copy(syncState = syncState)
        // Off Main.immediate: this combine re-runs on every Room emission AND every Media3
        // progress tick — six list mappings plus StatFs have no business on the main thread.
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    /** Removes tracks from cache and queue. Idempotent (REQ-006). */
    fun removeTracks(trackIds: List<String>) {
        viewModelScope.launch {
            trackDownloader.remove(trackIds)
            _refreshSignal.value += 1
        }
    }

    /**
     * Triggers an immediate sync (PLAN.md §4.3).
     *
     * On a metered network (Bluetooth proxy, cellular), sync is **never** started
     * silently: explicit consent is requested first (REQ-007). The worker
     * will still attempt to acquire Wi-Fi; consent is only a fallback.
     */
    fun syncNow() {
        if (networkAwareness.shouldWarnBeforeManualSync()) {
            _batteryWarning.value = true
            return
        }
        doSyncNow(allowLowBattery = false)
    }

    private fun doSyncNow(allowLowBattery: Boolean) {
        val action = NetworkPolicy.decide(
            wasUnmetered = networkAwareness.isUnmetered.value,
            isUnmetered = networkAwareness.isUnmetered.value,
            syncRunning = false,
            manualRequested = true,
        )
        if (action == NetworkPolicy.Action.WARN_METERED) {
            lowBatteryConsentForMeteredPrompt = allowLowBattery
            _meteredPrompt.value = true
            return
        }
        viewModelScope.launch { syncScheduler.syncNow(allowLowBattery = allowLowBattery) }
    }

    fun confirmBatterySync() {
        _batteryWarning.value = false
        doSyncNow(allowLowBattery = true)
    }

    fun dismissBatteryWarning() {
        _batteryWarning.value = false
    }

    fun confirmMeteredSync() {
        _meteredPrompt.value = false
        viewModelScope.launch {
            syncScheduler.syncNow(
                allowMetered = true,
                allowLowBattery = lowBatteryConsentForMeteredPrompt,
            )
            lowBatteryConsentForMeteredPrompt = false
        }
    }

    fun dismissMeteredPrompt() {
        _meteredPrompt.value = false
    }
}

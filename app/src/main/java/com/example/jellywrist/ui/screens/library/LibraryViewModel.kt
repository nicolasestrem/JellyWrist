package com.example.jellywrist.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.auth.AuthSession
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.LibraryRepository
import com.example.jellywrist.network.NetworkAwareness
import com.example.jellywrist.network.NetworkPolicy
import com.example.jellywrist.sync.SyncScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Library tabs: the three collection types + the **Songs** tab
 * (product request 2026-08-08: browse individual songs and launch a random
 * shuffle).
 */
enum class LibraryTab { PLAYLISTS, ALBUMS, ARTISTS, SONGS }

/**
 * Library ViewModel: playlists, albums, artists, songs.
 *
 * Cache-first (PLAN.md §8 task 5): each tab is fed by a Room Flow
 * immediately consumable offline; [refresh] triggers a server fetch.
 */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler,
    private val networkAwareness: NetworkAwareness,
) : ViewModel() {

    private val _selectedTab = MutableStateFlow(LibraryTab.PLAYLISTS)
    val selectedTab: StateFlow<LibraryTab> = _selectedTab

    private val _session = MutableStateFlow(authRepository.currentSession())
    val session: StateFlow<AuthSession?> = _session

    /** Random shuffle in progress ("500 random songs" button). */
    private val _authExpired = MutableStateFlow(false)
    val authExpired: StateFlow<Boolean> = _authExpired
    private val _randomSyncState = MutableStateFlow<RandomSyncState>(RandomSyncState.Idle)
    val randomSyncState: StateFlow<RandomSyncState> = _randomSyncState

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<LibraryUiState> =
        _selectedTab.flatMapLatest { tab ->
            when (tab) {
                LibraryTab.SONGS -> libraryRepository.observeAllTracks().map { tracks ->
                    LibraryUiState(
                        // Full-list sort with a lowercase() allocation per element: worth a
                        // background dispatcher — see the flowOn below.
                        songs = tracks.sortedBy { it.title.lowercase() },
                        baseUrl = _session.value?.baseUrl.orEmpty(),
                    )
                }

                else -> libraryRepository.observeCollections(tab.asCollectionType()).map { collections ->
                    LibraryUiState(
                        collections = collections,
                        baseUrl = _session.value?.baseUrl.orEmpty(),
                    )
                }
            }
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // PLAN.md §8 task 4: cache-first -> first display triggers a server fetch
        // to populate the Room cache for a fresh install.
        refreshAll()
    }

    fun selectTab(tab: LibraryTab) {
        _selectedTab.value = tab
        refresh()
    }

    /** Refreshes the current tab from the server (errors leave the cache intact). */
    fun refresh() {
        viewModelScope.launch {
            when (val tab = _selectedTab.value) {
                LibraryTab.SONGS -> libraryRepository.refreshSongs()
                else -> libraryRepository.refreshCollections(tab.asCollectionType())
            }
        }
    }

    /** Refreshes all categories (at startup). */
    fun refreshAll() {
        viewModelScope.launch {
            _session.value = authRepository.currentSession()
            CollectionType.entries.forEach { libraryRepository.refreshCollections(it) }
            libraryRepository.refreshSongs()
            // Detect expired session (review bot bug #13, round 2):
            // if the token was invalidated server-side, the library appears empty
            if (authRepository.currentSession() == null) _authExpired.value = true
        }
    }

    /**
     * "Sync N random songs" (product request 2026-08-08, sizes 50/100/200/500):
     * server-side shuffle, local pinned collection, then immediate sync — storage
     * budget applied as for any pin (REQ-008).
     *
     * On a metered network (Bluetooth proxy), nothing is sent without explicit consent
     * (REQ-007): the state goes through [RandomSyncState.MeteredPrompt].
     */
    fun syncRandomSongs(count: Int) {
        if (_randomSyncState.value == RandomSyncState.Picking) return

        // REQ-007: battery warning before manual sync (bug #10, round 2)
        if (networkAwareness.shouldWarnBeforeManualSync()) {
            _randomSyncState.value = RandomSyncState.BatteryWarning(count)
            return
        }

        requestRandomSync(count, allowLowBattery = false)
    }

    private fun requestRandomSync(count: Int, allowLowBattery: Boolean) {
        val action = NetworkPolicy.decide(
            wasUnmetered = networkAwareness.isUnmetered.value,
            isUnmetered = networkAwareness.isUnmetered.value,
            syncRunning = false,
            manualRequested = true,
        )
        if (action == NetworkPolicy.Action.WARN_METERED) {
            _randomSyncState.value = RandomSyncState.MeteredPrompt(count, allowLowBattery)
            return
        }
        launchRandomSync(count, allowMetered = false, allowLowBattery = allowLowBattery)
    }

    /** The user dismisses the battery warning and launches the sync. */
    fun confirmBatteryWarning() {
        val warning = _randomSyncState.value as? RandomSyncState.BatteryWarning ?: return
        requestRandomSync(warning.count, allowLowBattery = true)
    }

    fun dismissBatteryWarning() {
        if (_randomSyncState.value is RandomSyncState.BatteryWarning) {
            _randomSyncState.value = RandomSyncState.Idle
        }
    }

    /** Metered network consent granted for the pending shuffle. */
    fun confirmRandomSyncOnMetered() {
        val prompt = _randomSyncState.value as? RandomSyncState.MeteredPrompt ?: return
        launchRandomSync(
            prompt.count,
            allowMetered = true,
            allowLowBattery = prompt.allowLowBattery,
        )
    }

    /** Consent refused: return to the idle state. */
    fun dismissRandomSyncPrompt() {
        if (_randomSyncState.value is RandomSyncState.MeteredPrompt) {
            _randomSyncState.value = RandomSyncState.Idle
        }
    }

    private fun launchRandomSync(count: Int, allowMetered: Boolean, allowLowBattery: Boolean) {
        _randomSyncState.value = RandomSyncState.Picking
        viewModelScope.launch {
            libraryRepository.syncRandomSongs(count).fold(
                onSuccess = { picked ->
                    syncScheduler.syncNow(
                        allowMetered = allowMetered,
                        allowLowBattery = allowLowBattery,
                    )
                    _randomSyncState.value = RandomSyncState.Enqueued(picked)
                },
                onFailure = { e ->
                    _randomSyncState.value =
                        RandomSyncState.Failed(e.message ?: e.javaClass.simpleName)
                },
            )
        }
    }

    private fun LibraryTab.asCollectionType(): CollectionType = when (this) {
        LibraryTab.PLAYLISTS -> CollectionType.PLAYLIST
        LibraryTab.ALBUMS -> CollectionType.ALBUM
        LibraryTab.ARTISTS -> CollectionType.ARTIST
        LibraryTab.SONGS -> error("SONGS is not a collection")
    }
}

/** State of the random shuffle, rendered by the Songs tab. */
sealed interface RandomSyncState {
    data object Idle : RandomSyncState

    data object Picking : RandomSyncState

    /** Metered network: consent required before shuffling [count] songs (REQ-007). */
    data class MeteredPrompt(val count: Int, val allowLowBattery: Boolean = false) : RandomSyncState

    /** Low battery: warning before sync (REQ-007, bug #10 round 2). */
    data class BatteryWarning(val count: Int) : RandomSyncState

    data class Enqueued(val count: Int) : RandomSyncState

    data class Failed(val message: String) : RandomSyncState
}

/**
 * State of the library screen — purely declarative.
 *
 * [collections] feeds the Playlists/Albums/Artists tabs; [songs] the Songs tab.
 */
data class LibraryUiState(
    val collections: List<CollectionEntity> = emptyList(),
    val songs: List<TrackEntity> = emptyList(),
    val baseUrl: String = "",
)

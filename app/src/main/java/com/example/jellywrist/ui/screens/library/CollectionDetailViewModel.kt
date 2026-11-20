package com.example.jellywrist.ui.screens.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.repo.LibraryRepository
import com.example.jellywrist.sync.ConsentPrompt
import com.example.jellywrist.sync.KeepOnWatchCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Collection detail ViewModel: list of tracks observed in real time.
 *
 * [collectionId] is extracted from navigation arguments (JellyNav).
 *
 * PLAN.md §8 task 10: collection detail.
 */
@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class CollectionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val authRepository: AuthRepository,
    private val keepOnWatch: KeepOnWatchCoordinator,
) : ViewModel() {
    val collectionId: String =
        savedStateHandle[com.example.jellywrist.ui.nav.JellyRoutes.ARG_COLLECTION_ID]
            ?: error("collectionId required")

    /** Observed collection for the name and cover art. */
    val collection: StateFlow<CollectionEntity?> =
        libraryRepository.observeCollection(collectionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Tracks of the collection, sorted by sortIndex. */
    val tracks: StateFlow<List<TrackEntity>> =
        libraryRepository.observeTracks(collectionId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Base URL for images.
     *
     * `AuthRepository.currentSession()` is synchronous (Keystore read), not a Flow:
     * we wrap it in a `MutableStateFlow` initialized at construction.
     */
    private val _baseUrl = MutableStateFlow(authRepository.currentSession()?.baseUrl.orEmpty())

    val baseUrl: StateFlow<String> = _baseUrl

    private val _refreshError = MutableStateFlow<String?>(null)

    /** Error from the last server refresh, `null` on success — displayed by the screen. */
    val refreshError: StateFlow<String?> = _refreshError

    init {
        // Cache-first: opening a non-cached collection triggers a server fetch.
        // PLAN.md §8 task 5: the Room cache stays intact on network failure.
        refresh()
    }

    /** Consent the pending sync is waiting on, or `null`. Rendered as a prompt by the screen. */
    val consentPrompt: StateFlow<ConsentPrompt?> = keepOnWatch.prompt

    /**
     * Pins/unpins the collection (PLAN.md §4.1).
     *
     * Pinning first refreshes the tracks (sizes needed for capacity control, REQ-008) —
     * handled by [LibraryRepository.setPinned] — then starts a **manual** pass. It used to
     * schedule an automatic one, which carries the REQ-007 defaults of charger plus unmetered
     * Wi-Fi: the request simply waited, silently, until the watch was next docked, so the
     * button appeared to do nothing. The overnight cycle keeps those constraints; a pass the
     * user just asked for does not, and asks about battery or metered data instead.
     */
    fun setPinned(pinned: Boolean) {
        viewModelScope.launch {
            libraryRepository.setPinned(collectionId, pinned)
            if (pinned) keepOnWatch.request()
        }
    }

    /** The user granted the pending consent. */
    fun confirmConsent() {
        viewModelScope.launch { keepOnWatch.confirm() }
    }

    /** The user refused: nothing is downloaded, the pin stays. */
    fun dismissConsent() {
        keepOnWatch.dismiss()
    }
    /** Refreshes the tracks of this collection from the server. */
    fun refresh() {
        viewModelScope.launch {
            _baseUrl.value = authRepository.currentSession()?.baseUrl.orEmpty()
            libraryRepository.refreshTracks(collectionId).fold(
                onSuccess = { _refreshError.value = null },
                onFailure = { e ->
                    // Never fail silently: the technical cause is shown — it
                    // is what lets a self-hoster understand (DEC/REQ-008 spirit).
                    _refreshError.value = e.message ?: e.javaClass.simpleName
                },
            )
        }
    }
}

package com.example.jellywrist.mobile.ui.screens.browse

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.mobile.data.CatalogItem
import com.example.jellywrist.mobile.data.CatalogType
import com.example.jellywrist.mobile.R
import com.example.jellywrist.mobile.data.PhoneCatalogRepository
import com.example.jellywrist.mobile.datalayer.WatchStatusRepository
import com.example.jellywrist.mobile.ui.OpState
import com.example.jellywrist.mobile.ui.toOpState
import com.example.jellywrist.wire.PinCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Independent loading state for each catalog tab. */
sealed interface CatalogLoadState {
    /** Never loaded — distinct from [Loading] so the UI can tell "untouched" from "working". */
    data object Idle : CatalogLoadState
    data object Loading : CatalogLoadState
    data class Content(val items: List<CatalogItem>, val fetchedAtMs: Long) : CatalogLoadState

    /** [message] is a string resource — raw exception text is not UI copy. */
    data class Error(@StringRes val message: Int) : CatalogLoadState
}

/** Remote catalog and pin commands sent to the watch. */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val catalogRepository: PhoneCatalogRepository,
    private val authRepository: AuthRepository,
    private val watchStatusRepository: WatchStatusRepository,
) : ViewModel() {
    private val _tabs = MutableStateFlow<Map<CatalogType, CatalogLoadState>>(
        CatalogType.entries.associateWith { CatalogLoadState.Idle },
    )
    val tabs: StateFlow<Map<CatalogType, CatalogLoadState>> = _tabs.asStateFlow()
    val watchStatus = watchStatusRepository.status

    /** Items with a pin/unpin round trip in flight — their chip is disabled meanwhile. */
    private val _pendingIds = MutableStateFlow<Set<String>>(emptySet())
    val pendingIds: StateFlow<Set<String>> = _pendingIds.asStateFlow()

    /** Outcome of the last pin/unpin — [OpState.Error] is rendered above the list. */
    private val _pinOp = MutableStateFlow<OpState>(OpState.Idle)
    val pinOp: StateFlow<OpState> = _pinOp.asStateFlow()

    fun load(type: CatalogType, forceRefresh: Boolean = false) {
        when (val current = _tabs.value[type]) {
            is CatalogLoadState.Loading -> return
            is CatalogLoadState.Content ->
                // In-memory cache: a tab switch used to re-paginate the whole catalog
                // every time. An explicit refresh or an expired entry bypasses it.
                if (!forceRefresh && elapsedRealtimeMs() - current.fetchedAtMs < CACHE_TTL_MS) return
            else -> Unit
        }
        _tabs.value = _tabs.value + (type to CatalogLoadState.Loading)
        viewModelScope.launch {
            _tabs.value = _tabs.value + (type to catalogRepository.collections(type).fold(
                onSuccess = { CatalogLoadState.Content(it, fetchedAtMs = elapsedRealtimeMs()) },
                onFailure = { CatalogLoadState.Error(R.string.setup_error_unreachable) },
            ))
        }
    }

    fun togglePin(item: CatalogItem, pin: Boolean) {
        if (item.id in _pendingIds.value) return
        // Guard and mark synchronously: a double tap must not launch two round trips.
        _pendingIds.update { it + item.id }
        _pinOp.value = OpState.InFlight
        viewModelScope.launch {
            try {
                // No trailing refresh(): on an ok ack, pin() already awaits the follow-up
                // STATUS_REPLY the watch pushes — a second round trip was redundant.
                _pinOp.value = watchStatusRepository.pin(
                    PinCommand(item.id, item.type.name, item.name, item.trackCount, pin),
                ).toOpState()
            } finally {
                _pendingIds.update { it - item.id }
            }
        }
    }

    fun imageUrl(item: CatalogItem): String? =
        authRepository.currentSession()?.baseUrl?.let { catalogRepository.primaryImageUrl(it, item) }

    /** Monotonic clock, swappable in JVM tests (SystemClock is stubbed to 0 there). */
    internal var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    private fun elapsedRealtimeMs(): Long = clock()

    private companion object {
        /** Catalog changes rarely; five minutes keeps tab switches instant. */
        const val CACHE_TTL_MS = 5 * 60 * 1_000L
    }
}

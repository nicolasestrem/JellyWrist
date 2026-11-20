package com.example.jellywrist.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.repo.LibraryRepository
import com.example.jellywrist.playback.PlayerController
import com.example.jellywrist.playback.PlayerUiSnapshot
import com.google.android.horologist.audio.SystemAudioRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Player ViewModel (PLAN.md §8 task 9).
 *
 * Delegates everything to [PlayerController] (the service holds the real state); only
 * handles starting a collection and crown-driven volume. Volume goes through
 * [SystemAudioRepository] (Horologist): it's the **system** media-stream volume —
 * consistent with the physical buttons and the Output Switcher.
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerController: PlayerController,
    private val libraryRepository: LibraryRepository,
    private val authRepository: AuthRepository,
    private val audioRepository: SystemAudioRepository,
) : ViewModel() {

    val snapshot: StateFlow<PlayerUiSnapshot> = playerController.snapshot

    init {
        // Immediately reflect playback that's already in progress (returning from the watch face).
        playerController.connect()
    }

    /**
     * Starts playback of a collection from track [startIndex] (index in the **full**
     * list). Since the playable queue is filtered to `READY` tracks, the index is
     * translated: clicking a track that isn't ready starts on the next `READY` track.
     */
    fun playCollection(collectionId: String, startIndex: Int = 0) {
        val baseUrl = authRepository.currentSession()?.baseUrl ?: return
        viewModelScope.launch {
            val tracks = libraryRepository.observeTracks(collectionId).first()
            val playable = tracks.filter { it.state == TrackState.READY }
            if (playable.isEmpty()) return@launch
            val clickedId = tracks.getOrNull(startIndex)?.id
            val playableIndex = playable.indexOfFirst { it.id == clickedId }
                .takeIf { it >= 0 }
                ?: playable.indexOfFirst { candidate ->
                    tracks.indexOfFirst { it.id == candidate.id } >= startIndex
                }.coerceAtLeast(0)
            playerController.playQueue(playable, baseUrl, playableIndex)
        }
    }
    fun togglePlayPause() = playerController.togglePlayPause()

    fun next() = playerController.next()

    fun previous() = playerController.previous()

    /** Seek within the current track (player progress bar). */
    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun toggleShuffle() = playerController.toggleShuffle()

    fun cycleRepeat() = playerController.cycleRepeat()

    /** Rotary crown = volume (PLAN.md §5): each detent adjusts the system volume. */
    fun onRotaryDelta(delta: Float) {
        if (delta > 0f) audioRepository.increaseVolume() else audioRepository.decreaseVolume()
    }
}

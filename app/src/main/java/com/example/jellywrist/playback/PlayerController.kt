package com.example.jellywrist.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.jellywrist.data.db.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Observable player state, consumed by the Player screen. */
data class PlayerUiSnapshot(
    val title: String? = null,
    val artist: String? = null,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val hasQueue: Boolean = false,
)

/**
 * [MediaController] facade for the playback service (PLAN.md §8 task 9).
 *
 * The controller is connected lazily on first use then retained: the service stays
 * controllable from any screen without reconnecting. Media3 requires the main thread —
 * all commands and state reads are confined to it
 * ([ContextCompat.getMainExecutor], ticker on [Dispatchers.Main]).
 */
@Singleton
class PlayerController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appScope: CoroutineScope,
) {

    private val _snapshot = MutableStateFlow(PlayerUiSnapshot())
    val snapshot: StateFlow<PlayerUiSnapshot> = _snapshot.asStateFlow()

    private var controller: MediaController? = null
    private var connecting = false
    private var tickerStarted = false

    /**
     * Pending actions during async connection: `buildAsync` takes a few hundred ms, and
     * the normal "detail → track → player" flow emits `connect()` then `playQueue()`
     * back to back — losing the second would make the tap silent. Drained in order on
     * connection. Access confined to the main thread.
     */
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()

    /** Connects the controller to the service if needed, then runs [action]. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { return action(it) }
        pendingActions.addLast(action)
        if (connecting) return
        connecting = true

        val token = SessionToken(context, ComponentName(context, JellyPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                val c = runCatching { future.get() }.getOrNull()
                connecting = false
                if (c == null) {
                    pendingActions.clear()
                    return@addListener
                }
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish(c)
                })
                startPositionTicker()
                publish(c)
                while (pendingActions.isNotEmpty()) {
                    pendingActions.removeFirst().invoke(c)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Replaces the queue with [tracks] (filtered to `READY` by [MediaItems]) and starts playback. */
    fun playQueue(tracks: List<TrackEntity>, baseUrl: String, startIndex: Int = 0) =
        withController { c ->
            val items = MediaItems.playableQueue(tracks, baseUrl)
            if (items.isEmpty()) return@withController
            c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0L)
            c.prepare()
            c.play()
        }

    fun togglePlayPause() = withController { c -> if (c.isPlaying) c.pause() else c.play() }

    fun next() = withController { it.seekToNextMediaItem() }

    fun previous() = withController { it.seekToPreviousMediaItem() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs) }

    fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun cycleRepeat() = withController {
        it.repeatMode = when (it.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Wakes the connection (Player screen shown) to reflect ongoing playback. */
    fun connect() = withController { publish(it) }

    fun release() {
        controller?.release()
        controller = null
    }

    private fun publish(c: MediaController) {
        val metadata: MediaMetadata = c.mediaMetadata
        val item: MediaItem? = c.currentMediaItem
        _snapshot.value = PlayerUiSnapshot(
            title = metadata.title?.toString() ?: item?.mediaMetadata?.title?.toString(),
            artist = metadata.artist?.toString(),
            artworkUri = metadata.artworkUri?.toString(),
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition,
            durationMs = c.duration.coerceAtLeast(0L),
            shuffle = c.shuffleModeEnabled,
            repeatMode = c.repeatMode,
            hasQueue = c.mediaItemCount > 0,
        )
    }

    /**
     * Position tick during playback — Media3 does not notify every second.
     *
     * Gated on [MutableStateFlow.subscriptionCount]: the loop used to run for the whole
     * life of the process, waking the main thread once a second even with no Player
     * screen visible. `collectLatest` cancels the inner loop as soon as the last
     * collector leaves and restarts it when one returns.
     */
    private fun startPositionTicker() {
        if (tickerStarted) return
        tickerStarted = true
        appScope.launch(Dispatchers.Main) {
            _snapshot.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { observed ->
                    if (!observed) return@collectLatest
                    while (isActive) {
                        delay(POSITION_TICK_MS)
                        controller?.let { if (it.isPlaying) publish(it) }
                    }
                }
        }
    }

    private companion object {
        const val POSITION_TICK_MS = 1_000L
    }
}

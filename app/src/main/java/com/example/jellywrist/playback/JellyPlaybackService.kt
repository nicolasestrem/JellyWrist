package com.example.jellywrist.playback

import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.jellywrist.di.PlaybackDataSource
import com.google.android.horologist.audio.AudioOutput
import com.google.android.horologist.audio.OutputSwitcher
import com.google.android.horologist.audio.SystemAudioRepository
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.repo.AuthRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Playback service: ExoPlayer inside a [MediaSessionService] (PLAN.md §5, REQ-009).
 *
 * - **Cache only**: the injected [PlaybackDataSource] factory has no upstream — any
 *   attempt to play a non-downloaded track fails by construction (no streaming in v1).
 *   Queues are produced by [MediaItems.playableQueue], which filters to `READY` tracks.
 * - **Speaker guard** (REQ-009 / DEC-006): Horologist 0.7.15 no longer exposes
 *   `WearConfiguredPlayer`, so the guard is carried by [SpeakerGuardPlayer] — a
 *   [ForwardingPlayer] that, on `play()` with no Bluetooth output connected, does not
 *   start playback and opens the system Output Switcher ([OutputSwitcher]).
 * - **Audio offload**: decoding is delegated to the DSP while the AP sleeps — crucial
 *   for battery life on a wrist (PLAN.md §5).
 * - The queue and position are persisted in [QueueStore] and restored on startup.
 */
@AndroidEntryPoint
class JellyPlaybackService : MediaSessionService() {

    @Inject @PlaybackDataSource lateinit var playbackDataSourceFactory: DataSource.Factory

    @Inject lateinit var queueStore: QueueStore

    @Inject lateinit var trackDao: TrackDao

    @Inject lateinit var authRepository: AuthRepository

    /** Hilt singleton ([com.example.jellywrist.di.AudioModule]) — never closed here. */
    @Inject lateinit var audioRepository: SystemAudioRepository

    /**
     * Application scope for queue persistence: it outlives [serviceScope], so the last
     * save on destruction completes without blocking the main thread (`onDestroy` used to
     * `runBlocking` on a DataStore write).
     */
    @Inject lateinit var appScope: CoroutineScope

    private var mediaSession: MediaSession? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this).setDataSourceFactory(playbackDataSourceFactory),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        // Audio offload: also allowed in gapless; variable speed is a non-issue here.
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setAudioOffloadPreferences(
                AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                    .setIsGaplessSupportRequired(false)
                    .build(),
            )
            .build()

        val guarded = SpeakerGuardPlayer(applicationContext, exoPlayer, audioRepository)
        mediaSession = MediaSession.Builder(this, guarded).build()

        restoreQueue(guarded)
        persistPeriodically(guarded)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    /** Restores queue + position (PLAN.md §5: resume after restart). */
    private fun restoreQueue(player: Player) {
        serviceScope.launch {
            val saved = queueStore.load() ?: return@launch
            val session = mediaSession ?: return@launch
            // awaitSession: this scope runs on Main and the first Keystore read must not.
            val baseUrl = authRepository.awaitSession()?.baseUrl

            // Restore only on cold start (empty queue): never overwrite a queue the user
            // just started. Only tracks still READY are replayable; the saved order is
            // reapplied because Room does not guarantee the order of `byIds`.
            if (player.mediaItemCount == 0 && saved.trackIds.isNotEmpty() && baseUrl != null) {
                val byId = trackDao.byIds(saved.trackIds).associateBy { it.id }
                val ordered = saved.trackIds.mapNotNull { byId[it] }
                val items = MediaItems.playableQueue(ordered, baseUrl)
                if (items.isNotEmpty()) {
                    player.setMediaItems(items, saved.index.coerceIn(0, items.lastIndex), saved.positionMs)
                    player.prepare()
                }
            }
            session.player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                    persistNow(player)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    // Eager save on every pause/stop: the state is already current when the
                    // service is destroyed, so onDestroy never has to block for it.
                    if (!isPlaying) persistNow(player)
                }
            })
        }
    }

    /** Periodic position save during playback (every 5 s). */
    private fun persistPeriodically(player: Player) {
        serviceScope.launch {
            while (isActive) {
                delay(PERSIST_INTERVAL_MS)
                if (player.isPlaying) persistNow(player)
            }
        }
    }

    /**
     * Captures the player state synchronously (main thread), writes it on [appScope]:
     * the write survives the service scope being cancelled during destruction.
     */
    private fun persistNow(player: Player) {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (ids.isEmpty()) return
        val index = player.currentMediaItemIndex
        val position = player.currentPosition
        appScope.launch {
            queueStore.save(ids, index, position, collectionId = null)
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            // Nothing playing: no need to keep a foreground service alive.
            stopSelf()
        }
    }

    override fun onDestroy() {
        // Fire-and-forget on appScope: state capture is synchronous, the write outlives
        // this service — no more runBlocking on a main-thread lifecycle callback.
        mediaSession?.player?.let { persistNow(it) }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val PERSIST_INTERVAL_MS = 5_000L
    }
}

/**
 * Audio output guard (REQ-009): playback must **never** start on the watch speaker by
 * accident.
 *
 * On any play command with no Bluetooth headset connected ([AudioOutput.BluetoothHeadset]
 * or remote output), the command is neutralized and the system Output Switcher is opened
 * so the user can connect a device. The command is not "put on hold": the user will tap
 * play again once the headset is connected — standard Wear media-app behavior.
 */
private class SpeakerGuardPlayer(
    private val context: Context,
    player: Player,
    private val audioRepository: SystemAudioRepository,
) : ForwardingPlayer(player) {

    override fun play() {
        setPlayWhenReady(true)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (playWhenReady && !hasSafeAudioOutput()) {
            // No safe output: do not start, offer device connection instead.
            // `launchSystemMediaOutputSwitcherUi` is a Context extension declared in the
            // OutputSwitcher object; in its absence on the system image, Horologist falls
            // back to Bluetooth settings — in both cases a picker is shown.
            with(OutputSwitcher) { context.launchSystemMediaOutputSwitcherUi() }
            return
        }
        super.setPlayWhenReady(playWhenReady)
    }

    private fun hasSafeAudioOutput(): Boolean = when (audioRepository.audioOutput.value) {
        is AudioOutput.BluetoothHeadset, is AudioOutput.Remote -> true
        else -> false
    }
}

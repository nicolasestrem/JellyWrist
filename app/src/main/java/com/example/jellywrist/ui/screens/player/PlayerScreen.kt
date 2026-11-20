package com.example.jellywrist.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import coil3.compose.AsyncImage
import com.example.jellywrist.R
import com.example.jellywrist.playback.PlayerUiSnapshot
import com.example.jellywrist.ui.components.AuroraButton
import com.example.jellywrist.ui.components.AuroraSeekBar
import com.example.jellywrist.ui.components.formatDuration
import com.example.jellywrist.ui.theme.auroraScrimCenter

/**
 * Player screen — the "Aurora" visual showcase (PLAN.md §6).
 *
 * Faded artwork background under a centered radial scrim, title/artist, draggable
 * signature progress bar, transport row (previous / play-pause / next) and
 * secondary row (shuffle / repeat).
 *
 * - **Progress bar** ([AuroraSeekBar]): converts the touch position to
 *   milliseconds and routes the request to the ViewModel via [onSeekTo].
 *   Disabled while `durationMs == 0` (no media loaded).
 * - **Crown = volume** (PLAN.md §5): rotary events are captured by
 *   [onRotaryScrollEvent] on a focused node and routed to the system volume via
 *   the ViewModel ([com.google.android.horologist.audio.SystemAudioRepository]).
 * - Touch targets ≥ 40 dp; the central play/pause button is 56 dp and carries the
 *   signature gradient via [AuroraButton].
 * - Without an active queue, an empty state points to the library — never a
 *   silent screen.
 */
@Composable
fun PlayerScreen(
    snapshot: PlayerUiSnapshot,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onRotaryDelta: (Float) -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    val playPauseDescription = stringResource(
        if (snapshot.isPlaying) R.string.player_pause else R.string.player_play,
    )
    val repeatDescription = stringResource(
        if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) {
            R.string.player_repeat_one
        } else {
            R.string.player_repeat
        },
    )


    ScreenScaffold(timeText = { TimeText() }) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { event ->
                    onRotaryDelta(event.verticalScrollPixels)
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
        ) {
            // Artwork in the background, faded to preserve legibility (and ambient mode).
            if (snapshot.artworkUri != null) {
                AsyncImage(
                    model = snapshot.artworkUri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .alpha(BACKGROUND_ARTWORK_ALPHA),
                )
            }
            // Centered radial scrim: guarantees text contrast over the artwork.
            Box(modifier = Modifier.fillMaxSize().background(auroraScrimCenter()))

            // Empty state: no active queue — point the user to the library.
            if (!snapshot.hasQueue) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.player_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Box
            }

            // Main player content.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = snapshot.title ?: stringResource(R.string.player_unknown_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                if (snapshot.artist != null) {
                    Text(
                        text = snapshot.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(12.dp))

                AuroraSeekBar(
                    positionMs = snapshot.positionMs,
                    durationMs = snapshot.durationMs,
                    onSeek = onSeekTo,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = formatDuration(snapshot.positionMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = formatDuration(snapshot.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(8.dp))

                // Transport row.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = onPrevious,
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipPrevious,
                            contentDescription = stringResource(R.string.player_previous),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    AuroraButton(
                        onClick = onTogglePlayPause,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (snapshot.isPlaying) {
                                Icons.Rounded.Pause
                            } else {
                                Icons.Rounded.PlayArrow
                            },
                            contentDescription = playPauseDescription,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(30.dp),
                        )
                    }

                    Button(
                        onClick = onNext,
                        modifier = Modifier.size(48.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.SkipNext,
                            contentDescription = stringResource(R.string.player_next),
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }

                Spacer(Modifier.height(6.dp))

                // Secondary row: shuffle + repeat.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                ) {
                    Button(
                        onClick = onToggleShuffle,
                        modifier = Modifier.size(40.dp),
                        colors = if (snapshot.shuffle) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Shuffle,
                            contentDescription = stringResource(R.string.player_shuffle),
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    Button(
                        onClick = onCycleRepeat,
                        modifier = Modifier.size(40.dp),
                        colors = if (snapshot.repeatMode != Player.REPEAT_MODE_OFF) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.filledTonalButtonColors()
                        },
                    ) {
                        Icon(
                            imageVector = if (snapshot.repeatMode == Player.REPEAT_MODE_ONE) {
                                Icons.Rounded.RepeatOne
                            } else {
                                Icons.Rounded.Repeat
                            },
                            contentDescription = repeatDescription,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Artwork faded enough to stay legible and avoid ambient burn-in risk. */
private const val BACKGROUND_ARTWORK_ALPHA = 0.35f

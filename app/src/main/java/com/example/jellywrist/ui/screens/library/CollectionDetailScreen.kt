package com.example.jellywrist.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.example.jellywrist.R
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.sync.ConsentPrompt
import com.example.jellywrist.ui.components.AuroraChip
import com.example.jellywrist.ui.components.AuroraChipVariant
import com.example.jellywrist.ui.components.JellyImage
import com.example.jellywrist.ui.components.TrackStatusIcon
import com.example.jellywrist.ui.components.formatBytes
import com.example.jellywrist.ui.theme.auroraGradient
import com.example.jellywrist.ui.theme.auroraScrimBottom

/**
 * Collection detail screen ("Aurora"): hero header over cover art,
 * signature pin toggle, then track list with status icon.
 *
 * Tracks are displayed in server order
 * ([com.example.jellywrist.data.db.CollectionTrackEntity.sortIndex]). Download
 * state is indicated visually by [TrackStatusIcon]:
 * - [TrackState.READY]: playable track (downloaded)
 * - [TrackState.TO_DOWNLOAD] / [TrackState.DOWNLOADING]: queued / in progress
 * - [TrackState.UNSUPPORTED]: non-decodable codec, unplayable track (DEC-003 / DEC-004)
 * - [TrackState.ERROR]: download error
 * - [TrackState.PENDING_BUDGET]: awaiting space (REQ-008)
 *
 * Only `READY` tracks are clickable (navigation to the player); the
 * others are disabled and carry their status icon.
 *
 * The header cover art uses the first track's image (the [TrackEntity]
 * does not carry a collection identifier); lacking an image tag, a gradient
 * Aurora background and a music icon serve as illustration.
 *
 * PLAN.md §8 task 10: collection detail with track list.
 *
 * @param collectionName Collection name displayed in the hero header.
 * @param tracks List of tracks observed from Room.
 * @param baseUrl Server base URL (for images).
 * @param pinned Current pin state (sync to the watch).
 * @param estimatedBytes Cumulative server size — displayed BEFORE pinning so
 *        the user knows what they are about to download (PLAN.md §4.4).
 * @param refreshError Refresh error message to display, or `null`.
 * @param onPinnedChange Pin toggle callback.
 * @param onTrackClick Navigation callback to play a track.
 */
@Composable
fun CollectionDetailScreen(
    collectionName: String,
    tracks: List<TrackEntity>,
    baseUrl: String,
    pinned: Boolean,
    estimatedBytes: Long,
    refreshError: String?,
    consentPrompt: ConsentPrompt?,
    onPinnedChange: (Boolean) -> Unit,
    onConsentConfirm: () -> Unit,
    onConsentDismiss: () -> Unit,
    onTrackClick: (trackIndex: Int) -> Unit,
) {
    val scrollState = rememberScalingLazyListState()

    ScreenScaffold(
        timeText = { TimeText() },
        scrollState = scrollState,
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = 40.dp,
                start = 12.dp,
                end = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            state = scrollState,
        ) {
            // Hero header: full-width cover art + scrim + title and size.
            // TrackEntity does not carry a collection id, so we fall back to
            // the first track's image; without a tag, an Aurora gradient takes
            // over (PLAN.md §6 — Aurora visual identity).
            item {
                val heroTrack = tracks.firstOrNull()
                val heroTag = heroTrack?.imageTag
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) {
                    if (heroTrack != null && heroTag != null) {
                        JellyImage(
                            itemId = heroTrack.id,
                            baseUrl = baseUrl,
                            imageTag = heroTag,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(auroraGradient()),
                        )
                    }
                    // Bottom scrim: ensures text readability over the cover art.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(auroraScrimBottom()),
                    )
                    if (heroTrack == null || heroTag == null) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(36.dp),
                        )
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp),
                    ) {
                        Text(
                            text = collectionName,
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // Real size displayed BEFORE pinning: the user
                            // must know what they are about to download (PLAN.md §4.4).
                            text = pluralStringResource(
                                R.plurals.collection_detail_size,
                                tracks.size,
                                formatBytes(estimatedBytes),
                                tracks.size,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Pin toggle: AuroraChip Filled (gradient) when active,
            // Tonal at rest — the selection carries the Aurora signature.
            item {
                AuroraChip(
                    label = stringResource(R.string.collection_detail_pin_toggle),
                    onClick = { onPinnedChange(!pinned) },
                    variant = if (pinned) AuroraChipVariant.Filled else AuroraChipVariant.Tonal,
                    leadingIcon = if (pinned) Icons.Rounded.Check else Icons.Rounded.PushPin,
                    checked = pinned,
                )
            }

            // Keeping a collection starts a real sync, so anything it would spend has to be
            // agreed to first (REQ-007). Without this the pin would silently do nothing on a
            // metered network or a low battery — the very failure this screen is fixing.
            if (consentPrompt != null) {
                item {
                    KeepOnWatchConsent(
                        prompt = consentPrompt,
                        onConfirm = onConsentConfirm,
                        onDismiss = onConsentDismiss,
                    )
                }
            }

            if (refreshError != null) {
                item {
                    Text(
                        text = stringResource(R.string.collection_detail_refresh_error, refreshError),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (tracks.isEmpty()) {
                // Empty state: centered icon + label (no tracks in the collection).
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(36.dp),
                        )
                        Text(
                            text = stringResource(R.string.collection_detail_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(
                    count = tracks.size,
                    key = { index -> tracks[index].id },
                ) { index ->
                    val track = tracks[index]
                    AuroraChip(
                        label = track.title,
                        secondaryLabel = track.artist,
                        onClick = {
                            // Double guard: only READY tracks navigate to the player.
                            if (track.state == TrackState.READY) onTrackClick(index)
                        },
                        enabled = track.state == TrackState.READY,
                        leadingContent = {
                            JellyImage(
                                itemId = track.id,
                                baseUrl = baseUrl,
                                imageTag = track.imageTag,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                        },
                        trailing = {
                            TrackStatusIcon(state = track.state)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Consent asked for before a keep-on-watch sync spends battery or metered data (REQ-007).
 *
 * Rendered inline, below the toggle that raised it, so the control the user just touched
 * stays on screen instead of being replaced by the question.
 */
@Composable
private fun KeepOnWatchConsent(
    prompt: ConsentPrompt,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = when (prompt) {
                ConsentPrompt.Battery -> Icons.Rounded.BatteryAlert
                ConsentPrompt.Metered -> Icons.Rounded.CloudOff
            },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(
                when (prompt) {
                    ConsentPrompt.Battery -> R.string.downloads_battery_warning
                    ConsentPrompt.Metered -> R.string.downloads_metered_warning
                },
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(
                stringResource(
                    when (prompt) {
                        ConsentPrompt.Battery -> R.string.downloads_battery_confirm
                        ConsentPrompt.Metered -> R.string.downloads_metered_confirm
                    },
                ),
            )
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.filledTonalButtonColors(),
        ) {
            Text(stringResource(R.string.downloads_metered_cancel))
        }
    }
}

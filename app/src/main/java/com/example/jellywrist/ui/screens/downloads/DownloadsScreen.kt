package com.example.jellywrist.ui.screens.downloads

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.example.jellywrist.R
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.sync.SyncState
import com.example.jellywrist.ui.components.AuroraChip
import com.example.jellywrist.ui.components.AuroraChipVariant
import com.example.jellywrist.ui.components.GradientProgressRing
import com.example.jellywrist.ui.components.SectionHeader
import com.example.jellywrist.ui.components.StorageGauge
import com.example.jellywrist.ui.components.formatBytes

/**
 * Downloads screen — Aurora redesign (PLAN.md §8, task 6).
 *
 * Self-contained: owns its [ScreenScaffold] + [ScalingLazyColumn] whose scroll
 * state is now wired (fixes the unwired state of the previous version, which
 * prevented the crown and `TimeText` from reacting to scroll). Local duplicates
 * `SectionHeader`, `StorageGauge` and `formatBytes` are removed in favor of
 * the shared components from `com.example.jellywrist.ui.components`.
 *
 * Sections:
 * 1. Hero: annular storage gauge (used / total disk).
 * 2. Large "Sync" button (or metered-network / battery consent prompt).
 * 3. Active tracks (TO_DOWNLOAD, DOWNLOADING, PENDING_BUDGET) with progress ring.
 * 4. Ready tracks (READY, UNSUPPORTED).
 * 5. Error tracks (ERROR).
 */
@Composable
fun DownloadsScreen(
    state: DownloadsUiState,
    meteredPrompt: Boolean,
    batteryWarning: Boolean,
    onRemoveTracks: (List<String>) -> Unit,
    onSyncNow: () -> Unit,
    onConfirmMeteredSync: () -> Unit,
    onDismissMeteredPrompt: () -> Unit,
    onConfirmBatterySync: () -> Unit,
    onDismissBatteryWarning: () -> Unit,
) {
    val scrollState = rememberScalingLazyListState()

    ScreenScaffold(
        timeText = { TimeText() },
        scrollState = scrollState,
    ) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            state = scrollState,
        ) {
            // ── Hero: storage gauge ──
            item {
                StorageGauge(
                    usedBytes = state.usedBytes,
                    totalBytes = state.totalDiskBytes,
                    modifier = Modifier
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally),
                    center = {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = formatBytes(state.usedBytes),
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(
                                    R.string.settings_available,
                                    formatBytes(state.availableDiskBytes),
                                ) + " · " + stringResource(
                                    R.string.downloads_storage_total,
                                    formatBytes(state.totalDiskBytes),
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }

            // ── Sync CTA or consent prompts (REQ-007) ──
            when {
                batteryWarning -> item { BatteryWarningPrompt(onConfirmBatterySync, onDismissBatteryWarning) }
                meteredPrompt -> item { MeteredSyncPrompt(onConfirmMeteredSync, onDismissMeteredPrompt) }
                else -> item { SyncCta(state.syncState, onSyncNow) }
            }

            // ── Active tracks (TO_DOWNLOAD, DOWNLOADING, PENDING_BUDGET) ──
            if (state.activeTracks.isNotEmpty()) {
                val anyPending = state.activeTracks.any { it.state == TrackState.PENDING_BUDGET }
                item {
                    SectionHeader(
                        title = if (anyPending) {
                            stringResource(R.string.downloads_section_pending, state.activeTracks.size)
                        } else {
                            stringResource(R.string.downloads_section_downloading, state.activeTracks.size)
                        },
                        icon = if (anyPending) Icons.Rounded.HourglassEmpty else Icons.Rounded.Download,
                    )
                }
                items(state.activeTracks, key = { it.id }) { track ->
                    ActiveTrackChip(track)
                }
            }

            // ── Ready tracks (READY, UNSUPPORTED) ──
            if (state.readyTracks.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.downloads_section_ready, state.readyTracks.size),
                        icon = Icons.Rounded.Check,
                    )
                }
                items(state.readyTracks, key = { it.id }) { track ->
                    ReadyTrackChip(track, onRemoveTracks)
                }
            }

            // ── Error tracks (ERROR) ──
            if (state.errorTracks.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(R.string.downloads_section_errors, state.errorTracks.size),
                        icon = Icons.Rounded.ErrorOutline,
                    )
                }
                items(state.errorTracks, key = { it.id }) { track ->
                    ErrorTrackChip(track, onRemoveTracks)
                }
            }

            // ── Empty state ──
            if (state.activeTracks.isEmpty() && state.readyTracks.isEmpty() &&
                state.errorTracks.isEmpty() && state.syncState != SyncState.SYNCING
            ) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.downloads_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Large "Sync" button — full-pill with the signature gradient.
 *
 * Disabled (and visually dimmed) while a sync is in progress or paused;
 * the label reflects the state (`SYNCING` / `PAUSED` / idle).
 */
@Composable
private fun SyncCta(syncState: SyncState, onSyncNow: () -> Unit) {
    val enabled = syncState == SyncState.IDLE || syncState == SyncState.FAILED
    val label = when (syncState) {
        SyncState.QUEUED -> stringResource(R.string.downloads_queued)
        SyncState.SYNCING -> stringResource(R.string.downloads_syncing)
        SyncState.PAUSED -> stringResource(R.string.downloads_paused)
        else -> stringResource(R.string.downloads_sync_button)
    }
    AuroraChip(
        label = label,
        onClick = onSyncNow,
        enabled = enabled,
        variant = AuroraChipVariant.Filled,
        leadingIcon = Icons.Rounded.Sync,
        modifier = if (enabled) {
            Modifier.fillMaxWidth()
        } else {
            Modifier.fillMaxWidth().alpha(0.5f)
        },
    )
}

/** Metered-network consent prompt before manual sync (REQ-007). */
@Composable
private fun MeteredSyncPrompt(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.CloudOff,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.downloads_metered_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.downloads_metered_confirm))
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

/** Low-battery warning before manual sync. */
@Composable
private fun BatteryWarningPrompt(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.BatteryAlert,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.downloads_battery_warning),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.downloads_battery_confirm))
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

/**
 * Active track: progress ring + percentage (or hourglass + "Budget" label
 * if state is [TrackState.PENDING_BUDGET]).
 */
@Composable
private fun ActiveTrackChip(track: TrackUiItem) {
    if (track.state == TrackState.PENDING_BUDGET) {
        AuroraChip(
            label = track.title,
            secondaryLabel = buildSubtitle(track.artist, track.album),
            onClick = {},
            enabled = false,
            variant = AuroraChipVariant.Tonal,
            leadingIcon = Icons.Rounded.HourglassEmpty,
            trailing = {
                Text(
                    text = stringResource(R.string.downloads_budget_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            },
        )
    } else {
        val progress = if (track.percent >= 0f) (track.percent / 100f).coerceIn(0f, 1f) else 0f
        val trailingLabel = if (track.percent >= 0f) {
            "${track.percent.toInt()}%"
        } else {
            formatBytes(track.downloadedBytes)
        }
        AuroraChip(
            label = track.title,
            secondaryLabel = buildSubtitle(track.artist, track.album),
            onClick = {},
            enabled = false,
            variant = AuroraChipVariant.Tonal,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GradientProgressRing(progress = progress)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = trailingLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
        )
    }
}

/** Ready track: remove button; "Unplayable" if codec is unsupported. */
@Composable
private fun ReadyTrackChip(track: TrackUiItem, onRemoveTracks: (List<String>) -> Unit) {
    val unsupported = track.state == TrackState.UNSUPPORTED
    AuroraChip(
        label = track.title,
        secondaryLabel = if (unsupported) {
            stringResource(R.string.downloads_unplayable)
        } else {
            buildSubtitle(track.artist, track.album)
        },
        onClick = {},
        enabled = false,
        variant = AuroraChipVariant.Tonal,
        leadingIcon = if (unsupported) Icons.Rounded.Warning else null,
        trailing = {
            Button(
                onClick = { onRemoveTracks(listOf(track.id)) },
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.downloads_remove_track),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/** Error track: error icon (red) + remove button. */
@Composable
private fun ErrorTrackChip(track: TrackUiItem, onRemoveTracks: (List<String>) -> Unit) {
    AuroraChip(
        label = track.title,
        secondaryLabel = buildSubtitle(track.artist, track.album),
        onClick = {},
        enabled = false,
        variant = AuroraChipVariant.Tonal,
        leadingContent = {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(22.dp),
            )
        },
        trailing = {
            Button(
                onClick = { onRemoveTracks(listOf(track.id)) },
                modifier = Modifier.size(36.dp),
                colors = ButtonDefaults.filledTonalButtonColors(),
                contentPadding = PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.downloads_remove_track),
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/** Builds the "artist — album" subtitle. */
private fun buildSubtitle(artist: String?, album: String?): String? = when {
    artist != null && album != null -> "$artist — $album"
    artist != null -> artist
    album != null -> album
    else -> null
}

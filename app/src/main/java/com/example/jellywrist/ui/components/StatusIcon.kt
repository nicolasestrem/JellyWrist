package com.example.jellywrist.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import com.example.jellywrist.R
import com.example.jellywrist.data.db.TrackState

/**
 * Vector icon for a track's state — replaces the unicode glyphs (`● ✓`).
 *
 * - [TrackState.READY]: downloaded (tertiary green)
 * - [TrackState.TO_DOWNLOAD]: queued (gray)
 * - [TrackState.DOWNLOADING]: in progress (primary violet — the progress ring
 *   usually overlays it in the active row)
 * - [TrackState.UNSUPPORTED]: unplayable codec (gray, "blocked" icon)
 * - [TrackState.ERROR]: failed (`error` red)
 * - [TrackState.PENDING_BUDGET]: waiting for space (gray, hourglass)
 */
@Composable
fun TrackStatusIcon(
    state: TrackState,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val contentDescription = stringResource(state.statusDescriptionRes())
    val vector = state.iconVector()
    val tint = state.iconTint()
    Icon(
        imageVector = vector,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@StringRes
internal fun TrackState.statusDescriptionRes(): Int = when (this) {
    TrackState.READY -> R.string.track_status_ready
    TrackState.TO_DOWNLOAD -> R.string.track_status_to_download
    TrackState.DOWNLOADING -> R.string.track_status_downloading
    TrackState.UNSUPPORTED -> R.string.track_status_unsupported
    TrackState.ERROR -> R.string.track_status_error
    TrackState.PENDING_BUDGET -> R.string.track_status_pending_budget
}

@Composable
private fun TrackState.iconVector(): ImageVector = when (this) {
    TrackState.READY -> Icons.Rounded.DownloadDone
    TrackState.DOWNLOADING -> Icons.Rounded.Downloading
    TrackState.TO_DOWNLOAD -> Icons.Rounded.Download
    TrackState.UNSUPPORTED -> Icons.Rounded.Block
    TrackState.ERROR -> Icons.Rounded.ErrorOutline
    TrackState.PENDING_BUDGET -> Icons.Rounded.HourglassEmpty
}

@Composable
private fun TrackState.iconTint(): Color = when (this) {
    TrackState.READY -> MaterialTheme.colorScheme.tertiary
    TrackState.DOWNLOADING -> MaterialTheme.colorScheme.primary
    TrackState.ERROR -> MaterialTheme.colorScheme.error
    TrackState.TO_DOWNLOAD,
    TrackState.UNSUPPORTED,
    TrackState.PENDING_BUDGET -> MaterialTheme.colorScheme.onSurfaceVariant
}

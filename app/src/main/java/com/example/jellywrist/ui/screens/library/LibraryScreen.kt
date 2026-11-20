package com.example.jellywrist.ui.screens.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.TimeText
import com.example.jellywrist.R
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.ui.components.AuroraChip
import com.example.jellywrist.ui.components.AuroraChipVariant
import com.example.jellywrist.ui.components.JellyImage
import com.example.jellywrist.ui.components.SectionHeader
import com.example.jellywrist.ui.components.TrackStatusIcon
import com.example.jellywrist.ui.components.formatBytes
import com.example.jellywrist.ui.theme.auroraGradient

/**
 * Main library screen, "Aurora" redesign (PLAN.md §6; Songs tab
 * + random shuffle: product request 2026-08-08).
 *
 * Signature visual identity:
 * - brand header (primary `LibraryMusic` icon + app name);
 * - pill tab selector — the active tab carries the [auroraGradient]
 *   on `onPrimary` text, others a `surfaceContainerHigh` background;
 * - collections/songs rendered as [AuroraChip] (52 dp row) with 40 dp cover art;
 * - random shuffle section (50/100/200/500) + inline network/battery prompts;
 * - secondary navigation (Downloads / Settings) as [AuroraChipVariant.Outlined]
 *   at the bottom of the list.
 *
 * The public contract is **stateless**: state ([LibraryUiState], selected tab,
 * [RandomSyncState]) is injected; all callbacks are retained. Vertical scroll
 * goes through [ScalingLazyColumn] (the chin is handled natively).
 *
 * `onRefresh` is part of the stateless contract reserved for the refresh trigger
 * (future pull-to-refresh) but is not wired to a gesture in this layout —
 * identical behavior to the previous version.
 *
 * @param state Declarative state (collections, songs, baseUrl).
 * @param selectedTab Current tab (Playlists / Albums / Artists / Songs).
 * @param randomSyncState Random shuffle state (Idle, Picking, prompts, result).
 * @param onTabChange Tab selection.
 * @param onRefresh Refresh of the current tab (reserved contract).
 * @param onCollectionClick Open a collection's detail.
 * @param onSyncRandomSongs Shuffle N random songs.
 * @param onConfirmRandomMetered Consent to metered network for the pending shuffle.
 * @param onDismissRandomPrompt Refuse metered network consent.
 * @param onConfirmBatteryWarning Launch sync despite battery warning.
 * @param onDismissBatteryWarning Cancel sync after battery warning.
 * @param onNavigateDownloads Navigate to the Downloads screen.
 * @param onNavigateSettings Navigate to the Settings screen.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    selectedTab: LibraryTab,
    randomSyncState: RandomSyncState,
    onTabChange: (LibraryTab) -> Unit,
    onRefresh: () -> Unit,
    onCollectionClick: (collectionId: String) -> Unit,
    onSyncRandomSongs: (Int) -> Unit,
    onConfirmRandomMetered: () -> Unit,
    onDismissRandomPrompt: () -> Unit,
    onConfirmBatteryWarning: () -> Unit,
    onDismissBatteryWarning: () -> Unit,
    onNavigateDownloads: () -> Unit,
    onNavigateSettings: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
            state = scrollState,
        ) {
            // -- Brand header --
            item {
                SectionHeader(
                    title = stringResource(R.string.app_name),
                    icon = Icons.Rounded.LibraryMusic,
                )
            }

            // -- Tab selector (pills, horizontal scroll) --
            item {
                AuroraTabRow(
                    selected = selectedTab,
                    onSelect = onTabChange,
                )
            }

            // -- Current tab content --
            if (selectedTab == LibraryTab.SONGS) {
                item {
                    RandomSyncSection(
                        state = randomSyncState,
                        onPick = onSyncRandomSongs,
                        onConfirmMetered = onConfirmRandomMetered,
                        onDismissPrompt = onDismissRandomPrompt,
                        onConfirmBattery = onConfirmBatteryWarning,
                        onDismissBattery = onDismissBatteryWarning,
                    )
                }
                if (state.songs.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(
                        count = state.songs.size,
                        key = { index -> state.songs[index].id },
                    ) { index ->
                        SongChip(track = state.songs[index], baseUrl = state.baseUrl)
                    }
                }
            } else {
                if (state.collections.isEmpty()) {
                    item { EmptyState() }
                } else {
                    items(
                        count = state.collections.size,
                        key = { index -> state.collections[index].id },
                    ) { index ->
                        val collection = state.collections[index]
                        CollectionChip(
                            collection = collection,
                            baseUrl = state.baseUrl,
                            onClick = { onCollectionClick(collection.id) },
                        )
                    }
                }
            }

            // -- Secondary navigation (bottom of list) --
            item {
                AuroraChip(
                    label = stringResource(R.string.library_nav_downloads),
                    onClick = onNavigateDownloads,
                    leadingIcon = Icons.Rounded.Download,
                    variant = AuroraChipVariant.Outlined,
                )
            }
            item {
                AuroraChip(
                    label = stringResource(R.string.library_nav_settings),
                    onClick = onNavigateSettings,
                    leadingIcon = Icons.Rounded.Settings,
                    variant = AuroraChipVariant.Outlined,
                )
            }
        }
    }
}

/**
 * Horizontal pill tab selector.
 *
 * Four tabs do not fit on a 454 px round screen: horizontal scroll
 * (inherited from the previous version) guarantees access to the Songs tab. The active
 * tab carries the signature [auroraGradient] with `onPrimary` text; others a
 * `surfaceContainerHigh` background with `onSurfaceVariant` text.
 */
@Composable
private fun AuroraTabRow(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LibraryTab.entries.forEach { tab ->
            val isSelected = selected == tab
            SmallPill(
                selected = isSelected,
                onClick = { onSelect(tab) },
            ) {
                val tint = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
                Icon(
                    imageVector = tab.icon(),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = tab.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = tint,
                )
            }
        }
    }
}

/**
 * Reusable compact pill — tabs and random shuffle options.
 *
 * Selected: [auroraGradient] background. Otherwise: `surfaceContainerHigh` background.
 * Press indication uses alpha (no ripple), like [AuroraChip];
 * a disabled pill is dimmed to 40%.
 */
@Composable
private fun SmallPill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val alpha = when {
        !enabled -> 0.4f
        pressed -> 0.85f
        else -> 1f
    }
    val shape = RoundedCornerShape(50)
    Row(
        modifier = modifier
            .clip(shape)
            .then(
                if (selected) {
                    Modifier.background(auroraGradient())
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)
                },
            )
            .alpha(alpha)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Collection row: 40 dp cover art, name, estimated size + track count, and a
 * tertiary `Check` icon if the collection is pinned ("synced" badge PLAN.md §6).
 */
@Composable
private fun CollectionChip(
    collection: CollectionEntity,
    baseUrl: String,
    onClick: () -> Unit,
) {
    AuroraChip(
        label = collection.name,
        secondaryLabel = formatBytes(collection.estimatedBytes) +
            " · " + pluralStringResource(
                R.plurals.library_track_count,
                collection.trackCount,
                collection.trackCount,
            ),
        onClick = onClick,
        leadingContent = {
            JellyImage(
                itemId = collection.id,
                baseUrl = baseUrl,
                imageTag = collection.imageTag,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
        },
        trailing = {
            if (collection.pinned) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

/**
 * Song row (Songs tab) — browse only: individual playback is launched
 * from a collection's detail (no direct playback callback exposed by this
 * screen). The status icon ([TrackStatusIcon]) remains informative for all tracks;
 * the row is only interactive for ready tracks (`READY`).
 */
@Composable
private fun SongChip(track: TrackEntity, baseUrl: String) {
    AuroraChip(
        label = track.title,
        secondaryLabel = track.artist,
        onClick = {},
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

/**
 * "Sync random songs" section (product request 2026-08-08): header + picker
 * 50/100/200/500 + inline prompts (metered network / low battery) + shuffle
 * result. Exact callback wiring (REQ-007).
 */
@Composable
private fun RandomSyncSection(
    state: RandomSyncState,
    onPick: (Int) -> Unit,
    onConfirmMetered: () -> Unit,
    onDismissPrompt: () -> Unit,
    onConfirmBattery: () -> Unit,
    onDismissBattery: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.library_random_sync_label),
            icon = Icons.Rounded.Shuffle,
        )

        when (state) {
            is RandomSyncState.BatteryWarning -> PromptCard(
                icon = Icons.Rounded.BatteryAlert,
                message = stringResource(R.string.downloads_battery_warning),
                confirmLabel = stringResource(R.string.downloads_battery_confirm),
                cancelLabel = stringResource(R.string.downloads_metered_cancel),
                onConfirm = onConfirmBattery,
                onDismiss = onDismissBattery,
            )

            is RandomSyncState.MeteredPrompt -> PromptCard(
                icon = Icons.Rounded.CloudOff,
                message = stringResource(R.string.downloads_metered_warning),
                confirmLabel = stringResource(R.string.downloads_metered_confirm),
                cancelLabel = stringResource(R.string.downloads_metered_cancel),
                onConfirm = onConfirmMetered,
                onDismiss = onDismissPrompt,
            )

            else -> {
                // Picker: 50 / 100 / 200 / 500 (pills, disabled during shuffle).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(50, 100, 200, 500).forEach { count ->
                        SmallPill(
                            selected = false,
                            enabled = state != RandomSyncState.Picking,
                            onClick = { onPick(count) },
                        ) {
                            Text(
                                text = "$count",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
                // Current shuffle state.
                when (state) {
                    RandomSyncState.Picking -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = stringResource(R.string.downloads_syncing),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is RandomSyncState.Enqueued -> Text(
                        text = pluralStringResource(
                            R.plurals.library_random_sync_done,
                            state.count,
                            state.count,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    is RandomSyncState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    else -> Unit
                }
            }
        }
    }
}

/**
 * Inline prompt card (metered network consent or battery warning):
 * alert icon + centered message, then two [AuroraChip] — confirm (Filled) and
 * cancel (Outlined).
 */
@Composable
private fun PromptCard(
    icon: ImageVector,
    message: String,
    confirmLabel: String,
    cancelLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        AuroraChip(
            label = confirmLabel,
            onClick = onConfirm,
            variant = AuroraChipVariant.Filled,
        )
        AuroraChip(
            label = cancelLabel,
            onClick = onDismiss,
            variant = AuroraChipVariant.Outlined,
        )
    }
}

/** Centered empty state: brand icon + label. */
@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.LibraryMusic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Text(
            text = stringResource(R.string.library_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Localized label for a tab. */
@Composable
private fun LibraryTab.label(): String = when (this) {
    LibraryTab.PLAYLISTS -> stringResource(R.string.library_tab_playlists)
    LibraryTab.ALBUMS -> stringResource(R.string.library_tab_albums)
    LibraryTab.ARTISTS -> stringResource(R.string.library_tab_artists)
    LibraryTab.SONGS -> stringResource(R.string.library_tab_songs)
}

/** Vector icon for a tab. */
private fun LibraryTab.icon(): ImageVector = when (this) {
    LibraryTab.PLAYLISTS -> Icons.Rounded.QueueMusic
    LibraryTab.ALBUMS -> Icons.Rounded.Album
    LibraryTab.ARTISTS -> Icons.Rounded.Person
    LibraryTab.SONGS -> Icons.Rounded.MusicNote
}

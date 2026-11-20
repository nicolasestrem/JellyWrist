package com.example.jellywrist.mobile.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.jellywrist.mobile.R
import com.example.jellywrist.mobile.datalayer.WatchSetupState
import com.example.jellywrist.mobile.ui.OpState
import com.example.jellywrist.mobile.ui.wireErrorRes
import com.example.jellywrist.wire.WatchStatus
import com.example.jellywrist.wire.WireSyncBlockers

/** Status and control view for the paired watch. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBrowse: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsState()
    val setupState by viewModel.setupState.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val syncOp by viewModel.syncOp.collectAsState()
    val meteredConfirmationRequired by viewModel.meteredConfirmationRequired.collectAsState()
    val lowBatteryConfirmationRequired by viewModel.lowBatteryConfirmationRequired.collectAsState()
    val unpinOp by viewModel.unpinOp.collectAsState()
    val pendingIds by viewModel.pendingCollectionIds.collectAsState()
    val availability by viewModel.watchAvailability.collectAsState()
    val statusIsStale by viewModel.statusIsStale.collectAsState()
    var menuExpanded by remember { mutableStateOf(false) }
    if (meteredConfirmationRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMeteredConfirmation,
            title = { Text(stringResource(R.string.sync_metered_title)) },
            text = { Text(stringResource(R.string.sync_metered_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMeteredSync) {
                    Text(stringResource(R.string.sync_metered_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMeteredConfirmation) {
                    Text(stringResource(R.string.sync_metered_cancel))
                }
            },
        )
    }
    if (lowBatteryConfirmationRequired) {
        AlertDialog(
            onDismissRequest = viewModel::dismissLowBatteryConfirmation,
            title = { Text(stringResource(R.string.sync_low_battery_title)) },
            text = { Text(stringResource(R.string.sync_low_battery_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmLowBatterySync) {
                    Text(stringResource(R.string.sync_low_battery_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissLowBatteryConfirmation) {
                    Text(stringResource(R.string.sync_metered_cancel))
                }
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.sign_out))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sign_out)) },
                            onClick = {
                                menuExpanded = false
                                viewModel.signOut(onSignedOut)
                            },
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = viewModel::refresh,
        // The scaffold's inset padding is applied here: the app draws edge to edge on
        // Android 15+, and without it the title collided with the status-bar clock.
        modifier = Modifier.padding(innerPadding),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
        item {
            WatchCard(
                status = status,
                availability = availability,
                statusIsStale = statusIsStale,
                setupState = setupState,
                syncOp = syncOp,
                onSetup = viewModel::setupWatch,
                onSync = viewModel::syncNow,
                onBrowse = onBrowse,
            )
        }
        item { Text(stringResource(R.string.pinned_collections), style = MaterialTheme.typography.titleLarge) }
        if (unpinOp is OpState.Error) {
            item { OpStateLine(unpinOp) }
        }
        if (status?.configured == true && status?.collections?.isNotEmpty() == true) {
            items(status?.collections.orEmpty(), key = { it.id }) { collection ->
                Card {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(collection.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${collection.trackCount}", style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(
                            onClick = { viewModel.unpin(collection.id, collection.type, collection.name) },
                            enabled = collection.id !in pendingIds,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.unpin))
                        }
                    }
                }
            }
        } else {
            item { Text(stringResource(R.string.no_pinned_collections)) }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
    }
    }
}

@Composable
private fun WatchCard(
    status: WatchStatus?,
    availability: WatchAvailability,
    /** The cached [status] is no longer confirmed by the watch — see `HomeViewModel`. */
    statusIsStale: Boolean,
    setupState: WatchSetupState,
    syncOp: OpState,
    onSetup: () -> Unit,
    onSync: () -> Unit,
    onBrowse: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.watch), style = MaterialTheme.typography.titleLarge)
            // Rendered independently of whether a status is cached. The repository keeps the
            // last answer on purpose — the storage and sync figures stay useful — so without
            // this the card went on presenting them as live once the watch stopped answering.
            // `statusIsStale` implies a non-null status, so it never doubles the line below.
            if (statusIsStale) {
                Text(watchAvailabilityMessage(availability), color = MaterialTheme.colorScheme.error)
                Text(
                    stringResource(R.string.watch_status_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                status == null -> {
                    // Exactly one line: this *is* the diagnosis, so a setup failure repeating
                    // "unreachable"/"timeout" underneath is suppressed as pure noise — that
                    // duplication is what printed the same sentence twice.
                    Text(watchAvailabilityMessage(availability))
                    SetupButton(setupState, onSetup, suppressLinkErrors = true)
                }

                !status.configured -> {
                    // A setup error is worth showing unless the stale line above already said
                    // the watch is unreachable — that duplication is the noise this suppresses.
                    Text(stringResource(R.string.watch_unconfigured))
                    SetupButton(setupState, onSetup, suppressLinkErrors = statusIsStale)
                }

                else -> {
                    Text(status.serverName ?: status.serverUrl.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(status.userName.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    StorageIndicator(status)
                    Text(
                        "${stringResource(R.string.last_sync)} : ${status.lastSyncOutcome ?: stringResource(R.string.unknown)}" +
                            status.lastSyncEpochMs?.let { " · ${relativeTime(it)}" }.orEmpty(),
                    )
                    SyncStatusLine(status)
                    if (status.collectionsTruncated) {
                        Text(
                            stringResource(R.string.collections_truncated),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onSync, enabled = syncOp !is OpState.InFlight) {
                            Text(stringResource(R.string.sync_now))
                        }
                        Button(onClick = onSetup) { Text(stringResource(R.string.reconfigure_watch)) }
                    }
                    OpStateLine(syncOp)
                    // "Set up again" feedback used to be invisible here: the setup states
                    // were only rendered in the unconfigured branches.
                    when (setupState) {
                        is WatchSetupState.Approving ->
                            Text(stringResource(R.string.setup_approving, setupState.code))
                        is WatchSetupState.Failure ->
                            Text(
                                stringResource(wireErrorRes(setupState.error)),
                                color = MaterialTheme.colorScheme.error,
                            )
                        is WatchSetupState.Success ->
                            if (setupState.alreadyConfigured) {
                                Text(stringResource(R.string.watch_already_configured))
                            }
                        else -> Unit
                    }
                    Button(onClick = onBrowse, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.browse_library))
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncStatusLine(status: WatchStatus) {
    val message = when {
        status.syncBlocker == WireSyncBlockers.LOW_BATTERY_CONFIRMATION_REQUIRED ->
            R.string.sync_status_waiting_for_low_battery_consent
        status.syncBlocker == WireSyncBlockers.METERED_CONFIRMATION_REQUIRED ->
            R.string.sync_status_waiting_for_consent
        status.syncState == "QUEUED" -> R.string.sync_status_queued
        status.syncState == "SYNCING" -> R.string.sync_status_syncing
        status.syncState == "FAILED" -> R.string.sync_status_failed
        status.syncState == "PAUSED" -> R.string.sync_status_paused
        status.syncState == "IDLE" && status.lastSyncEpochMs != null -> R.string.sync_status_completed
        else -> null
    }
    message?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/** Renders the lifecycle of a remote command — silent only when [OpState.Idle]. */
@Composable
private fun OpStateLine(op: OpState) {
    when (op) {
        OpState.Idle -> Unit
        OpState.InFlight -> Text(
            stringResource(R.string.op_contacting_watch),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OpState.Success -> Text(stringResource(R.string.op_sync_requested))
        is OpState.Error -> Text(
            stringResource(wireErrorRes(op.code)),
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Single actionable sentence for the state of the phone-to-watch link.
 *
 * "Open JellyWrist on the watch" is deliberately absent: the capability is declared
 * statically in the watch's `wear.xml`, so it is advertised from installation onwards and
 * launching the watch app changes nothing.
 */
@Composable
private fun watchAvailabilityMessage(availability: WatchAvailability): String = when (availability) {
    // Reachable with no status means a refresh is still settling — same wording as Unknown.
    WatchAvailability.Unknown, WatchAvailability.Reachable ->
        stringResource(R.string.watch_status_checking)
    WatchAvailability.NoWatchConnected -> stringResource(R.string.watch_status_no_watch_connected)
    is WatchAvailability.AppMissing ->
        stringResource(R.string.watch_status_app_missing, availability.watchName)
    WatchAvailability.NotAnswering -> stringResource(R.string.watch_status_no_answer)
    WatchAvailability.DiscoveryFailed -> stringResource(R.string.watch_status_discovery_failed)
}

@Composable
private fun SetupButton(
    state: WatchSetupState,
    onSetup: () -> Unit,
    /** Hides link-level errors the card's status line already states. */
    suppressLinkErrors: Boolean,
) {
    when (state) {
        is WatchSetupState.Approving -> Text(stringResource(R.string.setup_approving, state.code))
        is WatchSetupState.Failure -> if (!(suppressLinkErrors && state.error.isLinkError())) {
            Text(
                stringResource(wireErrorRes(state.error)),
                color = MaterialTheme.colorScheme.error,
            )
        }
        is WatchSetupState.Success -> Text(state.userName.orEmpty())
        else -> Unit
    }
    Button(onClick = onSetup, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.configure_watch))
    }
}

@Composable
private fun StorageIndicator(status: WatchStatus) {
    if (status.quotaBytes <= 0L) {
        // Without a quota there is no meaningful fraction — the max(quota, 1) fallback
        // used to render any usage as a 100 % full bar.
        Text("${stringResource(R.string.storage)} : ${formatBytes(status.usedBytes)} · ${stringResource(R.string.storage_no_quota)}")
        return
    }
    val progress = (status.usedBytes.toFloat() / status.quotaBytes).coerceIn(0f, 1f)
    Text("${stringResource(R.string.storage)} : ${formatBytes(status.usedBytes)} / ${formatBytes(status.quotaBytes)}")
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

/** Localized size formatting — the previous hand-rolled version mixed French units in. */
@Composable
private fun formatBytes(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * Localized relative time that actually updates: computed via [produceState] with a
 * one-minute tick — reading `System.currentTimeMillis()` directly in composition froze
 * the label at whatever it said on first composition ("just now", forever).
 */
@Composable
private fun relativeTime(epochMs: Long): String {
    val label by produceState(initialValue = relativeTimeLabel(epochMs), epochMs) {
        while (true) {
            value = relativeTimeLabel(epochMs)
            delay(60_000L)
        }
    }
    return label
}


private fun relativeTimeLabel(epochMs: Long): String = DateUtils.getRelativeTimeSpanString(
    epochMs,
    System.currentTimeMillis(),
    DateUtils.MINUTE_IN_MILLIS,
).toString()

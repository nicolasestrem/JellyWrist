package com.example.jellywrist.mobile.ui.screens.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.example.jellywrist.mobile.R
import com.example.jellywrist.mobile.data.CatalogItem
import com.example.jellywrist.mobile.data.CatalogType
import com.example.jellywrist.mobile.ui.OpState
import com.example.jellywrist.mobile.ui.wireErrorRes

/** Remote library browsing and selection of collections to sync.  */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(viewModel: BrowseViewModel = hiltViewModel()) {
    var selected by remember { mutableStateOf(CatalogType.PLAYLIST) }
    val tabs by viewModel.tabs.collectAsState()
    val status by viewModel.watchStatus.collectAsState()
    val pendingIds by viewModel.pendingIds.collectAsState()
    val pinOp by viewModel.pinOp.collectAsState()
    LaunchedEffect(selected) { viewModel.load(selected) }
    Scaffold(
        // The app bar names the screen; its inner padding keeps the tab row clear of the
        // status bar and the list clear of the navigation bar.
        topBar = { TopAppBar(title = { Text(stringResource(R.string.browse_library)) }) },
    ) { innerPadding ->
    Column(Modifier.fillMaxSize().padding(innerPadding)) {
        TabRow(selectedTabIndex = CatalogType.entries.indexOf(selected)) {
            CatalogType.entries.forEach { type ->
                Tab(
                    selected = type == selected,
                    onClick = { selected = type },
                    text = { Text(tabTitle(type)) },
                )
            }
        }
        when (val state = tabs.getValue(selected)) {
            // Idle (never loaded) and Loading are distinct on purpose: an untouched tab
            // shows a hint, not an indefinite spinner.
            CatalogLoadState.Idle -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(stringResource(R.string.browse_hint_select_tab)) }

            CatalogLoadState.Loading -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            is CatalogLoadState.Error -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(state.message), color = MaterialTheme.colorScheme.error)
                Button(onClick = { viewModel.load(selected, forceRefresh = true) }) {
                    Text(stringResource(R.string.retry))
                }
            }

            is CatalogLoadState.Content -> if (state.items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text(stringResource(R.string.no_items)) }
            } else {
                val pinnedIds = status?.collections?.mapTo(mutableSetOf()) { it.id }.orEmpty()
                Column(Modifier.fillMaxSize()) {
                    if (pinOp is OpState.Error) {
                        Text(
                            stringResource(wireErrorRes((pinOp as OpState.Error).code)),
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            CatalogRow(
                                item = item,
                                pinned = item.id in pinnedIds,
                                pending = item.id in pendingIds,
                                imageUrl = viewModel.imageUrl(item),
                                onToggle = { viewModel.togglePin(item, it) },
                            )
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun CatalogRow(
    item: CatalogItem,
    pinned: Boolean,
    pending: Boolean,
    imageUrl: String?,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop,
        )
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${item.trackCount}", style = MaterialTheme.typography.bodySmall)
        }
        FilterChip(
            selected = pinned,
            enabled = !pending,
            onClick = { onToggle(!pinned) },
            label = {
                if (pending) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (pinned) R.string.unpin else R.string.pin))
                }
            },
        )
    }
}

@Composable
private fun tabTitle(type: CatalogType): String = stringResource(
    when (type) {
        CatalogType.PLAYLIST -> R.string.playlists
        CatalogType.ALBUM -> R.string.albums
        CatalogType.ARTIST -> R.string.artists
    },
)

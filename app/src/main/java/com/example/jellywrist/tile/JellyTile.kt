package com.example.jellywrist.tile

import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.example.jellywrist.R
import com.example.jellywrist.data.db.CollectionDao
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * "Resume playback" tile (PLAN.md §6 — Wear OS extras).
 *
 * Shows the last synced collection and opens the app on tap — the
 * player restores its persisted queue ([com.example.jellywrist.playback.QueueStore]).
 * Source of truth: Room (most recently synced pinned collection) — reliable
 * even if no playback has happened yet.
 *
 * `TileService` API (androidx.wear.tiles 1.6.2): `ListenableFuture` contract — bridged
 * to a coroutine via [SettableFuture], since the service has no suspend variant
 * in our dependencies.
 */
@AndroidEntryPoint
class JellyTile : TileService() {

    @Inject lateinit var collectionDao: CollectionDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<TileBuilders.Tile> {
        val future = SettableFuture.create<TileBuilders.Tile>()
        scope.launch {
            val lastSynced = collectionDao.pinned().maxByOrNull { it.lastSyncAtEpochMs ?: 0L }
            val subtitle = lastSynced?.name ?: getString(R.string.tile_no_collection)

            val layout = LayoutElementBuilders.Layout.Builder()
                .setRoot(tileLayout(subtitle))
                .build()

            future.set(
                TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setTileTimeline(
                        TimelineBuilders.Timeline.Builder()
                            .addTimelineEntry(
                                TimelineBuilders.TimelineEntry.Builder().setLayout(layout).build(),
                            )
                            .build(),
                    )
                    // The displayed collection changes with each sync: an
                    // hourly refresh is more than enough.
                    .setFreshnessIntervalMillis(FRESHNESS_MS)
                    .build(),
            )
        }
        return future
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> =
        com.google.common.util.concurrent.Futures.immediateFuture(
            ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build(),
        )

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /** Centered column: title + last collection, clickable to open the app. */
    private fun tileLayout(subtitle: String): LayoutElementBuilders.LayoutElement {
        val openApp = ModifiersBuilders.Clickable.Builder()
            .setId(CLICK_ID)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MAIN_ACTIVITY)
                            .build(),
                    )
                    .build(),
            )
            .build()

        return LayoutElementBuilders.Box.Builder()
            .setWidth(expand())
            .setHeight(expand())
            .setModifiers(ModifiersBuilders.Modifiers.Builder().setClickable(openApp).build())
            .addContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(getString(R.string.tile_resume_title))
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setColor(argb(COLOR_TITLE))
                                    .build(),
                            )
                            .build(),
                    )
                    .addContent(
                        LayoutElementBuilders.Text.Builder()
                            .setText(subtitle)
                            .setFontStyle(
                                LayoutElementBuilders.FontStyle.Builder()
                                    .setColor(argb(COLOR_SUBTITLE))
                                    .build(),
                            )
                            .build(),
                    )
                    .build(),
            )
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        const val CLICK_ID = "open_player"
        const val MAIN_ACTIVITY = "com.example.jellywrist.ui.MainActivity"
        const val FRESHNESS_MS = 60L * 60L * 1000L
        const val COLOR_TITLE = 0xFFFFFFFF.toInt()
        const val COLOR_SUBTITLE = 0xFFB0B0C0.toInt()
    }
}

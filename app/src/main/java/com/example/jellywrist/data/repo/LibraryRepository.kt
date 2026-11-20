package com.example.jellywrist.data.repo

import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionTrackDao
import com.example.jellywrist.data.db.CollectionTrackEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.util.SyncTrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Access to the Jellyfin library: playlists, albums, artists, tracks (PLAN.md §8 tasks 4-5).
 *
 * Cache-first: Room DAOs expose consumable [Flow]s immediately, even offline;
 * [refreshCollections] / [refreshTracks] refresh the cache from the server. Browsing
 * must work with Wi-Fi off — the cache is authoritative, the network only feeds it.
 *
 * **Local state preservation:** a refresh is a metadata operation. It must never
 * unpin a collection nor regress a track's download state
 * (`READY` → `TO_DOWNLOAD` would re-download the whole library): locally-owned
 * fields are merged before each upsert.
 *
 * The codec is kept in [TrackEntity.codec]; the [TrackState.UNSUPPORTED] marking
 * happens at the end of the download (DEC-003: we download anyway, DEC-004: we
 * report instead of failing at playback).
 *
 * REQ-002: all calls go through the official SDK — no manual HTTP here.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val backend: JellyfinBackend,
    private val collectionDao: CollectionDao,
    private val trackDao: TrackDao,
    private val collectionTrackDao: CollectionTrackDao,
) {

    /** Collections of a given type, sorted by sort name then name. */
    fun observeCollections(type: CollectionType): Flow<List<CollectionEntity>> =
        collectionDao.observeByType(type)

    /** Pinned collections (the sync engine's "feed", PLAN.md §4.1). */
    fun observePinnedCollections(): Flow<List<CollectionEntity>> =
        collectionDao.observePinned()

    /** Collection detail observed continuously. */
    fun observeCollection(id: String): Flow<CollectionEntity?> =
        collectionDao.observeById(id)

    /** Tracks of a collection, in server order ([CollectionTrackEntity.sortIndex]). */
    fun observeTracks(collectionId: String): Flow<List<TrackEntity>> =
        collectionTrackDao.observeTracks(collectionId)

    /** All locally-known titles, sorted by title (Titles tab). */
    fun observeAllTracks(): Flow<List<TrackEntity>> = trackDao.observeAll()

    /**
     * Refreshes a first batch of titles for the Titles tab (one alphabetical page).
     *
     * Deliberately capped at one page: a library can contain tens of
     * thousands of titles — the tab is for browsing, content building goes through
     * pinning collections or [syncRandomSongs].
     */
    suspend fun refreshSongs(): Result<Unit> = runCatching {
        val session = authRepository.currentSession() ?: error("No session")
        val api = backend.apiClient(session.baseUrl, session.accessToken)

        val items = api.itemsApi.getItems(
            userId = session.userId.toUUID(),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
            fields = listOf(ItemFields.MEDIA_SOURCES),
            startIndex = 0,
            limit = PAGE_SIZE,
        ).content.items

        mergeTracks(items.map { it.toTrackEntity() })
    }

    /**
     * "Sync N random songs": asks the server for [count] random songs
     * (`sortBy=RANDOM` — the draw is done on the Jellyfin side across the whole library),
     * materializes them in the **local** pinned collection [RANDOM_COLLECTION_ID], and
     * lets the sync engine download under budget control (REQ-008).
     *
     * Replayable: each call REPLACES the previous draw — outgoing songs lose
     * their reference and will be purged by the recon if they belong to nothing else.
     *
     * @return the number of songs retained
     */
    suspend fun syncRandomSongs(count: Int = RANDOM_COUNT): Result<Int> = runCatching {
        val session = authRepository.currentSession() ?: error("No session")
        val api = backend.apiClient(session.baseUrl, session.accessToken)

        val items = api.itemsApi.getItems(
            userId = session.userId.toUUID(),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            sortBy = listOf(ItemSortBy.RANDOM),
            fields = listOf(ItemFields.MEDIA_SOURCES),
            limit = count,
        ).content.items

        val tracks = items.map { it.toTrackEntity() }
        val merged = mergeTracks(tracks)

        collectionDao.upsertAll(
            listOf(
                CollectionEntity(
                    id = RANDOM_COLLECTION_ID,
                    type = CollectionType.PLAYLIST,
                    name = "$count random songs",
                    pinned = true,
                    localOnly = true,
                    estimatedBytes = merged.sumOf { it.sizeBytes },
                    trackCount = merged.size,
                ),
            ),
        )
        collectionTrackDao.replaceContent(
            RANDOM_COLLECTION_ID,
            merged.mapIndexed { index, track ->
                CollectionTrackEntity(RANDOM_COLLECTION_ID, track.id, index)
            },
        )
        merged.size
    }

    /**
     * Pins or unpins a collection.
     *
     * On pinning, tracks are refreshed first: the sync engine needs
     * server sizes for capacity control before enqueueing anything (REQ-008).
     */
    suspend fun setPinned(collectionId: String, pinned: Boolean) {
        if (pinned) {
            runCatching { refreshTracks(collectionId) }
                .onFailure {
                    if (it is CancellationException) throw it
                    SyncTrace.w("setPinned: track refresh failed for $collectionId", it)
                }
        }
        collectionDao.setPinned(collectionId, pinned)
        SyncTrace.i("setPinned collection=$collectionId pinned=$pinned")
    }

    /**
     * Refreshes collections of a [type] from the server (paginated to exhaustion).
     *
     * @return [Result.failure] on network/HTTP error — the Room cache stays intact.
     */
    suspend fun refreshCollections(type: CollectionType): Result<Unit> = runCatching {
        val session = authRepository.currentSession() ?: error("No session")
        val api = backend.apiClient(session.baseUrl, session.accessToken)
        val userId = session.userId.toUUID()

        val items = when (type) {
            CollectionType.PLAYLIST -> fetchAllItems { start ->
                api.itemsApi.getItems(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }

            CollectionType.ALBUM -> fetchAllItems { start ->
                api.itemsApi.getItems(
                    userId = userId,
                    includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }

            CollectionType.ARTIST -> fetchAllItems { start ->
                api.artistsApi.getAlbumArtists(
                    userId = userId,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }
        }

        // A metadata refresh owns neither the pin nor the sync state: merge.
        val merged = items.map { it.toCollectionEntity(type) }.map { incoming ->
            val existing = collectionDao.byId(incoming.id)
            if (existing == null) {
                incoming
            } else {
                incoming.copy(
                    pinned = existing.pinned,
                    lastSyncAtEpochMs = existing.lastSyncAtEpochMs,
                    reconWatermark = existing.reconWatermark,
                    estimatedBytes = existing.estimatedBytes,
                )
            }
        }

        collectionDao.upsertAll(merged)
    }

    /**
     * Refreshes a collection's tracks from the server.
     *
     * - Playlist: `PlaylistsApi.getPlaylistItems` — preserves playlist order.
     * - Album: album's audio children, sorted by track number.
     * - Artist: all of the artist's songs (`artistIds` filter), grouped by album.
     *
     * Also updates [CollectionEntity.estimatedBytes]: the size shown before pinning
     * must be real (PLAN.md §4.4).
     */
    suspend fun refreshTracks(collectionId: String): Result<Unit> = runCatching {
        val session = authRepository.currentSession() ?: error("No session")
        val collection = collectionDao.byId(collectionId)
            ?: error("Unknown collection: $collectionId")
        if (collection.localOnly) {
            // A local-only collection (the random mix) is assembled on the watch: it has no
            // server counterpart and its id is not a Jellyfin UUID, so handing it to the SDK
            // threw "Invalid UUID string" — straight onto the detail screen, and swallowed
            // inside setPinned. Its content is already authoritative here; the sync engine
            // reconciles it by identity (SyncEngine step 2), so there is nothing to fetch.
            return@runCatching
        }
        val api = backend.apiClient(session.baseUrl, session.accessToken)
        val userId = session.userId.toUUID()
        val collectionUuid = collectionId.toUUID()

        val items = when (collection.type) {
            CollectionType.PLAYLIST -> fetchAllItems { start ->
                api.playlistsApi.getPlaylistItems(
                    playlistId = collectionUuid,
                    userId = userId,
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }

            CollectionType.ALBUM -> fetchAllItems { start ->
                api.itemsApi.getItems(
                    userId = userId,
                    parentId = collectionUuid,
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.INDEX_NUMBER),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }

            CollectionType.ARTIST -> fetchAllItems { start ->
                api.itemsApi.getItems(
                    userId = userId,
                    artistIds = listOf(collectionUuid),
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    recursive = true,
                    sortBy = listOf(ItemSortBy.ALBUM, ItemSortBy.INDEX_NUMBER),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }
        }

        val mergedTracks = mergeTracks(items.map { it.toTrackEntity() })
        collectionTrackDao.replaceContent(
            collectionId,
            mergedTracks.mapIndexed { index, track ->
                CollectionTrackEntity(collectionId = collectionId, trackId = track.id, sortIndex = index)
            },
        )
        collectionDao.updateSizing(
            collectionId,
            mergedTracks.sumOf { it.sizeBytes },
            mergedTracks.size,
        )
    }

    /**
     * Merges server tracks with local state then upserts them.
     *
     * Never regresses the download state: a `READY` track stays `READY`,
     * a track in error keeps its diagnostic — only the download engine owns
     * these fields. @return the merged list, in input order.
     */
    private suspend fun mergeTracks(incoming: List<TrackEntity>): List<TrackEntity> {
        val existingMap = trackDao.byIds(incoming.map { it.id }).associateBy { it.id }
        val merged = incoming.map { track ->
            val existing = existingMap[track.id] ?: return@map track
            track.copy(
                state = existing.state,
                downloadedBytes = existing.downloadedBytes,
                errorMessage = existing.errorMessage,
            )
        }
        trackDao.upsertAll(merged)
        return merged
    }

    /** Pagination loop: concatenates pages from [fetch] until an incomplete page. */
    private suspend fun fetchAllItems(
        fetch: suspend (startIndex: Int) -> List<BaseItemDto>,
    ): List<BaseItemDto> {
        val all = mutableListOf<BaseItemDto>()
        var start = 0
        while (true) {
            val page = fetch(start)
            all += page
            if (page.size < PAGE_SIZE) return all
            start += PAGE_SIZE
        }
    }

    // ── Mappers BaseItemDto → Room entities ──────────────────────────────

    private fun BaseItemDto.toCollectionEntity(type: CollectionType): CollectionEntity =
        CollectionEntity(
            id = id.toString(),
            type = type,
            name = name.orEmpty(),
            sortName = sortName,
            artistName = albumArtist,
            trackCount = childCount ?: 0,
            imageTag = imageTags?.get(ImageType.PRIMARY),
        )

    private fun BaseItemDto.toTrackEntity(): TrackEntity {
        val source = mediaSources?.firstOrNull()
        val codec = source?.mediaStreams.orEmpty()
            .firstOrNull { it.type == MediaStreamType.AUDIO }
            ?.codec
            ?: mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.AUDIO }?.codec

        return TrackEntity(
            id = id.toString(),
            title = name.orEmpty(),
            artist = albumArtist ?: artists?.firstOrNull(),
            album = album,
            albumId = albumId?.toString(),
            // runTimeTicks is in 100-ns ticks: /10,000 → milliseconds.
            durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
            codec = codec,
            container = source?.container ?: container,
            sizeBytes = source?.size ?: 0L,
            state = TrackState.TO_DOWNLOAD,
            indexNumber = indexNumber,
            imageTag = imageTags?.get(ImageType.PRIMARY),
        )
    }

    companion object {
        /** Stable identifier of the "random songs" local collection. */
        const val RANDOM_COLLECTION_ID = "local-random-songs"

        /** Display name (library + tile) of the random collection. */
        const val RANDOM_COLLECTION_NAME = "500 random songs"

        /** Default draw size — product request from 2026-08-08. */
        const val RANDOM_COUNT = 500

        /** Trade-off: large enough to limit Wi-Fi round-trips, bounded in memory. */
        private const val PAGE_SIZE = 200

        private const val TICKS_PER_MS = 10_000L
    }
}

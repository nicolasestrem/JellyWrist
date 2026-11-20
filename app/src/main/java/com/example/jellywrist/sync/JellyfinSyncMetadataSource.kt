package com.example.jellywrist.sync

import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.api.client.extensions.playlistsApi
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
 * Jellyfin implementation of the sync engine's metadata port.
 *
 * The fetch depends on the collection type (see [SyncMetadataSource.fetchCollectionTracks]):
 * - playlist → `PlaylistsApi.getPlaylistItems` (preserves playlist order);
 * - album → audio children sorted by track number;
 * - artist → all tracks via the `artistIds` filter, grouped by album.
 *
 * The codec comes from the first audio stream of `mediaSources` ([ItemFields.MEDIA_SOURCES]) —
 * the basis of DEC-004 detection. Pagination loops until exhaustion: the server default
 * would silently truncate large collections, and the diff would then purge perfectly
 * valid tracks.
 *
 * REQ-002: all JSON access goes through the typed SDK.
 */
@Singleton
class JellyfinSyncMetadataSource @Inject constructor(
    private val backend: JellyfinBackend,
) : SyncMetadataSource {

    override suspend fun fetchCollectionTracks(
        collectionId: String,
        type: CollectionType,
        accessToken: String,
        baseUrl: String,
    ): List<SyncTrackMeta> {
        val api = backend.apiClient(baseUrl, accessToken)
        val collectionUuid = collectionId.toUUID()

        val items = when (type) {
            CollectionType.PLAYLIST -> fetchAll { start ->
                api.playlistsApi.getPlaylistItems(
                    playlistId = collectionUuid,
                    fields = listOf(ItemFields.MEDIA_SOURCES),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }

            CollectionType.ALBUM -> fetchAll { start ->
                api.itemsApi.getItems(
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

            CollectionType.ARTIST -> fetchAll { start ->
                api.itemsApi.getItems(
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

        return items.map { it.toSyncTrackMeta() }
    }

    private suspend fun fetchAll(
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

    private fun BaseItemDto.toSyncTrackMeta(): SyncTrackMeta {
        val source = mediaSources?.firstOrNull()
        val codec = source?.mediaStreams.orEmpty()
            .firstOrNull { it.type == MediaStreamType.AUDIO }
            ?.codec
            ?: mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.AUDIO }?.codec

        return SyncTrackMeta(
            id = id.toString(),
            title = name.orEmpty(),
            artist = albumArtist ?: artists?.firstOrNull(),
            album = album,
            albumId = albumId?.toString(),
            // 100-ns ticks → milliseconds.
            durationMs = (runTimeTicks ?: 0L) / TICKS_PER_MS,
            codec = codec,
            container = source?.container ?: container,
            sizeBytes = source?.size ?: 0L,
            indexNumber = indexNumber,
            imageTag = imageTags?.get(ImageType.PRIMARY),
        )
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val TICKS_PER_MS = 10_000L
    }
}

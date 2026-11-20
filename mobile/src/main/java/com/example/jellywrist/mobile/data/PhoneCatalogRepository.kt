package com.example.jellywrist.mobile.data

import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.jellyfin.JellyfinUrls
import com.example.jellywrist.data.repo.AuthRepository
import org.jellyfin.sdk.api.client.extensions.artistsApi
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.serializer.toUUID
import javax.inject.Inject
import javax.inject.Singleton

/** Types dont les noms constituent le contrat avec [com.example.jellywrist.wire.PinCommand]. */
enum class CatalogType {
    PLAYLIST,
    ALBUM,
    ARTIST,
}

/** Metadata for a collection displayable on the phone. */
data class CatalogItem(
    val id: String,
    val name: String,
    val type: CatalogType,
    val trackCount: Int,
    val imageTag: String?,
)

/** Lit le catalogue Jellyfin sans dupliquer la base Room, qui reste sur la montre. */
@Singleton
class PhoneCatalogRepository @Inject constructor(
    private val authRepository: AuthRepository,
    private val backend: JellyfinBackend,
) {
    suspend fun collections(type: CatalogType): Result<List<CatalogItem>> = runCatching {
        val session = authRepository.currentSession() ?: error("Aucune session")
        val api = backend.apiClient(session.baseUrl, session.accessToken)
        val userId = session.userId.toUUID()
        val items = when (type) {
            CatalogType.PLAYLIST -> fetchAllItems { start ->
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

            CatalogType.ALBUM -> fetchAllItems { start ->
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

            CatalogType.ARTIST -> fetchAllItems { start ->
                api.artistsApi.getAlbumArtists(
                    userId = userId,
                    sortBy = listOf(ItemSortBy.SORT_NAME),
                    sortOrder = listOf(SortOrder.ASCENDING),
                    startIndex = start,
                    limit = PAGE_SIZE,
                ).content.items
            }
        }
        items.map { it.toCatalogItem(type) }
    }

    /**
     * Cover art URL served to the phone's authenticated Coil loader.
     *
     * Goes through [JellyfinUrls]: hand-assembled Jellyfin URLs are centralized there,
     * and the list thumbnail is resized server-side rather than downloaded at full
     * resolution.
     */
    fun primaryImageUrl(baseUrl: String, item: CatalogItem): String? = item.imageTag?.let { tag ->
        JellyfinUrls.primaryImage(baseUrl = baseUrl, itemId = item.id, tag = tag, fillWidth = THUMBNAIL_WIDTH_PX)
    }

    /** Concatenates server pages until the first incomplete page. */
    private suspend fun fetchAllItems(fetch: suspend (Int) -> List<BaseItemDto>): List<BaseItemDto> {
        val all = mutableListOf<BaseItemDto>()
        var start = 0
        while (true) {
            val page = fetch(start)
            all += page
            if (page.size < PAGE_SIZE) return all
            start += PAGE_SIZE
        }
    }

    private fun BaseItemDto.toCatalogItem(type: CatalogType) = CatalogItem(
        id = id.toString(),
        name = name.orEmpty(),
        type = type,
        trackCount = childCount ?: 0,
        imageTag = imageTags?.get(ImageType.PRIMARY),
    )

    private companion object {
        const val PAGE_SIZE = 200

        /** 56 dp thumbnail: 200 px covers densities up to xxxhdpi. */
        const val THUMBNAIL_WIDTH_PX = 200
    }
}

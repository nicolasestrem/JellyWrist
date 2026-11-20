package com.example.jellywrist.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.example.jellywrist.R

/**
 * Jellyfin artwork image: loads from the server via Coil, falls back to a local icon.
 *
 * Builds the primary image URL via [com.example.jellywrist.data.jellyfin.JellyfinUrls]
 * (endpoint `/Items/{id}/Images/Primary` with a cache tag and a size suited to the watch).
 *
 * [fallback] is shown when [imageTag] is absent, the URL is empty, or a network error occurs.
 * Defaults to the app's bundled music icon.
 *
 * PLAN.md §8 task 10: reusable component for album/artist/track artwork.
 *
 * @param itemId Jellyfin identifier of the item (album, artist, playlist).
 * @param baseUrl Base URL of the Jellyfin server (authenticated by the Coil interceptor).
 * @param imageTag Jellyfin image tag — cache variation key (avoids serving stale
 *                artwork after a change).
 * @param fallback Replacement painter (local icon).
 */
@Composable
fun JellyImage(
    itemId: String,
    baseUrl: String,
    imageTag: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallback: Painter = painterResource(R.drawable.ic_music_note),
) {
    val data = if (imageTag != null) {
        com.example.jellywrist.data.jellyfin.JellyfinUrls.primaryImage(
            baseUrl = baseUrl,
            itemId = itemId,
            tag = imageTag,
        )
    } else {
        null
    }

    AsyncImage(
        model = if (data != null) {
            ImageRequest.Builder(LocalContext.current)
                .data(data)
                .crossfade(true)
                .build()
        } else {
            null
        },
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
        fallback = fallback,
        error = fallback,
        placeholder = fallback,
    )
}

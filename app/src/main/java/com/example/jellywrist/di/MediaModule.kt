package com.example.jellywrist.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.DownloadManager
import com.example.jellywrist.BuildConfig
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinUrls
import com.example.jellywrist.data.net.JellyDataSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

/** Upstream network source: used to **write** into the cache (downloads). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UpstreamDataSource

/** Playback source: cache first, never writes — see [MediaModule.providePlaybackDataSourceFactory]. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlaybackDataSource

/**
 * Media3 infrastructure shared between the download engine and the player.
 *
 * **Critical invariant:** only one [SimpleCache] can exist per directory in a process —
 * instantiating a second one throws `IllegalStateException` on startup. The cache is
 * therefore provided here as a single `@Singleton`, and neither the download service nor
 * the playback service may construct one.
 *
 * The cache replaces the notion of a "local file path": a track's bytes are addressed by
 * `customCacheKey = trackId` (PLAN.md §8 task 6). This is what lets `DownloadManager` resume
 * via Range requests and the player serve the same resource offline without duplicating bytes.
 */
@Module
@InstallIn(SingletonComponent::class)
object MediaModule {

    @Provides
    @Singleton
    fun provideDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    /**
     * Download cache, in the application's private storage (`filesDir`): no permissions
     * required and automatic cleanup on uninstall (PLAN.md §4.4).
     *
     * [NoOpCacheEvictor] is deliberate: the content is **pinned by the user**, not an LRU
     * cache. Automatic eviction would silently delete music the user explicitly requested;
     * space is reclaimed by orphan purge (REQ-006) and by the budget planner (REQ-008),
     * both driven by user intent.
     */
    @Provides
    @Singleton
    fun provideDownloadCache(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
    ): Cache = SimpleCache(
        File(context.filesDir, DOWNLOAD_CACHE_DIR),
        NoOpCacheEvictor(),
        databaseProvider,
    )

    /**
     * Authenticated upstream source.
     *
     * Headers are recomputed on each connection open: a download enqueued before a
     * re-login must restart with the current token. The token is never placed in URL
     * parameters — it must not appear in server logs or in a cache key (REQ-005).
     */
    @Provides
    @Singleton
    @UpstreamDataSource
    fun provideUpstreamDataSourceFactory(
        okHttpClient: OkHttpClient,
        tokenStore: TokenStore,
    ): DataSource.Factory = JellyDataSource.Factory(okHttpClient) {
        tokenStore.load().sessionOrNull
            ?.let { session ->
                mapOf(
                    JellyfinUrls.HEADER_AUTHORIZATION to
                        JellyfinUrls.authorizationHeader(session, BuildConfig.VERSION_NAME),
                )
            }
            .orEmpty()
    }

    /**
     * Playback source: **cache only**.
     *
     * No upstream source is declared, which deliberately fails any playback of a track
     * missing from the cache: v1 does no streaming (PLAN.md §1.2), and a silent network
     * fallback would consume mobile data to make a track the user believes downloaded
     * playable. Media3 substitutes a `PlaceholderDataSource` that throws on cache miss —
     * the player must therefore only build a `MediaItem` for tracks in the `READY` state.
     *
     * `setCacheWriteDataSinkFactory(null)` additionally forbids any writes: the cache can
     * only grow via the `DownloadManager`, the only path subject to the storage budget
     * (REQ-008).
     */
    @Provides
    @Singleton
    @PlaybackDataSource
    fun providePlaybackDataSourceFactory(cache: Cache): DataSource.Factory =
        CacheDataSource.Factory()
            .setCache(cache)
            .setCacheWriteDataSinkFactory(null)

    /**
     * Persistent download queue.
     *
     * `maxParallelDownloads = 1`: a watch's Wi-Fi is the bottleneck, and parallelizing
     * would lengthen the time until the **first** track is playable while multiplying
     * partial resumes after a dropout.
     */
    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context,
        databaseProvider: DatabaseProvider,
        cache: Cache,
        @UpstreamDataSource upstream: DataSource.Factory,
        @DownloadExecutor executor: Executor,
    ): DownloadManager = DownloadManager(context, databaseProvider, cache, upstream, executor)
        .apply {
            maxParallelDownloads = 1
            // Media3 recommends letting DownloadManager handle its own retries;
            // attempts resume on the next sync worker pass (DEC-005).
            minRetryCount = MIN_RETRY_COUNT
        }

    @Provides
    @Singleton
    @DownloadExecutor
    fun provideDownloadExecutor(): Executor = Executors.newSingleThreadExecutor()

    private const val DOWNLOAD_CACHE_DIR = "media_downloads"
    private const val MIN_RETRY_COUNT = 3
}

/** Executor dedicated to downloads — a single thread, aligned with `maxParallelDownloads = 1`. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadExecutor

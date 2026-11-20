package com.example.jellywrist.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.example.jellywrist.BuildConfig
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinUrls
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Coil 3 module for cover art loading (PLAN.md §8 task 10).
 *
 * A singleton [ImageLoader] with **disk cache**: covers must survive offline —
 * browse works with Wi-Fi off (PLAN.md §8 task 5), and so must images.
 *
 * Authentication follows the same pattern as `MediaModule.provideUpstreamDataSourceFactory`:
 * the token is reloaded on each request via [TokenStore.load], never placed in URL parameters
 * (REQ-005). The shared [OkHttpClient] is reused so any TLS policy (opt-in pinning,
 * DEC-006) also applies to images.
 */
@Module
@InstallIn(SingletonComponent::class)
object CoilModule {

    @Provides
    @Singleton
    fun provideImageLoader(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
        tokenStore: TokenStore,
    ): ImageLoader {
        val authedClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                tokenStore.load().sessionOrNull?.let { session ->
                    request.header(
                        JellyfinUrls.HEADER_AUTHORIZATION,
                        JellyfinUrls.authorizationHeader(session, BuildConfig.VERSION_NAME),
                    )
                }
                chain.proceed(request.build())
            }
            .build()

        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { authedClient }))
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.filesDir, IMAGE_CACHE_DIR))
                    .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private const val IMAGE_CACHE_DIR = "image_cache"

    /** 64 MiB ~ 3,000+ covers at fillWidth=200 — plenty, capped for the watch. */
    private const val IMAGE_CACHE_MAX_BYTES = 64L * 1024 * 1024
}

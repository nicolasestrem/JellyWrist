package com.example.jellywrist.mobile.di

import android.content.Context
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.example.jellywrist.data.auth.TokenStore
import com.example.jellywrist.data.jellyfin.JellyfinUrls
import com.example.jellywrist.mobile.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.io.File
import javax.inject.Singleton

/**
 * Coil 3 module for the phone companion — counterpart of `:app`/`di/CoilModule`.
 *
 * Without it, `AsyncImage` would fall back to Coil's default loader, i.e. a fresh
 * `OkHttpClient`: album art would fail TLS on a server with a self-signed certificate
 * that was nonetheless approved (DEC-006; [PinRegistry][com.example.jellywrist.data.net.PinRegistry]
 * is only wired into the shared client) and would carry no Jellyfin header on servers
 * that protect `/Items/{id}/Images`.
 *
 * The token is re-read on each request via [TokenStore.load] and never appears as a URL
 * parameter (REQ-005). The disk cache lives in `cacheDir`: unlike the watch, the companion
 * has no offline requirement — the system may purge it freely.
 */
@Module
@InstallIn(SingletonComponent::class)
object MobileCoilModule {

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
                        JellyfinUrls.authorizationHeader(
                            session = session,
                            clientVersion = BuildConfig.VERSION_NAME,
                            device = JellyfinUrls.DEVICE_PHONE,
                        ),
                    )
                }
                chain.proceed(request.build())
            }
            .build()

        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { authedClient })) }
            .diskCache {
                DiskCache.Builder()
                    .directory(File(context.cacheDir, IMAGE_CACHE_DIR))
                    .maxSizeBytes(IMAGE_CACHE_MAX_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    private const val IMAGE_CACHE_DIR = "image_cache"

    /** 64 MiB: browse list thumbnails, nothing more. */
    private const val IMAGE_CACHE_MAX_BYTES = 64L * 1024 * 1024
}

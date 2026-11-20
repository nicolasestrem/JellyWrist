package com.example.jellywrist.di

import android.content.Context
import com.example.jellywrist.BuildConfig
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.jellyfin.JellyfinBackendImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton

/**
 * Hilt module for the Jellyfin layer.
 *
 * Provides the singleton [Jellyfin] SDK instance (OkHttp backend) and binds the
 * [JellyfinBackend] port to its [JellyfinBackendImpl] implementation. The SDK then exposes
 * `jellyfin.discovery` (UDP 7359 discovery) and `jellyfin.createApi(baseUrl)` (typed HTTP
 * client). [com.example.jellywrist.data.repo.ServerRepository] is built via constructor
 * injection.
 *
 * See PLAN.md §3.1 (server connection) and §11 (`di/` + `data/jellyfin/` layout).
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class JellyfinModule {

    @Binds
    @Singleton
    abstract fun bindJellyfinBackend(impl: JellyfinBackendImpl): JellyfinBackend

    companion object {
        /**
         * The SDK instance is built on the **shared OkHttp client**: JSON calls thus
         * benefit from the same TLS policy (opt-in pinning, DEC-006) as Media3 downloads
         * and Coil cover art — two divergent policies would leave pinning ineffective on
         * half the traffic.
         */
        @Provides
        @Singleton
        fun provideJellyfin(
            @ApplicationContext appContext: Context,
            okHttpClient: okhttp3.OkHttpClient,
        ): Jellyfin = createJellyfin {
            // `context` is required with the Android variant of the SDK (jellyfin-android).
            context = appContext
            clientInfo = ClientInfo("JellyWrist", BuildConfig.VERSION_NAME)
            apiClientFactory = org.jellyfin.sdk.api.okhttp.OkHttpFactory(base = okHttpClient)
            // deviceInfo is auto-filled via androidDevice(context) (Build.MODEL / MANUFACTURER).
        }
    }
}

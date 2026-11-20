package com.example.jellywrist.di

import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.data.net.PinningTrustManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * HTTP client shared by **all** Jellyfin traffic: SDK JSON calls (the
 * [org.jellyfin.sdk.api.okhttp.OkHttpFactory] is built on this base — see
 * [JellyfinModule]), Media3 downloads ([com.example.jellywrist.data.net.JellyDataSource])
 * and Coil artwork.
 *
 * **TLS policy (DEC-006).** Trust is the system's, complemented by
 * [PinningTrustManager]: a self-signed certificate is accepted only if its SHA-256
 * fingerprint has been explicitly approved by the user (stored via the settings,
 * relayed by [PinRegistry]). The policy is **scoped to this client** — never
 * `HttpsURLConnection.setDefaultSSLSocketFactory`, which would extend the exemption to the
 * whole process. Without a registered pin, behavior is strictly identical to the system.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(pinRegistry: PinRegistry): OkHttpClient {
        val trustManager = PinningTrustManager.create { pinRegistry.pins }
        return OkHttpClient.Builder()
            // A watch's Wi-Fi takes several seconds to come up: a short connection
            // timeout would fail syncs that would have succeeded.
            .connectTimeout(30.seconds.toJavaDuration())
            .readTimeout(60.seconds.toJavaDuration())
            .sslSocketFactory(trustManager.sslContext().socketFactory, trustManager)
            .build()
    }
}

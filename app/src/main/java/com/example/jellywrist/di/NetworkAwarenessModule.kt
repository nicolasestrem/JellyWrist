package com.example.jellywrist.di

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.google.android.horologist.networks.highbandwidth.HighBandwidthNetworkMediator
import com.google.android.horologist.networks.highbandwidth.StandardHighBandwidthNetworkMediator
import com.google.android.horologist.networks.logging.NetworkStatusLogger
import com.google.android.horologist.networks.request.NetworkRequesterImpl
import com.google.android.horologist.networks.status.NetworkRepository
import com.google.android.horologist.networks.status.NetworkRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Horologist network-awareness module (REQ-007, PLAN.md §4.3).
 *
 * Also provides the application [CoroutineScope]: a supervised scope living as long as
 * the process, for observers that must not die with a screen (network observation,
 * download listeners).
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkAwarenessModule {

    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Last line of defense (GAP-011): an exception escaping a launch{} on this scope
            // would otherwise kill the process — a failed remote command must never do that.
            CoroutineExceptionHandler { _, t ->
                Log.e("JellyWristAppScope", "Uncaught exception in appScope", t)
            },
    )

    @Provides
    @Singleton
    fun provideConnectivityManager(@ApplicationContext context: Context): ConnectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    @Provides
    @Singleton
    fun provideNetworkRepository(
        connectivityManager: ConnectivityManager,
        appScope: CoroutineScope,
    ): NetworkRepository = NetworkRepositoryImpl(connectivityManager, appScope)

    @Provides
    @Singleton
    fun provideHighBandwidthMediator(
        connectivityManager: ConnectivityManager,
        appScope: CoroutineScope,
    ): HighBandwidthNetworkMediator = StandardHighBandwidthNetworkMediator(
        logger = NetworkStatusLogger.Logging,
        networkRequester = NetworkRequesterImpl(connectivityManager),
        coroutineScope = appScope,
        // Delay before actually releasing the network: avoids Wi-Fi ping-pong when two
        // requests follow each other (sync end + immediate relaunch).
        delayToRelease = 3.seconds,
    )
}

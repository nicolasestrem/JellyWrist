package com.example.jellywrist.mobile.di

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.example.jellywrist.data.jellyfin.JellyfinBackend
import com.example.jellywrist.data.jellyfin.JellyfinBackendImpl
import com.example.jellywrist.mobile.BuildConfig
import com.example.jellywrist.mobile.datalayer.WatchLink
import com.example.jellywrist.mobile.datalayer.WearableWatchLink
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.okhttp.OkHttpFactory
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import javax.inject.Singleton

/** Dependencies specific to the phone app; shared networking lives in :core. */
@Module
@InstallIn(SingletonComponent::class)
object MobileModule {
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            // Last line of defense (GAP-011): an exception escaping a launch{} on this scope
            // would otherwise kill the process — a failed Data Layer exchange must never do that.
            CoroutineExceptionHandler { _, t ->
                Log.e("JellyWristAppScope", "Uncaught exception in appScope", t)
            },
    )

    /**
     * Phone preferences store.
     *
     * The path exactly reproduces that of the `preferencesDataStore` delegate previously
     * used (`datastore/<name>.preferences_pb`): existing installations keep their pinned
     * certificate. Single instance — opening two `DataStore` on the same file would throw
     * on first access.
     */
    @Provides
    @Singleton
    fun provideMobileDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create {
        context.preferencesDataStoreFile(MOBILE_PREFS_NAME)
    }

    private const val MOBILE_PREFS_NAME = "jellywrist_mobile_settings"

    @Provides
    @Singleton
    fun provideJellyfin(
        @ApplicationContext context: Context,
        okHttpClient: OkHttpClient,
    ): Jellyfin = createJellyfin {
        this.context = context
        clientInfo = ClientInfo("JellyWrist Mobile", BuildConfig.VERSION_NAME)
        apiClientFactory = OkHttpFactory(base = okHttpClient)
    }
}

/** Phone binding of the shared SDK port; :core does not know its host app. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MobileBindingsModule {
    @Binds
    @Singleton
    abstract fun bindJellyfinBackend(impl: JellyfinBackendImpl): JellyfinBackend

    /** The Data Layer transport goes through a port: orchestrations remain testable on JVM. */
    @Binds
    @Singleton
    abstract fun bindWatchLink(impl: WearableWatchLink): WatchLink
}

package com.example.jellywrist

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.example.jellywrist.network.NetworkAwareness
import com.example.jellywrist.sync.SyncScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * Application entry point.
 *
 * - Provides the WorkManager configuration with the Hilt factory (injected sync workers).
 *   The default initializer is removed from the manifest: leaving it would create an
 *   instance with the standard factory before Hilt's factory is available.
 * - Starts network observation (pause/resume of downloads, PLAN.md §8 task 12).
 * - Arms periodic sync per settings (charging + Wi-Fi by default, REQ-007) — idempotent:
 *   `ExistingPeriodicWorkPolicy.UPDATE`.
 * - Overrides the Coil image loader from `di/CoilModule` (shared OkHttp client: TLS policy
 *   DEC-006 + Jellyfin headers): implementing [SingletonImageLoader.Factory] is the only
 *   documented way to substitute the default loader used by `AsyncImage`.
 */
@HiltAndroidApp
class JellyWristApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var networkAwareness: NetworkAwareness

    /**
     * [Provider]: constructing the scheduler pulls SyncEngine → TrackDownloader →
     * OkHttpClient (whose TLS trust-index build alone costs ~400 ms) — StrictMode caught
     * that chain running on the main thread during Application field injection. The
     * periodic-sync arming below resolves it on the app scope instead.
     */
    @Inject lateinit var syncScheduler: Provider<SyncScheduler>

    /** [Provider]: constructing the repository pulls the Jellyfin backend → OkHttpClient. */
    @Inject lateinit var authRepository: Provider<com.example.jellywrist.data.repo.AuthRepository>

    @Inject lateinit var settingsRepository: com.example.jellywrist.data.settings.SettingsRepository

    @Inject lateinit var pinRegistry: com.example.jellywrist.data.net.PinRegistry

    @Inject lateinit var appScope: CoroutineScope

    /** [Provider]: the loader is only constructed on first album-art display. */
    @Inject lateinit var imageLoader: Provider<ImageLoader>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        // Before super.onCreate(): Hilt performs field injection there, and that work must
        // already be under the policy. Debug only — penaltyLog keeps violations visible in
        // logcat without killing the app.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
        super.onCreate()
        // First: warm the session cache on IO so the first Keystore/EncryptedSharedPreferences
        // read (MasterKey + Tink + file I/O) never lands on the main thread.
        appScope.launch { authRepository.get().warmUp() }
        networkAwareness.start()
        appScope.launch {
            // Hydrate TLS pinning before any dependent traffic, then follow settings.
            settingsRepository.settings.collect { pinRegistry.update(it.pinnedCertificateSha256) }
        }
        appScope.launch { syncScheduler.get().schedulePeriodic() }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()
}

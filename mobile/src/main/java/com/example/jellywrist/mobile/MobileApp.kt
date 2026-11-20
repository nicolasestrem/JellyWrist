package com.example.jellywrist.mobile

import android.app.Application
import android.os.StrictMode
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.example.jellywrist.data.net.PinRegistry
import com.example.jellywrist.mobile.data.MobilePrefs
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Provider

/**
 * Phone entry point: hydrates TLS pinning before Jellyfin calls.
 *
 * Implements [SingletonImageLoader.Factory] — the only documented way to impose Hilt's
 * image loader on the app's `AsyncImage`: without it Coil creates its default loader,
 * outside TLS policy and without Jellyfin authentication.
 */
@HiltAndroidApp
class MobileApp : Application(), SingletonImageLoader.Factory {
    @Inject
    lateinit var appScope: CoroutineScope

    @Inject
    lateinit var mobilePrefs: MobilePrefs

    @Inject
    lateinit var pinRegistry: PinRegistry

    /** [Provider]: constructing the repository pulls the Jellyfin backend → OkHttpClient. */
    @Inject
    lateinit var authRepository: Provider<com.example.jellywrist.data.repo.AuthRepository>

    /** [Provider]: the loader is only built on first cover art display. */
    @Inject
    lateinit var imageLoader: Provider<ImageLoader>

    override fun onCreate() {
        // Before super.onCreate(): Hilt injects fields there — that work must already be
        // under the policy. Debug only; penaltyLog keeps violations visible in logcat.
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder().detectLeakedClosableObjects().penaltyLog().build(),
            )
        }
        super.onCreate()
        // Warm the session cache on IO so the first EncryptedSharedPreferences read never
        // lands on the main thread (MainActivity reads it before setContent).
        appScope.launch { authRepository.get().warmUp() }
        appScope.launch {
            mobilePrefs.pinnedCertificateSha256.collect { pinRegistry.update(it) }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader.get()
}

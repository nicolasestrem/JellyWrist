package com.example.jellywrist.data.storage

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Actual disk space of the volume hosting the app's private storage.
 *
 * Abstracted so the budget planner stays a pure function testable on the JVM:
 * [StatFs] requires a mounted path. The user quota is not enough — an 8 GB watch can
 * be full well before reaching a 6 GB quota, and a sync must then stop cleanly
 * rather than fail on write (REQ-008).
 */
interface DeviceStorage {
    /** Bytes actually available to the app. Suspends: [StatFs] is a filesystem syscall. */
    suspend fun availableBytes(): Long

    /** Total size of the volume — used to render the gauge proportionally. */
    suspend fun totalBytes(): Long
}

@Singleton
class StatFsDeviceStorage @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : DeviceStorage {

    /**
     * Measured on `filesDir`, not on the system root: that is the volume Media3 actually
     * writes to, and it can differ from `/data` on some devices.
     */
    private fun statFs() = StatFs(context.filesDir.absolutePath)

    // withContext(IO): these used to run inside main-dispatcher flow transforms — two
    // StatFs constructions per download-progress tick, on the main thread.
    override suspend fun availableBytes(): Long = withContext(Dispatchers.IO) { statFs().availableBytes }

    override suspend fun totalBytes(): Long = withContext(Dispatchers.IO) { statFs().totalBytes }
}

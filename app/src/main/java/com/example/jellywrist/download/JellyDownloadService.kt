package com.example.jellywrist.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.workmanager.WorkManagerScheduler
import com.example.jellywrist.R
import com.example.jellywrist.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Foreground download service (PLAN.md §8 task 6).
 *
 * Extends Media3's [DownloadService]: the superclass manages the FGS lifecycle, resume
 * after process death, and the progress notification. No business logic here — it lives
 * in [Media3TrackDownloader] and in the sync engine.
 *
 * **FGS type: `dataSync`** — subject to the Android 15+ 6-hour cap (DEC-005). If the cap
 * kills the service, the [WorkManagerScheduler] returned by [getScheduler] relaunches it
 * as soon as conditions allow and [DownloadManager] resumes via Range (REQ-003). The
 * notification under unmet conditions says "paused — will resume", never "failed".
 */
@AndroidEntryPoint
class JellyDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_notification_channel,
    /* channelDescriptionResourceId = */ 0,
) {
    @Inject lateinit var manager: DownloadManager

    private val notificationHelper by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = manager

    /**
     * Scheduled restart when conditions become favorable again (network, charging).
     * Without a scheduler, a service killed by the 6-hour cap would only return on the
     * next app open — unacceptable for overnight sync (PLAN.md §4.3).
     */
    override fun getScheduler(): Scheduler = WorkManagerScheduler(this, WORK_NAME)

    override fun getForegroundNotification(
        downloads: List<Download>,
        notMetRequirements: Int,
    ): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val message = if (notMetRequirements != 0) {
            // Unmet conditions (metered network, etc.): paused, not failed (DEC-005).
            getString(R.string.downloads_paused)
        } else {
            null
        }
        return notificationHelper.buildProgressNotification(
            this,
            R.drawable.ic_launcher_monochrome,
            openApp,
            message,
            downloads,
            notMetRequirements,
        )
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "jellywatch_downloads"
        private const val WORK_NAME = "jellywatch_download_restart"
    }
}

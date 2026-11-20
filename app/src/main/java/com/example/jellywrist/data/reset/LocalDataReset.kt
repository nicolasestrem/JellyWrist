package com.example.jellywrist.data.reset

import com.example.jellywrist.data.db.AccountDao
import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionTrackDao
import com.example.jellywrist.data.db.ServerDao
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.repo.AuthRepository
import com.example.jellywrist.data.settings.SettingsRepository
import com.example.jellywrist.download.TrackDownloader
import com.example.jellywrist.playback.QueueStore
import com.example.jellywrist.sync.SyncScheduler
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Purge port for data **tied to the current server**.
 *
 * Abstracted for two reasons: the companion coordinator only needs this operation
 * (not the full factory reset), and keeping it abstract leaves it constructible in
 * a JVM test — [LocalDataReset] aggregates Room DAOs and Media3's `DownloadManager`, both
 * of which depend on an Android `Context`.
 */
fun interface ServerDataReset {
    /**
     * Erases the previous server's data and leaves the watch **ready to sync** the
     * new one — the session and settings are untouched.
     */
    suspend fun clearServerData()
}

/**
 * Erasure of the watch's local data (PLAN.md §8 task 13).
 *
 * Extracted from the settings screen: sign-out is no longer the only path that must erase
 * everything — a reconfiguration to another server ordered by the phone
 * (`CompanionSetupCoordinator`) must purge the old server's catalog, otherwise foreign
 * Jellyfin identifiers survive in the database and the downloaded tracks no longer
 * correspond to anything.
 */
@Singleton
class LocalDataReset @Inject constructor(
    private val syncScheduler: SyncScheduler,
    private val trackDao: TrackDao,
    private val trackDownloader: TrackDownloader,
    private val collectionTrackDao: CollectionTrackDao,
    private val collectionDao: CollectionDao,
    private val syncLogDao: SyncLogDao,
    private val accountDao: AccountDao,
    private val serverDao: ServerDao,
    private val queueStore: QueueStore,
    private val settingsRepository: SettingsRepository,
    private val authRepository: AuthRepository,
) : ServerDataReset {

    /**
     * Purges the previous server's catalog, then **re-arms** the periodic sync.
     *
     * The re-arming is not cosmetic: [purge] cancels every job carrying the sync tag,
     * unique periodic job included, and nothing else recreates it before the next process
     * start or a settings change. Without it, the reconfigured watch would silently stop
     * syncing on its own.
     *
     * The settings and the session **survive** — a successful reconfiguration has just
     * written the new session.
     */
    override suspend fun clearServerData() {
        purge()
        syncScheduler.schedulePeriodic()
    }

    /**
     * Factory reset: [purge] plus the settings and the token — nothing survives, not even
     * the periodic job (unlike [clearServerData], which prepares a new server).
     *
     * The token goes **first**, and the ordering here is a safety guarantee, not a detail:
     * [purge] touches WorkManager, Media3's `DownloadManager`, five Room tables and the
     * playback queue — as many chances to fail. But `SettingsViewModel.signOut` announces
     * "signed out" from its `finally` and navigates regardless: a token surviving a
     * failed purge would leave the user convinced they're signed out while their
     * credentials still sit in the Keystore. Nothing in [purge] needs the session.
     */
    suspend fun factoryReset() {
        authRepository.signOut()
        purge()
        settingsRepository.clear()
    }

    /**
     * Erases everything indexed by the server's identifiers: scheduled jobs,
     * downloaded bytes, Room catalog, sync log and persisted playback queue.
     *
     * Order matters: downloads go first (the `DownloadManager` references
     * track ids), then the tables.
     */
    private suspend fun purge() {
        syncScheduler.cancelAll()
        val allTrackIds = trackDao.observeAll().first().map { it.id }
        if (allTrackIds.isNotEmpty()) trackDownloader.remove(allTrackIds)
        collectionTrackDao.clear()
        trackDao.clear()
        collectionDao.clear()
        syncLogDao.clear()
        accountDao.clear()
        serverDao.clear()
        queueStore.clear()
    }
}

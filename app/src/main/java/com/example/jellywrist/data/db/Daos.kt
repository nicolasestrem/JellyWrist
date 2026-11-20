package com.example.jellywrist.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Access to known servers.
 *
 * The DAOs are interfaces so the repositories stay testable on the JVM with in-memory
 * fakes — Room requires a `Context`, unavailable in a pure unit test
 * (see docs/claude-code-reference.md "Testing the Jellyfin layer on JVM").
 */
@Dao
interface ServerDao {
    @Upsert
    suspend fun upsert(server: ServerEntity)

    @Query("SELECT * FROM servers WHERE id = :id")
    suspend fun byId(id: String): ServerEntity?

    @Query("SELECT * FROM servers LIMIT 1")
    fun observeCurrent(): Flow<ServerEntity?>

    @Query("DELETE FROM servers")
    suspend fun clear()
}

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts LIMIT 1")
    fun observeCurrent(): Flow<AccountEntity?>

    @Query("DELETE FROM accounts")
    suspend fun clear()
}

@Dao
interface CollectionDao {
    @Upsert
    suspend fun upsertAll(collections: List<CollectionEntity>)

    /**
     * Inserts only if the row does not exist yet — atomic replacement for the
     * check-then-upsert pattern, which let a concurrent writer's fresher row
     * (name, counts refreshed from the server) be clobbered by stale wire values.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(collection: CollectionEntity)

    @Query("SELECT * FROM collections WHERE type = :type ORDER BY COALESCE(sortName, name)")
    fun observeByType(type: CollectionType): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE pinned = 1")
    suspend fun pinned(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE pinned = 1")
    fun observePinned(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun byId(id: String): CollectionEntity?

    @Query("SELECT * FROM collections WHERE id = :id")
    fun observeById(id: String): Flow<CollectionEntity?>

    @Query("UPDATE collections SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE collections SET lastSyncAtEpochMs = :epochMs, reconWatermark = :watermark WHERE id = :id")
    suspend fun markSynced(id: String, epochMs: Long, watermark: String?)

    @Query("UPDATE collections SET estimatedBytes = :bytes, trackCount = :count WHERE id = :id")
    suspend fun updateSizing(id: String, bytes: Long, count: Int)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM collections")
    suspend fun clear()
}

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun byId(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id IN (:ids)")
    suspend fun byIds(ids: List<String>): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE state = :state")
    suspend fun byState(state: TrackState): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE state = :state")
    fun observeByState(state: TrackState): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("UPDATE tracks SET state = :state, downloadedBytes = :downloadedBytes, errorMessage = :error WHERE id = :id")
    suspend fun updateProgress(id: String, state: TrackState, downloadedBytes: Long, error: String?)

    @Query("UPDATE tracks SET state = :state WHERE id = :id")
    suspend fun updateState(id: String, state: TrackState)

    /** Octets effectivement disponibles hors-ligne — alimente la jauge de stockage. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM tracks WHERE state IN ('READY', 'UNSUPPORTED')")
    suspend fun storedBytes(): Long

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM tracks WHERE state IN ('READY', 'UNSUPPORTED')")
    fun observeStoredBytes(): Flow<Long>

    /**
     * Tracks the download queue still owes, counted from the states the sync pass writes.
     *
     * Room rather than Media3 on purpose: `SyncEngine` writes `TO_DOWNLOAD` *before* handing
     * the ids to the downloader, so this count is true the instant the pass returns. Asking
     * `DownloadManager` instead would race — `addDownload` only posts to its internal thread,
     * so `currentDownloads` can still be empty for a pass that just enqueued forty tracks.
     */
    @Query("SELECT COUNT(*) FROM tracks WHERE state IN ('TO_DOWNLOAD', 'DOWNLOADING')")
    suspend fun outstandingCount(): Int

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM tracks")
    suspend fun clear()
}

@Dao
interface CollectionTrackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(links: List<CollectionTrackEntity>)

    @Query("DELETE FROM collection_tracks WHERE collectionId = :collectionId")
    suspend fun deleteForCollection(collectionId: String)

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN collection_tracks ct ON ct.trackId = t.id
        WHERE ct.collectionId = :collectionId
        ORDER BY ct.sortIndex
        """,
    )
    fun observeTracks(collectionId: String): Flow<List<TrackEntity>>

    @Query(
        """
        SELECT t.* FROM tracks t
        INNER JOIN collection_tracks ct ON ct.trackId = t.id
        WHERE ct.collectionId = :collectionId
        ORDER BY ct.sortIndex
        """,
    )
    suspend fun tracks(collectionId: String): List<TrackEntity>

    @Query("SELECT trackId FROM collection_tracks WHERE collectionId = :collectionId ORDER BY sortIndex")
    suspend fun trackIds(collectionId: String): List<String>

    /**
     * Tracks that no longer belong to any pinned collection: candidates for purging
     * (REQ-006). The reference counting is done in SQL to stay atomic with respect to
     * a concurrent unpin.
     */
    @Query(
        """
        SELECT t.id FROM tracks t
        WHERE NOT EXISTS (
            SELECT 1 FROM collection_tracks ct
            INNER JOIN collections c ON c.id = ct.collectionId
            WHERE ct.trackId = t.id AND c.pinned = 1
        )
        """,
    )
    suspend fun orphanTrackIds(): List<String>

    /** Remplace en une transaction le contenu d'une collection (diff serveur → local). */
    @Transaction
    suspend fun replaceContent(collectionId: String, links: List<CollectionTrackEntity>) {
        deleteForCollection(collectionId)
        insertAll(links)
    }

    @Query("DELETE FROM collection_tracks")
    suspend fun clear()
}

@Dao
interface SyncLogDao {
    @Insert
    suspend fun insert(entry: SyncLogEntity): Long

    @Upsert
    suspend fun upsert(entry: SyncLogEntity)

    @Query("SELECT * FROM sync_log ORDER BY startedAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int = 20): Flow<List<SyncLogEntity>>

    @Query("SELECT * FROM sync_log ORDER BY startedAtEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<SyncLogEntity?>

    /** Log rotation: keep only the last [keep] runs (PLAN.md §8 task 13). */
    @Query("DELETE FROM sync_log WHERE id NOT IN (SELECT id FROM sync_log ORDER BY startedAtEpochMs DESC LIMIT :keep)")
    suspend fun trimTo(keep: Int)

    @Query("DELETE FROM sync_log")
    suspend fun clear()
}

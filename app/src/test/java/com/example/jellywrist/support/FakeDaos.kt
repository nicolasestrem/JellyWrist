package com.example.jellywrist.support

import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionEntity
import com.example.jellywrist.data.db.CollectionTrackDao
import com.example.jellywrist.data.db.CollectionTrackEntity
import com.example.jellywrist.data.db.CollectionType
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.db.SyncLogEntity
import com.example.jellywrist.data.db.TrackDao
import com.example.jellywrist.data.db.TrackEntity
import com.example.jellywrist.data.db.TrackState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory doubles of Room DAOs, for JVM tests (REQ-011).
 *
 * Room requires an Android `Context` unavailable in pure unit tests (docs/claude-code-reference.md); these
 * fakes implement the query semantics **faithfully** — in particular the orphan
 * refcount of [FakeCollectionTrackDao.orphanTrackIds], computed from the actual link
 * table, so that a purge bug is detectable by tests.
 */
class FakeCollectionDao : CollectionDao {
    val rows = linkedMapOf<String, CollectionEntity>()
    private val flow = MutableStateFlow<List<CollectionEntity>>(emptyList())

    private fun emit() {
        flow.value = rows.values.toList()
    }

    override suspend fun upsertAll(collections: List<CollectionEntity>) {
        collections.forEach { rows[it.id] = it }
        emit()
    }

    override suspend fun insertIgnore(collection: CollectionEntity) {
        if (collection.id !in rows) {
            rows[collection.id] = collection
            emit()
        }
    }

    override fun observeByType(type: CollectionType): Flow<List<CollectionEntity>> =
        flow.map { list -> list.filter { it.type == type }.sortedBy { it.sortName ?: it.name } }

    override suspend fun pinned(): List<CollectionEntity> = rows.values.filter { it.pinned }

    override fun observePinned(): Flow<List<CollectionEntity>> = flow.map { list -> list.filter { it.pinned } }

    override suspend fun byId(id: String): CollectionEntity? = rows[id]

    override fun observeById(id: String): Flow<CollectionEntity?> = flow.map { rows[id] }

    override suspend fun setPinned(id: String, pinned: Boolean) {
        rows[id]?.let { rows[id] = it.copy(pinned = pinned) }
        emit()
    }

    override suspend fun markSynced(id: String, epochMs: Long, watermark: String?) {
        rows[id]?.let { rows[id] = it.copy(lastSyncAtEpochMs = epochMs, reconWatermark = watermark) }
        emit()
    }

    override suspend fun updateSizing(id: String, bytes: Long, count: Int) {
        rows[id]?.let { rows[id] = it.copy(estimatedBytes = bytes, trackCount = count) }
        emit()
    }

    override suspend fun delete(id: String) {
        rows.remove(id)
        emit()
    }

    override suspend fun clear() {
        rows.clear()
        emit()
    }
}

class FakeTrackDao : TrackDao {
    val rows = linkedMapOf<String, TrackEntity>()
    private val flow = MutableStateFlow<List<TrackEntity>>(emptyList())

    private fun emit() {
        flow.value = rows.values.toList()
    }

    override suspend fun upsertAll(tracks: List<TrackEntity>) {
        tracks.forEach { rows[it.id] = it }
        emit()
    }

    override suspend fun byId(id: String): TrackEntity? = rows[id]

    override suspend fun byIds(ids: List<String>): List<TrackEntity> = ids.mapNotNull { rows[it] }

    override suspend fun byState(state: TrackState): List<TrackEntity> =
        rows.values.filter { it.state == state }

    override fun observeByState(state: TrackState): Flow<List<TrackEntity>> =
        flow.map { list -> list.filter { it.state == state } }

    override fun observeAll(): Flow<List<TrackEntity>> = flow

    override suspend fun updateProgress(id: String, state: TrackState, downloadedBytes: Long, error: String?) {
        rows[id]?.let {
            rows[id] = it.copy(state = state, downloadedBytes = downloadedBytes, errorMessage = error)
        }
        emit()
    }

    override suspend fun updateState(id: String, state: TrackState) {
        rows[id]?.let { rows[id] = it.copy(state = state) }
        emit()
    }

    override suspend fun storedBytes(): Long = rows.values
        .filter { it.state == TrackState.READY || it.state == TrackState.UNSUPPORTED }
        .sumOf { it.sizeBytes }

    override suspend fun outstandingCount(): Int = rows.values
        .count { it.state == TrackState.TO_DOWNLOAD || it.state == TrackState.DOWNLOADING }

    override fun observeStoredBytes(): Flow<Long> = flow.map { list ->
        list.filter { it.state == TrackState.READY || it.state == TrackState.UNSUPPORTED }
            .sumOf { it.sizeBytes }
    }

    override suspend fun deleteByIds(ids: List<String>) {
        ids.forEach { rows.remove(it) }
        emit()
    }

    override suspend fun clear() {
        rows.clear()
        emit()
    }
}

class FakeCollectionTrackDao(
    private val collectionDao: FakeCollectionDao,
    private val trackDao: FakeTrackDao,
) : CollectionTrackDao {
    val links = mutableListOf<CollectionTrackEntity>()
    private val flow = MutableStateFlow<List<CollectionTrackEntity>>(emptyList())

    private fun emit() {
        flow.value = links.toList()
    }

    override suspend fun insertAll(links: List<CollectionTrackEntity>) {
        links.forEach { link ->
            this.links.removeAll { it.collectionId == link.collectionId && it.trackId == link.trackId }
            this.links.add(link)
        }
        emit()
    }

    override suspend fun deleteForCollection(collectionId: String) {
        links.removeAll { it.collectionId == collectionId }
        emit()
    }

    override fun observeTracks(collectionId: String): Flow<List<TrackEntity>> =
        flow.map { list ->
            list.filter { it.collectionId == collectionId }
                .sortedBy { it.sortIndex }
                .mapNotNull { trackDao.rows[it.trackId] }
        }

    override suspend fun tracks(collectionId: String): List<TrackEntity> =
        links.filter { it.collectionId == collectionId }
            .sortedBy { it.sortIndex }
            .mapNotNull { trackDao.rows[it.trackId] }

    override suspend fun trackIds(collectionId: String): List<String> =
        links.filter { it.collectionId == collectionId }.sortedBy { it.sortIndex }.map { it.trackId }

    override suspend fun orphanTrackIds(): List<String> {
        // Faithful refcount: a track is orphaned if no PINNED collection
        // references it — same semantics as the production SQL query.
        val pinnedIds = collectionDao.rows.values.filter { it.pinned }.map { it.id }.toSet()
        val referenced = links.filter { it.collectionId in pinnedIds }.map { it.trackId }.toSet()
        return trackDao.rows.keys.filter { it !in referenced }
    }

    override suspend fun clear() {
        links.clear()
        emit()
    }
}

class FakeSyncLogDao : SyncLogDao {
    val entries = mutableListOf<SyncLogEntity>()
    private val flow = MutableStateFlow<List<SyncLogEntity>>(emptyList())
    private var nextId = 1L

    private fun emit() {
        flow.value = entries.toList()
    }

    override suspend fun insert(entry: SyncLogEntity): Long {
        val id = nextId++
        entries.add(entry.copy(id = id))
        emit()
        return id
    }

    override suspend fun upsert(entry: SyncLogEntity) {
        if (entry.id == 0L) {
            insert(entry)
        } else {
            entries.removeAll { it.id == entry.id }
            entries.add(entry)
            emit()
        }
    }

    override fun observeRecent(limit: Int): Flow<List<SyncLogEntity>> =
        flow.map { list -> list.sortedByDescending { it.startedAtEpochMs }.take(limit) }

    override fun observeLatest(): Flow<SyncLogEntity?> =
        flow.map { list -> list.maxByOrNull { it.startedAtEpochMs } }

    override suspend fun trimTo(keep: Int) {
        val keepers = entries.sortedByDescending { it.startedAtEpochMs }.take(keep).toSet()
        entries.retainAll(keepers)
        emit()
    }

    override suspend fun clear() {
        entries.clear()
        emit()
    }
}

package com.example.jellywrist.playback

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persisted playback queue (PLAN.md §5: "resume queue and position after restart").
 *
 * Deliberately outside Room: it is player session state, not catalog state — storing it
 * in `tracks` would mix two lifecycles. DataStore is sufficient for a list of IDs and a
 * position.
 *
 * [SavedQueue.lastCollectionId] also serves the "resume playback" Tile.
 */
data class SavedQueue(
    val trackIds: List<String>,
    val index: Int,
    val positionMs: Long,
    val lastCollectionId: String?,
)

private val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(name = "jellywatch_queue")

@Singleton
class QueueStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    suspend fun save(trackIds: List<String>, index: Int, positionMs: Long, collectionId: String?) {
        context.queueDataStore.edit { prefs ->
            prefs[KEY_IDS] = trackIds.joinToString(SEPARATOR)
            prefs[KEY_INDEX] = index
            prefs[KEY_POSITION] = positionMs
            if (collectionId != null) prefs[KEY_COLLECTION] = collectionId else prefs.remove(KEY_COLLECTION)
        }
    }

    suspend fun load(): SavedQueue? {
        val prefs = context.queueDataStore.data.first()
        val ids = prefs[KEY_IDS]?.takeIf { it.isNotBlank() }?.split(SEPARATOR) ?: return null
        return SavedQueue(
            trackIds = ids,
            index = (prefs[KEY_INDEX] ?: 0).coerceIn(0, ids.lastIndex),
            positionMs = prefs[KEY_POSITION] ?: 0L,
            lastCollectionId = prefs[KEY_COLLECTION],
        )
    }

    suspend fun clear() {
        context.queueDataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_IDS = stringPreferencesKey("track_ids")
        val KEY_INDEX = intPreferencesKey("index")
        val KEY_POSITION = longPreferencesKey("position_ms")
        val KEY_COLLECTION = stringPreferencesKey("last_collection_id")

        /** Jellyfin UUIDs do not contain commas: safe separator. */
        const val SEPARATOR = ","
    }
}

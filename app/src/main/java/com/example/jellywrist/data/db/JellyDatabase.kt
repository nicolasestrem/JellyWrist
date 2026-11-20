package com.example.jellywrist.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Enum converters.
 *
 * Explicit rather than left to Room's automatic conversion: columns store
 * the constant **name**, which makes literal SQL queries in [TrackDao] (e.g.
 * `state IN ('READY', 'UNSUPPORTED')`) valid and stable against reordering of
 * the enum. An unknown value on read is corruption: we fail hard.
 */
class DbConverters {
    @TypeConverter
    fun fromCollectionType(value: CollectionType): String = value.name

    @TypeConverter
    fun toCollectionType(value: String): CollectionType = CollectionType.valueOf(value)

    @TypeConverter
    fun fromTrackState(value: TrackState): String = value.name

    @TypeConverter
    fun toTrackState(value: String): TrackState = TrackState.valueOf(value)

    @TypeConverter
    fun fromSyncOutcome(value: SyncOutcome): String = value.name

    @TypeConverter
    fun toSyncOutcome(value: String): SyncOutcome = SyncOutcome.valueOf(value)
}

/**
 * Local database: synced catalog and download state (PLAN.md §7).
 *
 * `exportSchema = true` (JSON schema under `app/schemas`) is required by REQ-012 / DEC-009:
 * each schema evolution ships a versioned `Migration`, tested in instrumentation;
 * no destructive fallback is allowed in release.
 *
 * History:
 * - v1: initial creation.
 * - v2: `collections.localOnly` — local collections ("500 random songs") that the
 *   sync engine reconciles by identity instead of refreshing from the server.
 */
@Database(
    entities = [
        ServerEntity::class,
        AccountEntity::class,
        CollectionEntity::class,
        TrackEntity::class,
        CollectionTrackEntity::class,
        SyncLogEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(DbConverters::class)
abstract class JellyDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao

    abstract fun accountDao(): AccountDao

    abstract fun collectionDao(): CollectionDao

    abstract fun trackDao(): TrackDao

    abstract fun collectionTrackDao(): CollectionTrackDao

    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val NAME = "jellywatch.db"

        /**
         * v1 → v2: `localOnly` column (default `0`) on `collections`.
         *
         * `NOT NULL DEFAULT 0`: existing collections are all server-based — the
         * historical semantics is preserved to the byte (REQ-012).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN localOnly INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

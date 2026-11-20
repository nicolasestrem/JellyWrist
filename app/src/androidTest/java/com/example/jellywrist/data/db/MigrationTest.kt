package com.example.jellywrist.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration tests (REQ-012 / DEC-009): each schema evolution is covered
 * by an instrumented test that runs the migration on a real populated database.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        JellyDatabase::class.java,
    )

    @Test
    fun migrate_v1_to_v2_preserving_collections() {
        // Populated v1 database: a pinned collection pre-migration.
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO collections
                    (id, type, name, sortName, artistName, pinned, estimatedBytes, trackCount,
                     lastSyncAtEpochMs, reconWatermark, imageTag)
                VALUES ('c1', 'PLAYLIST', 'My playlist', NULL, NULL, 1, 1234, 10, NULL, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        // Migration 1→2 with exported schema validation.
        val db = helper.runMigrationsAndValidate(TEST_DB, 2, true, JellyDatabase.MIGRATION_1_2)

        // Data survives and the new column has its default (0 = server).
        db.query("SELECT id, name, pinned, localOnly FROM collections").use { cursor ->
            assertEquals(1, cursor.count)
            cursor.moveToFirst()
            assertEquals("c1", cursor.getString(0))
            assertEquals("My playlist", cursor.getString(1))
            assertEquals(1, cursor.getInt(2))
            assertEquals("existing collections remain server collections", 0, cursor.getInt(3))
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}

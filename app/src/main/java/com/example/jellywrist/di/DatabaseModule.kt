package com.example.jellywrist.di

import android.content.Context
import androidx.room.Room
import com.example.jellywrist.data.db.AccountDao
import com.example.jellywrist.data.db.CollectionDao
import com.example.jellywrist.data.db.CollectionTrackDao
import com.example.jellywrist.data.db.JellyDatabase
import com.example.jellywrist.data.db.ServerDao
import com.example.jellywrist.data.db.SyncLogDao
import com.example.jellywrist.data.db.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the local database.
 *
 * No `fallbackToDestructiveMigration`: REQ-012 / DEC-009 forbid any destructive fallback.
 * A database whose schema does not match must cause open to fail — a crash on startup is
 * preferable to silently losing the user's catalog.
 *
 * Room applies foreign keys by default, which the refcounted purge of `collection_tracks`
 * (REQ-006) depends on via `ON DELETE CASCADE`.
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): JellyDatabase =
        Room.databaseBuilder(context, JellyDatabase::class.java, JellyDatabase.NAME)
            .addMigrations(JellyDatabase.MIGRATION_1_2)
            .build()

    @Provides
    fun provideServerDao(db: JellyDatabase): ServerDao = db.serverDao()

    @Provides
    fun provideAccountDao(db: JellyDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideCollectionDao(db: JellyDatabase): CollectionDao = db.collectionDao()

    @Provides
    fun provideTrackDao(db: JellyDatabase): TrackDao = db.trackDao()

    @Provides
    fun provideCollectionTrackDao(db: JellyDatabase): CollectionTrackDao = db.collectionTrackDao()

    @Provides
    fun provideSyncLogDao(db: JellyDatabase): SyncLogDao = db.syncLogDao()
}

package com.example.jellywrist.di

import com.example.jellywrist.network.NetworkAwareness
import com.example.jellywrist.sync.JellyfinSyncMetadataSource
import com.example.jellywrist.sync.SyncMetadataSource
import com.example.jellywrist.sync.SyncPreconditions
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Injection module for the sync engine (PLAN.md §8, task 9).
 *
 * Binds the [SyncMetadataSource] interface to its Jellyfin implementation
 * [JellyfinSyncMetadataSource]. The singleton is guaranteed by the `SingletonComponent`
 * scope.
 *
 * Note: [TrackDownloader] is bound in [DownloadModule] — this module only binds
 * the metadata-fetching port.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SyncModule {

    @Binds
    @Singleton
    abstract fun bindSyncMetadataSource(
        impl: JellyfinSyncMetadataSource,
    ): SyncMetadataSource

    /** The consent ladder reads the real network and battery state through this port. */
    @Binds
    @Singleton
    abstract fun bindSyncPreconditions(impl: NetworkAwareness): SyncPreconditions
}

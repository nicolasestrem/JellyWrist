package com.example.jellywrist.di

import com.example.jellywrist.data.reset.LocalDataReset
import com.example.jellywrist.data.reset.ServerDataReset
import com.example.jellywrist.sync.PeriodicSyncArmer
import com.example.jellywrist.sync.SyncControl
import com.example.jellywrist.sync.SyncScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the [ServerDataReset] port to full local erasure. */
@Module
@InstallIn(SingletonComponent::class)
abstract class ResetModule {

    @Binds
    @Singleton
    abstract fun bindServerDataReset(impl: LocalDataReset): ServerDataReset

    companion object {
        @Provides
        @Singleton
        fun providePeriodicSyncArmer(syncScheduler: SyncScheduler): PeriodicSyncArmer =
            PeriodicSyncArmer(syncScheduler::schedulePeriodic)

        @Provides
        @Singleton
        fun provideSyncControl(syncScheduler: SyncScheduler): SyncControl = syncScheduler
    }
}

package com.example.jellywrist.di

import com.example.jellywrist.download.DownloadQueue
import com.example.jellywrist.download.Media3DownloadQueue
import com.example.jellywrist.download.Media3TrackDownloader
import com.example.jellywrist.download.TrackDownloader
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Injection module for the download engine (PLAN.md §8, task 6).
 *
 * Binds the [TrackDownloader] interface to its Media3 implementation
 * [Media3TrackDownloader]. The singleton is guaranteed by the `SingletonComponent`
 * scope — only one `DownloadManager.Listener` is registered, and the internal
 * `CoroutineScope` lives as long as the application.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DownloadModule {

    @Binds
    @Singleton
    abstract fun bindTrackDownloader(impl: Media3TrackDownloader): TrackDownloader

    @Binds
    @Singleton
    abstract fun bindDownloadQueue(impl: Media3DownloadQueue): DownloadQueue
}

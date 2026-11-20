package com.example.jellywrist.di

import com.example.jellywrist.data.storage.DeviceStorage
import com.example.jellywrist.data.storage.StatFsDeviceStorage
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the [DeviceStorage] port to its real measurement via `StatFs`. */
@Module
@InstallIn(SingletonComponent::class)
abstract class StorageModule {

    @Binds
    @Singleton
    abstract fun bindDeviceStorage(impl: StatFsDeviceStorage): DeviceStorage
}

package com.example.jellywrist.di

import com.example.jellywrist.data.settings.DataStoreSettingsRepository
import com.example.jellywrist.data.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the [SettingsRepository] port to its DataStore implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class SettingsModule {

    @Binds
    @Singleton
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}

package com.example.jellywrist.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val pinnedCertificateSha256Key = stringPreferencesKey("pinned_certificate_sha256")

/**
 * Phone-specific preferences, separate from the watch settings.
 *
 * The [DataStore] is injected rather than derived from a `Context` (`preferencesDataStore`
 * delegate): the orchestrations that read these preferences — `WatchSetupController` first
 * and foremost — become testable on JVM with a temp-file store. The production path remains
 * unchanged (see `MobileModule.provideMobileDataStore`), so no migration.
 */
@Singleton
class MobilePrefs @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val pinnedCertificateSha256: Flow<String?> = dataStore.data.map {
        it[pinnedCertificateSha256Key]
    }

    suspend fun setPinnedCertificate(sha256: String?) {
        dataStore.edit { preferences ->
            if (sha256 == null) {
                preferences.remove(pinnedCertificateSha256Key)
            } else {
                preferences[pinnedCertificateSha256Key] = sha256
            }
        }
    }
}

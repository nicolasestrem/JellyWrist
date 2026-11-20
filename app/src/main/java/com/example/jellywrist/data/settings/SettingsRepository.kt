package com.example.jellywrist.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sync and storage settings (PLAN.md §4.3 / §4.4).
 *
 * [quotaBytes] caps what the app allows itself to occupy; it is distinct from free disk
 * space, which is a hardware constraint measured separately by
 * [com.example.jellywrist.data.storage.DeviceStorage] — the sync must honor both.
 */
data class SyncSettings(
    val quotaBytes: Long,
    val requiresCharging: Boolean,
    val unmeteredOnly: Boolean,
    val periodicityHours: Int,
    /** SHA-256 fingerprint of an explicitly accepted self-signed certificate (opt-in, DEC-006). */
    val pinnedCertificateSha256: String?,
) {
    companion object {
        /**
         * ~6 GB: reference value for a OnePlus Watch 4 (32 GB shared with the system),
         * deliberately conservative — a FLAC album weighs 400 MB+ (PLAN.md §4.4).
         */
        const val DEFAULT_QUOTA_BYTES = 6L * 1024 * 1024 * 1024

        /** Daily by default: the watch charges at night, the music updates on its own. */
        const val DEFAULT_PERIODICITY_HOURS = 24

        val DEFAULT = SyncSettings(
            quotaBytes = DEFAULT_QUOTA_BYTES,
            requiresCharging = true,
            unmeteredOnly = true,
            periodicityHours = DEFAULT_PERIODICITY_HOURS,
            pinnedCertificateSha256 = null,
        )
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "jellywrist_settings")

/**
 * Persisted settings — port testable on the JVM (REQ-011).
 *
 * No secret passes through here: the token stays in
 * [com.example.jellywrist.data.auth.TokenStore] (REQ-005). A public certificate
 * fingerprint is not a secret and may therefore live here.
 */
interface SettingsRepository {
    val settings: Flow<SyncSettings>

    suspend fun setQuotaBytes(bytes: Long)

    suspend fun setRequiresCharging(required: Boolean)

    suspend fun setUnmeteredOnly(unmetered: Boolean)

    suspend fun setPeriodicityHours(hours: Int)

    suspend fun setPinnedCertificate(sha256: String?)

    /** Restores the factory state — used by sign-out (PLAN.md §8 task 13). */
    suspend fun clear()
}

/** Production implementation: DataStore Preferences. */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : SettingsRepository {
    override val settings: Flow<SyncSettings> = context.settingsDataStore.data.map { prefs ->
        SyncSettings(
            quotaBytes = prefs[KEY_QUOTA_BYTES] ?: SyncSettings.DEFAULT_QUOTA_BYTES,
            requiresCharging = prefs[KEY_REQUIRES_CHARGING] ?: true,
            unmeteredOnly = prefs[KEY_UNMETERED_ONLY] ?: true,
            periodicityHours = prefs[KEY_PERIODICITY_HOURS] ?: SyncSettings.DEFAULT_PERIODICITY_HOURS,
            pinnedCertificateSha256 = prefs[KEY_PINNED_CERT],
        )
    }

    override suspend fun setQuotaBytes(bytes: Long) = edit { it[KEY_QUOTA_BYTES] = bytes }

    override suspend fun setRequiresCharging(required: Boolean) = edit { it[KEY_REQUIRES_CHARGING] = required }

    override suspend fun setUnmeteredOnly(unmetered: Boolean) = edit { it[KEY_UNMETERED_ONLY] = unmetered }

    override suspend fun setPeriodicityHours(hours: Int) = edit { it[KEY_PERIODICITY_HOURS] = hours }

    override suspend fun setPinnedCertificate(sha256: String?) = edit { prefs ->
        if (sha256 == null) prefs.remove(KEY_PINNED_CERT) else prefs[KEY_PINNED_CERT] = sha256
    }

    override suspend fun clear() = edit { it.clear() }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.settingsDataStore.edit(block)
    }

    private companion object {
        val KEY_QUOTA_BYTES = longPreferencesKey("quota_bytes")
        val KEY_REQUIRES_CHARGING = booleanPreferencesKey("requires_charging")
        val KEY_UNMETERED_ONLY = booleanPreferencesKey("unmetered_only")
        val KEY_PERIODICITY_HOURS = intPreferencesKey("periodicity_hours")
        val KEY_PINNED_CERT = stringPreferencesKey("pinned_certificate_sha256")
    }
}

package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.quotaDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "family_quota_prefs"
)

class QuotaPreferences(private val context: Context) {

    private object Keys {
        val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
        val USERNAME = stringPreferencesKey("username")
        val QUOTA_LIMIT_MB = longPreferencesKey("quota_limit_mb")
        val USED_MB_LEGACY = longPreferencesKey("used_mb")
        val USED_MB = doublePreferencesKey("used_mb_double")
        val IS_ACTIVE = booleanPreferencesKey("is_active")
        val SESSION_START_TS = longPreferencesKey("session_start_timestamp")
        val LAST_SYNC_EPOCH_MS = longPreferencesKey("last_sync_epoch_ms")
    }

    val sessionFlow: Flow<PersistedSession> = context.quotaDataStore.data.map { prefs ->
        val usedDouble = prefs[Keys.USED_MB] ?: (prefs[Keys.USED_MB_LEGACY]?.toDouble() ?: 0.0)
        PersistedSession(
            isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false,
            username = prefs[Keys.USERNAME] ?: "",
            quotaLimitMb = prefs[Keys.QUOTA_LIMIT_MB] ?: 0L,
            usedMb = roundMbTo2Decimals(usedDouble),
            isActive = prefs[Keys.IS_ACTIVE] ?: false,
            sessionStartTimestamp = prefs[Keys.SESSION_START_TS] ?: 0L,
            lastSyncEpochMs = prefs[Keys.LAST_SYNC_EPOCH_MS] ?: 0L
        )
    }

    suspend fun getSessionSnapshot(): PersistedSession = sessionFlow.first()

    suspend fun saveLoginSession(record: UserQuotaRecord) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = true
            prefs[Keys.USERNAME] = record.username
            prefs[Keys.QUOTA_LIMIT_MB] = record.quotaLimitMb
            prefs[Keys.USED_MB] = roundMbTo2Decimals(record.usedMb)
            prefs[Keys.IS_ACTIVE] = record.isActive
            prefs[Keys.SESSION_START_TS] = record.sessionStartTimestamp
            prefs[Keys.LAST_SYNC_EPOCH_MS] = System.currentTimeMillis()
        }
    }

    suspend fun updateRemoteQuota(
        quotaLimitMb: Long,
        usedMb: Double,
        isActive: Boolean,
        sessionStartTimestamp: Long
    ) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.QUOTA_LIMIT_MB] = quotaLimitMb
            prefs[Keys.USED_MB] = roundMbTo2Decimals(usedMb)
            prefs[Keys.IS_ACTIVE] = isActive
            if (sessionStartTimestamp > 0L) {
                prefs[Keys.SESSION_START_TS] = sessionStartTimestamp
            }
        }
    }

    suspend fun updateLocalUsage(usedMb: Double, syncEpochMs: Long) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.USED_MB] = roundMbTo2Decimals(usedMb)
            prefs[Keys.LAST_SYNC_EPOCH_MS] = syncEpochMs
        }
    }

    suspend fun clearSession() {
        context.quotaDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

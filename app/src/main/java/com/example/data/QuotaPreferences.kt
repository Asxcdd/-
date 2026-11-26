package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        val IS_DEMO = booleanPreferencesKey("is_demo")
        val QUOTA_LIMIT_MB = longPreferencesKey("quota_limit_mb")
        val USED_MB = longPreferencesKey("used_mb")
        val IS_ACTIVE = booleanPreferencesKey("is_active")
        val SESSION_START_TS = longPreferencesKey("session_start_timestamp")
        val SIMULATED_EXTRA_BYTES = longPreferencesKey("simulated_extra_bytes")
        val LAST_SYNC_EPOCH_MS = longPreferencesKey("last_sync_epoch_ms")
    }

    val sessionFlow: Flow<PersistedSession> = context.quotaDataStore.data.map { prefs ->
        PersistedSession(
            isLoggedIn = prefs[Keys.IS_LOGGED_IN] ?: false,
            username = prefs[Keys.USERNAME] ?: "",
            isDemo = prefs[Keys.IS_DEMO] ?: false,
            quotaLimitMb = prefs[Keys.QUOTA_LIMIT_MB] ?: 0L,
            usedMb = prefs[Keys.USED_MB] ?: 0L,
            isActive = prefs[Keys.IS_ACTIVE] ?: false,
            sessionStartTimestamp = prefs[Keys.SESSION_START_TS] ?: 0L,
            simulatedExtraBytes = prefs[Keys.SIMULATED_EXTRA_BYTES] ?: 0L,
            lastSyncEpochMs = prefs[Keys.LAST_SYNC_EPOCH_MS] ?: 0L
        )
    }

    suspend fun getSessionSnapshot(): PersistedSession = sessionFlow.first()

    suspend fun saveLoginSession(record: UserQuotaRecord) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.IS_LOGGED_IN] = true
            prefs[Keys.USERNAME] = record.username
            prefs[Keys.IS_DEMO] = record.isDemo
            prefs[Keys.QUOTA_LIMIT_MB] = record.quotaLimitMb
            prefs[Keys.USED_MB] = record.usedMb
            prefs[Keys.IS_ACTIVE] = record.isActive
            prefs[Keys.SESSION_START_TS] = record.sessionStartTimestamp
            prefs[Keys.SIMULATED_EXTRA_BYTES] = 0L
            prefs[Keys.LAST_SYNC_EPOCH_MS] = System.currentTimeMillis()
        }
    }

    suspend fun updateRemoteQuota(
        quotaLimitMb: Long,
        usedMb: Long,
        isActive: Boolean,
        sessionStartTimestamp: Long
    ) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.QUOTA_LIMIT_MB] = quotaLimitMb
            prefs[Keys.USED_MB] = usedMb
            prefs[Keys.IS_ACTIVE] = isActive
            if (sessionStartTimestamp > 0L) {
                prefs[Keys.SESSION_START_TS] = sessionStartTimestamp
            }
        }
    }

    suspend fun updateLocalUsage(usedMb: Long, syncEpochMs: Long) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.USED_MB] = usedMb.coerceAtLeast(0L)
            prefs[Keys.LAST_SYNC_EPOCH_MS] = syncEpochMs
        }
    }

    suspend fun setDemoSimulation(
        simulatedExtraBytes: Long,
        usedMb: Long,
        sessionStartTimestamp: Long
    ) {
        context.quotaDataStore.edit { prefs ->
            prefs[Keys.SIMULATED_EXTRA_BYTES] = simulatedExtraBytes.coerceAtLeast(0L)
            prefs[Keys.USED_MB] = usedMb.coerceAtLeast(0L)
            prefs[Keys.SESSION_START_TS] = sessionStartTimestamp
            prefs[Keys.LAST_SYNC_EPOCH_MS] = System.currentTimeMillis()
        }
    }

    suspend fun clearSession() {
        context.quotaDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}

package com.example.service

import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import androidx.core.app.ServiceCompat
import com.example.data.BYTES_PER_MB
import com.example.data.FirebaseQuotaRepository
import com.example.data.QuotaPreferences
import com.example.data.QuotaRemoteUpdate
import com.example.data.roundMbTo2Decimals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

class QuotaMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START_MONITOR"
        const val ACTION_FORCE_SYNC = "com.example.service.ACTION_FORCE_SYNC"
        const val TRAFFIC_PREFS_NAME = "quota_traffic_delta_prefs"

        private const val KEY_ACTIVE_USER = "active_user"
        private const val KEY_BASE_USED_BYTES = "base_used_bytes"
        private const val KEY_PENDING_DELTA_BYTES = "pending_delta_bytes"
        private const val KEY_LAST_TRAFFIC_TOTAL = "last_traffic_total_bytes"
        private const val KEY_INITIAL_FIREBASE_BYTES = "initial_firebase_bytes"
        private const val KEY_STATS_WINDOW_START_MS = "stats_window_start_ms"

        private const val MONITOR_LOOP_INTERVAL_MS = 15_000L

        fun createStartIntent(context: Context): Intent {
            return Intent(context, QuotaMonitorService::class.java).apply {
                action = ACTION_START
            }
        }

        fun createForceSyncIntent(context: Context): Intent {
            return Intent(context, QuotaMonitorService::class.java).apply {
                action = ACTION_FORCE_SYNC
            }
        }

        fun clearTrafficDeltaPrefs(context: Context) {
            context.getSharedPreferences(TRAFFIC_PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private lateinit var preferences: QuotaPreferences
    private lateinit var trafficPrefs: SharedPreferences
    private lateinit var firebaseRepo: FirebaseQuotaRepository
    private lateinit var connectivityManager: ConnectivityManager
    private var networkStatsManager: NetworkStatsManager? = null

    private var isMonitoringStarted = false
    private var monitorJobs: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var activeUsername: String = ""
    private var quotaLimitMb: Long = 0L
    private var firebaseUsedMb: Double = 0.0
    private var currentUsedBytes: Long = 0L
    private var userIsActive: Boolean = false
    private var sessionStartTimestamp: Long = 0L
    private var statsWindowStartMs: Long = 0L
    private var initialFirebaseBytes: Long = 0L
    private var isWifiConnected: Boolean = false
    private var lastSyncEpochMs: Long = 0L
    private var syncStatusMessage: String = "جاري المراقبة المباشرة"
    private var lastNotifiedRemainingMb: Long = -1L
    private var lastNotifiedBlockedState: Boolean? = null

    private var baseUsedBytes: Long = 0L
    private var pendingDeltaBytes: Long = 0L
    private var lastTrafficTotalBytes: Long = -1L

    override fun onCreate() {
        super.onCreate()
        preferences = QuotaPreferences(applicationContext)
        trafficPrefs = applicationContext.getSharedPreferences(TRAFFIC_PREFS_NAME, Context.MODE_PRIVATE)
        firebaseRepo = FirebaseQuotaRepository(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkStatsManager = getSystemService(NetworkStatsManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        if (intent?.action == ACTION_FORCE_SYNC && isMonitoringStarted) {
            serviceScope.launch {
                measureWifiUsageAndSync(forceFirebaseSync = true)
            }
            return START_STICKY
        }

        if (!isMonitoringStarted) {
            isMonitoringStarted = true
            startMonitoringPipeline()
        }
        return START_STICKY
    }

    private fun promoteToForeground() {
        val notification = QuotaNotificationHelper.buildMonitorNotification(
            this,
            QuotaRuntimeState.status.value
        )
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            QuotaNotificationHelper.MONITOR_NOTIFICATION_ID,
            notification,
            fgType
        )
    }

    private fun startMonitoringPipeline() {
        registerWifiCallback()

        monitorJobs = serviceScope.launch {
            val session = preferences.getSessionSnapshot()
            if (!session.isLoggedIn || session.username.isBlank()) {
                QuotaServiceController.stopAllProtection(applicationContext)
                stopSelf()
                return@launch
            }

            stateMutex.withLock {
                activeUsername = session.username
                quotaLimitMb = session.quotaLimitMb
                firebaseUsedMb = roundMbTo2Decimals(session.usedMb)
                userIsActive = session.isActive
                val now = System.currentTimeMillis()
                sessionStartTimestamp = if (session.sessionStartTimestamp > 0L) {
                    session.sessionStartTimestamp
                } else {
                    now
                }
                lastSyncEpochMs = session.lastSyncEpochMs
                isWifiConnected = checkCurrentWifiConnected()
                syncStatusMessage = "مزامنة Firebase المباشرة نشطة"

                restoreOrInitDeltaPrefsLocked(
                    username = activeUsername,
                    sessionUsedBytes = session.effectiveUsedBytes,
                    nowMs = now
                )
                currentUsedBytes = (baseUsedBytes + pendingDeltaBytes).coerceAtLeast(0L)
            }

            measureWifiUsageAndSync(forceFirebaseSync = false)

            // 1. Real-time Firebase ValueEventListener
            launch {
                firebaseRepo.observeUserQuota(activeUsername).collect { update ->
                    when (update) {
                        is QuotaRemoteUpdate.Updated -> {
                            val remote = update.record
                            val now = System.currentTimeMillis()
                            var needsImmediateEnforce = false

                            stateMutex.withLock {
                                val remoteUsedBytes = (remote.usedMb * BYTES_PER_MB).roundToLong()
                                val wasResetByAdmin =
                                    (remote.usedMb <= 0.0 && firebaseUsedMb > 0.0) ||
                                        (remote.sessionStartTimestamp > 0L &&
                                            remote.sessionStartTimestamp != sessionStartTimestamp)

                                if (wasResetByAdmin) {
                                    sessionStartTimestamp = if (remote.sessionStartTimestamp > 0L) {
                                        remote.sessionStartTimestamp
                                    } else {
                                        now
                                    }
                                    baseUsedBytes = remoteUsedBytes
                                    pendingDeltaBytes = 0L
                                    initialFirebaseBytes = remoteUsedBytes
                                    statsWindowStartMs = now
                                    lastTrafficTotalBytes = safeTotalRxTxBytes()
                                    currentUsedBytes = remoteUsedBytes
                                    saveDeltaPrefsLocked()
                                    needsImmediateEnforce = true
                                } else if (remoteUsedBytes > currentUsedBytes) {
                                    // Sync upward if another device (e.g., PC client) or admin increased usage
                                    baseUsedBytes = remoteUsedBytes
                                    pendingDeltaBytes = 0L
                                    initialFirebaseBytes = remoteUsedBytes
                                    statsWindowStartMs = now
                                    lastTrafficTotalBytes = safeTotalRxTxBytes()
                                    currentUsedBytes = remoteUsedBytes
                                    saveDeltaPrefsLocked()
                                    needsImmediateEnforce = true
                                }

                                quotaLimitMb = remote.quotaLimitMb
                                firebaseUsedMb = roundMbTo2Decimals(remote.usedMb)
                                userIsActive = remote.isActive
                                syncStatusMessage = "تم تحديث البيانات من Firebase"
                            }

                            preferences.updateRemoteQuota(
                                quotaLimitMb = remote.quotaLimitMb,
                                usedMb = remote.usedMb,
                                isActive = remote.isActive,
                                sessionStartTimestamp = sessionStartTimestamp
                            )
                            if (needsImmediateEnforce) {
                                publishAndEnforceState(forceNotify = true)
                            } else {
                                measureWifiUsageAndSync(forceFirebaseSync = false)
                            }
                        }

                        QuotaRemoteUpdate.UserRemoved -> {
                            stateMutex.withLock {
                                userIsActive = false
                                syncStatusMessage = "تم إيقاف الحساب من الإدارة"
                            }
                            preferences.updateRemoteQuota(
                                quotaLimitMb,
                                firebaseUsedMb,
                                false,
                                sessionStartTimestamp
                            )
                            publishAndEnforceState(forceNotify = true)
                        }

                        QuotaRemoteUpdate.NotConfigured -> {
                            stateMutex.withLock {
                                syncStatusMessage = "ملف google-services.json غير مضاف"
                            }
                            publishRuntimeState()
                        }

                        is QuotaRemoteUpdate.Error -> {
                            stateMutex.withLock {
                                syncStatusMessage = "تحذير المزامنة: ${update.message}"
                            }
                            publishRuntimeState()
                        }
                    }
                }
            }

            // 2. Reliable 15-second Wi-Fi measurement & Firebase sync loop
            launch {
                while (isActive) {
                    delay(MONITOR_LOOP_INTERVAL_MS)
                    measureWifiUsageAndSync(forceFirebaseSync = true)
                }
            }
        }
    }

    private fun restoreOrInitDeltaPrefsLocked(
        username: String,
        sessionUsedBytes: Long,
        nowMs: Long
    ) {
        val savedUser = trafficPrefs.getString(KEY_ACTIVE_USER, null)
        val currentTrafficTotal = safeTotalRxTxBytes()

        if (savedUser == username) {
            baseUsedBytes = trafficPrefs.getLong(KEY_BASE_USED_BYTES, sessionUsedBytes).coerceAtLeast(sessionUsedBytes)
            pendingDeltaBytes = trafficPrefs.getLong(KEY_PENDING_DELTA_BYTES, 0L).coerceAtLeast(0L)
            initialFirebaseBytes = trafficPrefs.getLong(KEY_INITIAL_FIREBASE_BYTES, baseUsedBytes).coerceAtLeast(0L)
            statsWindowStartMs = trafficPrefs.getLong(KEY_STATS_WINDOW_START_MS, nowMs)
            val savedTrafficTotal = trafficPrefs.getLong(KEY_LAST_TRAFFIC_TOTAL, -1L)

            // If TrafficStats counter reset (e.g., device reboot), fold previous pending and re-baseline
            if (currentTrafficTotal >= 0L && savedTrafficTotal >= 0L && currentTrafficTotal < savedTrafficTotal) {
                baseUsedBytes += pendingDeltaBytes
                pendingDeltaBytes = 0L
                initialFirebaseBytes = baseUsedBytes
                statsWindowStartMs = nowMs
                lastTrafficTotalBytes = currentTrafficTotal
            } else {
                lastTrafficTotalBytes = if (savedTrafficTotal >= 0L) savedTrafficTotal else currentTrafficTotal
            }
        } else {
            baseUsedBytes = sessionUsedBytes.coerceAtLeast(0L)
            pendingDeltaBytes = 0L
            initialFirebaseBytes = baseUsedBytes
            statsWindowStartMs = nowMs
            lastTrafficTotalBytes = currentTrafficTotal
        }
        saveDeltaPrefsLocked()
    }

    private fun saveDeltaPrefsLocked() {
        trafficPrefs.edit()
            .putString(KEY_ACTIVE_USER, activeUsername)
            .putLong(KEY_BASE_USED_BYTES, baseUsedBytes)
            .putLong(KEY_PENDING_DELTA_BYTES, pendingDeltaBytes)
            .putLong(KEY_LAST_TRAFFIC_TOTAL, lastTrafficTotalBytes)
            .putLong(KEY_INITIAL_FIREBASE_BYTES, initialFirebaseBytes)
            .putLong(KEY_STATS_WINDOW_START_MS, statsWindowStartMs)
            .apply()
    }

    private suspend fun measureWifiUsageAndSync(forceFirebaseSync: Boolean) {
        val hasUsageAccess = QuotaServiceController.hasUsageAccessPermission(applicationContext)
        val now = System.currentTimeMillis()
        var crossedLimitNow = false

        stateMutex.withLock {
            isWifiConnected = checkCurrentWifiConnected()
            val trafficTotal = safeTotalRxTxBytes()
            val isAllowedToCount = isWifiConnected &&
                userIsActive &&
                !QuotaRuntimeState.status.value.isVpnBlocking

            if (isAllowedToCount) {
                // Primary real-time byte delta via TrafficStats (persisted in SharedPreferences)
                if (trafficTotal >= 0L) {
                    if (lastTrafficTotalBytes >= 0L && trafficTotal >= lastTrafficTotalBytes) {
                        val delta = trafficTotal - lastTrafficTotalBytes
                        pendingDeltaBytes += delta
                    }
                    lastTrafficTotalBytes = trafficTotal
                }

                val trafficTotalBytes = (baseUsedBytes + pendingDeltaBytes).coerceAtLeast(0L)

                // Secondary accuracy cross-check via NetworkStatsManager (TYPE_WIFI) when Usage Access is granted
                val statsTotalBytes = if (hasUsageAccess) {
                    val windowBytes = queryWifiBytesViaNetworkStats(statsWindowStartMs, now)
                    (initialFirebaseBytes + windowBytes).coerceAtLeast(0L)
                } else {
                    0L
                }

                val prevBytes = currentUsedBytes
                currentUsedBytes = maxOf(prevBytes, trafficTotalBytes, statsTotalBytes)

                // Keep delta state aligned if NetworkStatsManager captured extra bytes
                if (currentUsedBytes > trafficTotalBytes) {
                    pendingDeltaBytes = (currentUsedBytes - baseUsedBytes).coerceAtLeast(0L)
                }
                saveDeltaPrefsLocked()

                val limitBytes = quotaLimitMb * BYTES_PER_MB
                if (prevBytes < limitBytes && currentUsedBytes >= limitBytes) {
                    crossedLimitNow = true
                }
            } else {
                // Keep baseline aligned while blocked or offline so non-counted traffic is ignored
                if (trafficTotal >= 0L) {
                    lastTrafficTotalBytes = trafficTotal
                    saveDeltaPrefsLocked()
                }
            }
        }

        publishAndEnforceState(forceNotify = false)

        if (crossedLimitNow || forceFirebaseSync) {
            syncUsedMbToFirebase(forceImmediate = crossedLimitNow)
        }
    }

    private fun queryWifiBytesViaNetworkStats(startTimeMs: Long, endTimeMs: Long): Long {
        val manager = networkStatsManager ?: return 0L
        if (endTimeMs <= startTimeMs) return 0L
        return try {
            @Suppress("DEPRECATION")
            val bucket = manager.querySummaryForDevice(
                ConnectivityManager.TYPE_WIFI,
                null,
                startTimeMs,
                endTimeMs
            )
            if (bucket != null) {
                (bucket.rxBytes + bucket.txBytes).coerceAtLeast(0L)
            } else {
                0L
            }
        } catch (_: SecurityException) {
            0L
        } catch (_: RemoteException) {
            0L
        } catch (_: Exception) {
            0L
        }
    }

    private suspend fun syncUsedMbToFirebase(forceImmediate: Boolean) {
        val usernameSnap: String
        val computedUsedMb: Double
        val previousFirebaseMb: Double
        val syncedBytesSnap: Long

        stateMutex.withLock {
            usernameSnap = activeUsername
            syncedBytesSnap = currentUsedBytes
            computedUsedMb = roundMbTo2Decimals(
                syncedBytesSnap.toDouble() / BYTES_PER_MB.toDouble()
            )
            previousFirebaseMb = firebaseUsedMb
        }

        if (usernameSnap.isBlank()) return

        val changed = abs(computedUsedMb - previousFirebaseMb) >= 0.01
        if (changed || forceImmediate) {
            val result = firebaseRepo.updateUsedMb(usernameSnap, computedUsedMb)
            val now = System.currentTimeMillis()
            if (result.isSuccess) {
                val formattedMb = String.format(Locale.US, "%.2f", computedUsedMb)
                stateMutex.withLock {
                    firebaseUsedMb = computedUsedMb
                    baseUsedBytes = syncedBytesSnap
                    pendingDeltaBytes = (currentUsedBytes - syncedBytesSnap).coerceAtLeast(0L)
                    lastSyncEpochMs = now
                    syncStatusMessage = "تمت المزامنة مع Firebase ($formattedMb ميجابايت)"
                    saveDeltaPrefsLocked()
                }
                preferences.updateLocalUsage(computedUsedMb, now)
            } else {
                stateMutex.withLock {
                    syncStatusMessage = "غير متصل بـ Firebase • حفظ محلي"
                }
            }
            publishAndEnforceState(forceNotify = false)
        }
    }

    private suspend fun publishAndEnforceState(forceNotify: Boolean) {
        val snapshot = publishRuntimeState()

        if (snapshot.shouldBlockTraffic) {
            QuotaServiceController.activateSinkholeVpn(applicationContext)
        } else {
            QuotaServiceController.activateAllowVpn(applicationContext)
        }

        val remainingMb = snapshot.remainingMb
        val isBlocked = snapshot.shouldBlockTraffic
        if (forceNotify || remainingMb != lastNotifiedRemainingMb || isBlocked != lastNotifiedBlockedState) {
            lastNotifiedRemainingMb = remainingMb
            lastNotifiedBlockedState = isBlocked
            QuotaNotificationHelper.notifyMonitorUpdate(applicationContext, snapshot)
        }
    }

    private suspend fun publishRuntimeState() = stateMutex.withLock {
        val current = QuotaRuntimeState.status.value
        val updated = current.copy(
            isLoggedIn = activeUsername.isNotBlank(),
            username = activeUsername,
            quotaLimitMb = quotaLimitMb,
            usedBytes = currentUsedBytes,
            isActive = userIsActive,
            sessionStartTimestamp = sessionStartTimestamp,
            isWifiConnected = isWifiConnected,
            isMonitoring = true,
            lastSyncEpochMs = lastSyncEpochMs,
            syncStatusMessage = syncStatusMessage,
            vpnPermissionGranted = QuotaServiceController.isVpnPermissionGranted(applicationContext),
            hasUsageAccessPermission = QuotaServiceController.hasUsageAccessPermission(applicationContext),
            isBatteryOptimizationIgnored = QuotaServiceController.isBatteryOptimizationIgnored(applicationContext),
            isFirebaseConfigured = firebaseRepo.isFirebaseConfigured()
        )
        QuotaRuntimeState.update { updated }
        updated
    }

    private fun registerWifiCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                serviceScope.launch {
                    stateMutex.withLock {
                        isWifiConnected = true
                        val total = safeTotalRxTxBytes()
                        if (total >= 0L) {
                            lastTrafficTotalBytes = total
                            saveDeltaPrefsLocked()
                        }
                    }
                    measureWifiUsageAndSync(forceFirebaseSync = false)
                }
            }

            override fun onLost(network: Network) {
                serviceScope.launch {
                    val stillOnWifi = checkCurrentWifiConnected()
                    stateMutex.withLock {
                        isWifiConnected = stillOnWifi
                    }
                    publishAndEnforceState(forceNotify = true)
                }
            }
        }

        networkCallback = callback
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (_: Exception) {
            // Safe fallback
        }
    }

    @Suppress("DEPRECATION")
    private fun checkCurrentWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return true
        }
        return try {
            connectivityManager.allNetworks.any { net ->
                val caps = connectivityManager.getNetworkCapabilities(net)
                caps != null &&
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun safeTotalRxTxBytes(): Long {
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) {
            return -1L
        }
        return (rx + tx).coerceAtLeast(0L)
    }

    override fun onDestroy() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
            } catch (_: Exception) {
                // Ignore
            }
        }
        monitorJobs?.cancel()
        QuotaRuntimeState.update { it.copy(isMonitoring = false) }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

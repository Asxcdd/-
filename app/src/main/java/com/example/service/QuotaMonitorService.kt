package com.example.service

import android.app.Service
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.content.Intent
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

class QuotaMonitorService : Service() {

    companion object {
        const val ACTION_START = "com.example.service.ACTION_START_MONITOR"
        const val ACTION_FORCE_SYNC = "com.example.service.ACTION_FORCE_SYNC"
        const val ACTION_SET_DEMO_SIMULATION = "com.example.service.ACTION_SET_DEMO_SIMULATION"
        const val EXTRA_SIMULATE_EXHAUSTED = "extra_simulate_exhausted"
        private const val POLL_INTERVAL_MS = 2_000L
        private const val SYNC_INTERVAL_MS = 60_000L

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

        fun createDemoSimulationIntent(context: Context, exhausted: Boolean): Intent {
            return Intent(context, QuotaMonitorService::class.java).apply {
                action = ACTION_SET_DEMO_SIMULATION
                putExtra(EXTRA_SIMULATE_EXHAUSTED, exhausted)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private lateinit var preferences: QuotaPreferences
    private lateinit var firebaseRepo: FirebaseQuotaRepository
    private lateinit var connectivityManager: ConnectivityManager
    private var networkStatsManager: NetworkStatsManager? = null

    private var isMonitoringStarted = false
    private var monitorJobs: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var activeUsername: String = ""
    private var isDemoAccount: Boolean = false
    private var quotaLimitMb: Long = 0L
    private var firebaseUsedMb: Long = 0L
    private var currentUsedBytes: Long = 0L
    private var simulatedExtraBytes: Long = 0L
    private var userIsActive: Boolean = false
    private var sessionStartTimestamp: Long = 0L
    private var isWifiConnected: Boolean = false
    private var lastSyncEpochMs: Long = 0L
    private var syncStatusMessage: String = "جاري المراقبة المباشرة"
    private var lastNotifiedRemainingMb: Long = -1L
    private var lastNotifiedBlockedState: Boolean? = null

    // Fallback delta tracking if Usage Access permission is not yet granted
    private var fallbackPendingBytes: Long = 0L
    private var lastRxBytes: Long = -1L
    private var lastTxBytes: Long = -1L

    override fun onCreate() {
        super.onCreate()
        preferences = QuotaPreferences(applicationContext)
        firebaseRepo = FirebaseQuotaRepository(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        networkStatsManager = getSystemService(NetworkStatsManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        if (intent?.action == ACTION_FORCE_SYNC && isMonitoringStarted) {
            serviceScope.launch {
                measureWifiUsageAndEvaluate(forceFirebaseSync = true)
            }
            return START_STICKY
        }

        if (intent?.action == ACTION_SET_DEMO_SIMULATION && isMonitoringStarted) {
            val exhausted = intent.getBooleanExtra(EXTRA_SIMULATE_EXHAUSTED, false)
            serviceScope.launch {
                applyDemoSimulation(exhausted)
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
                isDemoAccount = session.isDemo
                quotaLimitMb = session.quotaLimitMb
                firebaseUsedMb = session.usedMb
                userIsActive = session.isActive
                sessionStartTimestamp = if (session.sessionStartTimestamp > 0L) {
                    session.sessionStartTimestamp
                } else {
                    System.currentTimeMillis()
                }
                simulatedExtraBytes = session.simulatedExtraBytes
                currentUsedBytes = session.effectiveUsedBytes
                lastSyncEpochMs = session.lastSyncEpochMs
                isWifiConnected = checkCurrentWifiConnected()
                syncStatusMessage = if (isDemoAccount) {
                    "وضع الحساب التجريبي (محلي)"
                } else {
                    "مزامنة Firebase المباشرة نشطة"
                }
                if (isWifiConnected) {
                    lastRxBytes = safeTotalRxBytes()
                    lastTxBytes = safeTotalTxBytes()
                }
            }

            measureWifiUsageAndEvaluate(forceFirebaseSync = false)

            // 1. Real-time Firebase ValueEventListener (skipped for demo user)
            if (!isDemoAccount) {
                launch {
                    firebaseRepo.observeUserQuota(activeUsername).collect { update ->
                        when (update) {
                            is QuotaRemoteUpdate.Updated -> {
                                val remote = update.record
                                var resetBaseline = false
                                stateMutex.withLock {
                                    if (remote.sessionStartTimestamp > 0L &&
                                        remote.sessionStartTimestamp != sessionStartTimestamp
                                    ) {
                                        sessionStartTimestamp = remote.sessionStartTimestamp
                                        fallbackPendingBytes = 0L
                                        resetBaseline = true
                                    } else if (remote.usedMb == 0L && firebaseUsedMb > 0L) {
                                        // Admin cleared used_mb directly
                                        sessionStartTimestamp = System.currentTimeMillis()
                                        fallbackPendingBytes = 0L
                                        resetBaseline = true
                                    }
                                    quotaLimitMb = remote.quotaLimitMb
                                    firebaseUsedMb = remote.usedMb
                                    userIsActive = remote.isActive
                                    syncStatusMessage = "تم تحديث البيانات من Firebase"
                                }
                                if (resetBaseline) {
                                    lastRxBytes = safeTotalRxBytes()
                                    lastTxBytes = safeTotalTxBytes()
                                }
                                preferences.updateRemoteQuota(
                                    quotaLimitMb = remote.quotaLimitMb,
                                    usedMb = remote.usedMb,
                                    isActive = remote.isActive,
                                    sessionStartTimestamp = sessionStartTimestamp
                                )
                                measureWifiUsageAndEvaluate(forceFirebaseSync = false)
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
            }

            // 2. High-frequency Wi-Fi NetworkStatsManager loop (2s) for instant cutoff
            launch {
                while (isActive) {
                    delay(POLL_INTERVAL_MS)
                    measureWifiUsageAndEvaluate(forceFirebaseSync = false)
                }
            }

            // 3. Periodic 60-second Firebase sync loop
            launch {
                while (isActive) {
                    delay(SYNC_INTERVAL_MS)
                    syncUsedMbToFirebase(forceImmediate = true)
                }
            }
        }
    }

    private suspend fun applyDemoSimulation(exhausted: Boolean) {
        val newStartTs = System.currentTimeMillis()
        stateMutex.withLock {
            if (exhausted) {
                simulatedExtraBytes = quotaLimitMb * BYTES_PER_MB
                currentUsedBytes = simulatedExtraBytes
                syncStatusMessage = "محاكاة: انتهت الباقة (تم الحظر)"
            } else {
                simulatedExtraBytes = 0L
                fallbackPendingBytes = 0L
                firebaseUsedMb = 0L
                sessionStartTimestamp = newStartTs
                currentUsedBytes = 0L
                lastRxBytes = safeTotalRxBytes()
                lastTxBytes = safeTotalTxBytes()
                syncStatusMessage = "تمت إعادة تعيين الباقة التجريبية (50 ميجابايت)"
            }
        }
        preferences.setDemoSimulation(
            simulatedExtraBytes = if (exhausted) quotaLimitMb * BYTES_PER_MB else 0L,
            usedMb = if (exhausted) quotaLimitMb else 0L,
            sessionStartTimestamp = sessionStartTimestamp
        )
        publishAndEnforceState(forceNotify = true)
    }

    private suspend fun measureWifiUsageAndEvaluate(forceFirebaseSync: Boolean) {
        val hasUsageAccess = QuotaServiceController.hasUsageAccessPermission(applicationContext)
        var crossedLimitNow = false

        stateMutex.withLock {
            val baseStart = if (sessionStartTimestamp > 0L) {
                sessionStartTimestamp
            } else {
                System.currentTimeMillis().also { sessionStartTimestamp = it }
            }

            val measuredWifiBytes: Long = if (hasUsageAccess) {
                queryWifiBytesViaNetworkStats(baseStart)
            } else {
                // Fallback TrafficStats delta while user hasn't granted Usage Access yet
                if (isWifiConnected && userIsActive && !QuotaRuntimeState.status.value.isVpnBlocking) {
                    val curRx = safeTotalRxBytes()
                    val curTx = safeTotalTxBytes()
                    if (curRx >= 0L && curTx >= 0L) {
                        if (lastRxBytes >= 0L && lastTxBytes >= 0L) {
                            val dRx = if (curRx >= lastRxBytes) curRx - lastRxBytes else 0L
                            val dTx = if (curTx >= lastTxBytes) curTx - lastTxBytes else 0L
                            fallbackPendingBytes += (dRx + dTx)
                        }
                        lastRxBytes = curRx
                        lastTxBytes = curTx
                    }
                }
                (firebaseUsedMb * BYTES_PER_MB) + fallbackPendingBytes
            }

            // Never drop below Firebase-stored MB unless admin reset session_start_timestamp
            val effectiveBytes = maxOf(
                measuredWifiBytes + simulatedExtraBytes,
                if (hasUsageAccess) measuredWifiBytes + simulatedExtraBytes else (firebaseUsedMb * BYTES_PER_MB) + simulatedExtraBytes
            )
            val prevBytes = currentUsedBytes
            currentUsedBytes = effectiveBytes

            val limitBytes = quotaLimitMb * BYTES_PER_MB
            if (prevBytes < limitBytes && effectiveBytes >= limitBytes) {
                crossedLimitNow = true
            }
        }

        if (crossedLimitNow || forceFirebaseSync) {
            syncUsedMbToFirebase(forceImmediate = true)
        }
        publishAndEnforceState(forceNotify = false)
    }

    private fun queryWifiBytesViaNetworkStats(startTimeMs: Long): Long {
        val manager = networkStatsManager ?: return (firebaseUsedMb * BYTES_PER_MB)
        val endTimeMs = System.currentTimeMillis()
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
            (firebaseUsedMb * BYTES_PER_MB) + fallbackPendingBytes
        } catch (_: RemoteException) {
            (firebaseUsedMb * BYTES_PER_MB) + fallbackPendingBytes
        } catch (_: Exception) {
            (firebaseUsedMb * BYTES_PER_MB) + fallbackPendingBytes
        }
    }

    private suspend fun syncUsedMbToFirebase(forceImmediate: Boolean) {
        val usernameSnap: String
        val demoSnap: Boolean
        val computedUsedMb: Long
        val previousFirebaseMb: Long

        stateMutex.withLock {
            usernameSnap = activeUsername
            demoSnap = isDemoAccount
            computedUsedMb = currentUsedBytes / BYTES_PER_MB
            previousFirebaseMb = firebaseUsedMb
        }

        if (usernameSnap.isBlank()) return

        if (demoSnap) {
            val now = System.currentTimeMillis()
            stateMutex.withLock {
                firebaseUsedMb = computedUsedMb
                lastSyncEpochMs = now
                if (forceImmediate) {
                    syncStatusMessage = "وضع الحساب التجريبي (محلي)"
                }
            }
            preferences.updateLocalUsage(computedUsedMb, now)
            publishAndEnforceState(forceNotify = false)
            return
        }

        if (computedUsedMb != previousFirebaseMb || forceImmediate) {
            val result = firebaseRepo.updateUsedMb(usernameSnap, computedUsedMb)
            val now = System.currentTimeMillis()
            if (result.isSuccess) {
                stateMutex.withLock {
                    firebaseUsedMb = computedUsedMb
                    lastSyncEpochMs = now
                    syncStatusMessage = "تمت المزامنة مع Firebase ($computedUsedMb ميجابايت)"
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
            isDemo = isDemoAccount,
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
                        lastRxBytes = safeTotalRxBytes()
                        lastTxBytes = safeTotalTxBytes()
                    }
                    measureWifiUsageAndEvaluate(forceFirebaseSync = false)
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

    private fun checkCurrentWifiConnected(): Boolean {
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun safeTotalRxBytes(): Long {
        val value = TrafficStats.getTotalRxBytes()
        return if (value == TrafficStats.UNSUPPORTED.toLong()) -1L else value
    }

    private fun safeTotalTxBytes(): Long {
        val value = TrafficStats.getTotalTxBytes()
        return if (value == TrafficStats.UNSUPPORTED.toLong()) -1L else value
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

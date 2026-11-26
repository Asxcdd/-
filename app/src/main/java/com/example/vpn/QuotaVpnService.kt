package com.example.vpn

import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.ServiceCompat
import com.example.R
import com.example.data.QuotaPreferences
import com.example.service.QuotaNotificationHelper
import com.example.service.QuotaRuntimeState
import com.example.service.QuotaServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.IOException

/**
 * Local Sinkhole VPN Service running continuously as a Foreground Service.
 *
 * Dynamically switches between two TUN states by closing the old ParcelFileDescriptor
 * and calling Builder.establish() again:
 *
 * 1. ALLOW INTERNET STATE (Logged in && is_active && used_mb < quota_limit_mb):
 *    Calls addDisallowedApplication() for every installed package via
 *    packageManager.getInstalledApplications(0) so 100% of traffic flows at native
 *    Wi-Fi speed while the VPN stays active for Android's "Block connections without VPN".
 *
 * 2. BLOCK INTERNET STATE (Logged out || !is_active || used_mb >= quota_limit_mb):
 *    Does NOT exclude user apps — only excludes this app (packageName) so Firebase
 *    Realtime Database stays reachable, and drops all other device traffic locally.
 */
class QuotaVpnService : VpnService() {

    companion object {
        const val ACTION_ALLOW_TRAFFIC = "com.example.vpn.ACTION_ALLOW_TRAFFIC"
        const val ACTION_BLOCK_TRAFFIC = "com.example.vpn.ACTION_BLOCK_TRAFFIC"
        const val ACTION_STOP_OR_LOCK_ON_LOGOUT = "com.example.vpn.ACTION_STOP_OR_LOCK_ON_LOGOUT"
        const val EXTRA_APP_STARTED = "extra_app_started"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var drainJob: Job? = null
    private var currentBlockingState: Boolean? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isAppStarted = intent?.getBooleanExtra(EXTRA_APP_STARTED, false) == true
        val alwaysOnDetected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isAlwaysOn || !isAppStarted
        } else {
            !isAppStarted
        }
        val lockdownDetected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isLockdownEnabled
        } else {
            false
        }

        QuotaRuntimeState.update {
            it.copy(
                isAlwaysOnActive = alwaysOnDetected,
                isLockdownEnabled = lockdownDetected,
                vpnPermissionGranted = true
            )
        }

        when (intent?.action) {
            ACTION_ALLOW_TRAFFIC -> {
                promoteToForeground(isBlocking = false)
                switchVpnState(blockInternet = false)
                return START_STICKY
            }

            ACTION_BLOCK_TRAFFIC -> {
                promoteToForeground(isBlocking = true)
                switchVpnState(blockInternet = true)
                return START_STICKY
            }

            ACTION_STOP_OR_LOCK_ON_LOGOUT -> {
                val keepAlwaysOnLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    isAlwaysOn
                } else {
                    false
                }
                if (keepAlwaysOnLock) {
                    promoteToForeground(isBlocking = true)
                    switchVpnState(blockInternet = true)
                    return START_STICKY
                } else {
                    closeTunnel()
                    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
            }

            else -> {
                // Started by Android system (Always-on VPN on boot or Settings toggle)
                val initialBlock = QuotaRuntimeState.status.value.shouldBlockTraffic
                promoteToForeground(isBlocking = initialBlock)
                serviceScope.launch {
                    val session = QuotaPreferences(applicationContext).getSessionSnapshot()
                    if (session.isLoggedIn) {
                        QuotaServiceController.startProtection(applicationContext)
                    }
                    val shouldBlock = session.shouldBlockTraffic
                    promoteToForeground(isBlocking = shouldBlock)
                    switchVpnState(blockInternet = shouldBlock)
                }
                return START_STICKY
            }
        }
    }

    private fun promoteToForeground(isBlocking: Boolean) {
        val notification = QuotaNotificationHelper.buildVpnNotification(
            context = this,
            snapshot = QuotaRuntimeState.status.value,
            isBlocking = isBlocking
        )
        val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            QuotaNotificationHelper.VPN_NOTIFICATION_ID,
            notification,
            fgType
        )
    }

    @Synchronized
    private fun switchVpnState(blockInternet: Boolean) {
        if (vpnInterface != null && currentBlockingState == blockInternet) {
            QuotaRuntimeState.update { it.copy(isVpnBlocking = blockInternet) }
            QuotaNotificationHelper.notifyVpnUpdate(this, QuotaRuntimeState.status.value, blockInternet)
            return
        }

        // Close old ParcelFileDescriptor before establishing the new TUN state
        closeTunnel()

        val builder = Builder()
            .setSession(getString(R.string.vpn_service_name))
            .setMtu(1500)
            .addAddress("10.0.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .setBlocking(true)

        if (!blockInternet) {
            // ALLOW INTERNET STATE: exclude all installed apps for 100% native Wi-Fi speed
            val excludedPackages = HashSet<String>()
            val installedApps = try {
                packageManager.getInstalledApplications(0)
            } catch (_: Exception) {
                emptyList()
            }
            for (appInfo in installedApps) {
                val pkg = appInfo.packageName
                if (excludedPackages.add(pkg)) {
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // Ignore uninstalled or unreachable packages
                    }
                }
            }
            if (excludedPackages.add(packageName)) {
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    // Ignore
                }
            }
        } else {
            // BLOCK INTERNET STATE: also trap IPv6 and only exclude this app so Firebase stays reachable
            builder.addAddress("fd00::2", 128)
            builder.addRoute("::", 0)
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                // Ignore
            }
        }

        val tun = try {
            builder.establish()
        } catch (_: Exception) {
            null
        }

        if (tun == null) {
            currentBlockingState = null
            QuotaRuntimeState.update {
                it.copy(isVpnBlocking = false, vpnPermissionGranted = false)
            }
            return
        }

        vpnInterface = tun
        currentBlockingState = blockInternet
        QuotaRuntimeState.update {
            it.copy(isVpnBlocking = blockInternet, vpnPermissionGranted = true)
        }
        QuotaNotificationHelper.notifyVpnUpdate(this, QuotaRuntimeState.status.value, blockInternet)

        drainJob = serviceScope.launch {
            val buffer = ByteArray(32767)
            try {
                FileInputStream(tun.fileDescriptor).use { input ->
                    while (isActive) {
                        val length = input.read(buffer)
                        if (length < 0) break
                        // Sinkhole: drop incoming packets cleanly without forwarding
                    }
                }
            } catch (_: IOException) {
                // Expected when ParcelFileDescriptor is closed during state switch
            } catch (_: InterruptedException) {
                // Expected on shutdown
            }
        }
    }

    @Synchronized
    private fun closeTunnel() {
        drainJob?.cancel()
        drainJob = null
        try {
            vpnInterface?.close()
        } catch (_: IOException) {
            // Ignore close errors
        }
        vpnInterface = null
        currentBlockingState = null
    }

    override fun onRevoke() {
        closeTunnel()
        QuotaRuntimeState.update {
            it.copy(isVpnBlocking = false, vpnPermissionGranted = false)
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        closeTunnel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

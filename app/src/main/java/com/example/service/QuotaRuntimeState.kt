package com.example.service

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.os.Process
import androidx.core.content.ContextCompat
import com.example.data.FirebaseQuotaRepository
import com.example.data.QuotaStatusSnapshot
import com.example.vpn.QuotaVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object QuotaRuntimeState {
    private val _status = MutableStateFlow(QuotaStatusSnapshot())
    val status: StateFlow<QuotaStatusSnapshot> = _status.asStateFlow()

    fun update(transform: (QuotaStatusSnapshot) -> QuotaStatusSnapshot) {
        _status.update(transform)
    }

    fun refreshCapabilities(context: Context) {
        val vpnPrepared = VpnService.prepare(context) == null
        val firebaseReady = FirebaseQuotaRepository(context).isFirebaseConfigured()
        val usageAccess = QuotaServiceController.hasUsageAccessPermission(context)
        val batteryIgnored = QuotaServiceController.isBatteryOptimizationIgnored(context)
        _status.update {
            it.copy(
                vpnPermissionGranted = vpnPrepared,
                isFirebaseConfigured = firebaseReady,
                hasUsageAccessPermission = usageAccess,
                isBatteryOptimizationIgnored = batteryIgnored
            )
        }
    }
}

object QuotaServiceController {

    fun isVpnPermissionGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun hasUsageAccessPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun startProtection(context: Context) {
        QuotaRuntimeState.refreshCapabilities(context)
        val intent = QuotaMonitorService.createStartIntent(context)
        ContextCompat.startForegroundService(context, intent)
    }

    fun requestImmediateSync(context: Context) {
        val intent = QuotaMonitorService.createForceSyncIntent(context)
        ContextCompat.startForegroundService(context, intent)
    }

    fun activateAllowVpn(context: Context) {
        if (!isVpnPermissionGranted(context)) {
            QuotaRuntimeState.update { it.copy(vpnPermissionGranted = false) }
            return
        }
        val intent = Intent(context, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_ALLOW_TRAFFIC
            putExtra(QuotaVpnService.EXTRA_APP_STARTED, true)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun activateSinkholeVpn(context: Context) {
        if (!isVpnPermissionGranted(context)) {
            QuotaRuntimeState.update { it.copy(vpnPermissionGranted = false) }
            return
        }
        val intent = Intent(context, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_BLOCK_TRAFFIC
            putExtra(QuotaVpnService.EXTRA_APP_STARTED, true)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    fun stopAllProtection(context: Context) {
        context.stopService(Intent(context, QuotaMonitorService::class.java))
        QuotaMonitorService.clearTrafficDeltaPrefs(context)
        val stopVpnIntent = Intent(context, QuotaVpnService::class.java).apply {
            action = QuotaVpnService.ACTION_STOP_OR_LOCK_ON_LOGOUT
            putExtra(QuotaVpnService.EXTRA_APP_STARTED, true)
        }
        try {
            context.startService(stopVpnIntent)
        } catch (_: Exception) {
            context.stopService(Intent(context, QuotaVpnService::class.java))
        }
        QuotaRuntimeState.update {
            QuotaStatusSnapshot(
                vpnPermissionGranted = isVpnPermissionGranted(context),
                hasUsageAccessPermission = hasUsageAccessPermission(context),
                isBatteryOptimizationIgnored = isBatteryOptimizationIgnored(context),
                isFirebaseConfigured = FirebaseQuotaRepository(context).isFirebaseConfigured()
            )
        }
    }
}

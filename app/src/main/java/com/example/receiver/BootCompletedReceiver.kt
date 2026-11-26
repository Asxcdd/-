package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.QuotaPreferences
import com.example.service.QuotaServiceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = QuotaPreferences(appContext)
                val session = prefs.getSessionSnapshot()
                if (session.isLoggedIn && session.username.isNotBlank()) {
                    QuotaServiceController.startProtection(appContext)
                    if (QuotaServiceController.isVpnPermissionGranted(appContext)) {
                        if (session.shouldBlockTraffic) {
                            QuotaServiceController.activateSinkholeVpn(appContext)
                        } else {
                            QuotaServiceController.activateAllowVpn(appContext)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

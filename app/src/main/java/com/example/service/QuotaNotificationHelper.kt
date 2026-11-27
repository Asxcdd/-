package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BlockReason
import com.example.data.DataFormatter
import com.example.data.QuotaStatusSnapshot

object QuotaNotificationHelper {
    const val CHANNEL_ID = "family_quota_status_channel"
    const val MONITOR_NOTIFICATION_ID = 1001
    const val VPN_NOTIFICATION_ID = 1002

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = context.getString(R.string.notification_channel_description)
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun buildMonitorNotification(context: Context, snapshot: QuotaStatusSnapshot): Notification {
        ensureChannel(context)
        val remainingText = DataFormatter.formatBytes(snapshot.remainingBytes)
        val usedText = DataFormatter.formatBytes(snapshot.usedBytes)
        val limitText = DataFormatter.formatMb(snapshot.quotaLimitMb)

        val title = when {
            !snapshot.isLoggedIn -> "جيجاتك • غير مسجل الدخول"
            snapshot.blockReason == BlockReason.ACCOUNT_DISABLED -> "تم إيقاف الإنترنت - الحساب موقوف"
            snapshot.shouldBlockTraffic -> "تم إيقاف الإنترنت - انتهت الباقة"
            else -> "الباقة المتبقية: $remainingText"
        }

        val detail = when (snapshot.blockReason) {
            BlockReason.ACCOUNT_DISABLED -> "تم إيقاف الحساب مؤقتاً من لوحة الإدارة"
            BlockReason.QUOTA_EXCEEDED -> "تم استهلاك $usedText من أصل $limitText • تم حظر الاتصال"
            null -> if (snapshot.isLoggedIn) {
                val wifiLabel = if (snapshot.isWifiConnected) "متصل بالواي فاي" else "في انتظار الواي فاي"
                "المستخدم: $usedText من $limitText • $wifiLabel"
            } else {
                "سجّل الدخول لتفعيل حماية باقة الواي فاي"
            }
        }

        val progressMax = 1000
        val progressVal = (snapshot.usageFraction * progressMax).toInt().coerceIn(0, progressMax)

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (snapshot.shouldBlockTraffic) android.R.drawable.stat_sys_warning
                else android.R.drawable.ic_lock_idle_lock
            )
            .setContentTitle(title)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createMainPendingIntent(context))
            .apply {
                if (snapshot.isLoggedIn && snapshot.quotaLimitMb > 0L) {
                    setProgress(progressMax, progressVal, false)
                }
            }
            .build()
    }

    fun buildVpnNotification(context: Context, snapshot: QuotaStatusSnapshot, isBlocking: Boolean): Notification {
        ensureChannel(context)
        val remainingText = DataFormatter.formatBytes(snapshot.remainingBytes)

        val title = if (isBlocking) {
            when (snapshot.blockReason) {
                BlockReason.ACCOUNT_DISABLED -> "تم إيقاف الإنترنت - الحساب موقوف"
                BlockReason.QUOTA_EXCEEDED -> "تم إيقاف الإنترنت - انتهت الباقة"
                null -> "تم إيقاف الإنترنت - يرجى تسجيل الدخول"
            }
        } else {
            "الباقة المتبقية: $remainingText"
        }

        val text = if (isBlocking) {
            "جدار الحماية المحلي (10.0.0.2) نشط ويمنع اتصال جميع التطبيقات."
        } else {
            "جيجاتك VPN نشط • الإنترنت يعمل بالسرعة الكاملة."
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (isBlocking) android.R.drawable.ic_lock_lock
                else android.R.drawable.ic_lock_idle_lock
            )
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(createMainPendingIntent(context))
            .build()
    }

    fun notifyMonitorUpdate(context: Context, snapshot: QuotaStatusSnapshot) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(MONITOR_NOTIFICATION_ID, buildMonitorNotification(context, snapshot))
    }

    fun notifyVpnUpdate(context: Context, snapshot: QuotaStatusSnapshot, isBlocking: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(VPN_NOTIFICATION_ID, buildVpnNotification(context, snapshot, isBlocking))
    }

    private fun createMainPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package com.example.data

import java.util.Locale
import kotlin.math.roundToLong

const val BYTES_PER_MB: Long = 1024L * 1024L
const val BYTES_PER_GB: Long = 1024L * 1024L * 1024L

fun roundMbTo2Decimals(mb: Double): Double {
    val safe = mb.coerceAtLeast(0.0)
    return (safe * 100.0).roundToLong() / 100.0
}

data class UserQuotaRecord(
    val username: String,
    val quotaLimitMb: Long,
    val usedMb: Double,
    val isActive: Boolean,
    val sessionStartTimestamp: Long = System.currentTimeMillis()
)

data class PersistedSession(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val quotaLimitMb: Long = 0L,
    val usedMb: Double = 0.0,
    val isActive: Boolean = false,
    val sessionStartTimestamp: Long = 0L,
    val lastSyncEpochMs: Long = 0L
) {
    val effectiveUsedBytes: Long
        get() = (usedMb.coerceAtLeast(0.0) * BYTES_PER_MB).roundToLong()

    val shouldBlockTraffic: Boolean
        get() = !isLoggedIn || !isActive || effectiveUsedBytes >= (quotaLimitMb.coerceAtLeast(0L) * BYTES_PER_MB)
}

data class QuotaStatusSnapshot(
    val isLoggedIn: Boolean = false,
    val username: String = "",
    val quotaLimitMb: Long = 0L,
    val usedBytes: Long = 0L,
    val isActive: Boolean = false,
    val sessionStartTimestamp: Long = 0L,
    val isWifiConnected: Boolean = false,
    val isMonitoring: Boolean = false,
    val isVpnBlocking: Boolean = false,
    val vpnPermissionGranted: Boolean = false,
    val hasUsageAccessPermission: Boolean = false,
    val isBatteryOptimizationIgnored: Boolean = false,
    val isAlwaysOnActive: Boolean = false,
    val isLockdownEnabled: Boolean = false,
    val lastSyncEpochMs: Long = 0L,
    val syncStatusMessage: String = "",
    val isFirebaseConfigured: Boolean = true
) {
    val effectiveUsedMb: Double
        get() = roundMbTo2Decimals(usedBytes.coerceAtLeast(0L).toDouble() / BYTES_PER_MB.toDouble())

    val quotaLimitBytes: Long
        get() = quotaLimitMb.coerceAtLeast(0L) * BYTES_PER_MB

    val remainingBytes: Long
        get() = (quotaLimitBytes - usedBytes.coerceAtLeast(0L)).coerceAtLeast(0L)

    val remainingMb: Long
        get() = remainingBytes / BYTES_PER_MB

    val shouldBlockTraffic: Boolean
        get() = !isLoggedIn || !isActive || usedBytes.coerceAtLeast(0L) >= quotaLimitBytes

    val usageFraction: Float
        get() {
            val limit = quotaLimitBytes
            return if (limit <= 0L) 1f else (usedBytes.coerceAtLeast(0L).toFloat() / limit.toFloat()).coerceIn(0f, 1f)
        }

    val blockReason: BlockReason?
        get() = when {
            !isLoggedIn -> null
            !isActive -> BlockReason.ACCOUNT_DISABLED
            usedBytes.coerceAtLeast(0L) >= quotaLimitBytes -> BlockReason.QUOTA_EXCEEDED
            else -> null
        }
}

enum class BlockReason {
    ACCOUNT_DISABLED,
    QUOTA_EXCEEDED
}

object DataFormatter {
    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        return when {
            safeBytes >= BYTES_PER_GB -> {
                String.format(Locale.US, "%.2f جيجابايت", safeBytes.toDouble() / BYTES_PER_GB.toDouble())
            }
            safeBytes >= BYTES_PER_MB -> {
                String.format(Locale.US, "%.2f ميجابايت", safeBytes.toDouble() / BYTES_PER_MB.toDouble())
            }
            safeBytes >= 1024L -> {
                String.format(Locale.US, "%.0f كيلوبايت", safeBytes.toDouble() / 1024.0)
            }
            else -> "$safeBytes بايت"
        }
    }

    fun formatMb(mb: Long): String {
        val safeMb = mb.coerceAtLeast(0L)
        return if (safeMb >= 1024L) {
            String.format(Locale.US, "%.2f جيجابايت", safeMb.toDouble() / 1024.0)
        } else {
            "$safeMb ميجابايت"
        }
    }
}

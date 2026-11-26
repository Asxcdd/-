package com.example.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.DataFormatter
import com.example.data.QuotaStatusSnapshot
import com.example.ui.theme.Amber400
import com.example.ui.theme.Coral400
import com.example.ui.theme.Cyan400
import com.example.ui.theme.Emerald400
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    snapshot: QuotaStatusSnapshot,
    notificationsGranted: Boolean,
    contentPadding: PaddingValues,
    onRequestVpnPermission: () -> Unit,
    onRequestUsageAccess: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenVpnSettings: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onSyncNow: () -> Unit,
    onToggleDemoSimulation: (exhausted: Boolean) -> Unit,
    onLogout: () -> Unit
) {
    val animatedProgress by animateFloatAsState(
        targetValue = snapshot.usageFraction,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "quota_gauge_progress"
    )

    val statusColor = when {
        snapshot.shouldBlockTraffic -> Coral400
        snapshot.usageFraction >= 0.8f -> Amber400
        snapshot.isWifiConnected -> Emerald400
        else -> Cyan400
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(contentPadding),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Top Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = statusColor.copy(alpha = 0.16f),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (snapshot.shouldBlockTraffic) {
                                    Icons.Rounded.Block
                                } else {
                                    Icons.Rounded.Security
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Column {
                        Text(
                            text = if (snapshot.isDemo) {
                                "${snapshot.username} (حساب تجريبي)"
                            } else {
                                snapshot.username
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Text(
                            text = if (snapshot.shouldBlockTraffic) {
                                "حالة الاتصال: متوقف (انتهت الباقة أو الحساب موقوف)"
                            } else {
                                "حالة الاتصال: متصل (الإنترنت يعمل)"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = statusColor
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onOpenSetupGuide,
                        modifier = Modifier.testTag("dashboard_setup_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.HelpOutline,
                            contentDescription = "دليل إعداد Firebase"
                        )
                    }
                    OutlinedButton(
                        onClick = onLogout,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("logout_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.Logout,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("تسجيل الخروج")
                    }
                }
            }

            // Demo User Simulation Controls (ONLY visible for test user)
            if (snapshot.isDemo) {
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Science,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "أدوات اختبار الحساب التجريبي",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Text(
                            text = "يعمل عدّاد الشبكة وخدمة حظر الـ VPN بشكل حقيقي، ويمكنك اختبار الحظر وإعادة التفعيل الفوري بضغطة واحدة.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                        )
                        if (!snapshot.shouldBlockTraffic) {
                            Button(
                                onClick = { onToggleDemoSimulation(true) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("simulate_quota_exhausted_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Block,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("محاكاة انتهاء الباقة (تجربة الحظر)")
                            }
                        } else {
                            Button(
                                onClick = { onToggleDemoSimulation(false) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                    contentColor = MaterialTheme.colorScheme.onSecondary
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("reset_demo_quota_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.size(8.dp))
                                Text("إعادة تعيين الباقة التجريبية (50 ميجابايت)")
                            }
                        }
                    }
                }
            }

            // VPN Permission Prompt
            if (!snapshot.vpnPermissionGranted) {
                PermissionActionCard(
                    icon = Icons.Rounded.VpnKey,
                    title = "مطلوب موافقة الـ VPN",
                    description = "اسمح بتشغيل اتصال VPN المحلي ليتمكن التطبيق من تمرير الإنترنت بالسرعة الكاملة أثناء الباقة أو حظره فور انتهائها.",
                    buttonLabel = "منح موافقة الـ VPN",
                    isErrorContainer = true,
                    buttonTestTag = "grant_vpn_button",
                    onClick = onRequestVpnPermission
                )
            }

            // Usage Access Permission Prompt
            if (!snapshot.hasUsageAccessPermission) {
                PermissionActionCard(
                    icon = Icons.Rounded.DataUsage,
                    title = "صلاحية الوصول لبيانات الاستخدام (Usage Access)",
                    description = "افتح الإعدادات واختر \"باقة العائلة\" ثم فعّل \"السماح بالوصول إلى بيانات الاستخدام\" لكي يحسب NetworkStatsManager استهلاك الواي فاي بدقة.",
                    buttonLabel = "فتح إعدادات الوصول لبيانات الاستخدام",
                    isErrorContainer = false,
                    buttonTestTag = "grant_usage_access_button",
                    onClick = onRequestUsageAccess
                )
            }

            // Notification Permission Prompt (Android 13+)
            if (!notificationsGranted) {
                PermissionActionCard(
                    icon = Icons.Rounded.NotificationsActive,
                    title = "تفعيل إشعار الحالة المستمر",
                    description = "اسمح بالإشعارات لعرض الرصيد المتبقي من الباقة باستمرار في شريط الإشعارات.",
                    buttonLabel = "السماح بالإشعارات",
                    isErrorContainer = false,
                    buttonTestTag = "enable_notifications_button",
                    onClick = onRequestNotificationPermission
                )
            }

            // Quota Hero Gauge Card
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Status Pill
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.14f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    snapshot.shouldBlockTraffic -> Icons.Rounded.Lock
                                    snapshot.isWifiConnected -> Icons.Rounded.Speed
                                    else -> Icons.Rounded.WifiOff
                                },
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = if (snapshot.shouldBlockTraffic) {
                                    "حالة الاتصال: متوقف (انتهت الباقة أو الحساب موقوف)"
                                } else {
                                    "حالة الاتصال: متصل (الإنترنت يعمل)"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                    }

                    // Circular Arc Gauge
                    Box(
                        modifier = Modifier.size(216.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 18.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset(
                                (size.width - diameter) / 2f,
                                (size.height - diameter) / 2f
                            )
                            val arcSize = Size(diameter, diameter)

                            drawArc(
                                color = trackColor,
                                startAngle = 140f,
                                sweepAngle = 260f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                            )

                            val sweep = (260f * animatedProgress).coerceIn(0f, 260f)
                            if (sweep > 0.5f) {
                                drawArc(
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            statusColor.copy(alpha = 0.7f),
                                            statusColor,
                                            statusColor
                                        )
                                    ),
                                    startAngle = 140f,
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "الباقة المتبقية",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (snapshot.shouldBlockTraffic) {
                                    "٠ ميجابايت"
                                } else {
                                    DataFormatter.formatBytes(snapshot.remainingBytes)
                                },
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "المستهلك ${(snapshot.usageFraction * 100f).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor
                            )
                        }
                    }

                    // 3-Column Telemetry Strip
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        TelemetryTile(
                            label = "الاستهلاك الحالي",
                            value = DataFormatter.formatBytes(snapshot.usedBytes),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryTile(
                            label = "الحد الأقصى للباقة",
                            value = DataFormatter.formatMb(snapshot.quotaLimitMb),
                            modifier = Modifier.weight(1f)
                        )
                        TelemetryTile(
                            label = "الباقة المتبقية",
                            value = DataFormatter.formatBytes(snapshot.remainingBytes),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // Network & Sync Engine Card
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "محرك القياس والمزامنة",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        OutlinedButton(
                            onClick = onSyncNow,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("sync_now_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Sync,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.size(6.dp))
                            Text("مزامنة الآن")
                        }
                    }

                    StatusInfoRow(
                        icon = if (snapshot.isWifiConnected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff,
                        title = "قياس بيانات الواي فاي (NetworkStatsManager)",
                        value = when {
                            !snapshot.hasUsageAccessPermission -> "بانتظار صلاحية الوصول لبيانات الاستخدام"
                            snapshot.isWifiConnected -> "متصل بالواي فاي • قياس دقيق منذ بداية الجلسة"
                            else -> "غير متصل بالواي فاي حالياً"
                        },
                        tint = if (snapshot.isWifiConnected && snapshot.hasUsageAccessPermission) {
                            Emerald400
                        } else {
                            Amber400
                        }
                    )

                    StatusInfoRow(
                        icon = if (snapshot.isVpnBlocking) Icons.Rounded.Lock else Icons.Rounded.Speed,
                        title = "حالة جدار الحماية (باقة العائلة VPN)",
                        value = if (snapshot.isVpnBlocking) {
                            "وضع الحظر نشط (10.0.0.2/32) • تم منع جميع التطبيقات"
                        } else {
                            "وضع السماح نشط • استثناء جميع التطبيقات للسرعة الكاملة"
                        },
                        tint = if (snapshot.isVpnBlocking) Coral400 else Cyan400
                    )

                    val syncTimeLabel = if (snapshot.lastSyncEpochMs > 0L) {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                            .format(Date(snapshot.lastSyncEpochMs))
                    } else {
                        "قيد الانتظار"
                    }

                    StatusInfoRow(
                        icon = Icons.Rounded.CloudDone,
                        title = "المزامنة الفورية",
                        value = "${snapshot.syncStatusMessage.ifBlank { "كل ٦٠ ثانية + تحديث فوري" }} ($syncTimeLabel)",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Anti-Bypass, Battery & Always-On VPN Card
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "الحماية ومنع التجاوز",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    StatusInfoRow(
                        icon = Icons.Rounded.CheckCircle,
                        title = "التشغيل التلقائي عند الإقلاع (Auto-Start)",
                        value = "مفعّل تلقائياً عبر BOOT_COMPLETED",
                        tint = Emerald400
                    )

                    StatusInfoRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "إعدادات البطارية (Auto-Start)",
                        value = if (snapshot.isBatteryOptimizationIgnored) {
                            "مستثنى من توفير البطارية (عمل مستمر)"
                        } else {
                            "يُنصح باستثناء التطبيق من قيود البطارية لعدم إغلاقه في الخلفية"
                        },
                        tint = if (snapshot.isBatteryOptimizationIgnored) Emerald400 else Amber400
                    )

                    StatusInfoRow(
                        icon = Icons.Rounded.Security,
                        title = "وضع Always-on VPN",
                        value = when {
                            snapshot.isLockdownEnabled -> "مفعّل مع حظر الاتصالات بدون VPN"
                            snapshot.isAlwaysOnActive -> "وضع Always-on VPN نشط"
                            else -> "جاهز • يمكن تفعيله من إعدادات الـ VPN بالنظام"
                        },
                        tint = if (snapshot.isAlwaysOnActive) Emerald400 else MaterialTheme.colorScheme.primary
                    )

                    OutlinedButton(
                        onClick = onOpenBatterySettings,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("battery_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("فتح إعدادات البطارية (Auto-Start)")
                    }

                    OutlinedButton(
                        onClick = onOpenVpnSettings,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("vpn_settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(8.dp))
                        Text("فتح إعدادات Always-on VPN")
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun PermissionActionCard(
    icon: ImageVector,
    title: String,
    description: String,
    buttonLabel: String,
    isErrorContainer: Boolean,
    buttonTestTag: String,
    onClick: () -> Unit
) {
    val containerColor = if (isErrorContainer) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val contentColor = if (isErrorContainer) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val buttonColor = if (isErrorContainer) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.tertiary
    }
    val buttonContentColor = if (isErrorContainer) {
        MaterialTheme.colorScheme.onError
    } else {
        MaterialTheme.colorScheme.onTertiary
    }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = contentColor
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor
            )
            Button(
                onClick = onClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = buttonColor,
                    contentColor = buttonContentColor
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag(buttonTestTag)
            ) {
                Text(buttonLabel)
            }
        }
    }
}

@Composable
private fun TelemetryTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun StatusInfoRow(
    icon: ImageVector,
    title: String,
    value: String,
    tint: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = tint.copy(alpha = 0.14f),
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

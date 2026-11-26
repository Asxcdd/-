package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.ui.theme.JetBrainsMonoFamily

@Composable
fun SetupGuideDialog(
    isFirebaseConfigured: Boolean,
    onDismiss: () -> Unit
) {
    val schemaSample = """
        {
          "users": {
            "alex": {
              "password": "family_password",
              "quota_limit_mb": 2048,
              "used_mb": 0,
              "is_active": true,
              "session_start_timestamp": 1732640000000
            }
          }
        }
    """.trimIndent()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = if (isFirebaseConfigured) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                contentDescription = null,
                tint = if (isFirebaseConfigured) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = "دليل إعداد Firebase",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFirebaseConfigured) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.tertiaryContainer
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (isFirebaseConfigured) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = if (isFirebaseConfigured) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                        Text(
                            text = if (isFirebaseConfigured) {
                                "تم اكتشاف ملف google-services.json وربط Firebase بنجاح."
                            } else {
                                "ملف google-services.json غير موجود بعد. يمكنك تجربة الحساب التجريبي (test / 123) فوراً أو اتباع الخطوات التالية:"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isFirebaseConfigured) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            }
                        )
                    }
                }

                SetupStep(
                    step = "١. وضع ملف google-services.json",
                    detail = "قم بتنزيل ملف google-services.json من لوحة تحكم Firebase (باسم الحزمة: com.aistudio.familyquota.k9m2xp) وضعه داخل المسار app/google-services.json."
                )

                SetupStep(
                    step = "٢. تفعيل قاعدة البيانات الفورية (Realtime Database)",
                    detail = "أنشئ قاعدة بيانات Realtime Database وأضف بيانات المستخدمين تحت المسار users/{username} بهذا الشكل:"
                )

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = schemaSample,
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    )
                }

                SetupStep(
                    step = "٣. المكتبات المضافة تلقائياً",
                    detail = "تمت إضافة com.google.gms.google-services و firebase-bom و firebase-database في ملف app/build.gradle.kts."
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("حسناً")
            }
        }
    )
}

@Composable
private fun SetupStep(step: String, detail: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = step,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
    }
}

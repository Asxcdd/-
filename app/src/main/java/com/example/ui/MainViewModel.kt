package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AuthResult
import com.example.data.BYTES_PER_MB
import com.example.data.FirebaseQuotaRepository
import com.example.data.QuotaPreferences
import com.example.data.QuotaStatusSnapshot
import com.example.service.QuotaRuntimeState
import com.example.service.QuotaServiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MainUiState(
    val isInitializing: Boolean = true,
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val showSetupGuide: Boolean = false,
    val snapshot: QuotaStatusSnapshot = QuotaStatusSnapshot()
)

private data class LocalUiFlags(
    val isInitializing: Boolean = true,
    val isLoggingIn: Boolean = false,
    val loginError: String? = null,
    val showSetupGuide: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext = application.applicationContext
    private val preferences = QuotaPreferences(appContext)
    private val repository = FirebaseQuotaRepository(appContext)

    private val uiFlags = MutableStateFlow(LocalUiFlags())

    val uiState: StateFlow<MainUiState> = combine(
        uiFlags,
        QuotaRuntimeState.status
    ) { flags, snapshot ->
        MainUiState(
            isInitializing = flags.isInitializing,
            isLoggingIn = flags.isLoggingIn,
            loginError = flags.loginError,
            showSetupGuide = flags.showSetupGuide,
            snapshot = snapshot
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = MainUiState()
    )

    init {
        restoreSession()
    }

    private fun restoreSession() {
        viewModelScope.launch {
            QuotaRuntimeState.refreshCapabilities(appContext)
            val session = preferences.getSessionSnapshot()
            if (session.isLoggedIn && session.username.isNotBlank()) {
                QuotaRuntimeState.update {
                    it.copy(
                        isLoggedIn = true,
                        username = session.username,
                        isDemo = session.isDemo,
                        quotaLimitMb = session.quotaLimitMb,
                        usedBytes = session.effectiveUsedBytes,
                        isActive = session.isActive,
                        sessionStartTimestamp = session.sessionStartTimestamp,
                        lastSyncEpochMs = session.lastSyncEpochMs
                    )
                }
                QuotaServiceController.startProtection(appContext)
                if (QuotaServiceController.isVpnPermissionGranted(appContext)) {
                    if (session.shouldBlockTraffic) {
                        QuotaServiceController.activateSinkholeVpn(appContext)
                    } else {
                        QuotaServiceController.activateAllowVpn(appContext)
                    }
                }
            }
            uiFlags.update { it.copy(isInitializing = false) }
        }
    }

    fun login(
        username: String,
        password: String,
        onRequireVpnPermissionCheck: () -> Unit
    ) {
        viewModelScope.launch {
            uiFlags.update { it.copy(isLoggingIn = true, loginError = null) }
            when (val result = repository.authenticateUser(username, password)) {
                is AuthResult.Success -> {
                    val record = result.record
                    preferences.saveLoginSession(record)
                    QuotaRuntimeState.update {
                        it.copy(
                            isLoggedIn = true,
                            username = record.username,
                            isDemo = record.isDemo,
                            quotaLimitMb = record.quotaLimitMb,
                            usedBytes = record.usedMb * BYTES_PER_MB,
                            isActive = record.isActive,
                            sessionStartTimestamp = record.sessionStartTimestamp,
                            lastSyncEpochMs = System.currentTimeMillis(),
                            syncStatusMessage = if (record.isDemo) {
                                "وضع الحساب التجريبي (محلي)"
                            } else {
                                "مزامنة Firebase المباشرة نشطة"
                            }
                        )
                    }
                    uiFlags.update { it.copy(isLoggingIn = false, loginError = null) }
                    onRequireVpnPermissionCheck()
                }

                AuthResult.InvalidUsernameFormat -> {
                    uiFlags.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = "يجب أن يتكون اسم المستخدم من ٢ إلى ٣٢ حرفاً (أحرف إنجليزية أو أرقام أو _ أو -)."
                        )
                    }
                }

                AuthResult.InvalidCredentials -> {
                    uiFlags.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = "اسم المستخدم أو كلمة المرور غير صحيحة."
                        )
                    }
                }

                AuthResult.NotConfigured -> {
                    uiFlags.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = "لم يتم إعداد Firebase بعد. أضف ملف google-services.json في مجلد app/ أو جرّب الحساب التجريبي (test / 123)."
                        )
                    }
                }

                is AuthResult.Error -> {
                    uiFlags.update {
                        it.copy(
                            isLoggingIn = false,
                            loginError = result.message
                        )
                    }
                }
            }
        }
    }

    fun onVpnPermissionOutcome(granted: Boolean) {
        QuotaRuntimeState.refreshCapabilities(appContext)
        val snapshot = QuotaRuntimeState.status.value
        if (snapshot.isLoggedIn) {
            QuotaServiceController.startProtection(appContext)
            if (granted) {
                if (snapshot.shouldBlockTraffic) {
                    QuotaServiceController.activateSinkholeVpn(appContext)
                } else {
                    QuotaServiceController.activateAllowVpn(appContext)
                }
            }
        }
    }

    fun refreshSystemCapabilities() {
        QuotaRuntimeState.refreshCapabilities(appContext)
    }

    fun syncNow() {
        QuotaServiceController.requestImmediateSync(appContext)
    }

    fun toggleDemoQuotaSimulation(exhausted: Boolean) {
        QuotaServiceController.setDemoQuotaExhausted(appContext, exhausted)
    }

    fun logout() {
        viewModelScope.launch {
            QuotaServiceController.stopAllProtection(appContext)
            preferences.clearSession()
            uiFlags.update { it.copy(loginError = null) }
        }
    }

    fun clearError() {
        uiFlags.update { it.copy(loginError = null) }
    }

    fun setShowSetupGuide(show: Boolean) {
        uiFlags.update { it.copy(showSetupGuide = show) }
    }
}

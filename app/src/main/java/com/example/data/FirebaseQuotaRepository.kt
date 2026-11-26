package com.example.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.MessageDigest
import kotlin.coroutines.resume

sealed interface AuthResult {
    data class Success(val record: UserQuotaRecord) : AuthResult
    data object InvalidCredentials : AuthResult
    data object InvalidUsernameFormat : AuthResult
    data object NotConfigured : AuthResult
    data class Error(val message: String) : AuthResult
}

sealed interface QuotaRemoteUpdate {
    data class Updated(val record: UserQuotaRecord) : QuotaRemoteUpdate
    data object UserRemoved : QuotaRemoteUpdate
    data object NotConfigured : QuotaRemoteUpdate
    data class Error(val message: String) : QuotaRemoteUpdate
}

class FirebaseQuotaRepository(private val context: Context) {

    companion object {
        const val FIREBASE_DATABASE_URL =
            "https://limit-app-fb76a-default-rtdb.europe-west1.firebasedatabase.app"
        private val USERNAME_REGEX = Regex("^[a-zA-Z0-9_-]{2,32}$")
    }

    fun isFirebaseConfigured(): Boolean {
        return try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    }

    fun isValidUsername(username: String): Boolean = USERNAME_REGEX.matches(username.trim())

    private fun usersRef() =
        FirebaseDatabase.getInstance(FIREBASE_DATABASE_URL).reference.child("users")

    suspend fun authenticateUser(rawUsername: String, rawPassword: String): AuthResult {
        val username = rawUsername.trim()
        if (!isValidUsername(username)) {
            return AuthResult.InvalidUsernameFormat
        }
        if (rawPassword.isEmpty()) {
            return AuthResult.InvalidCredentials
        }

        // Built-in Demo/Test Account (works immediately without Firebase)
        if (username == DEMO_USERNAME) {
            return if (constantTimeEquals(DEMO_PASSWORD, rawPassword)) {
                AuthResult.Success(
                    UserQuotaRecord(
                        username = DEMO_USERNAME,
                        quotaLimitMb = DEMO_QUOTA_LIMIT_MB,
                        usedMb = 0L,
                        isActive = true,
                        sessionStartTimestamp = System.currentTimeMillis(),
                        isDemo = true
                    )
                )
            } else {
                AuthResult.InvalidCredentials
            }
        }

        if (!isFirebaseConfigured()) {
            return AuthResult.NotConfigured
        }

        return try {
            val userRef = usersRef().child(username)
            suspendCancellableCoroutine { continuation ->
                userRef.get()
                    .addOnSuccessListener { snapshot ->
                        if (!continuation.isActive) return@addOnSuccessListener
                        if (!snapshot.exists()) {
                            continuation.resume(AuthResult.InvalidCredentials)
                            return@addOnSuccessListener
                        }
                        val storedPassword = snapshot.child("password").getValue(String::class.java)
                        if (storedPassword == null || !constantTimeEquals(storedPassword, rawPassword)) {
                            continuation.resume(AuthResult.InvalidCredentials)
                            return@addOnSuccessListener
                        }
                        var record = parseUserRecord(username, snapshot)
                        if (record.sessionStartTimestamp <= 0L) {
                            val now = System.currentTimeMillis()
                            userRef.child("session_start_timestamp").setValue(now)
                            record = record.copy(sessionStartTimestamp = now)
                        }
                        continuation.resume(AuthResult.Success(record))
                    }
                    .addOnFailureListener { error ->
                        if (!continuation.isActive) return@addOnFailureListener
                        continuation.resume(
                            AuthResult.Error(
                                error.localizedMessage ?: "تعذر الاتصال بخادم Firebase."
                            )
                        )
                    }
            }
        } catch (_: IllegalStateException) {
            AuthResult.NotConfigured
        } catch (e: Exception) {
            AuthResult.Error(e.localizedMessage ?: "حدث خطأ غير متوقع أثناء تسجيل الدخول.")
        }
    }

    fun observeUserQuota(rawUsername: String): Flow<QuotaRemoteUpdate> = callbackFlow {
        val username = rawUsername.trim()
        if (!isValidUsername(username)) {
            trySend(QuotaRemoteUpdate.Error("صيغة اسم المستخدم غير صالحة"))
            close()
            return@callbackFlow
        }
        if (username == DEMO_USERNAME) {
            close()
            return@callbackFlow
        }
        if (!isFirebaseConfigured()) {
            trySend(QuotaRemoteUpdate.NotConfigured)
            close()
            return@callbackFlow
        }

        val userRef = try {
            usersRef().child(username)
        } catch (_: IllegalStateException) {
            trySend(QuotaRemoteUpdate.NotConfigured)
            close()
            return@callbackFlow
        }

        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    trySend(QuotaRemoteUpdate.UserRemoved)
                    return
                }
                trySend(QuotaRemoteUpdate.Updated(parseUserRecord(username, snapshot)))
            }

            override fun onCancelled(error: DatabaseError) {
                trySend(QuotaRemoteUpdate.Error(error.message))
            }
        }

        userRef.addValueEventListener(listener)
        awaitClose {
            userRef.removeEventListener(listener)
        }
    }

    suspend fun updateUsedMb(rawUsername: String, usedMb: Long): Result<Unit> {
        val username = rawUsername.trim()
        if (username == DEMO_USERNAME) return Result.success(Unit)
        if (!isValidUsername(username)) {
            return Result.failure(IllegalArgumentException("اسم المستخدم غير صالح"))
        }
        if (!isFirebaseConfigured()) {
            return Result.failure(IllegalStateException("لم يتم إعداد Firebase"))
        }

        return try {
            val usedMbRef = usersRef().child(username).child("used_mb")
            suspendCancellableCoroutine { continuation ->
                usedMbRef.setValue(usedMb.coerceAtLeast(0L))
                    .addOnSuccessListener {
                        if (continuation.isActive) {
                            continuation.resume(Result.success(Unit))
                        }
                    }
                    .addOnFailureListener { error ->
                        if (continuation.isActive) {
                            continuation.resume(Result.failure(error))
                        }
                    }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseUserRecord(username: String, snapshot: DataSnapshot): UserQuotaRecord {
        val quotaLimitMb = snapshot.child("quota_limit_mb").toLongSafe(0L)
        val usedMb = snapshot.child("used_mb").toLongSafe(0L)
        val isActive = snapshot.child("is_active").getValue(Boolean::class.java) ?: false
        val sessionStartTs = snapshot.child("session_start_timestamp").toLongSafe(0L)
        return UserQuotaRecord(
            username = username,
            quotaLimitMb = quotaLimitMb.coerceAtLeast(0L),
            usedMb = usedMb.coerceAtLeast(0L),
            isActive = isActive,
            sessionStartTimestamp = sessionStartTs,
            isDemo = false
        )
    }

    private fun DataSnapshot.toLongSafe(default: Long): Long {
        val raw = value ?: return default
        return when (raw) {
            is Long -> raw
            is Int -> raw.toLong()
            is Double -> raw.toLong()
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull() ?: default
            else -> default
        }
    }

    private fun constantTimeEquals(expected: String, actual: String): Boolean {
        val expectedBytes = expected.toByteArray(Charsets.UTF_8)
        val actualBytes = actual.toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(expectedBytes, actualBytes)
    }
}

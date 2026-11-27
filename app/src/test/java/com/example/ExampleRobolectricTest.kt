package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.BYTES_PER_MB
import com.example.data.BlockReason
import com.example.data.DataFormatter
import com.example.data.FirebaseQuotaRepository
import com.example.data.PersistedSession
import com.example.data.QuotaStatusSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app name from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("جيجاتك", appName)
    }

    @Test
    fun `quota status blocks traffic when used reaches limit or inactive`() {
        val allowed = QuotaStatusSnapshot(
            isLoggedIn = true,
            username = "alex",
            quotaLimitMb = 100L,
            usedBytes = (100L * BYTES_PER_MB) - 1L,
            isActive = true
        )
        assertFalse(allowed.shouldBlockTraffic)
        assertNull(allowed.blockReason)

        val exactLimitReached = allowed.copy(usedBytes = 100L * BYTES_PER_MB)
        assertTrue(exactLimitReached.shouldBlockTraffic)
        assertEquals(BlockReason.QUOTA_EXCEEDED, exactLimitReached.blockReason)

        val pausedByAdmin = allowed.copy(isActive = false)
        assertTrue(pausedByAdmin.shouldBlockTraffic)
        assertEquals(BlockReason.ACCOUNT_DISABLED, pausedByAdmin.blockReason)
    }

    @Test
    fun `persisted session enforces block when logged out`() {
        val loggedOut = PersistedSession(isLoggedIn = false, isActive = true, quotaLimitMb = 500L)
        assertTrue(loggedOut.shouldBlockTraffic)
    }

    @Test
    fun `username validation rejects Firebase RTDB disallowed characters`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = FirebaseQuotaRepository(context)
        assertTrue(repo.isValidUsername("alex_01"))
        assertTrue(repo.isValidUsername("test"))
        assertFalse(repo.isValidUsername("a"))
        assertFalse(repo.isValidUsername("alex.smith"))
        assertFalse(repo.isValidUsername("alex/admin"))
        assertFalse(repo.isValidUsername("alex\$1"))
    }

    @Test
    fun `data formatter renders Arabic MB and GB cleanly`() {
        assertEquals("512 ميجابايت", DataFormatter.formatMb(512L))
        assertEquals("2.00 جيجابايت", DataFormatter.formatMb(2048L))
    }
}

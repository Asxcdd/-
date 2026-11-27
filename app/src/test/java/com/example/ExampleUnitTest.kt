package com.example

import com.example.data.BYTES_PER_MB
import com.example.data.BlockReason
import com.example.data.DataFormatter
import com.example.data.PersistedSession
import com.example.data.QuotaStatusSnapshot
import com.example.data.roundMbTo2Decimals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

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
    fun `persisted session enforces block when logged out and tracks fractional MB`() {
        val loggedOut = PersistedSession(isLoggedIn = false, isActive = true, quotaLimitMb = 500L)
        assertTrue(loggedOut.shouldBlockTraffic)

        val fractional = PersistedSession(
            isLoggedIn = true,
            username = "alex",
            quotaLimitMb = 10L,
            usedMb = 2.54,
            isActive = true
        )
        assertFalse(fractional.shouldBlockTraffic)
        assertEquals(2.54, roundMbTo2Decimals(2.544), 0.001)
    }

    @Test
    fun `data formatter renders Arabic MB and GB cleanly`() {
        assertEquals("50 ميجابايت", DataFormatter.formatMb(50L))
        assertEquals("2.00 جيجابايت", DataFormatter.formatMb(2048L))
        assertEquals("1.50 جيجابايت", DataFormatter.formatBytes(1536L * BYTES_PER_MB))
        assertEquals("2.50 ميجابايت", DataFormatter.formatBytes((2.5 * BYTES_PER_MB).toLong()))
    }
}

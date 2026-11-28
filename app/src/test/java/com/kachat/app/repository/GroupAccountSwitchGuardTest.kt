package com.kachat.app.repository

import com.kachat.app.services.SendOutcomeUnknownException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit 2026-11-27 D1, D2, D3, D5, D6. */
class GroupAccountSwitchGuardTest {

    private val a = "kaspa:qa"
    private val b = "kaspa:qb"

    @Test
    fun `group work goes on only while its own account is signed in`() {
        assertTrue(groupWorkStillFor(a, loggedOut = false, active = a))
        // switched to another account
        assertFalse(groupWorkStillFor(a, loggedOut = false, active = b))
        // Log Out keeps the account record active, but nothing may run for it
        assertFalse(groupWorkStillFor(a, loggedOut = true, active = a))
        // Delete Account of the last account: none active
        assertFalse(groupWorkStillFor(a, loggedOut = false, active = null))
    }

    @Test
    fun `a control send stops on an unknown outcome, a cancel or an account change`() {
        assertFalse(controlSendMayRetry(SendOutcomeUnknownException("tx", RuntimeException("timeout"))))
        assertFalse(controlSendMayRetry(GroupSendAccountChangedException("switched")))
        assertFalse(controlSendMayRetry(kotlinx.coroutines.CancellationException("left")))
    }

    @Test
    fun `a plain control send failure is tried again`() {
        assertTrue(controlSendMayRetry(IllegalStateException("Insufficient funds")))
        assertTrue(controlSendMayRetry(java.io.IOException("node unreachable")))
    }

    @Test
    fun `a send left pending by a run that ended reads Not confirmed, not Failed with Retry`() {
        assertEquals("warning", settledLeftoverGroupSendStatus())
    }
}

package com.kachat.app.ui.screens.explorer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/** The Explorer's small KAS amounts and kUSDT to the cent (iOS 01f71184, release audit). */
class ExplorerFormatAmountsTest {
    private lateinit var saved: Locale

    @Before fun setUp() { saved = Locale.getDefault(); Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved) }

    @Test
    fun aFeeOnlyNetReadsItsDigitsNotZero() {
        // a post's or handshake's net is only its fee: it read "0 KAS" (shown as "−0 KAS")
        assertEquals("0.0001 KAS", ExplorerFormat.shortKAS(10_000L))
        assertEquals("0.000123 KAS", ExplorerFormat.shortKAS(12_345L))
        assertEquals("0.00000001 KAS", ExplorerFormat.shortKAS(1L))
        assertEquals("0.00999 KAS", ExplorerFormat.shortKAS(999_000L))
    }

    @Test
    fun largerAmountsAreUnchanged() {
        assertEquals("0 KAS", ExplorerFormat.shortKAS(0L))
        assertEquals("0.01 KAS", ExplorerFormat.shortKAS(1_000_000L))
        assertEquals("1.25 KAS", ExplorerFormat.shortKAS(125_000_000L))
    }

    @Test
    fun kusdtReadsToTheCent() {
        assertEquals("2.66 kUSDT", ExplorerFormat.kusdt(2_659_812L))
        assertEquals("1.00 kUSDT", ExplorerFormat.kusdt(1_000_000L))
        assertEquals("0.01 kUSDT", ExplorerFormat.kusdt(5_000L))
        // under a cent keeps its digits rather than reading 0
        assertEquals("0.000004 kUSDT", ExplorerFormat.kusdt(4L))
    }
}

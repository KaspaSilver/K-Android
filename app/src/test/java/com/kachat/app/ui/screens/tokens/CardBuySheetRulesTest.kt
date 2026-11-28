package com.kachat.app.ui.screens.tokens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Buy Kaspa with Debit Card's pure rules: the sign sheet's "Ends" date and which frames of the
 *  page may ask for the camera. */
class CardBuySheetRulesTest {
    private val utc = TimeZone.getTimeZone("UTC")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0, zone: TimeZone = utc): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, minute)
        }.timeInMillis

    @Test
    fun endsLaterTodayShowsTheTimeOnly() {
        assertFalse(permitEndsShowDate(at(2026, Calendar.NOVEMBER, 27, 23, 30), at(2026, Calendar.NOVEMBER, 27, 9), utc))
    }

    @Test
    fun endsTomorrowShowsTheDate() {
        assertTrue(permitEndsShowDate(at(2026, Calendar.NOVEMBER, 28, 0, 15), at(2026, Calendar.NOVEMBER, 27, 23, 50), utc))
    }

    @Test
    fun sameDayOfYearInAnotherYearShowsTheDate() {
        assertTrue(permitEndsShowDate(at(2027, Calendar.NOVEMBER, 27, 10), at(2026, Calendar.NOVEMBER, 27, 9), utc))
    }

    @Test
    fun theDayIsThePhonesOwn() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // 20:00 and 23:00 UTC on the 27th are the 28th 05:00 and 08:00 in Tokyo: one day there
        val now = at(2026, Calendar.NOVEMBER, 27, 20)
        val deadline = at(2026, Calendar.NOVEMBER, 27, 23)
        assertFalse(permitEndsShowDate(deadline, now, tokyo))
        // 14:00 and 16:00 UTC: the 27th 23:00 and the 28th 01:00 in Tokyo
        assertTrue(permitEndsShowDate(at(2026, Calendar.NOVEMBER, 27, 16), at(2026, Calendar.NOVEMBER, 27, 14), tokyo))
    }

    @Test
    fun cameraFromTheTrestlySite() {
        assertTrue(trestlyCameraOriginAllowed("https", "trestly.io", -1, "trestly.io", 443))
        assertTrue(trestlyCameraOriginAllowed("HTTPS", "Trestly.IO", 443, "trestly.io", 443))
        assertTrue(trestlyCameraOriginAllowed("https", "staging.example.org", 8443, "staging.example.org", 8443))
    }

    @Test
    fun cameraFromStripesFrames() {
        assertTrue(trestlyCameraOriginAllowed("https", "verify.stripe.com", -1, "trestly.io", 443))
        assertTrue(trestlyCameraOriginAllowed("https", "crypto-js.stripe.com", -1, "trestly.io", 443))
        assertTrue(trestlyCameraOriginAllowed("https", "stripe.com", -1, "trestly.io", 443))
        assertTrue(trestlyCameraOriginAllowed("https", "m.stripe.network", -1, "trestly.io", 443))
    }

    @Test
    fun noCameraForAnyoneElse() {
        assertFalse(trestlyCameraOriginAllowed("http", "trestly.io", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("http", "verify.stripe.com", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "evil.com", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "notstripe.com", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "stripe.com.evil.com", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "trestly.io.evil.com", -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "trestly.io", 8443, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "verify.stripe.com", 8443, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", null, -1, "trestly.io", 443))
        assertFalse(trestlyCameraOriginAllowed("https", "", -1, "", 443))
        assertFalse(trestlyCameraOriginAllowed(null, "trestly.io", -1, "trestly.io", 443))
    }
}

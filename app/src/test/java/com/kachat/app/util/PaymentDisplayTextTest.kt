package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS `ChatMessage.paymentDisplayText` / `readableContent` (owner, 2026-11-22). */
class PaymentDisplayTextTest {

    @Test
    fun memoShowsAfterAColon() {
        assertEquals("Sent 1 KAS: Testing", PaymentDisplayText.paymentDisplayText("Sent 1 KAS — Testing"))
        assertEquals("Received 1 KAS: Testing", PaymentDisplayText.paymentDisplayText("Received 1 KAS — Testing"))
    }

    @Test
    fun onlyTheFirstDashIsSwapped() {
        assertEquals("Sent 1 KAS: a — b", PaymentDisplayText.paymentDisplayText("Sent 1 KAS — a — b"))
    }

    @Test
    fun noMemoIsUnchanged() {
        assertEquals("Received 0.5 KAS", PaymentDisplayText.paymentDisplayText("Received 0.5 KAS"))
        assertEquals("Sent 1 KAS—x", PaymentDisplayText.paymentDisplayText("Sent 1 KAS—x"))
    }

    @Test
    fun onlyPaymentsAreRewritten() {
        assertEquals("Sent 1 KAS: Testing", PaymentDisplayText.readableBody(MessageProtocol.TYPE_PAY, "Sent 1 KAS — Testing"))
        assertEquals("Hello — world", PaymentDisplayText.readableBody("comm", "Hello — world"))
        assertEquals("Hello — world", PaymentDisplayText.readableBody(null, "Hello — world"))
        assertNull(PaymentDisplayText.readableBodyOrNull(MessageProtocol.TYPE_PAY, null))
    }
}

package com.kachat.app.ui.screens.tokens

import com.kachat.app.services.tokens.WalletToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS 5b7c772c TokenSupport / TokenActivityRow / MessageBubbleView.paymentCardParts. */
class TokenSupportTest {

    @Test
    fun `an all upper-case address is lowercased`() {
        assertEquals("kaspa:qrabc123", lowercasedKaspaAddress("KASPA:QRABC123"))
        assertEquals("kaspatest:qrabc", lowercasedKaspaAddress("KASPATEST:QRABC"))
        assertEquals("kaspa:qrabc123", lowercasedKaspaAddress("kaspa:qrabc123"))
    }

    @Test
    fun `mixed case, names and links stay as typed`() {
        assertEquals("kaspa:qrAbc", lowercasedKaspaAddress("kaspa:qrAbc"))
        assertEquals("Alice.kachat", lowercasedKaspaAddress("Alice.kachat"))
        assertEquals("KASPA:QR?amount=1", lowercasedKaspaAddress("KASPA:QR?amount=1"))
    }

    @Test
    fun `a fee has four decimals, eight under a thousandth`() {
        assertEquals("0.0123 KAS", tokenFeeText(1_230_000, "KAS"))
        assertEquals("0.00002036 KAS", tokenFeeText(2_036, "KAS"))
        assertEquals("0.0010 TKAS", tokenFeeText(100_000, "TKAS"))
    }

    @Test
    fun `a token payment bubble names its token, a Kaspa one doesn't`() {
        assertEquals(WalletToken.KUSDT, paymentCardToken("Sent 12.50 kUSDT"))
        assertEquals(WalletToken.KUSDT, paymentCardToken("Received 0.25 kUSDT"))
        assertNull(paymentCardToken("Sent 2.5 KAS"))
        assertNull(paymentCardToken("Received 1 KAS — kUSDT is nice"))
    }
}

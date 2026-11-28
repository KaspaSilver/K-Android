package com.kachat.app.services.tokens

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.kachat.app.util.EIP712
import com.kachat.app.util.EthereumCryptoTest
import com.kachat.app.util.EthereumHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Buy Kaspa with Debit Card's rules (iOS bc0e6ba9 / 6d1971c8 / 824a30bb `CardBuy.swift`): exactly
 * which permit K signs, how Trestly's order reads as a stage (`"delivered": null` isn't paid), and
 * that only a paid order is followed and never replaced by an unpaid one.
 */
class CardBuyTest {

    private val owner = "0x9858EfFD232B4033E47d90003D41EC34EcaEda94"
    private val target = TrestlyTarget("Address #3", "kaspa:qptarget", 2)
    /** A moment before the permit's deadline (1800000000), so it ends within a day. */
    private val now = 1_799_990_000_000L

    private fun permit(edit: (JsonObject) -> Unit = {}): JsonObject =
        JsonParser.parseString(EthereumCryptoTest.PERMIT_JSON).asJsonObject.also(edit)

    private fun params(typed: JsonObject, asText: Boolean = true, signer: String = owner): JsonArray = JsonArray().apply {
        add(signer)
        if (asText) add(typed.toString()) else add(typed)
    }

    private fun refused(params: JsonArray, nowMs: Long = now) {
        try {
            PermitRequest.parse(params, owner, target, nowMs)
            fail("a permit K mustn't sign was taken: $params")
        } catch (e: EIP712.Failure) {}
    }

    @Test fun trestlysPermitIsTaken() {
        val request = PermitRequest.parse(params(permit()), owner, target, now)
        assertEquals(50_000_000L, request.valueUnits)
        assertEquals("50.00 USDC", request.amountText)
        assertEquals("0x9d9D5d596d9157b00317FCa7faCcb885DD3fAa60", request.spender)
        assertEquals(1_800_000_000_000L, request.deadlineMs)
        assertEquals(target, request.target)
        assertEquals(EthereumHex.string(EIP712.digest(permit())), EthereumHex.string(request.digest))
    }

    @Test fun typedDataAsAnObjectIsTakenToo() {
        PermitRequest.parse(params(permit(), asText = false), owner, target, now)
        // the owner's case doesn't matter
        PermitRequest.parse(params(permit(), signer = owner.lowercase()), owner, target, now)
    }

    @Test fun otherSignerRefused() = refused(params(permit(), signer = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"))

    @Test fun malformedParamsRefused() {
        for (bad in listOf<com.google.gson.JsonElement?>(null, JsonPrimitive("x"), JsonArray().apply { add(owner) })) {
            try {
                PermitRequest.parse(bad, owner, target, now)
                fail("$bad was taken")
            } catch (e: EIP712.Failure) {}
        }
        refused(JsonArray().apply { add(owner); add("not json") })
    }

    @Test fun otherChainRefused() = refused(params(permit { it.getAsJsonObject("domain").addProperty("chainId", 137) }))

    @Test fun otherTokenRefused() = refused(params(permit {
        it.getAsJsonObject("domain").addProperty("verifyingContract", "0xdAC17F958D2ee523a2206206994597C13D831ec7")
    }))

    @Test fun notAPermitRefused() = refused(params(permit { it.addProperty("primaryType", "Mail") }))

    @Test fun extraTypeRefused() = refused(params(permit {
        it.getAsJsonObject("types").add("Extra", JsonParser.parseString("""[{"name":"a","type":"uint256"}]"""))
    }))

    @Test fun reorderedPermitFieldsRefused() = refused(params(permit {
        it.getAsJsonObject("types").add("Permit", JsonParser.parseString(
            """[{"name":"spender","type":"address"},{"name":"owner","type":"address"},{"name":"value","type":"uint256"},{"name":"nonce","type":"uint256"},{"name":"deadline","type":"uint256"}]"""
        ))
    }))

    @Test fun unknownDomainFieldRefused() = refused(params(permit {
        it.getAsJsonObject("types").getAsJsonArray("EIP712Domain").add(JsonParser.parseString("""{"name":"salt","type":"bytes32"}"""))
    }))

    @Test fun otherOwnerInMessageRefused() = refused(params(permit {
        it.getAsJsonObject("message").addProperty("owner", "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed")
    }))

    @Test fun valueLimits() {
        refused(params(permit { it.getAsJsonObject("message").addProperty("value", "0") }))
        // over $1M
        refused(params(permit { it.getAsJsonObject("message").addProperty("value", "1000000000001") }))
        PermitRequest.parse(params(permit { it.getAsJsonObject("message").addProperty("value", "1000000000000") }), owner, target, now)
    }

    @Test fun badSpenderRefused() = refused(params(permit { it.getAsJsonObject("message").addProperty("spender", "0x1234") }))

    @Test fun deadlineWithinADay() {
        // already over
        refused(params(permit()), nowMs = 1_800_000_000_000L)
        // more than a day out
        refused(params(permit()), nowMs = 1_800_000_000_000L - 86_401_000L)
        PermitRequest.parse(params(permit()), owner, target, 1_800_000_000_000L - 86_400_000L)
    }

    @Test fun respondsOnce() {
        val request = PermitRequest.parse(params(permit()), owner, target, now)
        request.respond(true)
        request.respond(false)
        assertTrue(kotlinx.coroutines.runBlocking { request.await() })
    }

    // Reading Trestly's order

    private val buy = CardBuy("sess_123456", "kaspa:qptarget", "Address #3", 2, startedAtMs = 0L)

    private fun read(json: String, nowMs: Long = now): CardBuyTracker.OrderReading =
        CardBuyTracker.readOrder(JsonParser.parseString(json).asJsonObject, buy, nowMs)

    private fun stage(json: String, nowMs: Long = now): CardBuy.Stage =
        (read(json, nowMs) as CardBuyTracker.OrderReading.Known).next.stage

    @Test fun unknownOrder() {
        assertEquals(CardBuyTracker.OrderReading.NotKnown, read("""{}"""))
        assertEquals(CardBuyTracker.OrderReading.NotKnown, read("""{"order":null,"purchase":null}"""))
    }

    @Test fun otherKaspaAddress() =
        assertEquals(CardBuyTracker.OrderReading.OtherAddress, read("""{"order":{"kaspaAddress":"kaspa:qpother","state":"unpaid"}}"""))

    /** iOS 6d1971c8: an order nobody paid has `"delivered": null`, which read as delivered. */
    @Test fun deliveredNullIsNotPaid() {
        assertEquals(CardBuy.Stage.WAITING_PAYMENT, stage("""{"order":{"kaspaAddress":"KASPA:QPTARGET","state":"unpaid"},"purchase":{"delivered":null,"stripe":"initialized"}}"""))
        assertEquals(CardBuy.Stage.WAITING_PAYMENT, stage("""{"purchase":{"delivered":""}}"""))
        assertEquals(CardBuy.Stage.PAID, stage("""{"purchase":{"delivered":"50000000"}}"""))
        assertEquals(CardBuy.Stage.PAID, stage("""{"purchase":{"stripe":"fulfillment_processing"}}"""))
        assertEquals(CardBuy.Stage.PAID, stage("""{"purchase":{"stripe":"fulfillment_complete"}}"""))
    }

    @Test fun stages() {
        assertEquals(CardBuy.Stage.REJECTED, stage("""{"purchase":{"stripe":"rejected"},"order":{"refund":{"state":"returned"}}}"""))
        val refunded = (read("""{"order":{"refund":{"state":"returned","amount":"49000000"},"state":"bridged","nonce":4}}""") as CardBuyTracker.OrderReading.Known)
        assertEquals(CardBuy.Stage.REFUNDED, refunded.next.stage)
        assertEquals(49_000_000L, refunded.next.refundUnits)
        assertNull(refunded.bridgedNonce)
        for (state in listOf("needs-sign", "expired", "replaced")) {
            assertEquals(CardBuy.Stage.SIGN_AGAIN, stage("""{"order":{"state":"$state"}}"""))
        }
        // unpaid past its deadline
        assertEquals(CardBuy.Stage.EXPIRED, stage("""{"order":{"state":"unpaid","deadline":1700000000}}"""))
        assertEquals(CardBuy.Stage.WAITING_PAYMENT, stage("""{"order":{"state":"unpaid","deadline":1900000000}}"""))
        val bridged = read("""{"order":{"state":"bridged","nonce":"17"}}""") as CardBuyTracker.OrderReading.Known
        assertEquals(CardBuy.Stage.BRIDGING, bridged.next.stage)
        assertEquals(17L, bridged.bridgedNonce)
        assertEquals(CardBuy.Stage.BRIDGING, stage("""{"order":{"state":"sent","tx":"0xabc"}}"""))
    }

    @Test fun dollars() {
        val known = read("""{"purchase":{"amount":"50","stripe":"fulfillment_complete"}}""") as CardBuyTracker.OrderReading.Known
        assertEquals("50.00", known.next.dollars)
        assertEquals("12.50", CardBuyTracker.dollarsText(JsonPrimitive(12.5)))
        assertNull(CardBuyTracker.dollarsText(JsonParser.parseString("null")))
    }

    @Test fun hasValue() {
        assertFalse(CardBuyTracker.hasValue(null))
        assertFalse(CardBuyTracker.hasValue(JsonParser.parseString("null")))
        assertFalse(CardBuyTracker.hasValue(JsonPrimitive("")))
        assertTrue(CardBuyTracker.hasValue(JsonPrimitive(0)))
        assertTrue(CardBuyTracker.hasValue(JsonObject()))
    }

    // Following

    @Test fun samesSessionMarksPaidOnce() {
        assertEquals(CardBuyTracker.FollowAction.MARK_PAID, CardBuyTracker.followAction(buy, buy.session, paid = true))
        assertEquals(CardBuyTracker.FollowAction.NOTHING, CardBuyTracker.followAction(buy, buy.session, paid = false))
        assertEquals(CardBuyTracker.FollowAction.NOTHING, CardBuyTracker.followAction(buy.copy(paidSeen = true), buy.session, paid = true))
    }

    /** iOS 824a30bb: a paid order still on its way isn't swapped for a new one nobody has paid. */
    @Test fun paidOrderNotReplacedByUnpaid() {
        val paid = buy.copy(paidSeen = true, stage = CardBuy.Stage.BRIDGING)
        assertEquals(CardBuyTracker.FollowAction.KEEP_CURRENT, CardBuyTracker.followAction(paid, "sess_other", paid = false))
        assertEquals(CardBuyTracker.FollowAction.START_NEW, CardBuyTracker.followAction(paid, "sess_other", paid = true))
        // once it's over, a new order takes its place
        assertEquals(CardBuyTracker.FollowAction.START_NEW, CardBuyTracker.followAction(paid.copy(stage = CardBuy.Stage.ARRIVED), "sess_other", paid = false))
        // an unpaid one is replaced by anything
        assertEquals(CardBuyTracker.FollowAction.START_NEW, CardBuyTracker.followAction(buy, "sess_other", paid = false))
        assertEquals(CardBuyTracker.FollowAction.START_NEW, CardBuyTracker.followAction(null, "sess_other", paid = false))
    }

    /** Only a paid order is followed once Buy Kaspa closes. */
    @Test fun unpaidDroppedOnceClosed() {
        assertTrue(CardBuyTracker.shouldDrop(buy, screenOpen = false))
        assertFalse(CardBuyTracker.shouldDrop(buy, screenOpen = true))
        assertTrue(CardBuyTracker.shouldDrop(buy.copy(stage = CardBuy.Stage.SIGN_AGAIN), screenOpen = false))
        assertTrue(CardBuyTracker.shouldDrop(buy.copy(stage = CardBuy.Stage.EXPIRED), screenOpen = false))
        assertFalse(CardBuyTracker.shouldDrop(buy.copy(paidSeen = true), screenOpen = false))
        // a refused purchase stays to be shown
        assertFalse(CardBuyTracker.shouldDrop(buy.copy(stage = CardBuy.Stage.REJECTED), screenOpen = false))
    }

    @Test fun storedOrderRoundTrips() {
        val stored = buy.copy(stage = CardBuy.Stage.ARRIVED, mintedUnits = 49_500_000L, paidSeen = true, dollars = "50.00")
        assertEquals(stored, CardBuyTracker.decode(CardBuyTracker.encode(stored)))
        assertNull(CardBuyTracker.decode("""{"session":"x"}"""))
        assertNull(CardBuyTracker.decode("not json"))
    }
}

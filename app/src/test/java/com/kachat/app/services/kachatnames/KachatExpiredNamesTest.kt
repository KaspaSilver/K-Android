package com.kachat.app.services.kachatnames

import com.kachat.app.services.kachatnames.KachatNames.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expired usernames (iOS b49a021d, owner 2026-11-27: "make sure no one misses it"): the local
 * reminders before grace ends for every name, the count on Your Usernames, the expired names the
 * dock's Wallet dot counts, and the bell's once-per-wallet catch-up.
 */
class KachatExpiredNamesTest {
    private val day = 86_400_000L
    private val now = 2_000_000_000_000L
    private val chatting = ByteArray(32) { 7 }
    private val other = ByteArray(32) { 9 }

    private fun params(periodMs: Long, graceMs: Long, renewWindowMs: Long) = Params(
        bond = 0, gapValue = 0, tCommit = 600, maxYears = 2, periodMs = periodMs, graceMs = graceMs,
        renewWindowMs = renewWindowMs, registerPrices = List(5) { 0L }, renewPrices = List(5) { 0L }, offerMaxFee = 0
    )

    /** Mainnet: a year, 30 days of grace, a 30-day renewal window. */
    private val mainnet = params(KachatNames.YEAR_MS, 30 * day, 30 * day)

    /** A short testnet clock: 2-hour grace and renewal window. */
    private val testnet = params(day, 7_200_000L, 7_200_000L)

    private fun name(n: String, exp: Long, owner: ByteArray = chatting) =
        NameInfo(n, Codec.key(n), owner, 0, exp, Outpoint(KachatNames.ZERO32, 0))

    private fun plan(names: List<NameInfo>, p: Params = mainnet, push: Boolean = false) =
        KachatNameReminders.plan(names, p, now, chattingKey = chatting, pushCoversChatting = push)
            .associate { it.kind to it.date }

    @Test
    fun withPushOffEveryNameGetsEveryReminder() {
        val exp = now + 60 * day
        val graceEnds = exp + 30 * day
        assertEquals(
            mapOf(
                KachatNameReminders.KIND_RENEW to exp - 30 * day,
                KachatNameReminders.KIND_GRACE to exp,
                KachatNameReminders.KIND_GRACE_WEEK to graceEnds - 7 * day,
                KachatNameReminders.KIND_LAST_CALL to graceEnds - day,
            ),
            plan(listOf(name("alice", exp)))
        )
    }

    @Test
    fun pushCoversOnlyTheChattingAddressesRenewalAndExpiry() {
        val exp = now + 60 * day
        val graceEnds = exp + 30 * day
        // the chatting address's name: push sends renewal-open and expired; the grace-ending ones are local
        assertEquals(
            mapOf(
                KachatNameReminders.KIND_GRACE_WEEK to graceEnds - 7 * day,
                KachatNameReminders.KIND_LAST_CALL to graceEnds - day,
            ),
            plan(listOf(name("alice", exp)), push = true)
        )
        // a name on another address of the wallet: push never reaches it, so all four
        assertEquals(4, plan(listOf(name("bob", exp, other)), push = true).size)
    }

    @Test
    fun aNameInGraceStillGetsTheWeekBeforeAndTheLastCall() {
        // expired two days ago: 28 days of grace left
        val exp = now - 2 * day
        val graceEnds = exp + 30 * day
        assertEquals(
            mapOf(
                KachatNameReminders.KIND_GRACE_WEEK to graceEnds - 7 * day,
                KachatNameReminders.KIND_LAST_CALL to graceEnds - day,
            ),
            plan(listOf(name("alice", exp)))
        )
        // under a week left: the last call only
        assertEquals(
            mapOf(KachatNameReminders.KIND_LAST_CALL to now - 25 * day + 30 * day - day),
            plan(listOf(name("alice", now - 25 * day)))
        )
        // past the last call: nothing more
        assertEquals(emptyMap<String, Long>(), plan(listOf(name("alice", now - 29 * day - 1))))
    }

    @Test
    fun aShortGraceHasNoWeekBeforeAndItsLastCallIsHalfway() {
        val exp = now + 3_600_000L
        val p = testnet
        val out = plan(listOf(name("alice", exp)), p)
        assertFalse(out.containsKey(KachatNameReminders.KIND_GRACE_WEEK))
        // halfway through a 2-hour grace, not a day before
        assertEquals(exp + 3_600_000L, out[KachatNameReminders.KIND_LAST_CALL])
    }

    @Test
    fun theWeekBeforeNeedsAGraceLongerThanTwoWeeks() {
        val exp = now + 60 * day
        assertFalse(plan(listOf(name("alice", exp)), params(KachatNames.YEAR_MS, 14 * day, 30 * day)).containsKey(KachatNameReminders.KIND_GRACE_WEEK))
        assertTrue(plan(listOf(name("alice", exp)), params(KachatNames.YEAR_MS, 15 * day, 30 * day)).containsKey(KachatNameReminders.KIND_GRACE_WEEK))
    }

    @Test
    fun aJunkExpiryCannotWrapADate() {
        val out = KachatNameReminders.plan(listOf(name("alice", Long.MAX_VALUE - 1)), mainnet, now, chatting, false)
        assertTrue(out.all { it.date > now })
    }

    @Test
    fun yourUsernamesCountsAtLeastTheExpiredOnes() {
        assertEquals(3, KachatExpiredNamesModel.usernamesCount(3, 1))
        assertEquals(2, KachatExpiredNamesModel.usernamesCount(0, 2))
        assertEquals(0, KachatExpiredNamesModel.usernamesCount(0, 0))
    }

    @Test
    fun theWalletDotCountsTheNamesInGraceOnly() {
        val grace = mainnet.graceMs
        val names = listOf(
            name("active", now + day),
            name("grace2", now - 2 * day),
            name("grace1", now - 1),
            name("lapsed", now - grace - 1),
        )
        assertEquals(listOf("grace2", "grace1"), KachatNamesRegistry.inGracePeriod(names, grace, now).map { it.name })
    }

    @Test
    fun theBellCatchUpNotesRenewalAndExpiryAgainButNotLapses() {
        val old = KachatNamesNotifier.Snapshot(
            names = mapOf("alice" to KachatNamesNotifier.NameNote(5, renewNoted = true, graceNoted = true, lapsedNoted = true)),
            offersOnMine = setOf("o1"),
        )
        val again = KachatNamesNotifier.renotedForBell(old)
        assertEquals(KachatNamesNotifier.NameNote(5, renewNoted = false, graceNoted = false, lapsedNoted = true), again.names["alice"])
        assertEquals(setOf("o1"), again.offersOnMine)
    }

    @Test
    fun expiredNamesLoadOncePerMinuteUnlessTheRegistryMoves() {
        // audit 2026-11-27 L19: launch and every come-back ran two or three loads at once
        val last = 1_000_000L to 7
        assertEquals(false, KachatExpiredNamesModel.skipLoad(null, 7, 1_000_001L, force = false))
        assertEquals(true, KachatExpiredNamesModel.skipLoad(last, 7, 1_059_999L, force = false))
        assertEquals(false, KachatExpiredNamesModel.skipLoad(last, 7, 1_060_000L, force = false))
        assertEquals(false, KachatExpiredNamesModel.skipLoad(last, 8, 1_000_010L, force = false))
        assertEquals(false, KachatExpiredNamesModel.skipLoad(last, 7, 1_000_010L, force = true))
        // a clock that went back doesn't hold loads off
        assertEquals(false, KachatExpiredNamesModel.skipLoad(last, 7, 999_000L, force = false))
    }
}

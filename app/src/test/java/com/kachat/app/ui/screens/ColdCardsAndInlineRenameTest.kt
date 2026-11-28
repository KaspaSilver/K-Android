package com.kachat.app.ui.screens

import com.kachat.app.viewmodels.ColdStorageViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS b49a021d: Cold Storage's address cards sort as Manage Addresses, and in-place rename's
 *  save rules. */
class ColdCardsAndInlineRenameTest {

    private fun row(index: Int, balance: Long = 0L, history: Boolean = false, hidden: Boolean = false, address: String = "a$index") =
        ColdStorageViewModel.AddressRow(index = index, address = address, balanceSompi = balance, hasHistory = history, hidden = hidden)

    @Test
    fun `unused first newest first, then used in number order, hidden left out`() {
        val rows = listOf(
            row(0, balance = 5),
            row(1, history = true),
            row(2),
            row(3, hidden = true),
            row(4),
            row(5, address = "owner"),
        )
        val sorted = ColdStorageViewModel.sortedForList(rows, domainOwners = setOf("owner"))
        assertEquals(listOf(4, 2, 0, 1, 5), sorted.map { it.index })
    }

    @Test
    fun `an address label is trimmed and empty goes back to the default`() {
        assertEquals("Savings", InlineRenameRules.addressLabel("  Savings "))
        assertNull(InlineRenameRules.addressLabel("   "))
        assertNull(InlineRenameRules.addressLabel(""))
    }

    @Test
    fun `an empty account name keeps the old one`() {
        assertEquals("Vault", InlineRenameRules.accountName(" Vault ", old = "Cold Storage 1"))
        assertEquals("Cold Storage 1", InlineRenameRules.accountName("  ", old = "Cold Storage 1"))
    }

    @Test
    fun `a kUSDT card starts empty only on its own default name`() {
        assertEquals("", InlineRenameRules.kusdtStartingText("Address #3", defaultTitle = "Address #3"))
        assertEquals("Trading", InlineRenameRules.kusdtStartingText("Trading", defaultTitle = "Address #3"))
        // a saved name that starts like the default is the person's own (audit 2026-11-27 L14)
        assertEquals("Address #5 savings", InlineRenameRules.kusdtStartingText("Address #5 savings", defaultTitle = "Address #5"))
        assertEquals("Address #4", InlineRenameRules.kusdtStartingText("Address #4", defaultTitle = "Address #5"))
    }

    @Test
    fun `the kUSDT placeholder is the number the card shows`() {
        // spending index 4 is the card "Address #5": the Chatting Address is #0 (L14)
        assertEquals("Address #5", com.kachat.app.services.WalletManager.spendingAddressTitle(4))
    }

    @Test
    fun `a rename saves once and only a change`() {
        assertEquals(false, InlineRenameRules.shouldSave("Savings", "Savings"))
        assertEquals(true, InlineRenameRules.shouldSave("Savings 2", "Savings"))
        // cleared: saved as empty, which puts the default name back
        assertEquals(true, InlineRenameRules.shouldSave("", "Savings"))
        assertEquals(false, InlineRenameRules.shouldSave("", ""))
    }

    @Test
    fun `a cleared account name keeps the old one, not its first letter`() {
        // M6: backspacing "Cold Storage 1" to empty saved "C" on the way
        assertNull(InlineRenameRules.accountNameToSave("", current = "Cold Storage 1"))
        assertNull(InlineRenameRules.accountNameToSave("   ", current = "Cold Storage 1"))
        assertNull(InlineRenameRules.accountNameToSave(" Cold Storage 1 ", current = "Cold Storage 1"))
        assertEquals("Vault", InlineRenameRules.accountNameToSave(" Vault ", current = "Cold Storage 1"))
    }

    @Test
    fun `an account's balance counts every address, hidden ones too`() {
        // L16: one rule with the account's card and Manage Addresses
        val rows = listOf(row(0, balance = 5), row(1, balance = 7, hidden = true), row(2))
        assertEquals(12L, ColdStorageViewModel.totalSompi(rows))
        // never wraps on a server's numbers (L4)
        assertEquals(Long.MAX_VALUE, ColdStorageViewModel.totalSompi(listOf(row(0, balance = Long.MAX_VALUE), row(1, balance = 1))))
    }

    @Test
    fun `a balances-only reload keeps what the earlier checks found`() {
        // L21: coins moved; the used checks and name tags aren't run again
        val rows = listOf(
            row(0, balance = 5, history = true).copy(label = "Vault", liveChecked = true),
            row(1),
            row(2, hidden = true),
        )
        val merged = ColdStorageViewModel.mergeBalances(rows, mapOf("a0" to 0L, "a1" to 9L))
        assertEquals(listOf(0L, 9L, 0L), merged.map { it.balanceSompi })
        assertEquals(listOf(true, true, false), merged.map { it.hasHistory })
        assertEquals("Vault", merged[0].label)
        assertEquals(true, merged[2].hidden)
        // an address the read left out keeps its figure and its check
        assertEquals(false, merged[2].liveChecked)
    }
}

package com.kachat.app.repository

import com.kachat.app.models.GroupMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Group deep-backfill marks survive a launch, per wallet; the decrypt cache never mixes accounts. */
class GroupDeepBackfillMarksTest {

    private val disk = mutableMapOf<String, Set<String>>()
    private fun marks() = GroupDeepBackfillMarks(load = { disk[it].orEmpty() }, save = { w, k -> if (k.isEmpty()) disk.remove(w) else disk[w] = k })

    @Test
    fun aMarkIsThereNextLaunch() {
        marks().mark("kaspa:qa", "gcomm|g1|b1")
        val next = marks()
        assertTrue(next.contains("kaspa:qa", "gcomm|g1|b1"))
    }

    @Test
    fun marksArePerWallet() {
        val m = marks()
        m.mark("kaspa:qa", "gctl|admin")
        assertFalse(m.contains("kaspa:qb", "gctl|admin"))
    }

    @Test
    fun aRepairClearsOnlyItsGroupsKeys() {
        val m = marks()
        m.mark("kaspa:qa", "gcomm|g1|b1")
        m.mark("kaspa:qa", "gcomm|g2|b1")
        m.removeWhere("kaspa:qa") { it.startsWith("gcomm|g1|") }
        val next = marks()
        assertFalse(next.contains("kaspa:qa", "gcomm|g1|b1"))
        assertTrue(next.contains("kaspa:qa", "gcomm|g2|b1"))
    }

    @Test
    fun deleteAccountClearsTheWallet() {
        val m = marks()
        m.mark("kaspa:qa", "gcomm|g1|b1")
        m.mark("kaspa:qb", "gcomm|g1|b2")
        m.clearWallet("kaspa:qa")
        assertFalse(m.contains("kaspa:qa", "gcomm|g1|b1"))
        assertFalse("kaspa:qa" in disk)
        assertTrue(marks().contains("kaspa:qb", "gcomm|g1|b2"))
    }

    @Test
    fun theCacheKeyCarriesTheWallet() {
        assertNotEquals(groupDecryptCacheKey("kaspa:qa", "tx1"), groupDecryptCacheKey("kaspa:qb", "tx1"))
    }

    @Test
    fun aCachedDecryptTakesTheRowsSenderAndDirection() {
        val cached = GroupMessage("tx1", "g1", "kaspa:qa", "aa", "hi", 100L, isOutgoing = true, deliveryStatus = "sent")
        val otherAccountsRow = GroupMessageEntity(
            txId = "tx1", walletAddress = "kaspa:qb", groupId = "g1", senderAddress = "kaspa:qa", senderIdHex = "aa",
            epoch = 0, msgIdHex = "m", contentEncryptedHex = "", blockTimestamp = 100L, isOutgoing = false, deliveryStatus = "sent",
        )
        val shown = cached.withRowState(otherAccountsRow)
        assertFalse(shown.isOutgoing)
        assertEquals("hi", shown.content)
        assertSame(cached, cached.withRowState(otherAccountsRow.copy(walletAddress = "kaspa:qa", isOutgoing = true)))
    }
}

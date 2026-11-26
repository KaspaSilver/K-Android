package com.kachat.app.models

import com.kachat.app.services.kachatnames.NameInfo
import com.kachat.app.services.kachatnames.Outpoint
import org.junit.Assert.assertEquals
import org.junit.Test

/** A .kachat name is always shown bare: the Show .kachat Endings switch is gone (iOS 522fd9f4
 *  `KachatNameLabel`). */
class KachatNameLabelTest {

    @Test
    fun `names always show bare`() {
        assertEquals("alice", KachatNameLabel.display("alice"))
    }

    @Test
    fun `a registry name shows bare`() {
        val info = NameInfo(
            name = "alice",
            key = ByteArray(32),
            owner = ByteArray(32),
            price = 0L,
            expiresAt = 0L,
            outpoint = Outpoint(ByteArray(32), 0),
        )
        assertEquals("alice", info.display)
    }
}

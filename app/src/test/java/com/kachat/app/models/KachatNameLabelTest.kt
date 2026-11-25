package com.kachat.app.models

import com.kachat.app.services.kachatnames.NameInfo
import com.kachat.app.services.kachatnames.Outpoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings > Customization > Show .kachat Endings (iOS 94ed3a35 `KachatNameLabel`; off by default
 *  since iOS 518fdc8c). */
class KachatNameLabelTest {

    @After
    fun restoreDefault() {
        KachatNameLabel.showsEnding = false
    }

    @Test
    fun `names show bare by default`() {
        assertEquals("alice", KachatNameLabel.display("alice"))
    }

    @Test
    fun `on shows the ending`() {
        KachatNameLabel.showsEnding = true
        assertEquals("alice.kachat", KachatNameLabel.display("alice"))
    }

    @Test
    fun `a registry name follows the switch`() {
        val info = NameInfo(
            name = "alice",
            key = ByteArray(32),
            owner = ByteArray(32),
            price = 0L,
            expiresAt = 0L,
            outpoint = Outpoint(ByteArray(32), 0),
        )
        assertEquals("alice", info.display)
        KachatNameLabel.showsEnding = true
        assertEquals("alice.kachat", info.display)
    }
}

package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Test

/** Photo and name changes in a group's thread, yours and others' (iOS 01f71184 `GroupSystemLine`). */
class GroupSystemLineRenameTest {
    private val alice = "kaspa:qypq9a8yz0exhv4mmalh8vuzpkgrrgk6ppx8c9xsz4mfrmazvvqj5psg3yjrsq"
    private val names: (String) -> String = { if (it == alice) "alice" else it }
    private val translate: (GroupSystemLine.Kind, String) -> String = { kind, who -> "[${kind.name}] $who" }
    private val own: (GroupSystemLine.OwnLine) -> String = { "[OWN ${it.name}]" }
    private val renamed: (String?, String) -> String = { who, name -> "[RENAMED ${who ?: "YOU"}] $name" }

    private fun show(stored: String) = GroupSystemLine.display(stored, translate, names, own, renamed)

    @Test
    fun yourOwnPhotoLinesAreWholeLines() {
        assertEquals("[OWN PHOTO_CHANGED]", show("You changed the group photo"))
        assertEquals("[OWN PHOTO_REMOVED]", show("You removed the group photo"))
    }

    @Test
    fun someoneElsesPhotoLinesNameThem() {
        assertEquals("[PHOTO_CHANGED] alice", show("$alice changed the group photo"))
        assertEquals("[PHOTO_REMOVED] Bob", show("Bob removed the group photo"))
    }

    @Test
    fun renamesKeepTheNewName() {
        assertEquals("[RENAMED YOU] Weekend \"plans\"", show("You changed the group name to \"Weekend \"plans\"\""))
        assertEquals("[RENAMED alice] Team", show("$alice changed the group name to \"Team\""))
    }

    @Test
    fun theEnglishDefaultsRebuildTheStoredLine() {
        val stored = "Bob changed the group name to \"Team\""
        assertEquals(stored, GroupSystemLine.display(stored, translate, names))
        assertEquals("You changed the group photo", GroupSystemLine.display("You changed the group photo", translate, names))
    }

    @Test
    fun otherLinesAreUnchanged() {
        assertEquals("[JOINED] alice", show("$alice joined the group"))
        assertEquals("Something else", show("Something else"))
    }
}

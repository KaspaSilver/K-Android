package com.kachat.app.util

import com.kachat.app.models.GroupActionPhase
import com.kachat.app.models.GroupActionProgress
import com.kachat.app.models.GroupActionProgress.State
import com.kachat.app.models.GroupActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The group create / add members progress (iOS GroupActionSheets.swift, 2026-11-24) and the
 *  membership lines shown with names (iOS GroupSystemLine). */
class GroupActionSheetsTest {

    private val alice = "kaspa:qypq9ty7jy7xwk2x5zh2j3dl9d4y0s4ykplj4ex9k0lv2r6wh4n5jrsqhkxqr3m"
    private val bob = "kaspatest:qqkqkzjvr7zwxxmjxjkmxxdwju9kjs6e9u82uh59z07vgaks6gg62v8707g73"

    private fun progress() = GroupActionProgress(
        "Creating \"Friends\"",
        listOf(
            GroupActionProgress.Step(GroupActionStep.SET_UP, "Setting up the group"),
            GroupActionProgress.Step(alice, "Inviting Alice"),
            GroupActionProgress.Step(GroupActionStep.RECOVERY_COPY, "Saving a recovery copy"),
        ),
    )

    @Test
    fun `steps follow the reports, matched by id in any case`() {
        val p = progress()
        p.apply(GroupActionStep.SET_UP, GroupActionPhase.Started) { "x" }
        assertEquals(State.Working, p.steps[0].state)
        p.apply(GroupActionStep.SET_UP, GroupActionPhase.Finished) { "x" }
        p.apply(alice.uppercase(), GroupActionPhase.Failed(IllegalStateException("boom"))) { it.message!! }
        assertEquals(State.Done, p.steps[0].state)
        assertEquals(State.Failed("boom"), p.steps[1].state)
        assertEquals(1, p.doneCount)
        assertEquals(1, p.failedCount)
        assertEquals(2, p.settledCount)
        assertFalse(p.isFinished)
    }

    @Test
    fun `an unknown key changes nothing`() {
        val p = progress()
        p.apply("#photo", GroupActionPhase.Started) { "x" }
        assertEquals(0, p.settledCount)
        assertTrue(p.steps.all { it.state == State.Waiting })
    }

    @Test
    fun `finish fails whatever is still waiting or working`() {
        val p = progress()
        p.set(GroupActionStep.SET_UP, State.Done)
        p.set(alice, State.Working, detail = "Sending the new group key: 1 of 3")
        p.finish("Not sent")
        assertTrue(p.isFinished)
        assertEquals(State.Done, p.steps[0].state)
        assertEquals(State.Failed("Not sent"), p.steps[1].state)
        assertEquals(State.Failed("Not sent"), p.steps[2].state)
        assertEquals("Sending the new group key: 1 of 3", p.steps[1].detail)
        assertEquals(3, p.settledCount)
    }

    @Test
    fun `setDetail keeps the state`() {
        val p = progress()
        p.set(alice, State.Working)
        p.setDetail(alice, "2 of 4")
        assertEquals(State.Working, p.steps[1].state)
        assertEquals("2 of 4", p.steps[1].detail)
    }

    private val translate: (GroupSystemLine.Kind, String) -> String = { kind, who -> "[${kind.name}] $who" }
    private val names: (String) -> String = { address -> if (address == alice) "alice.kachat" else "Bob" }

    @Test
    fun `a membership line is translated with the address as a name`() {
        assertEquals("[JOINED] alice.kachat", GroupSystemLine.display("$alice joined the group", translate, names))
        assertEquals("[DECLINED] Bob", GroupSystemLine.display("$bob declined the invite", translate, names))
        assertEquals("[INVITED] alice.kachat", GroupSystemLine.display("$alice was invited to the group chat", translate, names))
        assertEquals("[REMOVED] Bob", GroupSystemLine.display("$bob was removed from the group chat", translate, names))
    }

    @Test
    fun `a line stored with a name before stays that name`() {
        assertEquals("[JOINED] kaspa:qypq...jrsq", GroupSystemLine.display("kaspa:qypq...jrsq joined the group", translate, names))
        assertEquals("[INVITED] Carol", GroupSystemLine.display("Carol was invited to the group chat", translate, names))
    }

    @Test
    fun `any other line only has its addresses named`() {
        assertEquals("You changed the group photo", GroupSystemLine.display("You changed the group photo", translate, names))
        assertEquals("Bob set it", GroupSystemLine.display("$bob set it", translate, names))
    }
}

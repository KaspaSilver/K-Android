package com.kachat.app.services.arcade

import com.kachat.app.util.ArcadeGame
import com.kachat.app.util.KaspaAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCADE.md §1 (the stamp), §5 (the boards' rows) and §6 (challenge links). */
class ArcadeServicesTest {

    private val alice = KaspaAddress.encode("kaspa", 0x00, ByteArray(32) { 1 })
    private val bob = KaspaAddress.encode("kaspa", 0x00, ByteArray(32) { 2 })

    // MARK: The stamp (§1)

    @Test
    fun `the stamp's keys are sorted with no extra whitespace`() {
        val stamp = ArcadeStamp(game = "snake", g = "abc-1", seed = "9f2c41d07a3b55e1", score = 7, m = "12R 30U 41L")
        assertEquals(
            """{"g":"abc-1","game":"snake","m":"12R 30U 41L","score":7,"seed":"9f2c41d07a3b55e1","type":"arcade","v":1}""",
            stamp.encoded,
        )
    }

    @Test
    fun `stamp strings are escaped as JSON, slashes not`() {
        assertEquals("\"a\\\"b\\\\c/d\\n\"", ArcadeStamp.json("a\"b\\c/d\n"))
    }

    @Test
    fun `a game with no score is never sent`() {
        val job = ArcadeStampJob.notStamped(ArcadeGame.SNAKE)
        assertEquals(ArcadeStampJob.State.NotSent, job.state.value)
        assertEquals(0, job.score)
    }

    @Test
    fun `a transaction id is 64 hex digits`() {
        assertTrue(ArcadeTransactionCheck.isTransactionId("a".repeat(64)))
        assertFalse(ArcadeTransactionCheck.isTransactionId("a".repeat(63)))
        assertFalse(ArcadeTransactionCheck.isTransactionId("g".repeat(64)))
    }

    // MARK: Challenge links (§6)

    @Test
    fun `a challenge link round trips`() {
        val url = ArcadeChallenge.url(ArcadeGame.SNAKE, 1240, alice, "0f1e2d3c-aaaa-bbbb-cccc-000000000000")
        assertEquals("kachatarcade://snake?score=1240&by=$alice&game=0f1e2d3c-aaaa-bbbb-cccc-000000000000", url)
        val challenge = ArcadeChallenge.parse(url)!!
        assertEquals(ArcadeGame.SNAKE, challenge.game)
        assertEquals(1240, challenge.score)
        assertEquals(alice, challenge.by)
        assertEquals(url, challenge.url)
        assertFalse(challenge.byIsAuthor)
        assertNull(challenge.confirmedBy)
    }

    @Test
    fun `2048 is a game, other hosts are not`() {
        assertEquals(ArcadeGame.G2048, ArcadeChallenge.parse("kachatarcade://2048?score=5")!!.game)
        assertEquals(ArcadeGame.SNAKE, ArcadeChallenge.parse("KACHATARCADE://Snake")!!.game)
        assertNull(ArcadeChallenge.parse("kachatarcade://tetris?score=5"))
        assertNull(ArcadeChallenge.parse("kachatchess://bot/3"))
    }

    @Test
    fun `a bad score or address is dropped, and the link rebuilt without it`() {
        val challenge = ArcadeChallenge.parse("kachatarcade://snake?score=+12&by=kaspa:nope&game=x y")!!
        assertNull(challenge.score)
        assertNull(challenge.by)
        assertNull(challenge.gameId)
        assertEquals("kachatarcade://snake", challenge.url)
        assertNull(ArcadeChallenge.parse("kachatarcade://snake?score=10000001")!!.score)
        assertEquals(10_000_000, ArcadeChallenge.parse("kachatarcade://snake?score=10000000")!!.score)
        assertEquals(0, ArcadeChallenge.parse("kachatarcade://snake?score=0")!!.score)
    }

    @Test
    fun `a challenge is found in a post's text, trailing punctuation left out`() {
        val text = "I scored 7 in Snake on K. Think you can beat it?\n\nkachatarcade://snake?score=7&by=$alice&game=abc."
        val match = ArcadeChallenge.match(text)
        assertNotNull(match)
        assertEquals("kachatarcade://snake?score=7&by=$alice&game=abc", match!!.matchedText)
        assertEquals(7, match.challenge.score)
        assertNull(ArcadeChallenge.match("no link here"))
        assertNull(ArcadeChallenge.match("kachatarcade://pong?score=1"))
    }

    @Test
    fun `the challenger is named only when the link's by is the post's author`() {
        val url = ArcadeChallenge.url(ArcadeGame.G2048, 512, alice, "g1")
        ArcadeLinkRouter.open(url, author = alice.uppercase())
        assertEquals(alice, ArcadeLinkRouter.challenge.value!!.confirmedBy)
        ArcadeLinkRouter.open(url, author = bob)
        assertNull(ArcadeLinkRouter.challenge.value!!.confirmedBy)
        ArcadeLinkRouter.open(url)
        assertNull(ArcadeLinkRouter.challenge.value!!.confirmedBy)
        assertFalse(ArcadeLinkRouter.open("kachatarcade://nope"))
        ArcadeLinkRouter.challenge.value = null
    }

    @Test
    fun `a bot game holds challenges until its own token clears`() {
        ArcadeLinkRouter.botGame("a", running = true)
        assertTrue(ArcadeLinkRouter.isBusy)
        ArcadeLinkRouter.botGame("b", running = false)
        assertTrue(ArcadeLinkRouter.isBusy)
        ArcadeLinkRouter.botGame("a", running = false)
        assertFalse(ArcadeLinkRouter.isBusy)
    }

    // MARK: Boards (§5)

    @Test
    fun `the board keeps one row per address, its best, highest first`() {
        val rows = ArcadeProgressRules.boardRows(
            listOf(
                ArcadeBoardRow(alice, 10, 5),
                ArcadeBoardRow(bob, 30, 9),
                ArcadeBoardRow(alice, 40, 1),
                ArcadeBoardRow("kaspa:bad", 99, 1),
                ArcadeBoardRow(bob, -1, 1),
            ),
            limit = 100,
        )
        assertEquals(listOf(40, 30), rows.map { it.score })
        assertEquals(2, rows.size)
    }

    @Test
    fun `a tie goes to the earlier stamp`() {
        val rows = ArcadeProgressRules.boardRows(listOf(ArcadeBoardRow(alice, 10, 9), ArcadeBoardRow(bob, 10, 3)), 100)
        assertEquals(bob, rows.first().address)
    }

    @Test
    fun `the own stamped best joins the board, or beats the indexer's row`() {
        val board = listOf(ArcadeBoardRow(bob, 30, 1), ArcadeBoardRow(alice, 10, 1))
        val mine = ArcadeRecord("snake", 50, 2, "g", "t")
        val merged = ArcadeProgressRules.merged(board, alice, mine, 100)
        assertEquals(listOf(alice, bob), merged.map { it.address })
        assertEquals(50, merged.first().score)
        // a lower own score leaves the indexer's row
        val lower = ArcadeProgressRules.merged(listOf(ArcadeBoardRow(alice, 60, 1)), alice, mine, 100)
        assertEquals(60, lower.single().score)
        // not on the board yet
        val added = ArcadeProgressRules.merged(emptyList(), alice, mine, 100)
        assertEquals(50, added.single().score)
    }

    @Test
    fun `the best record is per game and period, the earlier on a tie`() {
        val records = listOf(
            ArcadeRecord("snake", 10, 100, "a", null),
            ArcadeRecord("snake", 10, 50, "b", null),
            ArcadeRecord("2048", 900, 100, "c", null),
            ArcadeRecord("snake", 99, 10, "d", null),
        )
        assertEquals("d", ArcadeProgressRules.best(records, ArcadeGame.SNAKE, 0)!!.gameId)
        assertEquals("b", ArcadeProgressRules.best(records, ArcadeGame.SNAKE, 20)!!.gameId)
        assertEquals("c", ArcadeProgressRules.best(records, ArcadeGame.G2048, 0)!!.gameId)
        assertNull(ArcadeProgressRules.best(records, ArcadeGame.G2048, 200))
    }
}

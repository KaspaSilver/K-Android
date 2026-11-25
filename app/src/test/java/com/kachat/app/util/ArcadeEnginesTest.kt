package com.kachat.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ARCADE.md's rules and test vectors (§2-§4): the indexer replays every stamp with them. */
class ArcadeEnginesTest {

    // MARK: SplitMix64 (§2)

    @Test
    fun `splitmix64 seed 42 gives the spec's first two numbers`() {
        val random = ArcadeRandom(42)
        assertEquals("13679457532755275413", java.lang.Long.toUnsignedString(random.next()))
        assertEquals("2949826092126892291", java.lang.Long.toUnsignedString(random.next()))
    }

    @Test
    fun `below is the unsigned remainder`() {
        val a = ArcadeRandom(42)
        val b = ArcadeRandom(42)
        val value = a.next()
        assertEquals(java.lang.Long.remainderUnsigned(value, 7L).toInt(), b.below(7))
        // 13679457532755275413 % 10 = 3
        assertEquals(3, ArcadeRandom(42).below(10))
    }

    @Test
    fun `seed hex round trips with 16 lowercase digits`() {
        assertEquals("0000000000000001", ArcadeRandom.hex(1))
        assertEquals("9f2c41d07a3b55e1", ArcadeRandom.hex(ArcadeRandom.seed("9f2c41d07a3b55e1")!!))
        assertEquals("ffffffffffffffff", ArcadeRandom.hex(-1))
        assertNull(ArcadeRandom.seed("123"))
        assertNull(ArcadeRandom.seed("zzzzzzzzzzzzzzzz"))
    }

    @Test
    fun `a copied generator draws the same numbers`() {
        val random = ArcadeRandom(7)
        random.next()
        val copy = random.copy()
        assertEquals(random.next(), copy.next())
    }

    // MARK: Snake (§3)

    @Test
    fun `snake starts three long heading right`() {
        val game = SnakeEngine(1)
        assertEquals(listOf(SnakeEngine.Cell(6, 8), SnakeEngine.Cell(5, 8), SnakeEngine.Cell(4, 8)), game.body)
        assertEquals(ArcadeDirection.RIGHT, game.heading)
    }

    @Test
    fun `snake vector - seed 1 first apple at 0,9`() {
        assertEquals(SnakeEngine.Cell(0, 9), SnakeEngine(1).food)
    }

    @Test
    fun `snake vector - seed 1 with 1D 3L ends after 11 ticks with score 0`() {
        val replay = SnakeEngine.replay(1, "1D 3L")
        assertNotNull(replay)
        assertEquals(0, replay!!.score)
        assertEquals(11, replay.ticks)
    }

    @Test
    fun `snake played live records the same moves the replay takes`() {
        val game = SnakeEngine(1)
        game.tick()
        game.tick(ArcadeDirection.DOWN)
        game.tick()
        game.tick(ArcadeDirection.LEFT)
        while (!game.isOver) game.tick()
        assertEquals("1D 3L", game.movesText)
        assertEquals(11, game.ticks)
        assertEquals(0, game.score)
    }

    @Test
    fun `snake can't turn straight back or the way it is going`() {
        val game = SnakeEngine(1)
        assertFalse(game.canTurn(ArcadeDirection.LEFT))
        assertFalse(game.canTurn(ArcadeDirection.RIGHT))
        assertTrue(game.canTurn(ArcadeDirection.UP))
        // an invalid turn is ignored live and not recorded
        game.tick(ArcadeDirection.LEFT)
        assertEquals("", game.movesText)
    }

    @Test
    fun `snake replay refuses bad moves`() {
        // the current heading
        assertNull(SnakeEngine.replay(1, "0R"))
        // straight back
        assertNull(SnakeEngine.replay(1, "0L"))
        // ticks not strictly increasing
        assertNull(SnakeEngine.replay(1, "3D 3L"))
        assertNull(SnakeEngine.replay(1, "3D 1L"))
        // not a direction
        assertNull(SnakeEngine.replay(1, "3X"))
        assertNull(SnakeEngine.replay(1, "D"))
    }

    @Test
    fun `snake with no turns runs into the right wall`() {
        val replay = SnakeEngine.replay(1, "")!!
        // from x=6 to the wall at x=16: ten ticks, the tenth off the board
        assertEquals(10, replay.ticks)
        assertEquals(0, replay.score)
    }

    @Test
    fun `snake eats an apple and grows`() {
        // seed 1: the apple is at (0,9); go down to row 9 then left along it
        val replay = SnakeEngine.replay(1, "0D 1L")!!
        assertEquals(1, replay.score)
        val game = SnakeEngine(1)
        game.tick(ArcadeDirection.DOWN)
        game.tick(ArcadeDirection.LEFT)
        repeat(5) { game.tick() }
        assertEquals(1, game.score)
        assertEquals(4, game.body.size)
    }

    @Test
    fun `a snake copy plays on the same`() {
        val game = SnakeEngine(5)
        game.tick(ArcadeDirection.UP)
        val copy = game.copy()
        repeat(4) {
            game.tick()
            copy.tick()
        }
        assertEquals(game.body, copy.body)
        assertEquals(game.food, copy.food)
        assertEquals(game.movesText, copy.movesText)
    }

    // MARK: 2048 (§4)

    @Test
    fun `2048 vector - seed 1 starts 2,2`() {
        val game = Game2048Engine(1)
        assertEquals(listOf(2, 2) + List(14) { 0 }, game.tiles)
    }

    @Test
    fun `2048 vector - LURDLURDLURD gives score 48 and the spec's tiles`() {
        val game = Game2048Engine(1)
        for (letter in "LURDLURDLURD") assertTrue(game.move(ArcadeDirection.fromLetter(letter)!!))
        assertEquals(48, game.score)
        assertEquals(listOf(0, 0, 0, 0, 0, 0, 0, 4, 0, 0, 2, 16, 2, 2, 4, 2), game.tiles)
        assertEquals("LURDLURDLURD", game.moves)
        val replay = Game2048Engine.replay(1, "LURDLURDLURD")!!
        assertEquals(48, replay.score)
    }

    @Test
    fun `2048 a move that changes nothing is not a move`() {
        val game = Game2048Engine(1)
        // [2,2,0,...]: up changes nothing (both are on the top row already)
        assertFalse(game.move(ArcadeDirection.UP))
        assertEquals("", game.moves)
        assertNull(Game2048Engine.replay(1, "U"))
        assertNull(Game2048Engine.replay(1, "LX"))
    }

    @Test
    fun `2048 merges once each, nearest the wall first`() {
        val game = Game2048Engine(1)
        // [2,2,...] left: one 4, score 4
        assertTrue(game.move(ArcadeDirection.LEFT))
        assertEquals(4, game.score)
        assertEquals(4, game.tiles[0])
    }

    @Test
    fun `2048 line indices start at the wall`() {
        assertEquals(listOf(0, 1, 2, 3), Game2048Engine.lineIndices(0, ArcadeDirection.LEFT))
        assertEquals(listOf(3, 2, 1, 0), Game2048Engine.lineIndices(0, ArcadeDirection.RIGHT))
        assertEquals(listOf(1, 5, 9, 13), Game2048Engine.lineIndices(1, ArcadeDirection.UP))
        assertEquals(listOf(13, 9, 5, 1), Game2048Engine.lineIndices(1, ArcadeDirection.DOWN))
    }

    @Test
    fun `a 2048 copy plays on the same`() {
        val game = Game2048Engine(9)
        game.move(ArcadeDirection.LEFT)
        val copy = game.copy()
        for (letter in "URDL") {
            val direction = ArcadeDirection.fromLetter(letter)!!
            assertEquals(game.move(direction), copy.move(direction))
        }
        assertEquals(game.tiles, copy.tiles)
        assertEquals(game.score, copy.score)
    }
}

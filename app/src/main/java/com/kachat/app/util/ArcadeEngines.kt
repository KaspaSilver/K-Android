package com.kachat.app.util

// KaPlay's arcade (owner's call, 2026-11-23; iOS 518fdc8c `Services/ArcadeEngines.swift`): Snake
// and 2048, native to K. Every game is seeded and deterministic, so a finished game's stamp (its
// seed and its moves, `ArcadeStamp`) replays to the same score anywhere: the indexer checks a
// score by playing it again (ARCADE.md). Nothing here knows about time or the screen: the screens
// drive these engines and draw them.

/** The arcade's games. [raw] is the stamp's `game` and the challenge link's host. */
enum class ArcadeGame(val raw: String) {
    SNAKE("snake"),
    G2048("2048");

    companion object {
        fun fromRaw(raw: String?): ArcadeGame? = entries.firstOrNull { it.raw == raw }
    }
}

/**
 * SplitMix64: the arcade's only source of chance. The same seed gives the same game, on any
 * device and in the indexer (ARCADE.md §2). The state is a `UInt64` held in a [Long]: the
 * arithmetic wraps the same, and the shifts are unsigned.
 */
class ArcadeRandom(seed: Long) {
    var state: Long = seed
        private set

    fun next(): Long {
        state += GOLDEN
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** 0 until [n] (n > 0), as `next() % n` on the unsigned value. */
    fun below(n: Int): Int = java.lang.Long.remainderUnsigned(next(), n.toLong()).toInt()

    /** The same generator, drawing the same numbers from here on (iOS copies the struct). */
    fun copy(): ArcadeRandom = ArcadeRandom(0).also { it.state = state }

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        private const val MIX_1 = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
        private const val MIX_2 = -0x6b2fb644ecceee15L // 0x94D049BB133111EB

        /** A fresh seed for a new game. */
        fun newSeed(): Long = java.security.SecureRandom().nextLong()

        /** 16 lowercase hex digits. */
        fun hex(seed: Long): String = java.lang.Long.toHexString(seed).padStart(16, '0')

        /** The seed a stamp wrote, or null when it isn't 16 hex digits. */
        fun seed(fromHex: String): Long? {
            if (fromHex.length != 16) return null
            return fromHex.toULongOrNull(16)?.toLong()
        }
    }
}

enum class ArcadeDirection(val letter: Char, val dx: Int, val dy: Int) {
    UP('U', 0, -1),
    DOWN('D', 0, 1),
    LEFT('L', -1, 0),
    RIGHT('R', 1, 0);

    val opposite: ArcadeDirection
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    companion object {
        fun fromLetter(letter: Char): ArcadeDirection? = entries.firstOrNull { it.letter == letter }
    }
}

// MARK: - Snake (ARCADE.md §3)

/**
 * A 16 by 16 board. The snake starts three long at (6,8), (5,8), (4,8), heading right. Each tick
 * it takes the turn queued for that tick (if any), moves one cell, and either eats the apple
 * (grows by one, score +1, a new apple) or moves its tail along. Hitting a wall or its own body
 * ends the game (the tail cell it is leaving doesn't count, unless it is eating).
 *
 * A value type on iOS: [copy] gives the screen a new instance to draw after every tick.
 */
class SnakeEngine private constructor(
    body: List<Cell>,
    heading: ArcadeDirection,
    food: Cell?,
    score: Int,
    ticks: Int,
    isOver: Boolean,
    turns: List<Turn>,
    private val random: ArcadeRandom,
) {
    data class Cell(val x: Int, val y: Int)

    data class Turn(val tick: Int, val direction: ArcadeDirection)

    var body: List<Cell> = body
        private set
    var heading: ArcadeDirection = heading
        private set
    var food: Cell? = food
        private set
    var score: Int = score
        private set
    var ticks: Int = ticks
        private set
    var isOver: Boolean = isOver
        private set
    /** The turns taken, as the stamp writes them: "12R 30U" (the tick it was taken on, then the
     *  direction). */
    var turns: List<Turn> = turns
        private set

    constructor(seed: Long) : this(
        body = listOf(Cell(6, 8), Cell(5, 8), Cell(4, 8)),
        heading = ArcadeDirection.RIGHT,
        food = null,
        score = 0,
        ticks = 0,
        isOver = false,
        turns = emptyList(),
        random = ArcadeRandom(seed),
    ) {
        placeFood()
    }

    val head: Cell get() = body[0]

    /** Whether [direction] would be taken now: not the way it is already going, nor straight back. */
    fun canTurn(direction: ArcadeDirection): Boolean = direction != heading && direction != heading.opposite

    /** One step. [turn], if it can be taken, is taken first and recorded. */
    fun tick(turn: ArcadeDirection? = null): Boolean {
        if (isOver) return false
        if (turn != null && canTurn(turn)) {
            heading = turn
            turns = turns + Turn(ticks, turn)
        }
        ticks += 1
        val next = Cell(head.x + heading.dx, head.y + heading.dy)
        val eating = next == food
        if (next.x < 0 || next.x >= WIDTH || next.y < 0 || next.y >= HEIGHT) {
            isOver = true
            return false
        }
        val blocking = if (eating) body else body.dropLast(1)
        if (next in blocking) {
            isOver = true
            return false
        }
        val moved = ArrayList<Cell>(body.size + 1)
        moved.add(next)
        moved.addAll(body)
        if (eating) {
            body = moved
            score += 1
            placeFood()
        } else {
            moved.removeAt(moved.lastIndex)
            body = moved
        }
        return true
    }

    /** The apple goes on one of the empty cells, counted row by row from the top left. */
    private fun placeFood() {
        val taken = body.toHashSet()
        val empty = ArrayList<Cell>(WIDTH * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until WIDTH) {
                val cell = Cell(x, y)
                if (cell !in taken) empty.add(cell)
            }
        }
        food = if (empty.isEmpty()) null else empty[random.below(empty.size)]
        if (empty.isEmpty()) isOver = true
    }

    val movesText: String get() = turns.joinToString(" ") { "${it.tick}${it.direction.letter}" }

    /** A copy to hand the screen (iOS's struct is copied on every change). */
    fun copy(): SnakeEngine = SnakeEngine(body, heading, food, score, ticks, isOver, turns, random.copy())

    data class Replay(val score: Int, val ticks: Int)

    companion object {
        const val WIDTH = 16
        const val HEIGHT = 16

        /** A game can't take more ticks than this without its turns making it loop forever. */
        private const val TICK_LIMIT = 1_000_000

        /** Replays a stamp: the score it really reaches, or null when the moves don't make a game. */
        fun replay(seed: Long, moves: String): Replay? {
            val turns = HashMap<Int, ArcadeDirection>()
            var lastTick = -1
            for (token in moves.split(' ')) {
                if (token.isEmpty()) continue
                val direction = ArcadeDirection.fromLetter(token.last()) ?: return null
                val tick = token.dropLast(1).toIntOrNull() ?: return null
                if (tick <= lastTick) return null
                turns[tick] = direction
                lastTick = tick
            }
            val game = SnakeEngine(seed)
            while (!game.isOver && game.ticks < TICK_LIMIT) {
                val turn = turns[game.ticks]
                if (turn != null) {
                    if (!game.canTurn(turn)) return null
                    game.tick(turn)
                } else {
                    game.tick()
                }
            }
            return if (game.isOver) Replay(game.score, game.ticks) else null
        }
    }
}

// MARK: - 2048 (ARCADE.md §4)

/**
 * A 4 by 4 board. Two tiles to start, then one after every move that changes the board: on one
 * of the empty cells (counted row by row from the top left), a 4 one time in ten, else a 2. A move
 * slides every tile as far as it goes and merges equal pairs once each, nearest the wall first;
 * each merge adds the new tile to the score. A move that changes nothing isn't a move. The game
 * is over when no move changes the board.
 */
class Game2048Engine private constructor(
    tiles: IntArray,
    score: Int,
    moves: String,
    isOver: Boolean,
    lastSpawn: Int?,
    private val random: ArcadeRandom,
) {
    /** Row by row: `tiles[y * 4 + x]`, 0 for empty. */
    private val cells: IntArray = tiles
    val tiles: List<Int> get() = cells.toList()
    var score: Int = score
        private set
    var moves: String = moves
        private set
    var isOver: Boolean = isOver
        private set
    /** Where the last tile appeared, for the screen's pop. */
    var lastSpawn: Int? = lastSpawn
        private set

    constructor(seed: Long) : this(IntArray(SIZE * SIZE), 0, "", false, null, ArcadeRandom(seed)) {
        spawn()
        spawn()
    }

    val bestTile: Int get() = cells.maxOrNull() ?: 0

    /** Plays [direction]; false (and nothing recorded) when it changes nothing. */
    fun move(direction: ArcadeDirection): Boolean {
        if (isOver) return false
        var changed = false
        var gained = 0
        for (line in 0 until SIZE) {
            val indices = lineIndices(line, direction)
            val values = indices.map { cells[it] }
            val (merged, points) = slide(values)
            if (merged != values) {
                changed = true
                indices.forEachIndexed { i, index -> cells[index] = merged[i] }
            }
            gained += points
        }
        if (!changed) return false
        score += gained
        moves += direction.letter
        spawn()
        if (!canMove) isOver = true
        return true
    }

    val canMove: Boolean
        get() {
            if (0 in cells) return true
            for (y in 0 until SIZE) {
                for (x in 0 until SIZE) {
                    val value = cells[y * SIZE + x]
                    if (x + 1 < SIZE && cells[y * SIZE + x + 1] == value) return true
                    if (y + 1 < SIZE && cells[(y + 1) * SIZE + x] == value) return true
                }
            }
            return false
        }

    private fun spawn() {
        val empty = cells.indices.filter { cells[it] == 0 }
        if (empty.isEmpty()) {
            lastSpawn = null
            return
        }
        val cell = empty[random.below(empty.size)]
        cells[cell] = if (random.below(10) == 0) 4 else 2
        lastSpawn = cell
    }

    /** A copy to hand the screen (iOS's struct is copied on every change). */
    fun copy(): Game2048Engine = Game2048Engine(cells.copyOf(), score, moves, isOver, lastSpawn, random.copy())

    data class Replay(val score: Int, val isOver: Boolean)

    companion object {
        const val SIZE = 4

        /** The cells of one line, from the wall the tiles move toward. (The screen follows each
         *  tile along these to slide it.) */
        fun lineIndices(line: Int, direction: ArcadeDirection): List<Int> = (0 until SIZE).map { step ->
            when (direction) {
                ArcadeDirection.LEFT -> line * SIZE + step
                ArcadeDirection.RIGHT -> line * SIZE + (SIZE - 1 - step)
                ArcadeDirection.UP -> step * SIZE + line
                ArcadeDirection.DOWN -> (SIZE - 1 - step) * SIZE + line
            }
        }

        /** One line, slid toward its start: equal neighbours merge once each, the first pair first. */
        private fun slide(values: List<Int>): Pair<List<Int>, Int> {
            val tiles = values.filter { it != 0 }
            val out = ArrayList<Int>(SIZE)
            var points = 0
            var index = 0
            while (index < tiles.size) {
                if (index + 1 < tiles.size && tiles[index] == tiles[index + 1]) {
                    val merged = tiles[index] * 2
                    out.add(merged)
                    points += merged
                    index += 2
                } else {
                    out.add(tiles[index])
                    index += 1
                }
            }
            while (out.size < SIZE) out.add(0)
            return out to points
        }

        /** Replays a stamp: the score it reaches and whether it ended, or null when a move changes
         *  nothing (a real game never records one). */
        fun replay(seed: Long, moves: String): Replay? {
            val game = Game2048Engine(seed)
            for (letter in moves) {
                val direction = ArcadeDirection.fromLetter(letter) ?: return null
                if (!game.move(direction)) return null
            }
            return Replay(game.score, game.isOver)
        }
    }
}

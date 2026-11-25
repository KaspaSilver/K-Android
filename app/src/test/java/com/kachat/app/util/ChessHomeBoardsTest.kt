package com.kachat.app.util

import com.kachat.app.ui.components.CycleButtons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Chess's leaderboard cycle button (iOS 518fdc8c ChessHomeView.boardChoices). */
class ChessHomeBoardsTest {
    private val choices = ChessHomeBoards.choices

    @Test
    fun fourteenStopsInIosOrder() {
        assertEquals(14, choices.size)
        assertEquals(ChessBoardChoice(ChessBoardChoice.Kind.Duel, ChessLeaderboardPeriod.MONTH), choices[0])
        assertEquals(ChessBoardChoice(ChessBoardChoice.Kind.Duel, ChessLeaderboardPeriod.ALL_TIME), choices[1])
        assertEquals(ChessBoardChoice(ChessBoardChoice.Kind.Tournament, ChessLeaderboardPeriod.MONTH), choices[2])
        assertEquals(ChessBoardChoice(ChessBoardChoice.Kind.Tournament, ChessLeaderboardPeriod.ALL_TIME), choices[3])
        val bots = choices.drop(4)
        assertEquals(ChessBot.all.flatMap { listOf(it.level, it.level) }, bots.map { (it.kind as ChessBoardChoice.Kind.Bot).level })
        assertEquals(
            List(ChessBot.all.size) { listOf(ChessLeaderboardPeriod.MONTH, ChessLeaderboardPeriod.ALL_TIME) }.flatten(),
            bots.map { it.period },
        )
        assertEquals(choices.size, choices.toSet().size)
    }

    @Test
    fun cycleShowsACountNotDotsAndWrapsRound() {
        assertFalse(CycleButtons.showsDots(choices.size))
        assertEquals("3/14", CycleButtons.positionLabel(choices, choices[2]))
        assertEquals(choices[0], CycleButtons.next(choices, choices.last()))
        assertEquals(choices[1], CycleButtons.next(choices, choices[0]))
    }

    @Test
    fun titlesAndEmptyTextsInEnglish() {
        // No resources in a unit test: the English keys come back, as iOS falls back to the key.
        assertEquals("1v1 · This month", ChessHomeBoards.title(choices[0]))
        assertEquals("Tournaments · All time", ChessHomeBoards.title(choices[3]))
        val first = ChessBot.all.first()
        assertEquals("${first.name} · This month", ChessHomeBoards.title(choices[4]))
        assertEquals("No finished 1v1 games this month yet.", ChessHomeBoards.emptyText(choices[0]))
        assertEquals("No finished tournaments yet.", ChessHomeBoards.emptyText(choices[3]))
        assertEquals("Nobody has beaten ${first.name} yet.", ChessHomeBoards.emptyText(choices[5]))
    }
}

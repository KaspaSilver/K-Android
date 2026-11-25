package com.kachat.app.util

/**
 * One stop of the leaderboard's cycle button on KaPlay > Chess (iOS 518fdc8c
 * `ChessHomeView.BoardChoice`): a board and its period.
 */
data class ChessBoardChoice(val kind: Kind, val period: ChessLeaderboardPeriod) {
    sealed interface Kind {
        data object Duel : Kind
        data object Tournament : Kind
        data class Bot(val level: Int) : Kind
    }
}

/**
 * The boards Chess's own screen steps through (owner, 2026-11-24: the cycle button goes through
 * every kind of leaderboard): 1v1, Tournaments, then each bot, each for this month and all time -
 * 14 stops with five bots, so the button shows "3/14" rather than dots.
 */
object ChessHomeBoards {
    val choices: List<ChessBoardChoice> by lazy {
        val kinds: List<ChessBoardChoice.Kind> =
            listOf(ChessBoardChoice.Kind.Duel, ChessBoardChoice.Kind.Tournament) +
                ChessBot.all.map { ChessBoardChoice.Kind.Bot(it.level) }
        kinds.flatMap { kind -> ChessLeaderboardPeriod.entries.map { ChessBoardChoice(kind, it) } }
    }

    /** The English key of a period's cycle label (iOS `ChessLeaderboardPeriod.cycleTitle`). */
    fun periodKey(period: ChessLeaderboardPeriod): String = when (period) {
        ChessLeaderboardPeriod.MONTH -> "This month"
        ChessLeaderboardPeriod.ALL_TIME -> "All time"
    }

    /** "1v1 · This month", "Kaspa Silver · All time": the button's label, in the picked language. */
    fun title(choice: ChessBoardChoice): String {
        val kind = when (val k = choice.kind) {
            ChessBoardChoice.Kind.Duel -> ChessText.string("1v1")
            ChessBoardChoice.Kind.Tournament -> ChessText.string("Tournaments")
            is ChessBoardChoice.Kind.Bot -> ChessBot.forLevel(k.level)?.name.orEmpty()
        }
        return "$kind · ${ChessText.string(periodKey(choice.period))}"
    }

    /** What an empty board says (iOS `ChessHomeView.emptyText`). */
    fun emptyText(choice: ChessBoardChoice): String {
        val month = choice.period == ChessLeaderboardPeriod.MONTH
        return when (val k = choice.kind) {
            ChessBoardChoice.Kind.Duel ->
                ChessText.string(if (month) "No finished 1v1 games this month yet." else "No finished 1v1 games yet.")
            ChessBoardChoice.Kind.Tournament ->
                ChessText.string(if (month) "No finished tournaments this month yet." else "No finished tournaments yet.")
            is ChessBoardChoice.Kind.Bot -> {
                val name = ChessBot.forLevel(k.level)?.name.orEmpty()
                ChessText.format(if (month) "Nobody has beaten %s this month yet." else "Nobody has beaten %s yet.", name)
            }
        }
    }
}

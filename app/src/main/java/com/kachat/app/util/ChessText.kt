package com.kachat.app.util

import java.util.Locale

/**
 * The chess screens' words that are built in code (iOS 077d777 ChessText). One whole sentence per
 * case, never English pieces glued together, which no translation can follow; a count picks its
 * singular or plural sentence here, in code. Each sentence goes through [s], the one place it is
 * looked up, and its numbers and names are filled in after.
 *
 * The English is the key, worded as iOS's keys (`%@` and `%lld` written as `%s` and `%d`), looked
 * up through [KaPlayText] in the language picked in Settings, with iOS's translations.
 */
object ChessText {
    /** The one lookup point for every sentence (iOS `AppLocalization.string`). */
    private fun s(key: String): String = KaPlayText.string(key)

    fun string(key: String): String = s(key)

    /** [key] is a format ("%s won on time."): looked up first, then filled in, in the picked
     *  language's locale (its digits). Ints go in as `%d`. */
    fun format(key: String, vararg args: Any): String = KaPlayText.format(key, *args)

    /** m:ss */
    fun clock(ms: Long): String {
        val seconds = (maxOf(0L, ms) / 1000).toInt()
        return String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
    }

    // MARK: - Rooms, rounds and seats

    /** A game's place in its room, as a title: "1v1", "Round 1", "Semifinal", "Final". */
    fun roundTitle(round: Int, isDuel: Boolean): String {
        if (isDuel) return string("1v1")
        return when (round) {
            3 -> string("Final")
            2 -> string("Semifinal")
            else -> string("Round 1")
        }
    }

    /** "Move 12 · white to move". */
    fun moveLine(game: ChessTournamentGame): String {
        val number = game.moves.size / 2 + 1
        return if (game.sideToMove == ChessColor.WHITE) {
            format("Move %d · white to move", number)
        } else {
            format("Move %d · black to move", number)
        }
    }

    fun seatsLeft(count: Int): String =
        if (count == 1) string("1 seat left") else format("%d seats left", count)

    fun playersWaiting(count: Int, capacity: Int): String =
        if (count == 1) format("1 of %d players waiting", capacity) else format("%d of %d players waiting", count, capacity)

    /** A room's row: "3 of 8 players", with its code when it's private. */
    fun playerCount(tournament: ChessTournament): String {
        val count = tournament.players.size
        if (tournament.isPublic) {
            return if (count == 1) {
                format("1 of %d players", tournament.capacity)
            } else {
                format("%d of %d players", count, tournament.capacity)
            }
        }
        return if (count == 1) {
            format("1 of %d players · code %s", tournament.capacity, tournament.id)
        } else {
            format("%d of %d players · code %s", count, tournament.capacity, tournament.id)
        }
    }

    /** "You're waiting in Public 1v1 #4" / "You're playing in …". */
    fun whereYouAre(tournament: ChessTournament): String =
        if (tournament.status == ChessTournament.Status.OPEN) {
            format("You're waiting in %s", tournament.displayName)
        } else {
            format("You're playing in %s", tournament.displayName)
        }

    /** The text a private room's share button sends: where the code goes, one sentence per kind. */
    fun invite(tournament: ChessTournament): String =
        if (tournament.isDuel) {
            format("Play me at chess in K: open KaPlay > Chess > 1v1 > Join with a code, and enter %s.", tournament.id)
        } else {
            format("Join my chess tournament in K: open KaPlay > Chess > Tournaments > Join with a code, and enter %s.", tournament.id)
        }

    // MARK: - Results

    /** A finished game in a list: "alice won by checkmate". */
    fun resultSummary(game: ChessTournamentGame, now: Long, name: (String) -> String): String {
        val winner = game.winner
        val outcome = game.outcome
        if (winner == null || outcome == null) {
            // Out of clock, claim not posted: the same result, said the same way.
            game.decidedWinner(now)?.let { return format("%s won on time", name(it)) }
            return string("finished")
        }
        val who = name(winner)
        return when (outcome) {
            ChessTournamentOutcome.Checkmate -> format("%s won by checkmate", who)
            ChessTournamentOutcome.Resignation -> format("%s won by resignation", who)
            ChessTournamentOutcome.Timeout -> format("%s won on time", who)
            is ChessTournamentOutcome.DrawTiebreak -> when (outcome.reason) {
                "stalemate" -> format("%s won on clock after a draw by stalemate", who)
                "insufficient material" -> format("%s won on clock after a draw by insufficient material", who)
                "fifty-move rule" -> format("%s won on clock after a draw by the fifty-move rule", who)
                "threefold repetition" -> format("%s won on clock after a draw by threefold repetition", who)
                else -> format("%s won on clock after a draw", who)
            }
        }
    }

    /** The board's and the result screen's sentence: "Checkmate. alice won." */
    fun resultSentence(who: String, outcome: ChessTournamentOutcome): String = when (outcome) {
        ChessTournamentOutcome.Checkmate -> format("Checkmate. %s won.", who)
        ChessTournamentOutcome.Resignation -> format("%s won by resignation.", who)
        ChessTournamentOutcome.Timeout -> format("%s won on time.", who)
        is ChessTournamentOutcome.DrawTiebreak -> when (outcome.reason) {
            "stalemate" -> format("Draw by stalemate. %s won on clock.", who)
            "insufficient material" -> format("Draw by insufficient material. %s won on clock.", who)
            "fifty-move rule" -> format("Draw by the fifty-move rule. %s won on clock.", who)
            "threefold repetition" -> format("Draw by threefold repetition. %s won on clock.", who)
            else -> format("Draw. %s won on clock.", who)
        }
    }

    /** Under the end overlay's headline. */
    fun outcomeDetail(outcome: ChessTournamentOutcome): String = when (outcome) {
        ChessTournamentOutcome.Checkmate -> string("Checkmate")
        ChessTournamentOutcome.Resignation -> string("By resignation")
        ChessTournamentOutcome.Timeout -> string("On time")
        is ChessTournamentOutcome.DrawTiebreak -> when (outcome.reason) {
            "stalemate" -> string("Draw by stalemate - won on clock")
            "insufficient material" -> string("Draw by insufficient material - won on clock")
            "fifty-move rule" -> string("Draw by the fifty-move rule - won on clock")
            "threefold repetition" -> string("Draw by threefold repetition - won on clock")
            else -> string("Draw - won on clock")
        }
    }
}

/**
 * The room's name as people read it, in the app's language. [ChessTournament.name] is the
 * reducer's (English, the same on every phone); this is only what's shown (iOS 077d777).
 */
val ChessTournament.displayName: String
    get() {
        ChessTournamentCodec.publicNumber(id)?.let { return ChessText.format("Public tournament #%d", it) }
        ChessTournamentCodec.duelNumber(id)?.let { return ChessText.format("Public 1v1 #%d", it) }
        return ChessText.string(if (isDuel) ChessTournamentCodec.PRIVATE_DUEL_NAME else ChessTournamentCodec.PRIVATE_TOURNAMENT_NAME)
    }

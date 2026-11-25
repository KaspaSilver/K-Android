package com.kachat.app.services.chess

import android.net.Uri
import com.kachat.app.util.ChessBot
import com.kachat.app.util.KaspaAddress
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Someone's win against a bot, from the Challenge button on their post: beat [bot] in fewer than
 * [moves] (iOS ChessBotChallenge, ddec987).
 */
data class ChessBotChallenge(
    val bot: ChessBot,
    val moves: Int?,
    /** The address that won. */
    val by: String?,
) {
    /** For a navigation route's `challenge` argument; [fromRouteArg] reads it back. */
    fun routeArg(): String = Uri.encode("${bot.level},${moves ?: ""},${by ?: ""}")

    companion object {
        fun fromRouteArg(arg: String?): ChessBotChallenge? {
            val parts = arg?.split(",")?.takeIf { it.size == 3 } ?: return null
            val bot = parts[0].toIntOrNull()?.let { ChessBot.forLevel(it) } ?: return null
            return ChessBotChallenge(bot, parts[1].toIntOrNull(), parts[2].ifEmpty { null })
        }
    }
}

/** A challenge link waiting for the KaPlay tab, which may not be up yet (a cold start, or a link
 *  opened before signing in). The KaPlay host ([com.kachat.app.ui.screens.chess.GamesScreen], then ChessHomeScreen)
 *  takes it (iOS ChessBotLinkRouter). */
object ChessBotLinkRouter {
    val challenge = MutableStateFlow<ChessBotChallenge?>(null)
}

/**
 * The two links of a bot win (KaPlay's ONLINE_CHESS.md §7): the post sharing it, written for the
 * KaPost composer, and the challenge link in that post. KaPlay's `kachatchess://` link is kept as
 * it is, so posts from KaPlay and from KaChat read the same; in KaChat its card
 * (`KaPostBotChallengeCard`) opens the game here, not KaPlay (iOS 148a5331).
 */
object ChessBotLinks {
    const val SCHEME = "kachatchess"

    private val _openGames = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** A bot challenge to play (iOS `.openGames`): the dock shows the KaPlay tab, wherever it
     *  lives, closing any covers over KaPost first; [ChessBotLinkRouter.challenge] says which. */
    val openGames: SharedFlow<Unit> = _openGames.asSharedFlow()

    /** `kachatchess://bot/<level>?name=<bot>&moves=<n>&by=<address>&game=<game id>` */
    fun challengeUrl(bot: ChessBot, moves: Int, by: String, gameId: String): String =
        Uri.Builder()
            .scheme(SCHEME)
            .authority("bot")
            .appendPath(bot.level.toString())
            .appendQueryParameter("name", bot.name)
            .appendQueryParameter("moves", moves.toString())
            .appendQueryParameter("by", by)
            .appendQueryParameter("game", gameId)
            .build()
            .toString()

    /** Links come from other people's posts, so only the level has to be right; a bad `moves` or
     *  `by` just leaves that part out of the challenge. */
    fun challenge(uri: Uri): ChessBotChallenge? {
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("bot", ignoreCase = true)) return null
        val bot = uri.lastPathSegment?.toIntOrNull()?.let { ChessBot.forLevel(it) } ?: return null
        val moves = uri.getQueryParameter("moves")?.toIntOrNull()?.takeIf { it in 1..999 }
        val by = uri.getQueryParameter("by")?.takeIf { KaspaAddress.isValid(it) }
        return ChessBotChallenge(bot, moves, by)
    }

    /** The post Share to KaPost writes after a win: the boast and the challenge link (iOS 518fdc8c
     *  `shareText`). KaPlay slides KaPost's composer up over the game with it. */
    fun shareText(bot: ChessBot, moves: Int, by: String, gameId: String): String {
        val challenge = challengeUrl(bot, moves, by, gameId)
        // One whole sentence, the bot's name and the numbers filled in (iOS 077d777).
        val boast = com.kachat.app.util.ChessText.format(
            "I beat %s (Level %d) in %d moves on K. Think you can do it in fewer?", bot.name, bot.level, moves,
        )
        return "$boast\n\n$challenge"
    }

    /** A challenge card's button in a post, or a `kachatchess://` link opened from anywhere: the
     *  KaPlay tab, wherever it lives, opens the bot (iOS `ChessBotLinks.open`). False when [uri]
     *  is not a challenge link. */
    fun open(uri: Uri): Boolean {
        val challenge = challenge(uri) ?: return false
        ChessBotLinkRouter.challenge.value = challenge
        _openGames.tryEmit(Unit)
        return true
    }

    /** [open] for a link as text. */
    fun open(url: String): Boolean = runCatching { Uri.parse(url) }.getOrNull()?.let { open(it) } ?: false
}

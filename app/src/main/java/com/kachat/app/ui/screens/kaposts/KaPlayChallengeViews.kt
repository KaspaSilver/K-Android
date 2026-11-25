package com.kachat.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.kachat.app.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kachat.app.ui.theme.KaspaTeal
import com.kachat.app.ui.theme.LocalAppColors

/**
 * A bot challenge from Chess (KaChat's, or KaPlay's), carried in a post (iOS 9e22075 `KaPlayBotChallenge`):
 *
 * `kachatchess://bot/<level>?name=<bot name>&moves=<n>&by=<address>&game=<game id>`
 *
 * `level` is 1 to 5 and is what makes it a challenge; a link without a valid one stays plain text.
 * Everything else is optional and attacker-controlled like any link in a post, so each value is
 * checked and a bad one dropped, and [url] is rebuilt from what passed: the card's button is the
 * one place KaPost opens this scheme, and it only ever opens this exact shape.
 */
data class KaPlayBotChallenge(
    val level: Int,
    val botName: String?,
    val moves: Int?,
    val url: String,
) {
    /** "Beat Coderofstuff (Level 3) in 14 moves", or "Challenge: beat Coderofstuff (Level 3)"
     *  without a move count. */
    fun title(context: Context): String {
        // in the app's language (iOS 369794e)
        val bot = botName?.let { context.getString(com.kachat.app.R.string.kn53_kaplay_bot_named, it, level) }
            ?: context.getString(com.kachat.app.R.string.kn53_kaplay_bot_level, level)
        val count = moves ?: return context.getString(com.kachat.app.R.string.kn53_kaplay_challenge_beat, bot)
        return context.getString(
            if (count == 1) com.kachat.app.R.string.kn53_kaplay_beat_in_move else com.kachat.app.R.string.kn53_kaplay_beat_in_moves,
            bot, count
        )
    }

    /** The first valid challenge in a post's text, and the exact substring it was found as. */
    data class Match(val challenge: KaPlayBotChallenge, val matchedText: String)

    companion object {
        const val SCHEME = "kachatchess"
        private const val NAME_LIMIT = 40
        private val LINK = Regex("""kachatchess://bot/[^\s<>"']+""", RegexOption.IGNORE_CASE)
        /** Trailing prose punctuation is not part of the link ("... kachatchess://bot/3."). */
        private const val TRAILING_TRIM = ".,;:!?)]}'\""

        fun parse(source: Uri): KaPlayBotChallenge? {
            if (!source.scheme.equals(SCHEME, ignoreCase = true) || !source.host.equals("bot", ignoreCase = true)) return null
            // A hierarchical link only: getQueryParameter throws on anything else.
            if (!source.isHierarchical) return null
            val parts = source.pathSegments
            // Digits only: toIntOrNull alone also takes "+3".
            val level = parts.singleOrNull()?.takeIf { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }?.toIntOrNull()
                ?: return null
            if (level !in 1..5) return null

            val botName = source.getQueryParameter("name")?.let(::cleanName)?.takeIf { it.isNotEmpty() }
            val moves = source.getQueryParameter("moves")
                ?.takeIf { raw -> raw.isNotEmpty() && raw.all { it in '0'..'9' } }
                ?.toIntOrNull()
                ?.takeIf { it in 1..9_999 }
            val by = source.getQueryParameter("by")?.takeIf { com.kachat.app.util.KaspaAddress.isValid(it) }
            val game = source.getQueryParameter("game")?.takeIf { raw ->
                raw.isNotEmpty() && raw.length <= 128 &&
                    raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
            }

            val rebuilt = Uri.Builder().scheme(SCHEME).authority("bot").appendPath(level.toString())
            botName?.let { rebuilt.appendQueryParameter("name", it) }
            moves?.let { rebuilt.appendQueryParameter("moves", it.toString()) }
            by?.let { rebuilt.appendQueryParameter("by", it) }
            game?.let { rebuilt.appendQueryParameter("game", it) }
            return KaPlayBotChallenge(level, botName, moves, rebuilt.build().toString())
        }

        /** No control or direction marks, which could make the card read as something else. */
        private fun cleanName(raw: String): String {
            val kept = buildString {
                var index = 0
                while (index < raw.length) {
                    val point = raw.codePointAt(index)
                    val type = Character.getType(point).toByte()
                    if (type != Character.CONTROL && type != Character.FORMAT) appendCodePoint(point)
                    index += Character.charCount(point)
                }
            }
            return kept.trim().take(NAME_LIMIT)
        }

        fun match(text: String): Match? {
            if (!text.contains("kachatchess://", ignoreCase = true)) return null
            LINK.findAll(text).forEach { found ->
                val candidate = found.value.trimEnd { it in TRAILING_TRIM }
                val challenge = runCatching { parse(Uri.parse(candidate)) }.getOrNull()
                if (challenge != null) return Match(challenge, candidate)
            }
            return null
        }
    }
}

/** A challenge opens the bot game here, in KaPlay (since 2026-11-21, when chess came back to
 *  KaChat; iOS 148a5331 `KaPlayHandoff.open` -> `ChessBotLinks.open`). */
object KaPlayHandoff {
    fun open(challenge: KaPlayBotChallenge) {
        com.kachat.app.services.chess.ChessBotLinks.open(challenge.url)
    }
}

/** iOS `crown.fill`: Material has no crown. */
private val KaPlayCrown: ImageVector by lazy {
    ImageVector.Builder("KaPlayCrown", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(3f, 7.5f)
            lineTo(7.5f, 11.5f)
            lineTo(12f, 4.5f)
            lineTo(16.5f, 11.5f)
            lineTo(21f, 7.5f)
            lineTo(19f, 19.5f)
            lineTo(5f, 19.5f)
            close()
        }
    }.build()
}

/** A bot challenge link in a KaPost, drawn as a card instead of the link's long text. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun KaPostBotChallengeCard(challenge: KaPlayBotChallenge, modifier: Modifier = Modifier) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = com.kachat.app.util.rememberHaptics()
    var showMenu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val opensChessHint = stringResource(R.string.kpui_opens_chess_bot)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KaspaTeal.copy(alpha = 0.08f))
            .border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .combinedClickable(
                role = Role.Button,
                onLongClick = { showMenu = true },
                onClick = {
                    haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                    KaPlayHandoff.open(challenge)
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = opensChessHint }
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(KaPlayCrown, contentDescription = null, tint = KaspaTeal, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.kpui_chess_challenge).uppercase(), color = KaspaTeal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    challenge.title(androidx.compose.ui.platform.LocalContext.current),
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(KaspaTeal)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.kpui_challenge), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
    if (showMenu) {
        ActionSheetContainer(title = stringResource(R.string.kpui_chess_challenge), subtitle = null, onDismiss = { showMenu = false }) {
            ActionSheetRow(
                icon = Icons.Filled.ContentCopy,
                title = stringResource(R.string.copy_link),
                subtitle = stringResource(R.string.copy_link_subtitle),
            ) {
                clipboard.setText(AnnotatedString(challenge.url))
                haptic(com.kachat.app.util.IosHaptic.SUCCESS)
                showMenu = false
            }
        }
    }
}

/**
 * An arcade score challenge in a KaPost (`kachatarcade://`, ARCADE.md §6; iOS 518fdc8c
 * `KaPostArcadeChallengeCard`), drawn as a card. Play opens that game in KaPlay with the score to
 * beat. [author] is the post's author: the game names the challenger only when it is the link's
 * `by` (audit 2026-11-24 #21). [enabled] false is the composer's preview, whose Play does nothing.
 */
@Composable
fun KaPostArcadeChallengeCard(
    challenge: com.kachat.app.services.arcade.ArcadeChallenge,
    modifier: Modifier = Modifier,
    author: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalAppColors.current
    val context = LocalContext.current
    val haptic = com.kachat.app.util.rememberHaptics()
    val shape = RoundedCornerShape(16.dp)
    val title = androidx.compose.runtime.remember(challenge.url) {
        com.kachat.app.ui.screens.arcade.ArcadeText.challengeTitle(context, challenge)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(KaspaTeal.copy(alpha = 0.08f))
            .border(1.dp, KaspaTeal.copy(alpha = 0.35f), shape)
            .then(
                if (enabled) {
                    Modifier.clickable(role = Role.Button) {
                        haptic(com.kachat.app.util.IosHaptic.IMPACT_LIGHT)
                        com.kachat.app.services.arcade.ArcadeLinkRouter.open(challenge.url, author)
                    }
                } else Modifier
            )
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(KaspaTeal.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            com.kachat.app.ui.screens.arcade.ArcadeGameIcon(challenge.game, 20.dp, KaspaTeal)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.arcade_challenge_label).uppercase(),
                color = KaspaTeal, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .clip(CircleShape)
                .background(KaspaTeal)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.kpui_play), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/**
 * A KaPlay challenge link (a bot's or an arcade score's) a post being written carries (iOS
 * 518fdc8c `KaPostComposerView.attachedChallenge`): taken out of the text and shown as the card
 * the feed will draw, so the composer previews the post rather than showing the raw link (owner,
 * 2026-11-24); it goes back on the end when posted. One challenge a post: the feed draws only the
 * first.
 */
object KaPostComposerChallenge {
    /** The challenge link in [text] (an arcade score's first, then a bot's), and the text without
     *  it; null when there is none. */
    fun detach(text: String): Pair<String, String>? {
        val matched = com.kachat.app.services.arcade.ArcadeChallenge.match(text)?.matchedText
            ?: KaPlayBotChallenge.match(text)?.matchedText
            ?: return null
        return matched to text.replace(matched, "").trim()
    }

    /** What is posted: the text, with the attached link on the end. */
    fun postBody(text: String, link: String?): String {
        val trimmed = text.trim()
        link ?: return trimmed
        return if (trimmed.isEmpty()) link else "$trimmed\n\n$link"
    }
}

/** The attached challenge's card under the composer's editor, with an x that takes it off the
 *  post. A preview: its button does nothing here. */
@Composable
fun KaPostComposerChallengeCard(link: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val removeLabel = stringResource(R.string.remove)
    Box(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp)) {
        val arcade = com.kachat.app.services.arcade.ArcadeChallenge.match(link)
        if (arcade != null) {
            KaPostArcadeChallengeCard(arcade.challenge, enabled = false)
        } else {
            KaPlayBotChallenge.match(link)?.let { bot ->
                KaPostBotChallengeCard(bot.challenge)
                // a preview: a cover over the card takes its taps
                Box(Modifier.matchParentSize().pointerInput(Unit) { detectTapGestures { } })
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeLabel },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

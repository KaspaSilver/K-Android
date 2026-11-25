package com.kachat.app.util

import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesRegistry

/**
 * @mentions by .kachat username (owner, 2026-11-24; iOS `UsernameMention`,
 * Views/Shared/UsernameMentions.swift): in group chats, public chats and KaPost you can @ someone
 * only if they have a username. One set of rules for all three:
 * - group chats keep their wire format: the mention goes out as `@<address>` ([encodeGroupMentions]),
 *   so it survives a rename, and shows as the member's name;
 * - public chats and KaPost are public text: the mention is the plain `@username` (KaPost also
 *   signs the mentioned keys, `KaPostsViewModel.mentionedPubkeys`);
 * - a token is only a mention when it is a valid username (`a-z 0-9 -`, 1-32, no hyphen at either
 *   end, an optional `.kachat` ending); `@word` that can't be a username stays text.
 *
 * The token rules here are pure (unit tested); [handle], [myName] and [mentionsMe] read the
 * registry's cached identities.
 */
object UsernameMention {
    /** `@alice` / `@alice.kachat` after a start of text, a space or an opening bracket or quote.
     *  `(?U)`: a space is any Unicode space, as NSRegularExpression's `\s` is. */
    private val TOKEN_REGEX = Regex(
        """(?U)(^|[\s(\[{<"'])@([A-Za-z0-9-]{1,32})(\.(?i:kachat))?(?![A-Za-z0-9-])""",
    )

    /** One @token: where `@name` (with any ending) sits in the text, and the bare username,
     *  lowercased. */
    data class Token(val range: IntRange, val name: String)

    /** Every username-shaped @token in [text]. Cheap when there's no "@". */
    fun tokens(text: String): List<Token> {
        if (!text.contains('@')) return emptyList()
        return TOKEN_REGEX.findAll(text).mapNotNull { match ->
            val nameGroup = match.groups[2] ?: return@mapNotNull null
            val name = nameGroup.value.lowercase()
            if (!KachatNames.Codec.isValid(name)) return@mapNotNull null
            val start = nameGroup.range.first - 1 // the "@"
            val end = match.groups[3]?.range?.last ?: nameGroup.range.last
            Token(start..end, name)
        }.toList()
    }

    /** Whether public [text] @'s [myName] (a bare username) by its username. */
    fun mentions(text: String, myName: String?): Boolean {
        val me = myName?.takeIf { it.isNotEmpty() } ?: return false
        return tokens(text).any { it.name == me }
    }

    /** The @token being typed at the end of [text] ("" right after the "@"), lowercased, or null
     *  (iOS `typedQuery(in:)`). */
    fun typedQuery(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        val before = text.getOrNull(at - 1)
        if (before != null && !before.isWhitespace() && before !in "([{<\"'") return null
        val query = text.substring(at + 1)
        if (query.codePointCount(0, query.length) > 32) return null
        if (!query.all { it.isLetterOrDigit() || it == '-' }) return null
        return query.lowercase()
    }

    /**
     * Where the unclosed "@query" run ending at [cursor] starts (the "@"), or null - e.g. with the
     * cursor right after "...hey @ali", the index of that "@". The run goes back to the last
     * whitespace (iOS `ComposerTextView.mentionTokenRange`).
     */
    fun composerTokenStart(text: String, cursor: Int): Int? {
        if (cursor < 0 || cursor > text.length) return null
        var start = cursor
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        return if (start < cursor && text[start] == '@') start else null
    }

    /**
     * Group text as it goes on the wire: each member's `@alice` or `@alice.kachat` (any case)
     * becomes `@<their address>` ([named]: bare username -> address). A name that isn't a member's
     * stays text. iOS `GroupChatDetailView.encodeMentions` replaces name by name, longest first;
     * here it is one pass over the text, which finds the same mentions and can't re-match a name
     * inside an address it has just put in (a member called "kaspa" and "@kaspa:qq…").
     */
    fun encodeGroupMentions(text: String, named: Map<String, String>): String {
        if (named.isEmpty() || !text.contains('@')) return text
        val byName = named.mapKeys { it.key.lowercase() }
        return GROUP_ENCODE_REGEX.replace(text) { match ->
            byName[match.groupValues[1].lowercase()]?.let { "@$it" } ?: match.value
        }
    }

    /** `@name` with an optional ending, as [encodeGroupMentions] looks for it (iOS: no rule for
     *  what comes before the "@"). */
    private val GROUP_ENCODE_REGEX = Regex("""@([A-Za-z0-9-]{1,32})(\.kachat)?(?![A-Za-z0-9-])""", RegexOption.IGNORE_CASE)

    /** A group mention on the wire: `@kaspa:qq…` / `@kaspatest:qq…`. */
    private val ADDRESS_MENTION_REGEX = Regex("""@(kaspa(?:test)?:[a-z0-9]{40,90})""")

    /**
     * Group text with each `@<address>` mention as `@<name>` (iOS NotificationService
     * `mentionsAsNames`, 2026-11-24): what a banner says. [nameFor] gives the name this phone
     * knows them by.
     */
    suspend fun mentionsAsNames(text: String, nameFor: suspend (String) -> String): String {
        if (!text.contains("@kaspa")) return text
        val matches = ADDRESS_MENTION_REGEX.findAll(text).toList()
        if (matches.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        for (match in matches) {
            out.append(text, cursor, match.range.first)
            out.append('@').append(nameFor(match.groupValues[1]))
            cursor = match.range.last + 1
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    // The registry's cached identities

    /**
     * The username someone is @'d by, as the app shows names (with or without the ending, per
     * Show .kachat Endings): null when they have none, so they can't be mentioned.
     */
    fun handle(address: String): String? {
        val label = KachatNamesRegistry.cachedIdentityOf(address)?.label ?: return null
        val name = KachatNames.Codec.normalize(label)
        if (!KachatNames.Codec.isValid(name)) return null
        return com.kachat.app.models.KachatNameLabel.display(name)
    }

    /** The bare username of [myAddress] (the signed-in account), if it has one. */
    fun myName(myAddress: String?): String? {
        if (myAddress.isNullOrBlank()) return null
        val label = KachatNamesRegistry.cachedIdentityOf(myAddress)?.label ?: return null
        return KachatNames.Codec.normalize(label)
    }

    /** Whether public text @'s the signed-in account by its username. */
    fun mentionsMe(text: String, myAddress: String?): Boolean = mentions(text, myName(myAddress))
}

/**
 * Who KaPost's @ suggests (iOS `KaPostMentionSources`, 157790b0; 2026-11-24: it offered chat
 * contacts only, so one person): the people you follow, people whose posts you've seen lately,
 * then your contacts - only those with a username, never you - plus a name looked up as you type.
 */
object KaPostMentionSources {
    private const val KEEP = 300
    private const val CONSIDER = 200
    private const val MAX = 30

    private val lock = Any()
    private val recent = ArrayList<String>()

    /** A poster whose post was read (the feed, threads, profiles - every post goes through
     *  `KaPostsViewModel.mapRemotePost`): offered by the @. Newest first, [KEEP] kept. */
    fun notePoster(address: String) {
        val key = address.lowercase()
        synchronized(lock) {
            recent.remove(key)
            recent.add(0, key)
            if (recent.size > KEEP) recent.subList(KEEP, recent.size).clear()
        }
    }

    /** The posters [notePoster] has seen, newest first. */
    fun recentPosters(): List<String> = synchronized(lock) { recent.toList() }

    /** Forgets the recent posters (tests). */
    internal fun clearRecent() = synchronized(lock) { recent.clear() }

    /**
     * The usernames to offer for [query] (what's typed after the "@"), as K shows names: each
     * address considered once - follows (sorted), the newest [CONSIDER] recent posters, then
     * contacts - those [handleOf] names and whose name starts with the typed text, [MAX] at most,
     * with [extra] (a name looked up live, already as K shows it) first when it matches too.
     * Pure: [handleOf] is [UsernameMention.handle] in the app.
     */
    fun handles(
        query: String,
        extra: String?,
        me: String?,
        following: Collection<String>,
        recentPosters: List<String>,
        contacts: List<String>,
        handleOf: (String) -> String?,
    ): List<String> {
        val myKey = me?.lowercase()
        val typed = KachatNames.Codec.normalize(query)
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        fun consider(address: String) {
            val key = address.lowercase()
            if (key == myKey || !seen.add(key)) return
            val handle = handleOf(address) ?: return
            if (typed.isNotEmpty() && !KachatNames.Codec.normalize(handle).startsWith(typed)) return
            if (handle !in out) out += handle
        }
        for (address in following.sorted()) consider(address)
        for (address in recentPosters.take(CONSIDER)) consider(address)
        for (address in contacts) consider(address)
        val result = out.take(MAX).toMutableList()
        if (extra != null && extra !in result &&
            (typed.isEmpty() || KachatNames.Codec.normalize(extra).startsWith(typed))
        ) {
            result.add(0, extra)
        }
        return result
    }
}

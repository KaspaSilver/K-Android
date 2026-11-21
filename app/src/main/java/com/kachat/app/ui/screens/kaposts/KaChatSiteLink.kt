package com.kachat.app.ui.screens

/**
 * A link to KaChat's own site (`home.kachat.app`, any page, iOS f81c467 `KaChatSiteLink`). Unlike a
 * stranger's link it previews by itself: fetching it tells nobody but us who is reading, so a post
 * draws it as a card, and a public chat room previews it for a sender with a `.kachat` name
 * (iOS b78fdb0).
 */
object KaChatSiteLink {
    const val HOST = "home.kachat.app"

    /** Exactly this host over the web. `home.kachat.app.evil.com` and `home.kachat.app@evil.com`
     *  are other hosts, and so is any port. */
    fun isSiteLink(url: String): Boolean {
        val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return false
        return uri.host.equals(HOST, ignoreCase = true) && uri.userInfo == null && uri.port == -1
    }

    /** [matchedText] is the exact substring matched, which a post leaves out of its text. */
    data class Match(val url: String, val matchedText: String)

    /** With or without a scheme, as "home.kachat.app/news/" is how people write it. The lookbehind
     *  and lookahead keep it from matching inside a longer host, an email or another site's path. */
    private val LINK = Regex(
        """(?<![\w.@/-])(?:https?://)?home\.kachat\.app(?![\w@:-]|\.\w)(?:[/?#][^\s<>"']*)?""",
        RegexOption.IGNORE_CASE,
    )
    /** Trailing prose punctuation is not part of the link ("... home.kachat.app/news/."). */
    private const val TRAILING_TRIM = ".,;:!?)]}'\"*"

    /** The first link to the site in a post's text. */
    fun match(text: String): Match? {
        if (!text.contains(HOST, ignoreCase = true)) return null
        LINK.findAll(text).forEach { found ->
            val candidate = found.value.trimEnd { it in TRAILING_TRIM }
            val url = if (candidate.startsWith("http", ignoreCase = true)) candidate else "https://$candidate"
            if (isSiteLink(url)) return Match(url, candidate)
        }
        return null
    }
}

package com.kachat.app.util

/** A detected link: [range] is where it sits in the original text, [uri] is what to actually open (scheme added if the displayed text didn't have one). */
data class UrlMatch(val range: IntRange, val uri: String)

/** Finds every URL-shaped substring in a message, no Compose/Android dependency so it's directly unit-testable. */
object TextLinkify {
    // Deliberately excludes ".kas" — that's a KNS domain identifier, not a real web address;
    // opening it as a URL would just fail. Covers common general + this app's own ecosystem TLDs.
    private const val TLDS = "com|org|net|io|co|dev|app|xyz|info|biz|me|tv|gg|ai|edu|gov|us|uk|ca|de|fr|jp|cn|ru|in|au|br|link|shop|store|tech|online|site|fyi|wtf"

    private const val LABEL = """[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?"""

    // `kachat://` is matched too, so an in-app link pasted into any chat is linkified rather
    // than left as dead text. Recognition of WHICH in-app link it is, and routing it in place
    // instead of out to a browser, is the caller's job (see KaChatLink).
    private val URL_REGEX = Regex(
        """kachat://\S+|https?://\S+|(?:www\.)?$LABEL(?:\.$LABEL)*\.(?:$TLDS)\b(?:/\S*)?""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '\'', '"', ')', ']', '}', '>')

    /** Links found per text: every chat bubble asks two or three times as it comes on screen
     *  (the styled text, the preview card, "is it only a link"), and again whenever it scrolls back
     *  (iOS d089011f `LinkifiedMessageTextView.sharedAttributed`, 2026-11-25 smoothness sweep).
     *  Texts above [MEMO_MAX_LENGTH] are scanned each time rather than held. */
    private val urlMemo = RenderMemo<String, List<UrlMatch>>(maxCount = 500)
    private const val MEMO_MAX_LENGTH = 4_000

    fun findUrls(text: String): List<UrlMatch> {
        if (text.isEmpty()) return emptyList()
        if (text.length > MEMO_MAX_LENGTH) return scanUrls(text)
        return urlMemo.getOrPut(text) { scanUrls(text) }
    }

    private fun scanUrls(text: String): List<UrlMatch> {
        return URL_REGEX.findAll(text).mapNotNull { match ->
            var end = match.range.last
            while (end >= match.range.first && text[end] in TRAILING_PUNCTUATION) end--
            if (end < match.range.first) return@mapNotNull null

            val range = match.range.first..end
            val display = text.substring(range.first, range.last + 1)
            val uri = if (display.contains("://")) display else "https://$display"
            UrlMatch(range, uri)
        }.toList()
    }

    /** True when [text] (trimmed) is nothing but a single link - mirrors iOS's
     *  `MessageTextRenderPlan.isEntirelyLink`, used so callers can show only the rich preview
     *  card instead of both a redundant raw-link bubble and the card stacked on top of it. */
    fun isEntirelyLink(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        // the first link of the trimmed text, trailing punctuation off, through the memo above
        // (the same text, untrimmed, is usually the same string, so it's found once)
        val match = findUrls(trimmed).firstOrNull() ?: return false
        return match.range.first == 0 && match.range.last == trimmed.length - 1
    }
}

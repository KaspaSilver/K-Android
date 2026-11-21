package com.kachat.app.services

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Which web links in a KaPost may draw a preview card (owner's call, 2026-11-18, iOS b78fdb0
 * `KaPostLinkPreviewPolicy`). Only a poster with a `.kachat` name gets previews at all, and then only
 * for popular, mainstream sites on this list, so a preview never fetches from an unknown server or
 * shows adult content. Anything else stays a plain link. A host matches itself and its subdomains
 * (www., m., mobile.). The same list holds for every hop: a redirect is followed only to an allowed
 * https page ([allowsRedirect]), and a preview's image loads only from the site or a known image
 * server of it ([allowsImage]).
 *
 * Every endpoint a preview fetches is on [allowedDomains] itself, so there is no list of extra
 * endpoints: YouTube's oEmbed (`https://www.youtube.com/oembed`) is youtube.com; Instagram and
 * Facebook are scraped at the link itself, only with Meta's crawler User-Agent; X has no special
 * endpoint. A new special endpoint may only be the platform's own domain, listed here explicitly.
 */
object KaPostLinkPreviewPolicy {
    val allowedDomains: Set<String> = setOf(
        // social and video
        "x.com", "twitter.com", "youtube.com", "youtu.be", "instagram.com", "facebook.com",
        "linkedin.com", "tiktok.com", "threads.net",
        // code, reference and writing
        "github.com", "wikipedia.org", "medium.com", "substack.com", "apple.com",
        // news
        "bbc.com", "bbc.co.uk", "reuters.com", "bloomberg.com", "cnbc.com", "cnn.com",
        "nytimes.com", "theguardian.com", "forbes.com", "apnews.com",
        // crypto news and data
        "coindesk.com", "cointelegraph.com", "decrypt.co", "theblock.co",
        "coingecko.com", "coinmarketcap.com",
        // Kaspa and KaChat
        "kaspa.org", "kas.fyi", "kaspa.stream", "kachat.app",
    )

    /**
     * The image servers of the allowed sites, for a preview's `og:image` (and the YouTube
     * thumbnail). Each matches itself and its subdomains. Subdomains of an allowed site need no
     * entry (miro.medium.com, media.cnn.com, imageio.forbes.com, dims.apnews.com,
     * images.cointelegraph.com, img.decrypt.co, coin-images.coingecko.com, s2.coinmarketcap.com).
     * Left out on purpose: shared hosting that serves anyone's files (S3 buckets, sanity.io,
     * arcpublishing.com, raw.githubusercontent.com, googleusercontent.com beyond YouTube's).
     */
    val imageCDNDomains: Set<String> = setOf(
        // X, YouTube, Meta (Instagram, Facebook, Threads), LinkedIn, TikTok
        "twimg.com", "ytimg.com", "ggpht.com", "yt3.googleusercontent.com",
        "cdninstagram.com", "fbcdn.net", "licdn.com", "tiktokcdn.com", "tiktokcdn-us.com",
        // GitHub, Substack, Wikipedia, Apple
        "githubassets.com", "avatars.githubusercontent.com", "repository-images.githubusercontent.com",
        "substackcdn.com", "wikimedia.org", "mzstatic.com",
        // BBC, New York Times, Guardian, CNBC, Bloomberg, The Block
        "bbci.co.uk", "nyt.com", "guim.co.uk", "cnbcfm.com", "bwbx.io", "tbstat.com",
    )

    /** Words that keep a link on an allowed site from previewing anyway (a path or query on a
     *  mainstream host can still point at adult content). Matched anywhere in the decoded,
     *  lowercased URL: none of them sits inside an ordinary word. */
    private val blockedSubstrings = listOf("porn", "xxx", "nsfw", "nude", "hentai", "onlyfans")
    /** Matched only as a whole word (between non-letters), so Sussex, Essex and unisex pass. */
    private val blockedTokens = setOf("sex", "sexy")

    /** [matchedText] is the exact substring matched, which the post leaves out of its text: the
     *  card shows it. [url] is what to fetch and open (a `www.` link gets `http://`, as iOS's link
     *  detector gives it). */
    data class Match(val url: String, val matchedText: String)

    private class MatchBox(val value: Match?)

    /** Same caching as iOS's `matchCache`: this runs from post rows. */
    private val matchCache = android.util.LruCache<String, MatchBox>(2_048)

    /** A link written as a link: http://, https:// or www., not inside a longer word, address or
     *  path. Stops at whitespace, quotes and angle brackets. */
    private val EXPLICIT_LINK = Regex(
        """(?<![\p{L}\p{N}@._/-])(?:https?://|www\.)[^\s<>"']+""",
        RegexOption.IGNORE_CASE,
    )
    /** Trailing prose punctuation is not part of the link ("... see https://x.com/a."). */
    private const val TRAILING_TRIM = ".,;:!?'\"*)]}>"

    /**
     * The first http(s) link in [text] that is written as a link ([isExplicitLink]), with the text
     * it was written as. A bare domain in prose ("I left facebook.com") is skipped, so it stays a
     * word in the text and draws no card.
     */
    fun firstLink(text: String): Match? {
        matchCache.get(text)?.let { return it.value }
        var found: Match? = null
        for (candidate in EXPLICIT_LINK.findAll(text)) {
            val written = trimTrailing(candidate.value)
            if (!isExplicitLink(written)) continue
            val url = if (written.startsWith("www.", ignoreCase = true)) "http://$written" else written
            // No host at all ("https://" and punctuation) isn't a link; anything else is the
            // first link, previewable or not, as iOS's detector.
            if (url.substringAfter("://").isEmpty()) continue
            found = Match(url, written)
            break
        }
        matchCache.put(text, MatchBox(found))
        return found
    }

    /**
     * The first explicit link in [text] if it may draw a card: a KaChat site link
     * ([com.kachat.app.ui.screens.KaChatSiteLink]) or a link [allows] takes; else null. As iOS, only
     * the first explicit link is looked at (posts' `textParts`, rooms' `firstExplicitHTTPLink` +
     * `previews`). Callers decide first whether the poster or sender has a `.kachat` name.
     */
    fun firstPreviewableLink(text: String): String? {
        val url = firstLink(text)?.url ?: return null
        return url.takeIf { com.kachat.app.ui.screens.KaChatSiteLink.isSiteLink(it) || allows(it) }
    }

    /** True when a match was written as a link: it starts with http://, https:// or www. (any
     *  case). Bare domains ("x.com") never preview. */
    fun isExplicitLink(matchedText: String): Boolean {
        val lowered = matchedText.trim().lowercase()
        return lowered.startsWith("http://") || lowered.startsWith("https://") || lowered.startsWith("www.")
    }

    fun allows(url: String): Boolean {
        val parsed = parse(url) ?: return false
        if (!matches(parsed.host, allowedDomains)) return false
        return !hasBlockedWords(url)
    }

    /** A page a preview may fetch: an allowed site or KaChat's own. */
    fun allowsPage(url: String): Boolean =
        allows(url) || com.kachat.app.ui.screens.KaChatSiteLink.isSiteLink(url)

    /** A redirect a page fetch may follow: https, to a page [allowsPage] takes. */
    fun allowsRedirect(url: String): Boolean = isHttps(url) && allowsPage(url)

    /**
     * An image a preview of [page] may load: https only (no http:, file: or data:), no credentials
     * or port, no adult words, from an allowed site, KaChat's own or one of their image servers
     * ([imageCDNDomains]), for a page that is itself allowed. Anything else draws the card without
     * its image. Redirects of an image fetch are held to the same rule.
     */
    fun allowsImage(url: String, page: String): Boolean {
        if (!isHttps(url)) return false
        val parsed = parse(url) ?: return false
        if (!allowsPage(page) || hasBlockedWords(url)) return false
        return matches(parsed.host, allowedDomains) || matches(parsed.host, imageCDNDomains)
    }

    private fun isHttps(url: String): Boolean =
        url.substringBefore("://", missingDelimiterValue = "").equals("https", ignoreCase = true)

    /**
     * The URL as OkHttp will fetch it, or null unless it is http(s) with a host and no user,
     * password or port (iOS `url.user == nil, url.password == nil, url.port == nil`). The authority
     * is also read from the raw text, so an explicit default port (`:443`) or a `user@` that a
     * parser would drop is refused too.
     */
    private fun parse(url: String): okhttp3.HttpUrl? {
        val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https") return null
        val authority = url.substring(scheme.length + 3).takeWhile { it !in "/?#\\" }
        if ('@' in authority) return null
        val hostAndPort = if (authority.startsWith("[")) authority.substringAfter("]", "") else authority
        if (':' in hostAndPort) return null
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (parsed.port != okhttp3.HttpUrl.defaultPort(parsed.scheme)) return null
        if (parsed.host.isEmpty()) return null
        return parsed
    }

    private fun matches(host: String, domains: Set<String>): Boolean {
        val lowered = host.lowercase()
        return domains.any { lowered == it || lowered.endsWith(".$it") }
    }

    /** The URL decoded (twice-encoded too, "%2570orn"), lowercased, then checked: the
     *  unambiguous words anywhere, the rest only as whole words. */
    private fun hasBlockedWords(url: String): Boolean {
        var text = url
        for (pass in 0 until 3) {
            val decoded = removingPercentEncoding(text)
            if (decoded == null || decoded == text) break
            text = decoded
        }
        text = text.lowercase()
        if (blockedSubstrings.any { text.contains(it) }) return true
        val tokens = text.split(Regex("""[^\p{L}\p{N}]+"""))
        return tokens.any { it in blockedTokens }
    }

    /** Foundation's `removingPercentEncoding`: every `%XX` decoded as UTF-8, `+` left alone; null
     *  for a malformed escape or bytes that aren't UTF-8. */
    private fun removingPercentEncoding(text: String): String? {
        if ('%' !in text) return text
        val out = java.io.ByteArrayOutputStream(text.length)
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch == '%') {
                if (index + 2 >= text.length) return null
                val hi = Character.digit(text[index + 1], 16)
                val lo = Character.digit(text[index + 2], 16)
                if (hi < 0 || lo < 0) return null
                out.write(hi * 16 + lo)
                index += 3
            } else {
                out.write(ch.toString().toByteArray(Charsets.UTF_8))
                index++
            }
        }
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            null
        }
    }

    private fun trimTrailing(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val last = raw[end - 1]
            if (last !in TRAILING_TRIM) break
            // A closing bracket the link itself opened stays ("wiki/Foo_(bar)"), as iOS's detector.
            if (last == ')' && raw.substring(0, end - 1).count { it == '(' } > raw.substring(0, end - 1).count { it == ')' }) break
            end--
        }
        return raw.substring(0, end)
    }
}

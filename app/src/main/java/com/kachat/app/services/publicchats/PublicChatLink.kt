package com.kachat.app.services.publicchats

import com.kachat.app.util.MessageProtocol

/**
 * Public Chats room links (iOS 148a5331 `KaChatInternalLink.publicChatRoom`): `kachat://broadcast/<room>`,
 * `kaposts://broadcast/<room>` (KaPost's own scheme, registered here since KaPost retired) and
 * `https://kachat.app/broadcast/<room>`. Kept here rather than in the shared link code so Public
 * Chats adds nothing there; the shared link detection calls [parse] to recognise a room link.
 */
object PublicChatLink {
    private const val WEB_HOST = "kachat.app"

    /** The form that goes out in a share: it previews everywhere and opens the app when installed. */
    fun webUrl(channel: String): String = "https://$WEB_HOST/broadcast/$channel"

    /**
     * The share sheet's text for a room invite (iOS `publicChatRoomShareText`): one human line and
     * the kachat.app link, never a bare kachat:// line. Through the same gate an incoming link goes
     * through, so a share never emits a link this app would refuse (a stray "#" is stripped).
     */
    fun shareText(channel: String): String {
        val normalized = normalizeAndValidateChannel(channel) ?: MessageProtocol.normalizeChannelName(channel)
        return "Join #$normalized on KaChat.\n\n${webUrl(normalized)}"
    }

    /**
     * The single gate every pasted, scanned or linked room name passes before it is joined or
     * opened (iOS `normalizeAndValidateChannel`). Beyond the protocol's own rules (non-empty, at
     * most 36 characters, no whitespace, no colon) it rejects URL-structural characters, "..",
     * and control, bidi and zero-width characters - the way to make an invite render as a
     * different room than the one it joins. A leading "#" is tolerated and stripped. A retired
     * default room's name comes back as its replacement.
     */
    fun normalizeAndValidateChannel(raw: String): String? {
        var trimmed = raw.trim()
        while (trimmed.startsWith("#")) trimmed = trimmed.removePrefix("#")
        val name = MessageProtocol.normalizeChannelName(trimmed)
        if (!MessageProtocol.isValidChannelName(name)) return null
        if (".." in name) return null
        if (name.any { it in "/\\?#%@\"'<>" || it.isISOControl() }) return null
        if (name.any {
                Character.getType(it) == Character.FORMAT.toInt() ||
                    it.code in 0x200B..0x200F || it.code in 0x202A..0x202E || it.code in 0x2066..0x2069 || it.code == 0xFEFF
            }
        ) return null
        // A retired room's name comes back as the room that replaced it ("kachat-bugs" is
        // "report-bugs"), so an old link, a push or a typed name opens the live room on every path
        // (iOS 961fc05a, audit 2026-11-25 C2). The move off the old room and leaving it use
        // MessageProtocol.normalizeChannelName, which keeps the old name.
        return com.kachat.app.models.FeaturedBroadcastChannels.replacingRetired(name)
    }

    /** The room a link names, or null when [raw] isn't a room link or its name fails the gate. */
    fun parse(raw: String): String? {
        val text = raw.trim()
        val lower = text.lowercase()
        val tail = when {
            lower.startsWith("kachat://broadcast/") -> text.substring("kachat://broadcast/".length)
            lower.startsWith("kaposts://broadcast/") -> text.substring("kaposts://broadcast/".length)
            lower.startsWith("https://$WEB_HOST/broadcast/") -> text.substring("https://$WEB_HOST/broadcast/".length)
            lower.startsWith("https://www.$WEB_HOST/broadcast/") -> text.substring("https://www.$WEB_HOST/broadcast/".length)
            lower.startsWith("http://$WEB_HOST/broadcast/") -> text.substring("http://$WEB_HOST/broadcast/".length)
            else -> return null
        }
        // One path segment, nothing after it but an optional trailing slash.
        val segment = tail.substringBefore('?').substringBefore('#').trimEnd('/')
        if (segment.isEmpty() || '/' in segment) return null
        val decoded = runCatching { java.net.URLDecoder.decode(segment, "UTF-8") }.getOrNull() ?: return null
        return normalizeAndValidateChannel(decoded)
    }

    /** The first room link in [text], as (the link as written, the room), or null. */
    fun findFirst(text: String): Pair<String, String>? {
        val match = LINK.find(text) ?: return null
        val room = parse(match.value) ?: return null
        return match.value to room
    }

    private val LINK = Regex(
        """(?:kachat://broadcast/|kaposts://broadcast/|https?://(?:www\.)?kachat\.app/broadcast/)[^\s<>"']+""",
        RegexOption.IGNORE_CASE,
    )
}

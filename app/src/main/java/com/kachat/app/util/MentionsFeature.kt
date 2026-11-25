package com.kachat.app.util

/**
 * @mentions by .kachat username (owner, 2026-11-24; iOS `MentionsFeature`), in group chats, public
 * chats and KaPost: only someone with a username can be @'d ([UsernameMention]). Groups send a
 * mention as `@<address>` and show it as the member's name; rooms and KaPost send the plain
 * `@username` (KaPost also signs the mentioned keys). Every mention entry point checks this
 * switch (it was off from 5.2 until mentions moved from .kas to .kachat): @ suggestions, mentions
 * sent, mention links and "Only Notify if I'm Mentioned".
 */
object MentionsFeature {
    const val ENABLED = true
}

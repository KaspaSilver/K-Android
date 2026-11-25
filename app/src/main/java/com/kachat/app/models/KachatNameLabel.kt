package com.kachat.app.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How a .kachat name is shown: "alice" by default, or "alice.kachat" when Settings >
 * Customization > Show .kachat Endings is on (iOS 94ed3a35 `KachatNameLabel`; off by default since
 * 2026-11-23, iOS 518fdc8c, the marketplace included). Display only:
 * typing, copying and anything sent keep working with either form (the codec strips the ending).
 * A name you gave someone yourself (an alias, an Address Book name) never has the ending.
 */
object KachatNameLabel {
    /**
     * Mirrors AppSettingsRepository's KEY_SHOW_KACHAT_NAME_ENDINGS (collected in
     * KaChatApplication, set straight away by the switch). Compose snapshot state, so every name
     * read through [display] redraws when the switch moves (iOS posts settingsDidChange). Read
     * from memory, never from storage per name drawn (iOS caches it, audit 2026-11-24 #28). Text
     * other people read (a KaPost @mention, a copied name, the Address Book) keeps the ending.
     */
    var showsEnding: Boolean by mutableStateOf(false)

    fun display(name: String): String = if (showsEnding) "$name.kachat" else name
}

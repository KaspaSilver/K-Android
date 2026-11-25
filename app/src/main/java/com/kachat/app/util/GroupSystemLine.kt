package com.kachat.app.util

import android.content.Context
import com.kachat.app.R

/**
 * A membership line as the thread shows it (iOS `GroupSystemLine`, 2026-11-24): the stored English
 * line ("<address> joined the group") in the reader's language, with each address as that
 * person's current name. Lines stored before carry a name or short address, which stays as it is.
 */
object GroupSystemLine {

    enum class Kind(val suffix: String) {
        JOINED(" joined the group"),
        DECLINED(" declined the invite"),
        INVITED(" was invited to the group chat"),
        REMOVED(" was removed from the group chat"),
    }

    private val addressPattern = Regex("kaspa(?:test)?:[a-z0-9]{40,90}")

    /** [stored] for display: [translate] gives a [Kind]'s line in the reader's language with the
     *  person filled in; [name] names an address. */
    fun display(stored: String, translate: (Kind, String) -> String, name: (String) -> String): String {
        for (kind in Kind.entries) {
            if (stored.endsWith(kind.suffix)) {
                val who = stored.dropLast(kind.suffix.length)
                return translate(kind, replacingAddresses(who, name))
            }
        }
        return replacingAddresses(stored, name)
    }

    /** [display] with the app's translations. */
    fun display(context: Context, stored: String, name: (String) -> String): String =
        display(stored, { kind, who -> context.getString(stringRes(kind), who) }, name)

    private fun stringRes(kind: Kind): Int = when (kind) {
        Kind.JOINED -> R.string.ga54_joined_group
        Kind.DECLINED -> R.string.ga54_declined_invite
        Kind.INVITED -> R.string.ga54_invited_to_group
        Kind.REMOVED -> R.string.ga54_removed_from_group
    }

    private fun replacingAddresses(text: String, name: (String) -> String): String {
        if (!text.contains("kaspa")) return text
        return addressPattern.replace(text) { name(it.value) }
    }
}

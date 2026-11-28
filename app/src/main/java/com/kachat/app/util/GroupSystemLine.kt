package com.kachat.app.util

import android.content.Context
import com.kachat.app.R

/**
 * A membership line as the thread shows it (iOS `GroupSystemLine`, 2026-11-24): the stored English
 * line ("<address> joined the group") in the reader's language, with each address as that
 * person's current name. Lines stored before carry a name or short address, which stays as it is.
 * Photo and name changes too, yours and others' (iOS 01f71184).
 */
object GroupSystemLine {

    enum class Kind(val suffix: String) {
        JOINED(" joined the group"),
        DECLINED(" declined the invite"),
        INVITED(" was invited to the group chat"),
        REMOVED(" was removed from the group chat"),
        PHOTO_CHANGED(" changed the group photo"),
        PHOTO_REMOVED(" removed the group photo"),
    }

    /** Your own changes, stored whole ("You changed the group photo"; iOS 01f71184 `ownLines`). */
    enum class OwnLine(val stored: String) {
        PHOTO_CHANGED("You changed the group photo"),
        PHOTO_REMOVED("You removed the group photo"),
    }

    private const val OWN_RENAME_PREFIX = "You changed the group name to \""
    private const val RENAME_INFIX = " changed the group name to \""

    private val addressPattern = Regex("kaspa(?:test)?:[a-z0-9]{40,90}")

    /** [stored] for display: [translate] gives a [Kind]'s line in the reader's language with the
     *  person filled in; [name] names an address. */
    fun display(
        stored: String,
        translate: (Kind, String) -> String,
        name: (String) -> String,
        /** An [OwnLine] in the reader's language. */
        own: (OwnLine) -> String = { it.stored },
        /** A rename line: who (null: you) and the new name (iOS 01f71184). */
        renamed: (who: String?, newName: String) -> String = { who, newName ->
            if (who == null) "$OWN_RENAME_PREFIX$newName\"" else "$who$RENAME_INFIX$newName\""
        },
    ): String {
        OwnLine.entries.firstOrNull { it.stored == stored }?.let { return own(it) }
        // "You changed the group name to "<name>"" / "<who> changed the group name to "<name>""
        val infix = stored.indexOf(RENAME_INFIX)
        if (stored.endsWith("\"") && infix >= 0 && stored.length > infix + RENAME_INFIX.length) {
            val newName = stored.substring(infix + RENAME_INFIX.length, stored.length - 1)
            if (stored.startsWith(OWN_RENAME_PREFIX)) return renamed(null, newName)
            return renamed(replacingAddresses(stored.substring(0, infix), name), newName)
        }
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
        display(
            stored,
            { kind, who -> context.getString(stringRes(kind), who) },
            name,
            own = { line ->
                context.getString(when (line) {
                    OwnLine.PHOTO_CHANGED -> R.string.rs_group_you_changed_photo
                    OwnLine.PHOTO_REMOVED -> R.string.rs_group_you_removed_photo
                })
            },
            renamed = { who, newName ->
                if (who == null) context.getString(R.string.rs_group_you_renamed, newName)
                else context.getString(R.string.rs_group_renamed, who, newName)
            },
        )

    private fun stringRes(kind: Kind): Int = when (kind) {
        Kind.JOINED -> R.string.ga54_joined_group
        Kind.DECLINED -> R.string.ga54_declined_invite
        Kind.INVITED -> R.string.ga54_invited_to_group
        Kind.REMOVED -> R.string.ga54_removed_from_group
        Kind.PHOTO_CHANGED -> R.string.rs_group_changed_photo
        Kind.PHOTO_REMOVED -> R.string.rs_group_removed_photo
    }

    private fun replacingAddresses(text: String, name: (String) -> String): String {
        if (!text.contains("kaspa")) return text
        return addressPattern.replace(text) { name(it.value) }
    }
}

package com.kachat.app.models

/**
 * How a .kachat name is shown: always bare, "alice", never "alice.kachat" (owner, iOS 522fd9f4:
 * the ending is never shown, and the Show .kachat Endings switch is gone). Display only: typing,
 * copying and anything sent keep working with either form (the codec strips the ending).
 */
object KachatNameLabel {
    fun display(name: String): String = name
}

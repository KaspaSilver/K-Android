package com.kachat.app.util

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import java.security.MessageDigest
import java.util.IllegalFormatException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A chess sentence in English, looked up in the language picked in Settings (iOS
 * `AppLocalization.string`): KaPlay's `AppText`, brought over with the chess screens (iOS
 * 148a5331, phase 4). The chess code builds its sentences in code - an error a service throws, a
 * sentence with numbers in it, [ChessText]'s sentences - where a `stringResource` id can't be
 * written in, so each is looked up by its English text.
 *
 * Each sentence is a string resource named after its English text ([resourceName]: `kp_` and 12
 * hex characters of its SHA-1), in `res/values*\/strings_kaplay.xml`, with the iOS translations of
 * the same key. A sentence with no resource comes back as the English it was asked with, as iOS
 * falls back to the key.
 *
 * Formats keep the English key's `%s` / `%d` (iOS `%@` / `%lld`); the translations are stored
 * unformatted (`formatted="false"`) and filled in by [format], in the picked language's locale.
 */
object KaPlayText {
    @Volatile
    private var app: Context? = null

    /** The resources of the picked language, for the language tags they were made for. */
    @Volatile
    private var cached: Pair<String, Context>? = null

    private val ids = ConcurrentHashMap<String, Int>()

    /** From the application (or the first chess service): before a sentence is asked for, or the
     *  English comes back. Calling it again is harmless. */
    fun init(context: Context) {
        if (app == null) app = context.applicationContext ?: context
    }

    /** The resource name a sentence is filed under. */
    fun resourceName(key: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return "kp_" + digest.joinToString("") { "%02x".format(it) }.take(12)
    }

    /**
     * A context whose resources follow the in-app language. On Android 12 and below AppCompat
     * applies a picked language to activities only, so the application context would answer in
     * the phone's language: its configuration is made here with the picked locales, and remade
     * when the pick changes.
     */
    private fun localized(): Context? {
        val base = app ?: return null
        val tags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        cached?.let { (forTags, context) -> if (forTags == tags) return context }
        val context = if (tags.isBlank()) {
            base
        } else {
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tags))
            base.createConfigurationContext(config)
        }
        cached = tags to context
        return context
    }

    /** For numbers inside a sentence (iOS `AppLocalization.locale`). */
    val locale: Locale
        get() = localized()?.resources?.configuration?.locales?.takeIf { !it.isEmpty }?.get(0)
            ?: Locale.getDefault()

    private fun id(context: Context, key: String): Int = ids.getOrPut(key) {
        // A resource looked up by its name: the sentence is the key, as on iOS.
        @Suppress("DiscouragedApi")
        context.resources.getIdentifier(resourceName(key), "string", context.packageName)
    }

    /** [key] in the picked language; [key] itself when it has no translation. */
    fun string(key: String): String {
        val context = localized() ?: return key
        val id = id(context, key)
        if (id == 0) return key
        return try {
            context.resources.getString(id)
        } catch (_: Resources.NotFoundException) {
            key
        }
    }

    /** Whether [key] is a sentence with a translation of its own. */
    fun has(key: String): Boolean {
        val context = localized() ?: return false
        return id(context, key) != 0
    }

    /** [key] is a format ("%s won on time."): looked up first, then filled in. A translation
     *  whose placeholders don't fit the arguments falls back to the English. */
    fun format(key: String, vararg args: Any?): String = try {
        String.format(locale, string(key), *args)
    } catch (_: IllegalFormatException) {
        String.format(Locale.US, key, *args)
    }
}

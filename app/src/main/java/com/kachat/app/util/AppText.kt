package com.kachat.app.util

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate

/**
 * A string resource in the language chosen in Settings > Language, from anywhere: a composable, a
 * click handler or a toast. Settings > Language recreates the activity, so what's on screen re-reads
 * it. The text ported from KaPay (`strings_kapay.xml`: Portfolio, Cold Storage, Trestly) goes
 * through this, the way iOS's goes through `AppLocalization`.
 *
 * [init] runs at app start (KaChatApplication) and again from each ported screen's entry point, so
 * it is set before any of that text is read.
 */
object AppText {
    @Volatile private var app: Context? = null
    private var cachedTag: String? = null
    private var cachedContext: Context? = null

    fun init(context: Context) {
        if (app == null) app = context.applicationContext ?: context
    }

    @Synchronized
    fun context(): Context {
        val base = app ?: error("AppText.init wasn't called")
        val locales = AppCompatDelegate.getApplicationLocales()
        val tag = locales.toLanguageTags()
        // No choice made: the app's own resources already follow the phone's language.
        if (tag.isEmpty()) return base
        cachedContext?.let { if (cachedTag == tag) return it }
        val config = Configuration(base.resources.configuration)
        config.setLocales(locales.unwrap() as LocaleList)
        return base.createConfigurationContext(config).also {
            cachedTag = tag
            cachedContext = it
        }
    }

    /** For numbers and upper-casing inside a sentence (iOS `AppLocalization.locale`). */
    val locale: java.util.Locale
        get() = (if (app == null) null else context()).let { ctx ->
            ctx?.resources?.configuration?.locales?.takeIf { !it.isEmpty }?.get(0) ?: java.util.Locale.getDefault()
        }
}

/** See [AppText]. */
fun tr(@StringRes id: Int, vararg args: Any?): String = AppText.context().getString(id, *args)

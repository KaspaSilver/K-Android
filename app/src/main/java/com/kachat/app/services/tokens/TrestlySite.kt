package com.kachat.app.services.tokens

import android.content.Context
import com.kachat.app.util.KaspaNetwork

/**
 * Trestly's site, the one Swap Stablecoins opens inside KaChat (iOS 148a5331
 * `AppSettings.trestlySiteURL`, Settings > Connection > Trestly > Trestly Site URL): kept per
 * network, blank meaning that network's own deployment. iOS keeps it in the connection profile with
 * the Trestly API URL; here it has its own small store, so the settings DataStore isn't touched.
 */
object TrestlySite {
    const val DEFAULT_MAINNET_SITE_URL = "https://trestly.io"
    const val DEFAULT_TESTNET_SITE_URL = "https://testnet.trestly.io"

    private const val PREFS = "kachat_trestly_site"

    fun defaultSiteUrl(network: KaspaNetwork.Type): String =
        if (network == KaspaNetwork.Type.MAINNET) DEFAULT_MAINNET_SITE_URL else DEFAULT_TESTNET_SITE_URL

    private fun key(network: KaspaNetwork.Type) = "site_url_${network.raw}"

    /** What Settings holds for [network]: blank when it's the default. */
    fun storedSiteUrl(context: Context, network: KaspaNetwork.Type): String =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key(network), null).orEmpty()

    /** Saves [value] for [network]; blank (or the network's default) goes back to the default. */
    fun setSiteUrl(context: Context, network: KaspaNetwork.Type, value: String) {
        val trimmed = value.trim()
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (trimmed.isEmpty() || trimmed.trimEnd('/') == defaultSiteUrl(network)) {
            prefs.edit().remove(key(network)).apply()
        } else {
            prefs.edit().putString(key(network), trimmed).apply()
        }
    }

    /** iOS `TrestlyBrowserView.siteURL`: Settings' site for the running network, else its own. */
    fun siteUrl(context: Context): String =
        storedSiteUrl(context, KaspaNetwork.launch).trim().takeIf { it.isNotEmpty() } ?: defaultSiteUrl(KaspaNetwork.launch)

    /** Forgets every saved site (nothing account-specific is kept here; for a full reset). */
    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

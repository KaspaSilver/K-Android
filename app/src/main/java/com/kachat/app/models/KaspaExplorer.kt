package com.kachat.app.models

import com.kachat.app.R
import com.kachat.app.services.explorer.ExplorerDeepLink
import com.kachat.app.util.tr

/** Which block explorer transaction links open in — user picks in Settings > Kaspa Explorer. */
enum class KaspaExplorer(
    private val siteName: String,
    private val txBaseUrl: String,
    private val addressBaseUrl: String
) {
    /** K's own Explorer (Kaspa Hub > Explorer; iOS bfb902b7, owner 2026-11-26): a link opens the
     *  transaction or address there, through `kachat://explorer-tx/<id>` /
     *  `kachat://explorer-address/<address>` ([ExplorerDeepLink]), on mainnet and testnet alike. */
    K("", "https://kaspa.stream/transactions/", "https://kaspa.stream/addresses/"),
    KASPA_STREAM("kaspa.stream", "https://kaspa.stream/transactions/", "https://kaspa.stream/addresses/"),
    KASPA_ORG("explorer.kaspa.org", "https://explorer.kaspa.org/txs/", "https://explorer.kaspa.org/addresses/");

    val displayName: String get() = if (this == K) tr(R.string.k_explorer) else siteName

    // On testnet every website link opens kaspa.stream's testnet-10 explorer, whichever site is
    // picked: explorer.kaspa.org has no live TN10 site (iOS 421a832). K's own Explorer follows the
    // network the app runs on.
    fun txUrl(txId: String): String = when {
        this == K -> ExplorerDeepLink.txUrl(txId)
        com.kachat.app.util.KaspaNetwork.isTestnet -> "$TESTNET_TX_BASE$txId"
        else -> "$txBaseUrl$txId"
    }
    fun addressUrl(address: String): String = when {
        this == K -> ExplorerDeepLink.addressUrl(address)
        com.kachat.app.util.KaspaNetwork.isTestnet -> "$TESTNET_ADDRESS_BASE$address"
        else -> "$addressBaseUrl$address"
    }

    companion object {
        private const val TESTNET_TX_BASE = "https://tn10.kaspa.stream/transactions/"
        private const val TESTNET_ADDRESS_BASE = "https://tn10.kaspa.stream/addresses/"
        /** K's own Explorer (iOS bfb902b7, owner 2026-11-26): a fresh install opens links there,
         *  and everyone was moved to it once (see `AppSettingsRepository.kaspaExplorer`);
         *  kaspa.stream and explorer.kaspa.org stay in the picker. */
        val default: KaspaExplorer = K

        fun fromName(name: String?): KaspaExplorer =
            entries.firstOrNull { it.name == name } ?: default

        /** The stored pick, read once everyone has been moved to K's own Explorer (iOS
         *  `kExplorerDefaultMigration`): until a pick is made after this update, it's K. */
        fun stored(name: String?, pickedSinceKExplorer: Boolean): KaspaExplorer =
            if (pickedSinceKExplorer) fromName(name) else K
    }
}

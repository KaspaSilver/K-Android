package com.kachat.app.services

/**
 * Small pieces Cold Storage needs that KaChat's core reads differently (ported from KaPay with
 * Cold Storage, iOS 148a5331). Kept here so the core files stay as they are.
 */

/** How long a REST UTXO look-up may take in all (iOS 5848fb0 `getUtxosByAddressesViaRest`). */
internal const val COLD_REST_UTXO_LOOKUP_TIMEOUT_MS = 30_000L

/**
 * [KaspaRestApi.getUtxos] as every Cold Storage read takes it (KaPay's `getUtxosOfAddress`, iOS
 * 5848fb0 `getUtxosByAddressesViaRest`): within 30 s in all, and only the coins of the address
 * asked about - a coin listed under another address is a broken answer and is dropped.
 */
internal suspend fun KaspaRestApi.coldUtxosOfAddress(address: String): List<UtxoEntry> {
    val fetched = kotlinx.coroutines.withTimeoutOrNull(COLD_REST_UTXO_LOOKUP_TIMEOUT_MS) { getUtxos(address) }
        ?: throw IllegalStateException("Kaspa REST API timed out on UTXO lookup")
    val asked = address.trim().lowercase()
    return fetched.filter { it.address.trim().lowercase() == asked }
}

/** A plain coin: not bound to a covenant, so a plain send may spend it (KaPay's
 *  `UtxoData.isPlainSpendable`, iOS 83806b3d `UTXO.covenantId`). */
internal val UtxoData.coldPlainSpendable: Boolean get() = covenantId.isNullOrEmpty()

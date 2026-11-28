package com.kachat.app.repository

import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * The last fetched KAS price, as Portfolio ([PortfolioRepository]) and the Send screens
 * ([KasPriceRepository]) both save it under `kachat_current_price_<currency>` in
 * `kachat_price_history_cache` (release audit 2026-11-27 L1).
 *
 * Written and read by name, with no reflection, so R8 can't rename the fields: each side used to
 * have its own Gson class, and in release builds only one of them kept its field names, so each
 * read the other's record as price 0. A record without a usable `price` (one written by such a
 * build, with fields `a`/`b`/`c`) reads as none, and the next fetch replaces it.
 */
internal data class StoredKasPrice(val fetchedAt: Long, val price: Double, val change: Double?) {
    fun toJson(): String = JsonObject().apply {
        addProperty(FETCHED_AT, fetchedAt)
        addProperty(PRICE, price)
        change?.let { addProperty(CHANGE, it) }
    }.toString()

    companion object {
        private const val FETCHED_AT = "fetchedAt"
        private const val PRICE = "price"
        private const val CHANGE = "change"

        /** The record in [json]; null when it's missing, unreadable or has no positive price. */
        fun fromJson(json: String?): StoredKasPrice? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JsonParser.parseString(json).asJsonObject
                val price = o.get(PRICE)?.takeIf { it.isJsonPrimitive }?.asDouble ?: return null
                if (!price.isFinite() || price <= 0.0) return null
                val fetchedAt = o.get(FETCHED_AT)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                val change = o.get(CHANGE)?.takeIf { it.isJsonPrimitive }?.asDouble?.takeIf { it.isFinite() }
                StoredKasPrice(fetchedAt, price, change)
            }.getOrNull()
        }
    }
}

package com.kachat.app.models

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * One side of a swap pair (iOS 518fdc8c `SwapCoin`, Models/SwapModels.swift): ChangeNOW addresses
 * currencies by (ticker, network) since some tickers exist on more than one chain. kUSDT is the one
 * coin that isn't ChangeNOW's: it goes through Trestly ([isTrestly]).
 */
data class SwapCoin(
    val ticker: String,
    val network: String,
    val displayName: String,
) {
    /** Swapped through Trestly's pool on Kaspa rather than ChangeNOW. */
    val isTrestly: Boolean get() = this == KUSDT

    /**
     * A coin whose addresses are shared (ChangeNOW's own deposit address, an exchange's) and tell
     * payments apart by a tag sent with them: XRP's destination tag. ChangeNOW's deposit for it
     * comes with `payinExtraId`, which must go with the payment, and a payout to an exchange may
     * need one (`extraId`) (iOS audit 2026-11-24 #1).
     */
    val needsMemo: Boolean get() = this == XRP

    /** A payout tag K can send for this coin: XRP's destination tag is a whole number below 2^32. */
    fun isValidMemo(text: String): Boolean {
        val memo = text.trim()
        if (memo.isEmpty()) return true
        if (this == XRP) {
            return memo.length <= 10 && memo.all { it in '0'..'9' } &&
                (memo.toLongOrNull() ?: Long.MAX_VALUE) <= 0xFFFF_FFFFL
        }
        return memo.length <= 100
    }

    companion object {
        /** Kaspa itself — always one side of every swap this app can do. Verified against
         *  ChangeNOW's live /v2/exchange/currencies list: ticker "kas", network "kas". */
        val KAS = SwapCoin("kas", "kas", "Kaspa")

        /** kUSDT on Kaspa: swapped natively through Trestly's pool (`TrestlyTrade`), never through
         *  ChangeNOW (which doesn't list it). First in the coin picker. */
        val KUSDT = SwapCoin("kusdt", "kaspa-trestly", "kUSDT (Kaspa)")

        /** ChangeNOW's network code for Polygon is "matic" (not "polygon"). */
        val USDC_POLYGON = SwapCoin("usdc", "matic", "USDC Coin (Polygon)")

        // Single-network coins. Every ticker/network pair below was verified against ChangeNOW's
        // live /v2/exchange/currencies list (iOS): getting one wrong would send a payout to the
        // wrong chain, so none of this is guessed.
        val BTC = SwapCoin("btc", "btc", "Bitcoin")
        val ETH = SwapCoin("eth", "eth", "Ethereum")
        val SOL = SwapCoin("sol", "sol", "Solana")
        val XRP = SwapCoin("xrp", "xrp", "XRP")
        val BNB = SwapCoin("bnb", "bsc", "BNB (BNB Smart Chain)")
        val TRX = SwapCoin("trx", "trx", "TRON")
        val HYPE = SwapCoin("hype", "hyperevm", "Hyperliquid")
        val DOGE = SwapCoin("doge", "doge", "Dogecoin")
        val LTC = SwapCoin("ltc", "ltc", "Litecoin")
        val ZEC = SwapCoin("zec", "zec", "Zcash")
        val XMR = SwapCoin("xmr", "xmr", "Monero")
        val ADA = SwapCoin("ada", "ada", "Cardano")
        val BCH = SwapCoin("bch", "bch", "Bitcoin Cash")
        val ETC = SwapCoin("etc", "etc", "Ethereum Classic")

        // Tether (USDT), every network ChangeNOW lists
        val USDT_ETH = SwapCoin("usdt", "eth", "Tether (ERC20)")
        val USDT_TRX = SwapCoin("usdt", "trx", "Tether (TRC20)")
        val USDT_BSC = SwapCoin("usdt", "bsc", "Tether (BNB Smart Chain)")
        val USDT_SOL = SwapCoin("usdt", "sol", "Tether (Solana)")
        val USDT_MATIC = SwapCoin("usdt", "matic", "Tether (Polygon)")
        val USDT_ARBITRUM = SwapCoin("usdt", "arbitrum", "Tether (Arbitrum)")
        val USDT_OP = SwapCoin("usdt", "op", "Tether (Optimism)")

        // USDC, every other network ChangeNOW lists (Polygon is USDC_POLYGON above)
        val USDC_ETH = SwapCoin("usdc", "eth", "USDC Coin (Ethereum)")
        val USDC_SOL = SwapCoin("usdc", "sol", "USDC Coin (Solana)")
        val USDC_BSC = SwapCoin("usdc", "bsc", "USDC Coin (BNB Smart Chain)")
        val USDC_ALGO = SwapCoin("usdc", "algo", "USDC Coin (Algorand)")
        val USDC_OP = SwapCoin("usdc", "op", "USDC Coin (Optimism)")
        val USDC_ARBITRUM = SwapCoin("usdc", "arbitrum", "USDC Coin (Arbitrum)")
        val USDC_BASE = SwapCoin("usdc", "base", "USDC Coin (Base)")
        val USDC_SUI = SwapCoin("usdc", "sui", "USDC Coin (Sui)")

        /** iOS `SwapCoin.curated`, in its order. */
        val CURATED: List<SwapCoin> = listOf(
            BTC, ETH, SOL, XRP, BNB, TRX, HYPE, DOGE, LTC, ZEC, XMR, ADA, BCH, ETC,
            USDT_ETH, USDT_TRX, USDT_BSC, USDT_SOL, USDT_MATIC, USDT_ARBITRUM, USDT_OP,
            USDC_POLYGON, USDC_ETH, USDC_SOL, USDC_BSC, USDC_ALGO, USDC_OP, USDC_ARBITRUM, USDC_BASE, USDC_SUI,
        )
    }
}

/**
 * Local record of a swap this device made, for Swap History (iOS `SwapTransaction`): ChangeNOW is
 * the source of truth for the exchange itself; this remembers it and caches the last status seen.
 * Stored as JSON written by hand ([toJson] / [fromJson]), so a minified build reads what an earlier
 * one wrote.
 */
data class SwapTransaction(
    /** ChangeNOW exchange id — also the primary key. */
    val id: String,
    val fromTicker: String,
    val fromNetwork: String,
    val toTicker: String,
    val toNetwork: String,
    val fromAmount: String,
    val toAmount: String,
    val payinAddress: String,
    val payoutAddress: String,
    val status: String,
    val createdAtMillis: Long,
    /** Set once this device auto-sent KAS to payinAddress, when KAS was the "from" side. */
    val kasSendTxId: String? = null,
    /** Set once the KAS leg of this swap has been recorded as a portfolio transaction. */
    val addedToPortfolio: Boolean = false,
    /** ChangeNOW's deposit tag (XRP's destination tag): the deposit must carry it or it isn't
     *  credited (iOS audit 2026-11-24 #1). */
    val payinExtraId: String? = null,
    /** The tag (destination tag / memo) the payout goes out with, when one was given. */
    val payoutExtraId: String? = null,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("fromTicker", fromTicker)
        addProperty("fromNetwork", fromNetwork)
        addProperty("toTicker", toTicker)
        addProperty("toNetwork", toNetwork)
        addProperty("fromAmount", fromAmount)
        addProperty("toAmount", toAmount)
        addProperty("payinAddress", payinAddress)
        addProperty("payoutAddress", payoutAddress)
        addProperty("status", status)
        addProperty("createdAt", createdAtMillis)
        kasSendTxId?.let { addProperty("kasSendTxId", it) }
        addProperty("addedToPortfolio", addedToPortfolio)
        payinExtraId?.let { addProperty("payinExtraId", it) }
        payoutExtraId?.let { addProperty("payoutExtraId", it) }
    }

    companion object {
        private fun JsonObject.str(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asString

        fun fromJson(o: JsonObject): SwapTransaction? {
            val id = o.str("id")?.takeIf { it.isNotEmpty() } ?: return null
            return try {
                SwapTransaction(
                    id = id,
                    fromTicker = o.str("fromTicker").orEmpty(),
                    fromNetwork = o.str("fromNetwork").orEmpty(),
                    toTicker = o.str("toTicker").orEmpty(),
                    toNetwork = o.str("toNetwork").orEmpty(),
                    fromAmount = o.str("fromAmount").orEmpty(),
                    toAmount = o.str("toAmount").orEmpty(),
                    payinAddress = o.str("payinAddress").orEmpty(),
                    payoutAddress = o.str("payoutAddress").orEmpty(),
                    status = o.str("status") ?: "new",
                    createdAtMillis = o.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
                    kasSendTxId = o.str("kasSendTxId"),
                    addedToPortfolio = o.get("addedToPortfolio")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    payinExtraId = o.str("payinExtraId")?.takeIf { it.isNotEmpty() },
                    payoutExtraId = o.str("payoutExtraId")?.takeIf { it.isNotEmpty() },
                )
            } catch (e: Exception) {
                null
            }
        }

        fun listToJson(list: List<SwapTransaction>): String = JsonArray().apply { list.forEach { add(it.toJson()) } }.toString()

        /** A stored list, newest first; unreadable entries are left out. */
        fun listFromJson(text: String?): List<SwapTransaction> {
            if (text.isNullOrEmpty()) return emptyList()
            return try {
                val array = JsonParser.parseString(text).takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
                array.mapNotNull { e -> e.takeIf { it.isJsonObject }?.asJsonObject?.let(::fromJson) }
                    .sortedByDescending { it.createdAtMillis }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}

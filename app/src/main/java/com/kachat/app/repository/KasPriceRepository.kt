package com.kachat.app.repository

import android.content.Context
import com.kachat.app.services.CoinGeckoApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The KAS price the Send screens show their fiat mirror with (iOS `KasPriceModel`, d54ea87):
 * CoinGecko's keyless simple-price call, in the app's currency, its last answer kept per currency
 * under the same key Portfolio used, so an upgrade paints the price it already had.
 */
@Singleton
class KasPriceRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val coinGeckoApi: CoinGeckoApi,
) {
    data class Price(val fetchedAtMillis: Long, val price: Double)

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Wall-clock time before which CoinGecko told us not to retry (a 429's Retry-After, observed
     * at 59 seconds on the keyless tier); null when no throttle window is known.
     */
    @Volatile
    var throttledUntilMillis: Long? = null
        private set

    /** The last price fetched for [currency], or null when there never was one. */
    fun readPersisted(currency: String): Price? {
        val json = prefs.getString(cacheKey(currency), null) ?: return null
        return try {
            StoredKasPrice.fromJson(json)?.let { Price(it.fetchedAt, it.price) }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The price now, or null on any failure (offline, throttled). A 429/5xx gets one in-place
     * retry when its Retry-After is short; a longer window is recorded in [throttledUntilMillis].
     */
    suspend fun fetch(currency: String): Double? {
        repeat(2) { attempt ->
            try {
                val kaspa = coinGeckoApi.getSimplePrice(vsCurrencies = currency).kaspa
                val price = kaspa[currency] ?: return null
                val stored = StoredKasPrice(System.currentTimeMillis(), price, kaspa["${currency}_24h_change"])
                prefs.edit().putString(cacheKey(currency), stored.toJson()).apply()
                throttledUntilMillis = null
                return price
            } catch (e: HttpException) {
                if (attempt > 0 || (e.code() != 429 && e.code() < 500)) return null
                // finite and 0..600 s, else 2 s (iOS 428a797b, audit 2026-11-21 #20)
                val retryAfterSeconds = PortfolioRepository.retryAfterSeconds(e.response()?.headers()?.get("Retry-After"))
                if (retryAfterSeconds > MAX_INLINE_RETRY_SECONDS) {
                    throttledUntilMillis = System.currentTimeMillis() + (retryAfterSeconds * 1000).toLong()
                    return null
                }
                delay((retryAfterSeconds * 1000).toLong())
            } catch (e: Exception) {
                return null
            }
        }
        return null
    }

    private fun cacheKey(currency: String) = "kachat_current_price_$currency"

    companion object {
        /** The prefs file the price lives in - Portfolio's, so the price carries over. */
        const val PREFS_NAME = "kachat_price_history_cache"
        /** A price younger than this is shown without asking again. */
        const val FRESH_MILLIS = 60 * 1000L
        private const val MAX_INLINE_RETRY_SECONDS = 10.0
    }
}

package com.kachat.app.services

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Portfolio's own two settings (iOS `AppSettings.chartPair` and `portfolioValuesHidden`), kept in
 * their own prefs file rather than KaChat's DataStore: KaPay kept them in its AppSettingsRepository,
 * and KaChat's still deletes every `portfolio_` DataStore key once from when Portfolio left
 * (2026-10-24), so they live apart from it now that Portfolio is back (iOS 148a5331).
 */
@Singleton
class PortfolioPreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _chartPair = MutableStateFlow(readChartPair())
    /** Which pair the portfolio charts flip to on a tap of the price: null means no comparison,
     *  never chosen means bitcoin (iOS 39adefe). */
    val chartPair: StateFlow<ChartPair?> = _chartPair.asStateFlow()

    private val _portfolioValuesHidden = MutableStateFlow(prefs.getBoolean(KEY_VALUES_HIDDEN, false))
    /** Portfolio's eye button: every amount on that screen reads as dots. */
    val portfolioValuesHidden: StateFlow<Boolean> = _portfolioValuesHidden.asStateFlow()

    private fun readChartPair(): ChartPair? {
        val raw = prefs.getString(KEY_CHART_PAIR, null) ?: return ChartPair.BITCOIN
        return ChartPair.fromId(raw)
    }

    fun setChartPair(value: ChartPair?) {
        prefs.edit().putString(KEY_CHART_PAIR, value?.id ?: "").apply()
        _chartPair.value = value
    }

    fun setPortfolioValuesHidden(value: Boolean) {
        prefs.edit().putBoolean(KEY_VALUES_HIDDEN, value).apply()
        _portfolioValuesHidden.value = value
    }

    private companion object {
        const val PREFS_NAME = "kachat_portfolio_settings"
        const val KEY_CHART_PAIR = "portfolio_chart_pair"
        const val KEY_VALUES_HIDDEN = "portfolio_values_hidden"
    }
}

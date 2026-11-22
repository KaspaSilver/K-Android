package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.repository.KaPostsTipAsset
import com.kachat.app.repository.KasPriceRepository
import com.kachat.app.services.WalletManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * KaPost's own settings (Settings > KaPost: Tip In, the instant tip, Tips From, cool down, which
 * activity notifies) and the few wallet reads its tip needs. KaPost kept these on its
 * SettingsViewModel and WalletViewModel; here they are KaPost's own, so KaChat's stay as they are.
 */
@HiltViewModel
class KaPostsSettingsViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
    private val walletManager: WalletManager,
    private val priceRepository: KasPriceRepository,
) : ViewModel() {

    /** The KaPost indexer the app runs on (KaChat's own indexer): null while it loads, blank on
     *  testnet, where KaPost is off (iOS `KaPostsUnavailableView`). */
    val kapostIndexerUrl: StateFlow<String?> =
        settings.kapostIndexerUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val kaPostsNotifyLikes = settings.kaPostsNotifyLikes
    val kaPostsNotifyReposts = settings.kaPostsNotifyReposts
    val kaPostsNotifyFollows = settings.kaPostsNotifyFollows
    val kaPostsNotifyDislikes = settings.kaPostsNotifyDislikes
    val kaPostsNotifyComments = settings.kaPostsNotifyComments
    val kaPostsNotifyMentions = settings.kaPostsNotifyMentions

    val kaPostsDefaultTipSompi: StateFlow<Long?> =
        settings.kaPostsDefaultTipSompi.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val kaPostsDefaultTipFiat: StateFlow<Pair<Double, String>?> =
        settings.kaPostsDefaultTipFiat.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val kaPostsCooldownEnabled: StateFlow<Boolean> =
        settings.kaPostsCooldownEnabled.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Tip In as it applies: the choice, or Kaspa while an instant tip is set (iOS `kaPostsTipAssetInUse`). */
    val kaPostsTipAssetInUse: StateFlow<KaPostsTipAsset> = combine(
        settings.kaPostsTipAsset, settings.kaPostsDefaultTipSompi, settings.kaPostsDefaultTipFiat,
    ) { asset, sompi, fiat -> KaPostsTipAsset.inUse(asset, sompi, fiat) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, KaPostsTipAsset.ASK)

    suspend fun loadKaPostsTipAssetInUse(): KaPostsTipAsset = KaPostsTipAsset.inUse(
        settings.kaPostsTipAsset.first(),
        settings.kaPostsDefaultTipSompi.first(),
        settings.kaPostsDefaultTipFiat.first(),
    )

    /** Tip In; anything but Kaspa turns the instant tip off. */
    fun setKaPostsTipAsset(asset: KaPostsTipAsset) = viewModelScope.launch {
        settings.setKaPostsTipAsset(asset)
        if (asset != KaPostsTipAsset.KASPA) settings.setKaPostsDefaultTipSompi(null)
    }

    fun setKaPostsNotifyLikes(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyLikes(value) }
    fun setKaPostsNotifyReposts(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyReposts(value) }
    fun setKaPostsNotifyFollows(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyFollows(value) }
    fun setKaPostsNotifyDislikes(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyDislikes(value) }
    fun setKaPostsNotifyComments(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyComments(value) }
    fun setKaPostsNotifyMentions(value: Boolean) = viewModelScope.launch { settings.setKaPostsNotifyMentions(value) }
    fun setKaPostsDefaultTipSompi(value: Long?) = viewModelScope.launch { settings.setKaPostsDefaultTipSompi(value) }
    fun setKaPostsDefaultTipFiat(amount: Double, currency: String) = viewModelScope.launch { settings.setKaPostsDefaultTipFiat(amount, currency) }
    fun setKaPostsCooldownEnabled(value: Boolean) = viewModelScope.launch { settings.setKaPostsCooldownEnabled(value) }

    // MARK: - Tips From (per account, iOS ee5ec98 `KaPostsTipSourceStore`)

    /** The active account's Tips From: a spending index, or null for the chatting address. */
    suspend fun kaPostsTipSourceIndex(): Int? {
        val account = walletManager.getActiveAccount()?.address ?: return null
        return settings.kaPostsTipSourceIndex(account).first()
    }

    /** [walletAddress]'s Tips From, for the Settings row. */
    fun kaPostsTipSourceFlow(walletAddress: String?): Flow<Int?> =
        if (walletAddress == null) flowOf(null) else settings.kaPostsTipSourceIndex(walletAddress)

    fun setKaPostsTipSourceIndex(index: Int?) {
        val account = walletManager.getActiveAccount()?.address ?: return
        viewModelScope.launch { settings.setKaPostsTipSourceIndex(account, index) }
    }

    /** Whether [index] is one of the active account's revealed spending slots: Tips From reads as
     *  not available otherwise, never quietly as the chatting address (iOS `SendSource.defaultTip`). */
    fun isRevealedSpendingIndex(index: Int): Boolean {
        if (index < 0) return false
        val account = walletManager.getActiveAccount() ?: return false
        return if (WalletManager.isQuantumIndex(index)) {
            index - WalletManager.QUANTUM_INDEX_BASE <= WalletManager.maxQuantumIndex(account)
        } else {
            index <= WalletManager.legacyMaxSpendingIndex(account)
        }
    }

    // MARK: - KAS price in a currency (iOS `KasPriceModel.freshPrice(in:)`)

    /** The last price known in [currencyCode], however old. */
    fun priceIn(currencyCode: String): Double? = priceRepository.readPersisted(currencyCode)?.price

    /** A price fetched in the last [maxAgeMillis] only: what a tip sends without asking can't rest
     *  on a price days old. Otherwise a fetch starts, so a moment later there is one. */
    fun freshPriceIn(currencyCode: String, maxAgeMillis: Long = 10 * 60 * 1000L): Double? {
        val saved = priceRepository.readPersisted(currencyCode)
        if (saved != null && System.currentTimeMillis() - saved.fetchedAtMillis <= maxAgeMillis) return saved.price
        viewModelScope.launch { runCatching { priceRepository.fetch(currencyCode) } }
        return null
    }
}

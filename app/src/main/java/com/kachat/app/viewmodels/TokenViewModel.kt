package com.kachat.app.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.R
import com.kachat.app.repository.AppSettingsRepository
import com.kachat.app.services.SendOutcomeUnknownException
import com.kachat.app.services.tokens.KusdtBalances
import com.kachat.app.services.tokens.KusdtTransfer
import com.kachat.app.services.tokens.TokenActivity
import com.kachat.app.services.tokens.TokenChatPayments
import com.kachat.app.services.tokens.TokenHolding
import com.kachat.app.services.tokens.TokenWallet
import com.kachat.app.services.tokens.WalletToken
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * What the kUSDT screens (Wallet's kUSDT card, Manage kUSDT, a holding's page, Send / Send Dollars,
 * Combine Coins, Address Visibility) read and do - a thin layer over [TokenWallet], [KusdtTransfer]
 * and [TokenChatPayments], which hold the state and rules (iOS `TokenWallet.shared`,
 * `KusdtTransfer`, `ChatService.recordTokenPayment`, and the send logic of `TokenSendView` /
 * `TokenCombineView`). The services are reachable directly too ([wallet], [transfer]).
 *
 * Every StateFlow here is safe to collect from Compose. Lookups run on [TokenWallet]'s own scope,
 * so leaving a screen never cuts one off. Errors come back as values (a [SendOutcome], a
 * [Result]) or as [lastError]; nothing here throws at a screen.
 */
@HiltViewModel
class TokenViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val wallet: TokenWallet,
    val transfer: KusdtTransfer,
    private val chatPayments: TokenChatPayments,
    settings: AppSettingsRepository,
) : ViewModel() {
    /** The tokens KaChat shows, in order (kUSDT). */
    val tokens: List<WalletToken> get() = wallet.tokens

    /** Raw holdings by token id - collect to redraw, then read [holdings] / [listedHoldings] /
     *  [balance], which never show another account's. */
    val holdingsFlow: StateFlow<Map<String, List<TokenHolding>>> = wallet.holdingsFlow
    val activityFlow: StateFlow<Map<String, List<TokenActivity>>> = wallet.activityFlow
    val isRefreshing: StateFlow<Boolean> = wallet.isRefreshing
    val isDiscovering: StateFlow<Boolean> = wallet.isDiscovering
    /** Why the last lookup failed ("Couldn't check kUSDT just now: ..." on iOS's screens). */
    val lastError: StateFlow<String?> = wallet.lastError
    val visibilityRevision: StateFlow<Int> = wallet.visibilityRevision

    /** Connection Settings' Trestly API URL (the network running now). */
    val trestlyApiUrl: StateFlow<String> = settings.trestlyApiUrl
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    /** The block explorer Settings names: a history row's tap and an address page's View in
     *  Explorer open there (iOS `settingsViewModel.settings.kaspaExplorer`). */
    val kaspaExplorer: StateFlow<com.kachat.app.models.KaspaExplorer> = settings.kaspaExplorer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), com.kachat.app.models.KaspaExplorer.default)

    // MARK: Reading (cheap; [listedHoldings] and [label] may derive addresses, call them from
    // a LaunchedEffect / produceState off the main thread when the list can be long)

    fun holdings(token: WalletToken = WalletToken.KUSDT): List<TokenHolding> = wallet.holdings(token)
    fun balance(token: WalletToken = WalletToken.KUSDT): Long? = wallet.balance(token)
    fun kasHeld(token: WalletToken = WalletToken.KUSDT): Long = wallet.kasHeld(token)
    fun coins(holding: TokenHolding): List<KusdtBalances.Coin> = wallet.coins(holding)
    fun activity(token: WalletToken = WalletToken.KUSDT): List<TokenActivity> = wallet.activity(token)
    fun activity(token: WalletToken, holding: TokenHolding): List<TokenActivity> = wallet.activity(token, holding)
    suspend fun listedHoldings(token: WalletToken = WalletToken.KUSDT): List<TokenHolding> =
        withContext(Dispatchers.Default) { wallet.listedHoldings(token) }
    suspend fun label(holding: TokenHolding): String = withContext(Dispatchers.Default) { wallet.label(holding) }
    suspend fun heldOnText(token: WalletToken = WalletToken.KUSDT): String = withContext(Dispatchers.Default) { wallet.heldOnText(token) }

    // MARK: Lookups (fire and forget; watch [isRefreshing] / [isDiscovering] / [lastError])

    /** A screen appeared: refresh unless the balances are a minute old. [everyAddress] for Manage
     *  kUSDT and Send's From (every revealed address); otherwise only the ones that matter. */
    fun refreshIfStale(everyAddress: Boolean = false) {
        viewModelScope.launch { wallet.refreshIfStale(everyAddress = everyAddress) }
    }

    /** Pull to refresh. */
    fun refresh(everyAddress: Boolean = false) {
        viewModelScope.launch { wallet.refresh(everyAddress) }
    }

    /** Send Dollars opens: Discover, unless one ran in the last five minutes (audit #21). */
    fun discoverIfStale(token: WalletToken = WalletToken.KUSDT) {
        viewModelScope.launch { wallet.discoverIfStale(token) }
    }

    /** Discover (kUSDT Actions, Wallet's pull): [progress] gets (address number checked through,
     *  addresses found so far) on a background thread; answers how many hold some. */
    suspend fun discover(token: WalletToken = WalletToken.KUSDT, progress: (Int, Int) -> Unit = { _, _ -> }): Int =
        wallet.discoverHoldings(token, progress)

    /** A receive screen drew [address]: the regular poll watches it for a week. */
    fun noteReceiveAddressShown(address: String) = wallet.noteReceiveAddressShown(address)

    // MARK: Address Visibility

    fun isListed(holding: TokenHolding, token: WalletToken = WalletToken.KUSDT): Boolean = wallet.isListed(holding, token)
    fun toggleListed(holding: TokenHolding, token: WalletToken = WalletToken.KUSDT) = wallet.toggleListed(holding, token)
    fun hideAllListed(token: WalletToken = WalletToken.KUSDT) = wallet.hideAllListed(token)

    /** Generate New Address (kUSDT Actions): its number ("#5"), or null. */
    suspend fun generateFreshAddress(token: WalletToken = WalletToken.KUSDT): Int? = wallet.generateFreshAddress(token)

    /** Receive's fresh regular address (derived only); [revealFreshReceiveAddress] once it's
     *  copied or shared. */
    suspend fun freshReceiveAddress(): Pair<Int, String>? = withContext(Dispatchers.Default) { wallet.freshReceiveAddress() }
    suspend fun revealFreshReceiveAddress(index: Int) = wallet.revealFreshReceiveAddress(index)

    // MARK: Build and send

    /** Builds and signs the transfer the review shows (iOS TokenSendView `prepare`). The failure is
     *  a [KusdtTransfer.Failure] (its message is the text to show; [KusdtTransfer.Failure.NotEnoughKas]
     *  carries how much KAS to add for Fund This Address), a builder error or an I/O error. Check
     *  [KusdtTransfer.matches] on the result before showing it: From, To or the amount may have
     *  changed while it was built. [recipientLabel]: the name it goes to, for the history. */
    suspend fun prepare(holding: TokenHolding, recipient: String, amount: Long, recipientLabel: String? = null): Result<KusdtTransfer.Prepared> =
        try {
            Result.success(transfer.prepare(holding, wallet.coins(holding), recipient, amount).withRecipientLabel(recipientLabel))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** Combine Coins' build ([KusdtTransfer.prepareCombine]). */
    suspend fun prepareCombine(holding: TokenHolding): Result<KusdtTransfer.Prepared> =
        try {
            Result.success(transfer.prepareCombine(holding, wallet.coins(holding)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    /** What a swipe to send came to. */
    sealed class SendOutcome {
        /** Sent: [txid] is the network's id for it. */
        data class Sent(val txid: String, val prepared: KusdtTransfer.Prepared) : SendOutcome()
        /** "Not confirmed": it may still land. Keep [prepared] and offer Try Again, which sends
         *  this same transaction again (`outcomeUnknown = true`), never a new build. */
        data class NotConfirmed(val prepared: KusdtTransfer.Prepared, val message: String) : SendOutcome()
        /** The review was rebuilt and isn't what was reviewed (or isn't for what the screen shows
         *  now): nothing was sent; show [rebuilt] (null: build again) with [message]. */
        data class Changed(val rebuilt: KusdtTransfer.Prepared?, val message: String) : SendOutcome()
        /** Nothing was sent; [message] says why. [rebuilt] when a rebuild at the swipe made a new
         *  build to show. */
        data class Failed(val message: String, val rebuilt: KusdtTransfer.Prepared? = null) : SendOutcome()
    }

    /**
     * The swipe (iOS TokenSendView / TokenCombineView `send()`), with the review's rules:
     * - a review older than two minutes is built again from the coins as they are now, and stops
     *   with [SendOutcome.Changed] if it would cost more, send from another address, or move other
     *   KAS (audit #5) - never after an unknown outcome ([outcomeUnknown]: Try Again resends the
     *   same transaction);
     * - only a build for what the review shows ([fromId], [recipient], [amount]; null for Combine
     *   Coins, which can't change) is sent (KaPost's audit);
     * - from a chat ([chatContact], the contact's address): the payment bubble and the
     *   `token_payment_notice` once it went out - or, after "Not confirmed", once it's seen to land
     *   (up to three minutes, even if the screen closed; audit #10). [token] is the token sent.
     */
    suspend fun send(
        reviewed: KusdtTransfer.Prepared,
        outcomeUnknown: Boolean,
        fromId: String? = null,
        recipient: String? = null,
        amount: Long? = null,
        chatContact: String? = null,
        token: WalletToken = WalletToken.KUSDT,
    ): SendOutcome {
        var prepared = reviewed
        val isCombine = reviewed.recipient.equals(reviewed.holding.address, ignoreCase = true) && fromId == null
        if (!outcomeUnknown && reviewed.isStale) {
            val rebuilt = if (isCombine) prepareCombine(reviewed.holding) else prepare(reviewed.holding, reviewed.recipient, reviewed.amount, reviewed.recipientLabel)
            val next = rebuilt.getOrElse { e ->
                return SendOutcome.Failed(e.message ?: context.getString(R.string.kusdt_build_failed))
            }
            if (KusdtTransfer.changedSinceReview(reviewed, next)) return SendOutcome.Changed(next, transfer.sendChanged().message.orEmpty())
            prepared = next
        }
        if (!isCombine && !KusdtTransfer.matches(prepared, fromId, recipient, amount)) {
            return SendOutcome.Changed(null, transfer.sendChanged().message.orEmpty())
        }
        return try {
            val txid = transfer.send(prepared)
            // the recipient's coin is output 0 (`KusdtTransfer.Prepared.recipient`)
            if (chatContact != null) chatPayments.recordTokenPayment(chatContact, token, txid, 0, prepared.amount)
            SendOutcome.Sent(txid, prepared)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SendOutcomeUnknownException) {
            // It may have landed. Its coins stay in the balances: a send built again picks one of
            // them first, so the two conflict and at most one lands. From a chat: if it did land,
            // the chat still gets its bubble and notice, even once the screen is closed.
            if (chatContact != null) {
                val txid = prepared.txid
                val sentAmount = prepared.amount
                wallet.launchDetached {
                    if (transfer.awaitAccepted(txid)) chatPayments.recordTokenPayment(chatContact, token, txid, 0, sentAmount)
                }
            }
            SendOutcome.NotConfirmed(prepared, context.getString(R.string.kusdt_not_confirmed))
        } catch (e: Exception) {
            SendOutcome.Failed(e.message ?: context.getString(R.string.kusdt_build_failed))
        }
    }

    /** Get the chat's bubble and notice for a send made outside [send] (iOS
     *  `ChatService.recordTokenPayment`). Once per txid. */
    fun recordTokenPayment(contactAddress: String, token: WalletToken, txId: String, index: Int, amount: Long) =
        chatPayments.recordTokenPayment(contactAddress, token, txId, index, amount)
}

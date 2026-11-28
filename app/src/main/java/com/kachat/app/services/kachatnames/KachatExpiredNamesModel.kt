package com.kachat.app.services.kachatnames

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * This wallet's .kachat names that have expired and sit in their grace period: one for the whole
 * app (iOS b49a021d `KachatExpiredNamesModel.shared`, owner 2026-11-27: "make sure no one misses
 * it"). The Wallet tab's banner, the red dot on the dock's Wallet and the "1 Expired" warning on
 * Your Usernames all read it, so the dot shows from any tab. Loaded by the shell's dock badge sync
 * (on appear, on every registry change, when the app comes back), not by the Wallet tab.
 *
 * [expired] holds every name of yours in grace, dismissed or not: the dot and the warning stay
 * until the name is renewed (or lapses); only the banner can be closed. A dismissal (the banner's
 * X) is per name and expiry, so a name that expires again brings it back (iOS 6ac48a7). Only
 * where the registry is launched; elsewhere [load] leaves it empty.
 */
@Singleton
class KachatExpiredNamesModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: dagger.Lazy<KachatNamesRegistry>,
    private val actions: dagger.Lazy<KachatNamesActions>,
) {
    private val _expired = MutableStateFlow<List<NameInfo>>(emptyList())
    /** Every name of yours in grace, soonest expiry first. */
    val expired: StateFlow<List<NameInfo>> = _expired.asStateFlow()

    private val _dismissed = MutableStateFlow<Set<String>>(emptySet())
    /** The banner's dismissals (name@expiresAt) for the signed-in wallet. */
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    /** The registry's revision, to load again when it moves on - zero where it isn't launched. */
    val revision: StateFlow<Int>
        get() = if (KachatNamesService.isLaunched) registry.get().revision else notLaunchedRevision
    private val notLaunchedRevision = MutableStateFlow(0)

    val graceMs: Long get() = if (KachatNamesService.isLaunched) registry.get().graceMs else 0L

    private val prefs get() = context.getSharedPreferences("kachat_expired_banner", Context.MODE_PRIVATE)
    private fun storeKey(): String? = actions.get().myAddress?.let { "kachatExpiredBannerDismissed.$it" }

    /** The names the banner still shows: [expired] less the dismissed ones. */
    fun visible(expired: List<NameInfo>, dismissed: Set<String>): List<NameInfo> =
        expired.filter { key(it) !in dismissed }

    /** The account [load]'s list belongs to: another account's names never show (iOS d88891ea,
     *  audit 2026-11-27 M5). */
    @Volatile private var loadedFor: String? = null
    /** One load at a time (audit 2026-11-27 L19): a second waits, then usually finds the first's
     *  answer fresh enough to skip. Waiting rather than dropping it keeps a load cut short by a
     *  revision change from losing the newer one. */
    private val loading = kotlinx.coroutines.sync.Mutex()
    /** When the last complete load ran, and at which registry revision. */
    @Volatile private var lastLoad: Pair<Long, Int>? = null

    /**
     * Loads this account's names in grace, from every address of the account that holds a name
     * (a name on a spending address too, as Your Usernames lists them). One at a time, and not
     * again within a minute unless the registry changed or [force] (iOS d88891ea, audit
     * 2026-11-27 L19). Another account (or a Log Out) first drops the last one's list and
     * dismissals, and an answer that comes back after an account switch is thrown away (M5).
     */
    suspend fun load(force: Boolean = false) {
        val wallet = actions.get().myAddress
        if (wallet != loadedFor) {
            // another account (or none): nothing of the last one stays on screen
            loadedFor = wallet
            if (_expired.value.isNotEmpty()) _expired.value = emptyList()
            _dismissed.value = emptySet()
            lastLoad = null
        }
        val me = if (KachatNamesService.isLaunched) actions.get().myKey else null
        val storeKey = storeKey()
        if (wallet == null || me == null || storeKey == null) {
            if (_expired.value.isNotEmpty()) _expired.value = emptyList()
            return
        }
        val reg = registry.get()
        if (skipLoad(lastLoad, reg.revision.value, System.currentTimeMillis(), force)) return
        loading.withLock {
            if (skipLoad(lastLoad, reg.revision.value, System.currentTimeMillis(), force)) return
            if (actions.get().myAddress != wallet || loadedFor != wallet) return
            val stored = prefs.getStringSet(storeKey, emptySet()).orEmpty().toSet()
            if (stored != _dismissed.value) _dismissed.value = stored
            // Five minutes, as the other screens' loads: a shorter window here refreshed in between
            // and restarted every effect keyed on the registry's revision (iOS 9de981a).
            reg.refreshIfStale(maxAgeMs = 300_000)
            // Every key of the account that may hold a name. A failed read keeps the list as it is.
            val owned = ArrayList<NameInfo>()
            val keys = runCatching { actions.get().ownNameKeysChecked().keys }.getOrNull() ?: return
            for (key in keys) {
                owned += runCatching { reg.names(key, includeInactive = true) }.getOrNull() ?: return
            }
            // the account changed while this read ran: not this account's list
            if (actions.get().myAddress != wallet || loadedFor != wallet) return
            val inGrace = KachatNamesRegistry.inGracePeriod(owned.distinctBy { key(it) }, reg.graceMs)
            lastLoad = System.currentTimeMillis() to reg.revision.value
            // assigned only on a change (NameInfo has no equals of its own: compared by name and expiry)
            if (inGrace.map { key(it) } != _expired.value.map { key(it) }) _expired.value = inGrace
        }
    }

    /** A Log Out (or a switch): nothing of this account's list stays for the next one (iOS
     *  d88891ea resets on `$currentWallet`, audit 2026-11-27 M5). */
    fun reset() {
        loadedFor = null
        lastLoad = null
        if (_expired.value.isNotEmpty()) _expired.value = emptyList()
        _dismissed.value = emptySet()
    }

    fun dismissAll() {
        val storeKey = storeKey() ?: return
        // the list on screen is another account's (not reloaded yet): nothing of it is written here
        if (actions.get().myAddress != loadedFor) return
        val expired = _expired.value
        val next = _dismissed.value + visible(expired, _dismissed.value).map { key(it) }
        _dismissed.value = next
        // only names still in grace are worth remembering
        prefs.edit().putStringSet(storeKey, next.intersect(expired.map { key(it) }.toSet())).apply()
    }

    companion object {
        fun key(n: NameInfo) = "${n.name}@${n.expiresAt}"

        /** [load] skips (unless [force]) when the last complete load was under a minute ago at
         *  the same registry revision (audit 2026-11-27 L19). */
        fun skipLoad(last: Pair<Long, Int>?, revision: Int, nowMs: Long, force: Boolean): Boolean =
            !force && last != null && last.second == revision && nowMs - last.first in 0 until 60_000L

        /** What Your Usernames' count shows (iOS b49a021d `YourDomainsCard`): the names held, or
         *  the ones in grace when that read is ahead of the count. */
        fun usernamesCount(ownedCount: Int, expiredCount: Int): Int = maxOf(ownedCount, expiredCount)
    }
}

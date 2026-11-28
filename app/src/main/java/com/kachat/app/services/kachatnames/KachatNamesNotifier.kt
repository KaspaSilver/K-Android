package com.kachat.app.services.kachatnames

import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.kachat.app.R
import com.kachat.app.services.GlobalNotificationCenterStore
import com.kachat.app.services.NotificationHelper
import com.kachat.app.util.KaspaUnit
import com.kachat.app.util.stringOrFormat
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns registry changes into Profile-bell rows ([GlobalNotificationCenterStore], source
 * "kachat"), so a missed push still leaves a trace: an offer on one of your names, a name sold or
 * reclaimed, its renewal window opening, its expiry and lapse, and what became of your own offers
 * (accepted, declined, expired and returned). It compares what the registry says now with what
 * it saw on the last check (persisted per wallet), after every registry refresh and when the app
 * comes to the foreground (through [KachatNamesRegistry.refreshIfStale]). The first check of a
 * wallet only records where things stand. Only where the registry is live
 * ([KachatNamesService.isLaunched]). iOS `KachatNamesNotifier` (86471dd).
 */
@Singleton
class KachatNamesNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val registry: KachatNamesRegistry,
    private val actions: KachatNamesActions,
    private val service: KachatNamesService,
    private val bell: GlobalNotificationCenterStore,
    /** Local reminders for the wallet's names (iOS d1026de, XP-024; every name since b49a021d). */
    private val reminders: KachatNameReminders,
    /** Whether the indexer's pushes reach this device (iOS `PushNotificationManager.isRegistered`). */
    private val pushState: com.kachat.app.services.PushState,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val checking = AtomicBoolean(false)
    /** A check asked for while one ran, with [check]'s `rerunIfBusy`: it runs once that one ends. */
    private val rerun = AtomicBoolean(false)

    init {
        // Push decides which reminders the phone schedules itself (`pushCoversChatting`), and it
        // changes after launch and account switches: check again whenever it does, so the phone's
        // reminders never double the pushes, nor go missing (iOS audit 2026-11-27 L9).
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO).launch {
            pushState.pushActive.drop(1).collect { runCatching { check(rerunIfBusy = true) } }
        }
    }

    private fun key(wallet: String) = "kachatNamesNotifier.$wallet"

    suspend fun check(rerunIfBusy: Boolean = false) {
        if (!KachatNamesService.isLaunched) return
        if (actions.myKey == null || actions.myAddress == null || service.manifest.value?.params == null) return
        if (!checking.compareAndSet(false, true)) {
            if (rerunIfBusy) rerun.set(true)
            return
        }
        try {
            checkOnce()
        } finally {
            checking.set(false)
        }
        if (rerun.getAndSet(false)) check()
    }

    private suspend fun checkOnce() {
        val me = actions.myKey ?: return
        val wallet = actions.myAddress ?: return
        val p = service.manifest.value?.params ?: return
        try {
            // every address of this wallet, not only the chatting one: a name claimed with Buy From
            // gets its notices too (iOS d1026de, IOS-085 / XP-024)
            // a failed address probe leaves that address's names out: the diff would read them
            // as gone ("Sold"), so the last snapshot is kept instead
            val own = orNull { actions.ownNamesAndOffers(heldOnly = false, requireAllKeys = true) } ?: return
            val owned = own.first
            val myOpenOffers = own.second
            val ownKeys = actions.allOwnKeys()
            val offersOnMine = ArrayList<OfferInfo>()
            for (n in owned) {
                if (n.status(p.graceMs) != Status.ACTIVE) continue
                // a failed read isn't "no offers": comparing it would announce them all gone, then new (iOS 2da5f4b)
                offersOnMine += (orNull { registry.offers(n.name) } ?: return)
                    .filter { o -> ownKeys.any { it.contentEquals(o.seller) } }
            }
            // The indexer pushes name reminders only to the chatting address, and none before grace
            // ends: the phone schedules its own for every name ([KachatNameReminders]), all of them
            // for a name on another address or when push isn't on, and the ones push doesn't send
            // for the chatting address's (iOS b49a021d, owner 2026-11-27: "make sure no one misses it").
            // a different wallet signed in meanwhile: this answer isn't its
            if (actions.myAddress != wallet) return
            reminders.schedule(owned, p, wallet, chattingKey = me, pushCoversChatting = pushState.isActive)

            var old = prefs.getString(key(wallet), null)?.let(Snapshot::decode)
            // Name rows reach the Profile bell since iOS b49a021d; before, the bell refused them.
            // Once per wallet, a name already noted as in grace or up for renewal is noted again,
            // so its row lands now instead of never. A new wallet has nothing to catch up on (its
            // first check is quiet), so it's marked done at once; otherwise the flag is saved with
            // the snapshot below, after a whole check for the same wallet, or the catch-up rows of
            // an interrupted one would be lost (iOS audit 2026-11-27 L8).
            val bellKey = "kachatNamesNotifier.bell.$wallet"
            var catchingUp = false
            if (old == null) {
                prefs.edit().putBoolean(bellKey, true).apply()
            } else if (!prefs.getBoolean(bellKey, false)) {
                old = renotedForBell(old)
                catchingUp = true
            }
            val now = KachatNames.nowMs()
            // A failed history read isn't "nothing happened": saving that diff forgets the name
            // and a later real sale is never told, so the last snapshot is kept for the next check.
            var historyFailed = false
            val (next, news) = diff(
                old = old, owned = owned, offersOnMine = offersOnMine, myOpenOffers = myOpenOffers,
                params = p, nowMs = now, selfClosed = selfClosedOffers,
                offersAreComplete = registry.source.value != KachatNamesRegistry.Source.Chain,
                history = { name ->
                    orNull { registry.history(name) } ?: run {
                        historyFailed = true
                        emptyList<Event>()
                    }
                }
            )
            if (historyFailed) return
            // the history reads above can outlast a sign-out: the bell is the signed-in account's (iOS 2da5f4b)
            if (actions.myAddress != wallet) return
            for (n in news) {
                bell.record("kachat-${n.id}", SOURCE, title(n), body(n), now, n.name)
            }
            val edit = prefs.edit().putString(key(wallet), next.encode())
            if (catchingUp) edit.putBoolean(bellKey, true)
            edit.apply()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "check failed: ${e.message ?: e}")
        }
    }

    private suspend fun <T> orNull(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    // The row's words (iOS: every formatted string goes through KaspaUnit.label)

    // in the app's language (iOS audit 2026-11-27 M10)
    private fun s(res: Int, vararg args: Any): String =
        KaspaUnit.label(runCatching { com.kachat.app.util.tr(res, *args) }.getOrElse { context.stringOrFormat(res, *args) })

    private fun day(ms: Long): String = com.kachat.app.ui.screens.KachatLive.day(ms)

    private fun title(n: News): String = when (n) {
        is News.RenewOpen -> s(R.string.kn_bell_renew_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.Expired -> s(R.string.kn_bell_expired_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.Lapsed -> s(R.string.kn_bell_no_longer_yours_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.Sold, is News.SoldByOffer -> s(R.string.kn_bell_sold_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.Reclaimed -> s(R.string.kn_bell_freed_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.NewOffer -> s(R.string.kn_bell_new_offer_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.MyOfferAccepted -> s(R.string.kn_ev_offer_accepted)
        is News.MyOfferDeclined -> s(R.string.kn_bell_declined_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.MyOfferExpired -> s(R.string.kn_bell_offer_expired_title, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.MyOfferReturned -> s(R.string.kn_bell_returned_title, com.kachat.app.models.KachatNameLabel.display(n.name))
    }

    private fun body(n: News): String = when (n) {
        is News.RenewOpen -> s(R.string.kn_bell_renewal_open, day(n.expiresAt))
        is News.Expired -> s(R.string.kn_bell_expired_body, day(n.renewBy))
        is News.Lapsed -> s(R.string.kn_bell_lapsed_available_body)
        is News.Sold -> n.price?.let { s(R.string.kn_bell_paid, KaspaUnit.plain(it)) } ?: s(R.string.kn_bell_listing_bought)
        is News.SoldByOffer -> s(R.string.kn_bell_accepted_offer)
        is News.Reclaimed -> s(R.string.kn_bell_freed_body)
        is News.NewOffer -> s(R.string.kn_bell_offered, KaspaUnit.plain(n.amount))
        is News.MyOfferAccepted -> s(R.string.kn_bell_yours_now, com.kachat.app.models.KachatNameLabel.display(n.name))
        is News.MyOfferDeclined -> s(R.string.kn_bell_declined_body)
        is News.MyOfferExpired -> s(R.string.kn_bell_offer_expired_body)
        is News.MyOfferReturned -> s(R.string.kn_bell_returned_body)
    }

    // The registry diff (pure)

    /** What the last check saw for one wallet. */
    data class Snapshot(
        /** the names this wallet owned at the last check */
        val names: Map<String, NameNote> = emptyMap(),
        /** open offers on those names, by id */
        val offersOnMine: Set<String> = emptySet(),
        /** this wallet's own open offers: id -> name ("" when unknown) */
        val myOffers: Map<String, String> = emptyMap(),
    ) {
        /** Hand-written JSON, so no field name depends on R8 (see proguard-rules.pro on Gson stores). */
        fun encode(): String {
            val root = JsonObject()
            val names = JsonObject()
            for ((k, v) in this.names) {
                names.add(k, JsonObject().apply {
                    addProperty("expiresAt", v.expiresAt)
                    addProperty("renewNoted", v.renewNoted)
                    addProperty("graceNoted", v.graceNoted)
                    addProperty("lapsedNoted", v.lapsedNoted)
                })
            }
            root.add("names", names)
            root.add("offersOnMine", com.google.gson.JsonArray().apply { offersOnMine.forEach { add(it) } })
            root.add("myOffers", JsonObject().apply { myOffers.forEach { (k, v) -> addProperty(k, v) } })
            return root.toString()
        }

        companion object {
            /** null for anything unreadable: the next check then only takes a baseline again. */
            fun decode(raw: String): Snapshot? = runCatching {
                val root = JsonParser.parseString(raw).asJsonObject
                val names = HashMap<String, NameNote>()
                root.getAsJsonObject("names")?.entrySet()?.forEach { (k, v) ->
                    val o = v.asJsonObject
                    fun flag(f: String) = o.get(f)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
                    names[k] = NameNote(o.get("expiresAt").asLong, flag("renewNoted"), flag("graceNoted"), flag("lapsedNoted"))
                }
                val offers = root.getAsJsonArray("offersOnMine")?.map { it.asString }?.toSet() ?: emptySet()
                val mine = root.getAsJsonObject("myOffers")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap()
                Snapshot(names, offers, mine)
            }.getOrNull()
        }
    }

    /** One owned name at the last check: which notices its current paid period already had. */
    data class NameNote(
        val expiresAt: Long,
        val renewNoted: Boolean = false,
        val graceNoted: Boolean = false,
        val lapsedNoted: Boolean = false,
    )

    /** One bell row's event; [id] dedupes it in the bell, [name] (bare) is what tapping it opens. */
    sealed class News {
        abstract val id: String
        abstract val name: String

        data class RenewOpen(override val name: String, val expiresAt: Long) : News() {
            override val id get() = "renew-$name-$expiresAt"
        }
        /** [renewBy] = the end of the grace period */
        data class Expired(override val name: String, val expiresAt: Long, val renewBy: Long) : News() {
            override val id get() = "grace-$name-$expiresAt"
        }
        data class Lapsed(override val name: String, val expiresAt: Long) : News() {
            override val id get() = "lapsed-$name-$expiresAt"
        }
        /** your listing bought; [price] in sompi when the history said */
        data class Sold(override val name: String, val txId: String, val price: Long?) : News() {
            override val id get() = "sold-$txId"
        }
        /** sold by accepting an offer */
        data class SoldByOffer(override val name: String, val txId: String) : News() {
            override val id get() = "sold-$txId"
        }
        data class Reclaimed(override val name: String, val txId: String) : News() {
            override val id get() = "reclaimed-$txId"
        }
        data class NewOffer(override val name: String, val offerId: String, val amount: Long) : News() {
            override val id get() = "offer-$offerId"
        }
        data class MyOfferAccepted(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferDeclined(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferExpired(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
        data class MyOfferReturned(override val name: String, val offerId: String) : News() {
            override val id get() = "myoffer-$offerId"
        }
    }

    companion object {
        private const val TAG = "KachatNamesNotifier"
        private const val PREFS = "kachat_names_notifier"
        const val SOURCE = "kachat"

        /** Offers this person closed themselves (Withdraw, Refund): not news when they disappear.
         *  Filled by [KachatNamesActions.perform]; for this session, as on iOS. */
        val selfClosedOffers: MutableSet<String> = ConcurrentHashMap.newKeySet()

        /** [old] with every name's renewal-open and expired notices forgotten, so the next diff
         *  notes them again: the once-per-wallet catch-up when name rows came back to the bell
         *  (iOS b49a021d). Lapsed notices stay noted. */
        fun renotedForBell(old: Snapshot): Snapshot =
            old.copy(names = old.names.mapValues { (_, v) -> v.copy(renewNoted = false, graceNoted = false) })

        private val LEFT_OPS = setOf("sale", "offer_accepted", "offer_accept", "transfer", "release", "reclaim")
        private val MY_OFFER_OPS = setOf("offer_decline", "offer_refund", "offer_withdraw")

        /**
         * The news since [old] and the snapshot to keep (iOS `KachatNamesNotifier.check`). [old]
         * null = the wallet's first check: the snapshot only, no news. [history] answers a name's
         * history newest first (empty when it can't be read). [offersAreComplete] false (read from
         * the chain copy, which knows only the offers this device tracked): another device's offer
         * isn't gone, so the saved offers stay as they were and are compared once the indexer is
         * back (iOS 2da5f4b).
         */
        suspend fun diff(
            old: Snapshot?,
            owned: List<NameInfo>,
            offersOnMine: List<OfferInfo>,
            myOpenOffers: List<OfferInfo>,
            params: Params,
            nowMs: Long,
            selfClosed: Set<String>,
            offersAreComplete: Boolean = true,
            history: suspend (String) -> List<Event>,
        ): Pair<Snapshot, List<News>> {
            val news = ArrayList<News>()
            val names = LinkedHashMap<String, NameNote>()

            // Your names: renewal open, expired (grace), lapsed - once per paid period.
            for (n in owned) {
                var s = old?.names?.get(n.name)?.takeIf { it.expiresAt == n.expiresAt } ?: NameNote(n.expiresAt)
                when (n.status(params.graceMs, nowMs)) {
                    Status.ACTIVE -> if (n.renewOpen(params, nowMs) && !s.renewNoted) {
                        news += News.RenewOpen(n.name, n.expiresAt)
                        s = s.copy(renewNoted = true)
                    }
                    Status.GRACE -> {
                        s = s.copy(renewNoted = true)
                        if (!s.graceNoted) {
                            // clamped: a junk expiry can't wrap the date (iOS 5848fb0)
                            news += News.Expired(n.name, n.expiresAt, KachatNames.clampedSum(n.expiresAt, params.graceMs))
                            s = s.copy(graceNoted = true)
                        }
                    }
                    Status.LAPSED -> {
                        s = s.copy(renewNoted = true, graceNoted = true)
                        if (!s.lapsedNoted) {
                            news += News.Lapsed(n.name, n.expiresAt)
                            s = s.copy(lapsedNoted = true)
                        }
                    }
                }
                names[n.name] = s
            }

            // Names that left this wallet: sold, bought through an offer, or reclaimed by someone.
            // A transfer or release is your own doing and needs no notice.
            for (name in old?.names?.keys ?: emptySet()) {
                if (names.containsKey(name)) continue
                val last = history(name).firstOrNull { it.op in LEFT_OPS } ?: continue
                when (last.op) {
                    "sale" -> news += News.Sold(name, last.txId, last.price)
                    "offer_accepted", "offer_accept" -> news += News.SoldByOffer(name, last.txId)
                    "reclaim" -> news += News.Reclaimed(name, last.txId)
                }
            }

            // Offers on your names.
            val onMine = LinkedHashSet<String>()
            if (!offersAreComplete) onMine += old?.offersOnMine.orEmpty()
            for (o in offersOnMine) {
                onMine += o.id
                if (old?.offersOnMine?.contains(o.id) == true) continue
                val name = o.name ?: continue
                news += News.NewOffer(name, o.id, o.amount)
            }

            // Your offers: accepted, or back with you.
            val mine = LinkedHashMap<String, String>()
            if (!offersAreComplete) mine.putAll(old?.myOffers.orEmpty())
            for (o in myOpenOffers) mine[o.id] = o.name ?: ""
            for ((id, name) in old?.myOffers ?: emptyMap()) {
                if (!offersAreComplete || mine.containsKey(id) || name.isEmpty() || id in selfClosed) continue
                if (owned.any { it.name == name }) {
                    news += News.MyOfferAccepted(name, id)
                    continue
                }
                news += when (history(name).firstOrNull { it.op in MY_OFFER_OPS }?.op) {
                    "offer_decline" -> News.MyOfferDeclined(name, id)
                    "offer_refund" -> News.MyOfferExpired(name, id)
                    else -> News.MyOfferReturned(name, id)
                }
            }

            val next = Snapshot(names, onMine, mine)
            // the first check only records where things stand
            return next to (if (old == null) emptyList() else news)
        }
    }
}

/**
 * Local reminders for this wallet's .kachat names: renewal opens, the name expired, a week before
 * grace ends (mainnet) and a day before (the last call). The indexer's renewal and expiry pushes
 * reach only the chatting address and stop at expiry, so the phone schedules every reminder for a
 * name on another address (iOS d1026de, XP-024) or when push is off, and the grace-ending ones for
 * every name (iOS b49a021d, owner 2026-11-27). Nothing leaves the phone. Rescheduled on every
 * notifier check: a renewed name moves its reminders, a name that left the wallet loses them.
 * Each one is a one-time [KachatNameReminderWorker] under its own unique work name; tapping it
 * opens the name, like the pushes.
 *
 * Names are `kachat-reminder-<network>-<wallet tag>-<name>-<expiresAt>-<kind>`: each wallet and
 * network keeps its own set, so a check on one never removes another's (iOS 6128f1e, IOS-088).
 */
@Singleton
class KachatNameReminders @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private class Reminder(val date: Long, val title: String, val body: String, val name: String)

    /** [schedule] and [removeAll] read and rewrite the one saved map (iOS: @MainActor). */
    private val mutex = Mutex()

    /** A short, stable tag for a wallet in the names (the address's last 12 characters). */
    private fun walletTag(wallet: String): String = wallet.lowercase().takeLast(12)

    private fun prefix(wallet: String, network: String): String = "$ROOT$network-${walletTag(wallet)}-"

    /** A wallet's reminders on every network, for when it leaves this phone (iOS 6128f1e). */
    suspend fun removeAll(forWallet: String) = withContext(Dispatchers.IO) {
        mutex.withLock { removeAllFor(context, forWallet) }
    }

    /** The reminders scheduled so far: unique work name -> when it fires (unix ms). */
    private fun scheduled(): Map<String, Long> = runCatching {
        val root = JsonParser.parseString(prefs.getString(KEY, null) ?: return emptyMap()).asJsonObject
        root.entrySet().associate { (k, v) -> k to v.asLong }
    }.getOrNull() ?: emptyMap()

    private fun save(map: Map<String, Long>) {
        val root = JsonObject()
        for ((k, v) in map) root.addProperty(k, v)
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    // in the app's language (iOS audit 2026-11-27 M10)
    private fun s(res: Int, vararg args: Any): String =
        KaspaUnit.label(runCatching { com.kachat.app.util.tr(res, *args) }.getOrElse { context.stringOrFormat(res, *args) })

    private fun day(ms: Long): String = com.kachat.app.ui.screens.KachatLive.day(ms)

    /**
     * [chattingKey]: the chatting address's owner key. Its names get the renewal and expiry pushes
     * from the indexer when [pushCoversChatting] (push active), so only the reminders push never
     * sends (grace about to end) are scheduled for them here; every other name, or any name with
     * push off, gets them all (iOS b49a021d).
     */
    suspend fun schedule(
        names: List<NameInfo>,
        params: Params,
        wallet: String,
        chattingKey: ByteArray? = null,
        pushCoversChatting: Boolean = false,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock { scheduleLocked(names, params, wallet, chattingKey, pushCoversChatting) }
    }

    private fun scheduleLocked(
        names: List<NameInfo>,
        params: Params,
        wallet: String,
        chattingKey: ByteArray?,
        pushCoversChatting: Boolean,
    ) {
        val p = params
        val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
        val all = scheduled()
        // the first build's names, one device-wide set: removed wherever they are found
        val legacy = all.keys.filter { it.startsWith(LEGACY_PREFIX) }
        for (id in legacy) work.cancelUniqueWork(id)
        val prefix = prefix(wallet, KachatNamesService.networkName)
        // only this wallet's reminders on this network are this call's to keep or drop
        val others = all.filterKeys { !it.startsWith(LEGACY_PREFIX) && !it.startsWith(prefix) }
        val pending = all.filterKeys { it.startsWith(prefix) }
        // notifications are off for the app: nothing is kept waiting (iOS: not authorized)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            for (id in pending.keys) work.cancelUniqueWork(id)
            if (pending.isNotEmpty() || legacy.isNotEmpty()) save(others)
            return
        }
        val now = KachatNames.nowMs()
        val wanted = LinkedHashMap<String, Reminder>()
        val bases = HashSet<String>()
        for (n in names) bases += "$prefix${n.name}-${n.expiresAt}-"
        for (r in plan(names, p, now, chattingKey, pushCoversChatting)) {
            val n = r.info
            val display = com.kachat.app.models.KachatNameLabel.display(n.name)
            val id = "$prefix${n.name}-${n.expiresAt}-${r.kind}"
            wanted[id] = if (r.kind == KIND_RENEW) {
                Reminder(r.date, s(R.string.kn_bell_renew_title, display), s(R.string.kn_bell_renewal_open, day(n.expiresAt)), n.name)
            } else {
                // expired, a week before grace ends, the last call: renew before grace ends
                Reminder(r.date, s(R.string.kn_bell_expired_title, display), s(R.string.kn_bell_expired_body, day(r.graceEnds)), n.name)
            }
        }
        // iOS hands a due notification to the system at its time; WorkManager may run one a little
        // late (Doze), so one just past its time for a name that is still here is left to fire
        val keep = HashMap<String, Long>()
        for ((id, date) in pending) {
            if (wanted.containsKey(id)) continue
            val overdue = date <= now && now - date < 86_400_000L && bases.any { id.startsWith(it) }
            if (overdue) keep[id] = date else work.cancelUniqueWork(id)
        }
        for ((id, r) in wanted) {
            if (pending.containsKey(id)) continue
            val request = OneTimeWorkRequestBuilder<KachatNameReminderWorker>()
                .setInitialDelay(maxOf(1_000L, r.date - now), TimeUnit.MILLISECONDS)
                .setInputData(
                    workDataOf(
                        KachatNameReminderWorker.KEY_ID to id,
                        KachatNameReminderWorker.KEY_TITLE to r.title,
                        KachatNameReminderWorker.KEY_BODY to r.body,
                        KachatNameReminderWorker.KEY_NAME to r.name,
                    )
                )
                .addTag(ROOT)
                .build()
            work.enqueueUniqueWork(id, ExistingWorkPolicy.REPLACE, request)
        }
        save(others + keep + wanted.mapValues { it.value.date })
    }

    companion object {
        const val KIND_RENEW = "renew"
        const val KIND_GRACE = "grace"
        const val KIND_GRACE_WEEK = "graceweek"
        const val KIND_LAST_CALL = "lastcall"

        /** One reminder to schedule: [kind] is the last part of its work name. */
        class Planned(val info: NameInfo, val kind: String, val date: Long, val graceEnds: Long)

        private const val DAY_MS = 86_400_000L
        private const val WEEK_MS = 7 * DAY_MS

        /**
         * Which reminders [names] get, and when (iOS b49a021d `KachatNameReminders.schedule`), only
         * those still ahead of [nowMs]:
         * - renewal opens, and the name expired: unless push already sends them (a name of
         *   [chattingKey] with [pushCoversChatting]);
         * - a week before grace ends, where grace is long enough to warn that early (mainnet);
         * - the last call: a day before grace ends, or halfway through a short testnet grace.
         * Push sends neither of the last two, so every name gets them. Sums are clamped: a junk
         * expiry can't wrap a date (iOS 5848fb0).
         */
        fun plan(
            names: List<NameInfo>,
            params: Params,
            nowMs: Long,
            chattingKey: ByteArray?,
            pushCoversChatting: Boolean,
        ): List<Planned> {
            val p = params
            val out = ArrayList<Planned>()
            for (n in names) {
                val graceEnds = KachatNames.clampedSum(n.expiresAt, p.graceMs)
                // the indexer already pushes renewal-open and expired for the chatting address's names
                val pushCovered = pushCoversChatting && chattingKey != null && n.owner.contentEquals(chattingKey)
                if (!pushCovered) {
                    val renewAt = p.renewOpens(n.expiresAt)
                    if (renewAt > nowMs) out += Planned(n, KIND_RENEW, renewAt, graceEnds)
                    if (n.expiresAt > nowMs) out += Planned(n, KIND_GRACE, n.expiresAt, graceEnds)
                }
                if (p.graceMs > 2 * WEEK_MS) {
                    val weekBefore = KachatNames.clampedSum(graceEnds, -WEEK_MS)
                    if (weekBefore > nowMs) out += Planned(n, KIND_GRACE_WEEK, weekBefore, graceEnds)
                }
                val lastCall = KachatNames.clampedSum(graceEnds, -minOf(p.graceMs / 2, DAY_MS))
                if (lastCall > nowMs) out += Planned(n, KIND_LAST_CALL, lastCall, graceEnds)
            }
            return out
        }

        private const val PREFS = "kachat_name_reminders"
        private const val KEY = "scheduled"
        /** `kachat-reminder-<network>-<wallet tag>-<name>-<expiresAt>-<kind>` (IOS-088). */
        private const val ROOT = "kachat-reminder-"
        /** The first build's names, one device-wide set: removed wherever they are found. */
        private const val LEGACY_PREFIX = "kachat-local-"

        /**
         * A wallet's reminders on every network, without an instance: Delete Account removes them
         * from [com.kachat.app.services.WalletManager] (iOS 148a5331, `KachatNameReminders.removeAll`
         * in `WalletManager.deleteAccount`). Quick: a preference read and WorkManager cancels.
         */
        fun removeAllFor(context: Context, wallet: String) {
            val work = runCatching { WorkManager.getInstance(context) }.getOrNull() ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pending: Map<String, Long> = runCatching {
                val raw = prefs.getString(KEY, null) ?: return
                JsonParser.parseString(raw).asJsonObject.entrySet().associate { (k, v) -> k to v.asLong }
            }.getOrNull() ?: return
            val tag = "-${wallet.lowercase().takeLast(12)}-"
            val ids = pending.keys.filter { it.startsWith(ROOT) && it.contains(tag) }
            if (ids.isEmpty()) return
            for (id in ids) work.cancelUniqueWork(id)
            val root = JsonObject()
            for ((k, v) in pending - ids.toSet()) root.addProperty(k, v)
            prefs.edit().putString(KEY, root.toString()).apply()
        }
    }
}

/** Shows one [KachatNameReminders] reminder when its time comes (iOS: the scheduled local
 *  notification, thread "kachat-names"); tapping it opens the name. */
@HiltWorker
class KachatNameReminderWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val notifications: NotificationHelper,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.success()
        val title = inputData.getString(KEY_TITLE) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val name = inputData.getString(KEY_NAME) ?: return Result.success()
        showKachatName(title, body, name, dedupeKey = id)
        return Result.success()
    }

    /**
     * A `.kachat` name notification (iOS thread "kachat-names"): tapping it opens that name
     * ([NotificationHelper.EXTRA_KACHAT_NAME], iOS beeedd4). KaPay's `NotificationHelper.showKachatName`,
     * kept here so the shared helper stays as it is; the same settings and channels as every
     * other notification.
     */
    private suspend fun showKachatName(title: String, text: String, name: String, dedupeKey: String) {
        if (!settings.notificationsEnabled.first()) return
        if (!notifications.claimForBanner("kachat_name_$dedupeKey")) return
        val context = applicationContext
        val intent = android.content.Intent(context, com.kachat.app.MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(NotificationHelper.EXTRA_KACHAT_NAME, name)
        }
        val notificationId = "kachat_name_$dedupeKey".hashCode()
        val pendingIntent = android.app.PendingIntent.getActivity(
            context, notificationId, intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        val soundEnabled = settings.notificationSoundEnabled.first()
        val vibrationEnabled = settings.notificationVibrationEnabled.first()
        val channel = NotificationHelper.channelFor(soundEnabled, vibrationEnabled)
        // The lock screen shows only the app's name, never the name or an amount.
        val publicVersion = androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(context.getString(R.string.app_name))
            .build()
        val notification = androidx.core.app.NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_kachat_logo)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setContentIntent(pendingIntent)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setSilent(!soundEnabled && !vibrationEnabled)
            .setVibrate(if (vibrationEnabled) longArrayOf(0, 250, 250, 250) else longArrayOf(0))
            .apply { if (!soundEnabled) setSound(null) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notificationId, notification)
        } catch (_: SecurityException) {
        }
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_NAME = "name"
    }
}

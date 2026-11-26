package com.kachat.app.services.explorer

import android.util.Log
import com.kachat.app.services.NodePoolManager
import com.kachat.app.util.KaspaNetwork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import protowire.Rpc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A new block as the Explorer lists it (iOS 961fc05a `ExplorerBlockDetail`): built only for the
 * blocks the list keeps, and only while the Explorer shows, so the chats' block scanners pay
 * nothing for it.
 */
data class ExplorerBlockDetail(
    val hash: String,
    val blueScore: Long,
    val daaScore: Long,
    /** Milliseconds. */
    val timestamp: Long,
    val isChainBlock: Boolean,
    val difficulty: Double,
    val parents: List<String>,
    val children: List<String>,
    val mergeSetBlues: Int,
    val mergeSetReds: Int,
    val transactionIds: List<String>,
    val transactions: List<Transaction>,
) {
    data class Output(val address: String, val amount: Long, val covenantId: String)
    data class Transaction(
        val id: String,
        val subnetwork: String,
        val payloadHex: String,
        val inputCount: Int,
        val outputs: List<Output>,
        val mass: Long,
    )

    companion object {
        /** The block's hash, from its verbose data or, failing that, its header (a wss node's). */
        fun hashOf(block: Rpc.RpcBlock): String =
            block.verboseData.hash.ifEmpty { block.header.hash }

        fun blueScoreOf(block: Rpc.RpcBlock): Long =
            if (block.verboseData.blueScore != 0L) block.verboseData.blueScore else block.header.blueScore

        fun from(block: Rpc.RpcBlock): ExplorerBlockDetail {
            val v = block.verboseData
            return ExplorerBlockDetail(
                hash = hashOf(block),
                blueScore = blueScoreOf(block).coerceAtLeast(0),
                daaScore = block.header.daaScore.coerceAtLeast(0),
                timestamp = block.header.timestamp.coerceAtLeast(0),
                isChainBlock = v.isChainBlock,
                difficulty = v.difficulty,
                parents = block.header.parentsList.firstOrNull()?.parentHashesList.orEmpty(),
                children = v.childrenHashesList,
                mergeSetBlues = v.mergeSetBluesHashesCount,
                mergeSetReds = v.mergeSetRedsHashesCount,
                transactionIds = v.transactionIdsList,
                transactions = block.transactionsList.mapIndexedNotNull { offset, tx ->
                    val id = tx.verboseData.transactionId.ifEmpty { v.transactionIdsList.getOrNull(offset).orEmpty() }
                    if (id.isEmpty()) return@mapIndexedNotNull null
                    Transaction(
                        id = id,
                        subnetwork = tx.subnetworkId,
                        payloadHex = tx.payload,
                        inputCount = tx.inputsCount,
                        outputs = tx.outputsList.map { out ->
                            Output(
                                address = out.verboseData.scriptPublicKeyAddress,
                                // a u64 past Long's range is a broken node's: shown as nothing moved
                                amount = out.amount.coerceAtLeast(0),
                                covenantId = if (out.hasCovenant()) out.covenant.covenantId else "",
                            )
                        },
                        mass = tx.verboseData.computeMass.coerceAtLeast(0),
                    )
                },
            )
        }
    }
}

/**
 * The Explorer home's live numbers and newest blocks while the Explorer shows (iOS 961fc05a
 * `ExplorerLive`): blocks as K's own node announces them (kaspa.stream's API has no live feed), the
 * network's numbers from kaspa.stream every minute, the mempool from the node. Blocks land in a
 * buffer and reach the screen once a second, not ten times. Between blocks the DAA score runs on at
 * the measured rate. Its state lives on the main thread; blocks are built off it.
 *
 * Battery (audit 2026-11-25): it runs only between [start] and [stop], which the screen calls as it
 * shows and hides (a hidden Hub section, the app in the background); [stop] sends the node its
 * NOTIFY_STOP when the Explorer was the last one listening. The block buffer is capped (E2), the
 * kaspa.stream refresh runs in its own job so it never holds up the once-a-second flush (E2), and
 * the feed empties itself on another network (C5).
 */
@Singleton
class ExplorerLive @Inject constructor(
    private val nodePool: NodePoolManager,
    private val api: ExplorerApi,
    private val kStats: ExplorerKStats,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stats = MutableStateFlow<ExplorerNetworkStats?>(null)
    val stats: StateFlow<ExplorerNetworkStats?> = _stats.asStateFlow()
    private val _latestBlocks = MutableStateFlow<List<ExplorerBlock>>(emptyList())
    val latestBlocks: StateFlow<List<ExplorerBlock>> = _latestBlocks.asStateFlow()
    private val _latestTransactions = MutableStateFlow<List<ExplorerTransaction>>(emptyList())
    val latestTransactions: StateFlow<List<ExplorerTransaction>> = _latestTransactions.asStateFlow()
    /** kaspa.stream couldn't be reached and nothing is known yet. */
    private val _failed = MutableStateFlow(false)
    val failed: StateFlow<Boolean> = _failed.asStateFlow()

    private var loop: Job? = null
    private var refreshJob: Job? = null
    private var streamJob: Job? = null

    private val pendingLock = Any()
    private val pending = ArrayList<Rpc.RpcBlock>()

    private var daaAnchor: Pair<Long, Long>? = null // score, at
    private var blocksPerSecond = 10.0
    /** Where the blocks-per-second measurement started: the DAA score rises about one per block. */
    private var rateStart: Pair<Long, Long>? = null
    private var blueScore: Long? = null
    /** When [blueScore] was last known good. */
    private var blueScoreAt = 0L
    private var difficulty = 0.0
    private class Slow(
        val hashrate: Double, val reward: Double, val nextDrop: Long, val circulating: Long, val max: Long,
        val price: Double, val feerate: Double, val priority: Double,
    )
    private var slow: Slow? = null
    private var slowAt = 0L
    private var restAt = 0L
    private var mempool = 0
    /** The network everything here belongs to (audit 2026-11-25 C5): a switch empties it all. */
    private var network: KaspaNetwork.Type? = null
    /** Bumped by every reset, so an answer that was on its way for the old network is dropped. */
    private var generation = 0

    /** Starts the feed (main thread); a second call while running does nothing. */
    fun start() {
        resetIfNetworkChanged()
        if (loop != null) return
        streamJob = ioScope.launch { streamBlocks() }
        loop = scope.launch {
            var tick = 0
            while (isActive) {
                flush()
                // in its own job, so a slow kaspa.stream never holds up the once-a-second flush
                if (tick % 10 == 0 && refreshJob == null) {
                    refreshJob = scope.launch {
                        try {
                            refresh()
                        } finally {
                            // a cancelled one was already let go by stop()
                            if (refreshJob === coroutineContext[Job]) refreshJob = null
                        }
                    }
                }
                tick++
                delay(1_000)
            }
        }
    }

    /** Stops the feed (main thread). Cancelling the pool's live blocks tells the node to stop
     *  sending them when nothing else on that connection wants them (NOTIFY_STOP, audit B1). */
    fun stop() {
        if (loop == null) return
        loop?.cancel()
        loop = null
        refreshJob?.cancel()
        refreshJob = null
        rateStart = null
        streamJob?.cancel()
        streamJob = null
        synchronized(pendingLock) { pending.clear() }
    }

    /** The node's blocks through the pool (whole blocks, so a wss node's are decoded with their
     *  header and verbose data, audit 2026-11-25 C3), into the buffer. */
    private suspend fun streamBlocks() {
        try {
            nodePool.liveBlocks(owner = CLIENT, fullBlocks = true).collect { receive(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Live blocks ended: ${e.message}")
        }
    }

    /** Off the main thread: into the buffer, at most [PENDING_CAP], the newest (audit 2026-11-25 E2). */
    private fun receive(block: Rpc.RpcBlock) {
        synchronized(pendingLock) {
            pending.add(block)
            if (pending.size > PENDING_CAP) {
                pending.sortByDescending { it.header.daaScore }
                while (pending.size > PENDING_CAP) pending.removeAt(pending.size - 1)
            }
        }
    }

    /** Empties everything when the network isn't the one it was gathered on: the scores only go
     *  up and the lists sort by DAA score, so the old network's would otherwise stay on top. */
    private fun resetIfNetworkChanged() {
        val current = KaspaNetwork.launch
        val previous = network
        network = current
        if (previous == null || previous == current) return
        generation++
        _stats.value = null
        _latestBlocks.value = emptyList()
        _latestTransactions.value = emptyList()
        _failed.value = false
        synchronized(pendingLock) { pending.clear() }
        daaAnchor = null
        blocksPerSecond = 10.0
        rateStart = null
        blueScore = null
        blueScoreAt = 0L
        difficulty = 0.0
        slow = null
        slowAt = 0L
        restAt = 0L
        mempool = 0
        kStats.resetIfNetworkChanged()
    }

    /** The DAA score now, run on from the newest block at the measured rate. */
    fun daaScore(now: Long): Long {
        val (score, at) = daaAnchor ?: return 0
        val seconds = ((now - at) / 1000.0).coerceIn(0.0, 5.0)
        return score + (seconds * blocksPerSecond).toLong()
    }

    /** For confirmations: the virtual chain's blue score, asked for again when it's more than ten
     *  seconds old (a detail page opened later would otherwise count from a stale one). */
    suspend fun currentBlueScore(): Long? = withContext(Dispatchers.Main.immediate) {
        resetIfNetworkChanged()
        val known = blueScore
        if (known != null && System.currentTimeMillis() - blueScoreAt < 10_000) return@withContext known
        val started = generation
        val fresh = runCatchingNonCancel { ExplorerParsing.long(api.getObject("/info/virtual-chain-blue-score").get("blueScore")) }
        if (fresh != null && started == generation) {
            blueScore = maxOf(blueScore ?: 0L, fresh)
            blueScoreAt = System.currentTimeMillis()
        }
        blueScore
    }

    /** A block or transaction the node sent while the Explorer was open. */
    fun block(hash: String): ExplorerBlock? = _latestBlocks.value.firstOrNull { it.id == hash }
    fun transaction(id: String): ExplorerTransaction? =
        _latestTransactions.value.firstOrNull { it.id == id }
            ?: _latestBlocks.value.asSequence().flatMap { it.transactions.asSequence() }.firstOrNull { it.id == id }

    /** The blocks the node sent since the last second, onto the lists. Only blocks the list will
     *  keep are built, off the main thread (audit 2026-11-25 E1). */
    private suspend fun flush() {
        resetIfNetworkChanged()
        val fresh = synchronized(pendingLock) {
            if (pending.isEmpty()) return
            ArrayList(pending).also { pending.clear() }
        }
        val now = System.currentTimeMillis()
        val newest = fresh.maxByOrNull { it.header.daaScore }
        if (newest != null && newest.header.daaScore >= (daaAnchor?.first ?: 0L)) {
            val score = newest.header.daaScore
            daaAnchor = score to now
            val start = rateStart
            if (start != null && score > start.first && now - start.second >= 10_000) {
                blocksPerSecond = (score - start.first) / ((now - start.second) / 1000.0)
                rateStart = score to now
            } else if (start == null) {
                rateStart = score to now
            }
            val newestBlue = ExplorerBlockDetail.blueScoreOf(newest)
            if (newestBlue >= (blueScore ?: 0L)) {
                blueScore = newestBlue
                blueScoreAt = now
            }
            difficulty = newest.verboseData.difficulty
        }
        val shown = _latestBlocks.value
        val seen = shown.map { it.id }.toHashSet()
        // below the 15th block shown: it would fall off the list at once
        val lowestShown = if (shown.size >= KEPT_BLOCKS) shown.last().daaScore else 0L
        val kept = fresh
            .filter { block ->
                val hash = ExplorerBlockDetail.hashOf(block)
                // no hash (a wss node's block without its header, audit C3) would hide every other
                hash.isNotEmpty() && seen.add(hash) && block.header.daaScore >= lowestShown
            }
            .sortedByDescending { it.header.daaScore }
            .take(KEPT_BLOCKS)
        if (kept.isEmpty()) {
            publishStats()
            return
        }
        val network = this.network ?: KaspaNetwork.launch
        val started = generation
        val blocks = withContext(Dispatchers.Default) {
            kept.map { ExplorerParsing.liveBlock(ExplorerBlockDetail.from(it), network) }
        }
        if (started != generation) return
        val current = _latestBlocks.value
        val currentIds = current.map { it.id }.toHashSet()
        val added = blocks.filter { it.id !in currentIds }
        _latestBlocks.value = (added + current).sortedByDescending { it.daaScore }.take(KEPT_BLOCKS)
        mergeTransactions(added.flatMap { it.transactions }.filter { it.kind != ExplorerTxKind.COINBASE })
        publishStats()
    }

    /** kaspa.stream's numbers (every 10 s for the scores while no block has come, every minute for
     *  the rest) and the node's mempool. */
    private suspend fun refresh() {
        resetIfNetworkChanged()
        val started = generation
        val now = System.currentTimeMillis()
        if (_latestBlocks.value.isEmpty() || now - restAt > 60_000) {
            val dag = runCatchingNonCancel { api.getObject("/info/blockdag") }
            val blue = runCatchingNonCancel { ExplorerParsing.long(api.getObject("/info/virtual-chain-blue-score").get("blueScore")) }
            if (started != generation) return
            val score = dag?.let { ExplorerParsing.long(it.get("virtualDaaScore")) }
            if (score != null && score > (daaAnchor?.first ?: 0L)) {
                daaAnchor = score to System.currentTimeMillis()
                difficulty = ExplorerParsing.double(dag.get("difficulty")) ?: difficulty
            }
            if (blue != null) {
                blueScore = maxOf(blueScore ?: 0L, blue)
                blueScoreAt = System.currentTimeMillis()
            }
            restAt = now
        }
        if (slow == null || now - slowAt > 60_000) refreshSlow()
        if (started != generation) return
        kStats.refresh()
        val info = runCatchingNonCancel { withContext(Dispatchers.IO) { nodePool.getBroadcastConnection().getInfo() } }
        if (started != generation) return
        if (info != null && !info.hasError()) mempool = info.mempoolSize.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        _failed.value = slow == null
        publishStats()
    }

    private suspend fun refreshSlow() = coroutineScope {
        val started = generation
        val hashrate = async { runCatchingNonCancel { ExplorerParsing.double(api.getObject("/info/hashrate", listOf("stringOnly" to "false")).get("hashrate")) } }
        val reward = async { runCatchingNonCancel { ExplorerParsing.double(api.getObject("/info/blockreward", listOf("stringOnly" to "false")).get("blockreward")) } }
        val halving = async { runCatchingNonCancel { ExplorerParsing.double(api.getObject("/info/halving").get("nextHalvingTimestamp")) } }
        val supply = async { runCatchingNonCancel { api.getObject("/info/coinsupply") } }
        val price = async { runCatchingNonCancel { ExplorerParsing.double(api.getObject("/info/price", listOf("stringOnly" to "false")).get("price")) } }
        val fees = async { runCatchingNonCancel { api.getObject("/info/fee-estimate") } }
        val h = hashrate.await()
        val r = reward.await()
        val s = supply.await()
        val p = price.await()
        if (h == null || r == null || s == null || p == null) {
            Log.w(TAG, "kaspa.stream's network numbers didn't load")
            return@coroutineScope
        }
        val next = halving.await()?.let { ExplorerMath.clampedLong(it * 1000) } ?: System.currentTimeMillis()
        val f = fees.await()
        if (started != generation) return@coroutineScope
        val normal = (f?.get("normalBuckets") as? com.google.gson.JsonArray)?.firstOrNull()?.let { (it as? com.google.gson.JsonObject)?.get("feerate") }
        val priority = (f?.get("priorityBucket") as? com.google.gson.JsonObject)?.get("feerate")
        slow = Slow(
            hashrate = h * 1e12,
            reward = r,
            nextDrop = next,
            circulating = ExplorerParsing.long(s.get("circulatingSupply")) ?: 0L,
            max = ExplorerParsing.long(s.get("maxSupply")) ?: 0L,
            price = p,
            feerate = ExplorerParsing.double(normal) ?: 0.0,
            priority = ExplorerParsing.double(priority) ?: 0.0,
        )
        slowAt = System.currentTimeMillis()
    }

    private fun publishStats() {
        val slow = slow ?: return
        val anchor = daaAnchor ?: return
        val next = ExplorerNetworkStats(
            daaScore = anchor.first,
            blueScore = blueScore ?: 0L,
            blocksPerSecond = blocksPerSecond,
            hashrate = slow.hashrate,
            mempoolSize = mempool,
            normalFeerate = slow.feerate,
            priorityFeerate = slow.priority,
            // not in kaspa.stream's API: the demo, shown only when Kaspalytics hasn't answered
            averageFee24h = ExplorerDemo.averageFee24h(System.currentTimeMillis()),
            circulatingSupply = slow.circulating,
            maxSupply = slow.max,
            priceUSD = slow.price,
            blockReward = ExplorerMath.sompi(slow.reward),
            nextRewardDrop = slow.nextDrop,
        )
        if (next != _stats.value) _stats.value = next
    }

    /** The newest transactions seen, newest first, at most 300. */
    private fun mergeTransactions(fresh: List<ExplorerTransaction>) {
        if (fresh.isEmpty()) return
        val current = _latestTransactions.value
        val seen = current.map { it.id }.toHashSet()
        val merged = current.toMutableList()
        for (tx in fresh) if (seen.add(tx.id)) merged.add(tx)
        _latestTransactions.value = merged.sortedByDescending { it.time }.take(300)
    }

    private companion object {
        const val TAG = "Explorer"
        /** The block stream's owner name on the connection ("explorer", as iOS's client). */
        const val CLIENT = "explorer"
        /** The newest blocks the list keeps. */
        const val KEPT_BLOCKS = 15
        /** At most this many blocks wait for the next flush, the newest (audit 2026-11-25 E2). */
        const val PENDING_CAP = 30
    }
}

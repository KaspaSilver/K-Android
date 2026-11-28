package com.kachat.app.services

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import com.kachat.app.services.grpc.BlockStreamRetry
import com.kachat.app.services.grpc.KaspadConnection
import com.kachat.app.services.grpc.NodeProbeResult
import com.kachat.app.services.grpc.PublicNodeResolver
import com.kachat.app.services.grpc.WssSeedStore
import com.kachat.app.services.grpc.gossipedGrpcCandidate
import com.kachat.app.services.grpc.isSameNode
import com.kachat.app.services.grpc.isWebSocketNodeAddress
import com.kachat.app.services.grpc.NodeRecord
import com.kachat.app.services.grpc.NodeRegistry
import com.kachat.app.services.grpc.probeExisting
import com.kachat.app.viewmodels.NodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Real gRPC-backed Kaspa node pool: probes seed/discovered/manual nodes over the
 * actual `protowire.RPC` MessageStream (GetInfo/GetBlockDagInfo/GetPeerAddresses),
 * replacing the previous fake always-healthy simulation.
 *
 * Scoped deliberately simpler than the full POOLS_v2.md architecture (no EWMA
 * scoring, no network-epoch tracking, no hedged requests — see the "explicit
 * non-goals" section of the implementation plan): this drives a status *display*,
 * not a live routing decision under load.
 *
 * If the user pins a "host:port" node in Connection Settings
 * ([AppSettingsRepository.trustedNodeAddress]), this switches to a Kaspium-style
 * fixed-node mode instead: all discovery (seeds/DNS/peer-gossip) stops, and every
 * connection this class hands out is that one address, with no automatic failover
 * to a different node if it goes down (see [trustedNodeAddress]'s doc comment).
 */
@Singleton
class NodePoolManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsRepository,
    // For the probe-loop background gate: while the app is backgrounded probing pauses, push or
    // not (audit 2026-11-27 R1, R2). Dependency-cycle-safe: NotificationHelper needs only
    // Context + settings.
    private val notificationHelper: NotificationHelper,
    // Metered-network (cellular) gate for discovery-mode probing — see probeCycle() and
    // startProbing(): a healthy pool probes only its primary node at a slower cadence, and
    // the other pooled connections are closed between cycles so their HTTP/2 keepalive pings
    // stop. Pinned trusted-node mode is untouched (it is already a single cheap connection).
    private val meteredNetwork: MeteredNetwork,
    // For the acceptance lookup after a node refused a send: the built-in REST server's client
    // ([builtInRestApi]), never a custom one set in Settings.
    private val okHttpClient: okhttp3.OkHttpClient,
    // Signed out, the pool stops probing and closes its idle connections (audit 2026-11-25 N12,
    // B6; iOS B19). WalletManager needs only a Context, so there is no cycle.
    private val walletManager: WalletManager,
) {
    // A failure in a probe or a node's reply is logged, never the process's uncaught exception.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("NodePoolManager"))
    private val registry = NodeRegistry()

    // Non-null (and non-blank) once the user pins a "host:port" node in Connection Settings -
    // see the reactive collector in init(). When set, probeCycle()/getBroadcastConnection()
    // both short-circuit to this single address, skipping seed/DNS/peer-gossip discovery
    // entirely (Kaspium-style: one trusted node, no automatic failover to a different one).
    private val trustedNodeAddress = MutableStateFlow<String?>(null)

    // Bumped every time trusted mode is entered/left/re-targeted, so a probeCycle() whose
    // network calls were still in flight when the switch happened can tell its own results are
    // now stale and discard them instead of writing them into the just-reset registry.
    private var probeEpoch = 0

    // Persistent, reused gRPC connections — one per known node address. gRPC channels
    // are designed to be long-lived and reused across many calls; an earlier version
    // of this class opened and closed a fresh channel per node on every 30s probe
    // cycle, and on-device testing found that churn accumulated enough native/thread
    // resources to get the app OOM-killed roughly every 90-140 seconds. Connections
    // now live here and are only torn down on clearPool()/reconnect() or when a probe
    // reveals the underlying stream has died.
    private val connections = ConcurrentHashMap<String, KaspadConnection>()

    private val _activeNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val activeNodes: StateFlow<List<NodeInfo>> = _activeNodes.asStateFlow()

    private val _allNodes = MutableStateFlow<List<NodeInfo>>(emptyList())
    val allNodes: StateFlow<List<NodeInfo>> = _allNodes.asStateFlow()

    // Bundled bootstrap-of-last-resort node IPs. These exist for networks where the DNS
    // seeders below are blocked or poisoned (captive portals, restrictive firewalls, hostile
    // resolvers) — with DNS dead and nothing persisted yet, they are the only way into the
    // network. All addresses come from live-resolving the same community DNS seeders listed
    // in [dnsSeedHostnames] (the source the whole Kaspa ecosystem already trusts for
    // bootstrap) and keeping only the ones that actually answered on the public gRPC port.
    // Last refreshed 2026-08-25; the previous six entries had ALL gone stale. Public node
    // IPs churn, which is why these are a last resort: the persisted last-known-good list
    // (see [persistKnownGoodNodes]) and the DNS seeders are always preferred, and this list
    // should be re-verified against the seeders' current answers whenever it is touched.
    private val seeds = if (com.kachat.app.util.KaspaNetwork.isTestnet) {
        // Testnet has no hardcoded fallback IPs (iOS testnetBootstrapFallbackIPs = []).
        emptyList()
    } else listOf(
        "212.227.144.45:16110",
        "87.236.31.226:16110",
        "82.66.82.52:16110",
        "72.28.135.10:16110",
        "23.118.8.164:16110",
        "218.81.36.168:16110"
    )

    // Same DNS seeders the iOS reference app (KaChat) uses — see NodeModels.swift's
    // mainnetDNSSeeds. Each hostname resolves to multiple A records run by independent
    // Kaspa community operators, so unlike the fixed IP list above this can recover on
    // its own if some/all of those hardcoded IPs go stale or become unreachable.
    private val dnsSeedHostnames = if (com.kachat.app.util.KaspaNetwork.isTestnet) {
        // Testnet-10's seeders - rusty-kaspa's own TESTNET_PARAMS.dns_seeders (the older
        // seeder1/2-testnet.kaspad.net names no longer resolve; iOS 76068bf).
        listOf("seeder1-tn.kaspad.net", "dnsseeder-kaspa-testnet.x-con.at", "n-testnet-10.kaspa.ws")
    } else listOf(
        "n.seeder1.kaspad.net",
        "n.seeder2.kaspad.net",
        "n.seeder3.kaspad.net",
        "n.seeder4.kaspad.net",
        "kaspadns.kaspacalc.net",
        "n-mainnet.kaspa.ws",
        "kaspa.aspectron.org"
    )
    /** gRPC port: 16110 on mainnet, 16210 on testnet-10. */
    private val dnsSeedPort = if (com.kachat.app.util.KaspaNetwork.isTestnet) 16210 else 16110

    private val manualEndpoints = mutableSetOf<String>()
    private val discoveredEndpoints = mutableSetOf<String>()
    private val dnsResolvedEndpoints = mutableSetOf<String>()
    private var lastDnsResolveAt = 0L

    // Real mainnet nodes' GetPeerAddresses responses can list dozens-to-hundreds of
    // peers, and with only 1/6 seeds typically fully "Active" the discovery trigger
    // below fires on almost every cycle. Without a cap, discoveredEndpoints (and the
    // persistent connection opened per address) grew unbounded and caused a real
    // OutOfMemoryError crash on-device after a few minutes of runtime.
    private val maxDiscoveredEndpoints = 20
    private val maxDnsResolvedEndpoints = 20

    // Roughly matches the floor of iOS's NodeProfiler (minActiveNodes = 8, maxActiveNodes = 12)
    // without importing its full EWMA/network-epoch/replacement machinery — this class is
    // deliberately simpler (see the class doc comment). Both DNS-seed refresh and peer-gossip
    // discovery below keep trying as long as the pool has fewer than this many genuinely
    // Active nodes, rather than stopping the moment a handful of the hardcoded seeds respond.
    private val targetActiveNodes = 8

    // Don't re-resolve DNS on every unhealthy 30s probe cycle — a resolver failure/slowness
    // shouldn't turn into a hot loop of lookups; matches iOS's periodic-not-per-cycle refresh.
    private val dnsResolveCooldownMillis = 60_000L

    // The probe loop's cadence (unhealthy, dead, healthy, pinned) is [NodeProbeCadence]: never
    // under 10 s while the pool is short of nodes (iOS 961fc05a `probeIntervalMultiplier`, audit
    // 2026-11-25 B7), and twice the Wi-Fi 30 s on a metered path once healthy.

    // Per-hostname bound for resolveDnsSeedsIfNeeded()'s parallel lookups.
    private val dnsLookupTimeoutMillis = 5_000L

    // Per-candidate RPC timeout while racing discovery probes in parallel. It also covers the
    // connection's own setup, and TCP, TLS and HTTP/2 on a slow cellular link must fit (iOS
    // 4f8e14bb `connectionTimeout`, 3 s to 5 s); the first responder is published immediately
    // anyway (see probeCycle).
    private val discoveryProbeTimeoutMillis = 5_000L

    // Consecutive completed automatic-mode cycles in which not a single probed node was
    // reachable — drives both the exponential probe backoff in startProbing() (a fully
    // blocked network must not burn battery on a 5s retry loop forever) and, from the
    // second such cycle on, the nodeConnectionsBlocked flag below.
    private var consecutiveDeadCycles = 0

    /** Automatic Scan short of [targetActiveNodes]: consecutive cycles in which the Active count
     *  didn't rise, which stretch the 10 s cadence to 20, 40 and then 60 s (audit 2026-11-25 N8). */
    private var unhealthyStallCycles = 0

    /** Discovered and DNS nodes pruned as dead, and when: gossip and DNS don't bring them back for
     *  an hour, so they aren't dialed again every cycle (audit 2026-11-25 N8). Cleared on a new
     *  network path, where the verdicts no longer hold. */
    private val prunedAt = ConcurrentHashMap<String, Long>()

    /** When the last keepalive probe of the pinned node finished: an answer after it was to
     *  something else, so the node is alive and the next probe is spared (audit 2026-11-25 N9). */
    @Volatile
    private var lastTrustedProbeDoneAt = 0L

    /** Whether an account is signed in ([WalletManager.signedInAddressFlow]). */
    private val signedIn = MutableStateFlow(!walletManager.isLoggedOut && walletManager.activeAddressFlow.value != null)

    /** In the background, and while signed out: closes connections nothing has used for
     *  a while, for as long as any are open (audit 2026-11-25 N6). */
    private var idleCloseJob: Job? = null

    /** Unmetered steady state (see probeCycle): how many Active nodes stay connected and probed
     *  between full waves, and how often (in cycles, ~30 s each) the full wave still runs. */
    private val wifiSteadyStateNodes = 4
    private val wifiFullWaveEveryCycles = 10
    private var cyclesSinceFullWave = 0

    /** The longest wait of a cycle that ended in the background, before the gate pauses (see
     *  startProbing). */
    private val backgroundDelayMillis = 120_000L

    // True once probing has concluded that no Kaspa gRPC node is reachable at all on the
    // current network (every candidate dead for 2+ consecutive automatic-mode cycles, or the
    // pinned trusted node failing 2+ probes in a row). Cleared by the very first successful
    // probe and on any network-path change. Surfaced on the connection screen so a user on a
    // firewalled network gets an honest "gRPC is blocked here" explanation instead of an
    // eternally red dot: indexer-based receiving still works over HTTPS, but sending cannot
    // (payload-carrying transactions only submit over gRPC — see KaspaWalletEngine.sendKaspa).
    private val _nodeConnectionsBlocked = MutableStateFlow(false)
    val nodeConnectionsBlocked: StateFlow<Boolean> = _nodeConnectionsBlocked.asStateFlow()

    // Persisted-known-good bookkeeping — see persistKnownGoodNodes().
    private var lastPersistedKnownGood: Set<String> = emptySet()
    private val maxPersistedKnownGood = 8

    // Network-path-change tracking (WiFi <-> cellular, VPN up/down) — see the
    // ConnectivityManager callback registered in init and onNetworkPathChanged().
    @Volatile
    private var lastNetworkHandle: Long? = null
    @Volatile
    private var lastNetworkResetAt = 0L

    private var probeJob: Job? = null

    // ---- Online / offline (iOS 961fc05a `NetworkEpochMonitor.waitUntilOnline`, audit B7, N9) ----

    /** Whether the device has a default network at all. Offline, the probe loop waits for the
     *  network instead of failing every node, and dropped connections don't redial on their own;
     *  coming back kicks one cycle at once. Any default network counts, validated or not (an
     *  on-demand VPN or a portal that still has to be signed into is reached by trying). */
    private val online = MutableStateFlow(true)

    // ---- Automatic Scan's wss seeds (iOS 961fc05a `NodeProfiler.refreshWssSeeds`) ----

    private val wssSeedStore = WssSeedStore(java.io.File(context.filesDir, "NodePool"), com.kachat.app.util.KaspaNetwork.isTestnet)
    /** The live wss seeds in the pool (automatic mode only), at most [WssSeedStore.MAX_SEEDS]. */
    private val wssSeedEndpoints: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private var lastWssSeedWalkAt = 0L
    /** How long the next walk of the resolvers for wss seeds waits: 0, then 2 minutes doubling to
     *  15 while walks find nothing new. */
    private var wssSeedWalkCooldownMillis = 0L

    // ---- Public wss fallback (iOS 961fc05a `NodePoolService.evaluatePublicFallback`) ----

    /** The default node and Automatic Scan may fall back to public wss nodes; a node the user
     *  pinned themselves ("Connected only to this node") may not. Compared as nodes, so
     *  "node.kaspium.io" with or without its scheme or port is still the default (audit C10). */
    @Volatile
    private var publicFallbackAllowed = true

    /** True while public wss nodes stand in for gRPC nodes that don't answer. */
    private val _usingPublicFallback = MutableStateFlow(false)
    val usingPublicFallback: StateFlow<Boolean> = _usingPublicFallback.asStateFlow()

    /** The node the next request goes to (the trusted node, a stand-in, or the best Active one);
     *  null before there is one. For Connection Status's protocol and node rows. */
    private val _primaryAddress = MutableStateFlow<String?>(null)
    val primaryAddress: StateFlow<String?> = _primaryAddress.asStateFlow()

    private var publicFallbackSince: Long? = null
    private var publicFallbackJob: Job? = null
    private var lastPublicFallbackAttempt: Long? = null
    /** How long after a walk of the resolvers the next may start: 60 s, doubling (to 15 minutes)
     *  while walks find nothing new, back to 60 s once one does (audit 2026-11-25 C8). */
    private var publicFallbackCooldownMillis = PUBLIC_FALLBACK_COOLDOWN_MS
    /** Stand-ins dropped as dead this outage: not taken again from the resolvers. */
    private val deadStandIns = mutableSetOf<String>()
    /** Requests go to a stand-in rather than the pool's own nodes (iOS: the subscription is on a
     *  stand-in). Set when stand-ins are adopted or when gRPC stops answering again; cleared by a
     *  hand-back. */
    @Volatile
    private var preferStandIns = false
    /** Since when gRPC has been serving while stand-ins are in the pool ([considerHandBack]). */
    private var handBackSince: Long? = null
    private var lastHandBackAttempt: Long? = null
    /** Since when gRPC has answered at every look; null after a look with no recent answer. */
    private var grpcSteadySince: Long? = null
    /** How long after a hand-back the next may be tried: 60 s, doubling (to 30 minutes) each time
     *  one doesn't hold (audit 2026-11-25 N5). */
    private var handBackBackoffMillis = 60_000L
    /** A hand-back was made and the stand-ins haven't left since: if requests go back to a
     *  stand-in, it didn't hold. */
    private var handBackUnproven = false
    /** Boot, pin switches, path changes and returns from the background: no fallback in the first
     *  seconds after one, while the gRPC nodes are still being dialed. */
    @Volatile
    private var publicFallbackAnchor = System.currentTimeMillis()
    /** Looks at the public fallback every 20 s while the app is open, on any screen (audit
     *  2026-11-25 N4). Stopped in the background, where nothing is served. */
    private var publicFallbackWatchJob: Job? = null

    // ---- Background (iOS 961fc05a `enterBackground` / `enterForeground`, audit B3, N2) ----

    /** Bumped, synchronously, by every background / foreground change. A phase change's steps stop
     *  after any suspension once it moved, so a late background step can't undo a quick return to
     *  the foreground (audit 2026-11-25 N2). */
    @Volatile
    private var lifecycleGeneration = 0
    private var lifecycleJob: Job? = null

    /** When the known-good list was last written (it is written at most every 5 minutes, and on
     *  background; audit 2026-11-25 N7). */
    private var lastPersistedKnownGoodAt = 0L

    init {
        registry.resetTo(seeds, "Seed")
        KaspadConnection.isNetworkOnline = { online.value }
        ForegroundBeat.isExpensivePath = { meteredNetwork.isMeteredFlow.value }
        scope.launch {
            // Load the persisted trusted-node setting BEFORE probing ever starts, rather than
            // racing a `collect` against `startProbing()`'s own coroutine - without this, on a
            // fresh launch with a trusted node already saved, the very first probe cycle could
            // run in normal discovery mode (DataStore's read hadn't landed yet), kick off
            // seed/DNS probes, and have those results land in the registry *after* the
            // trusted-mode reset below already ran - leaving stale entries "Other Nodes" would
            // then show despite trusted mode being active.
            val initial = settings.trustedNodeAddress.first().trim().ifBlank { null }
            trustedNodeAddress.value = initial
            publicFallbackAllowed = allowsPublicFallback(initial)
            probeEpoch++
            if (initial != null) {
                registry.resetTo(listOf(initial), "Trusted")
            } else {
                // Cold start in automatic mode: dial the persisted last-known-good nodes from
                // the previous run alongside the bundled seeds on the very first cycle —
                // recently-proven addresses connect near-instantly, where the bundled seeds
                // may have gone stale and DNS resolution takes a round trip.
                seedFromPersistedKnownGood()
            }
            startProbing()

            // Now watch for the setting changing while the app is already running.
            settings.trustedNodeAddress.collect { raw ->
                val next = raw.trim().ifBlank { null }
                if (next == trustedNodeAddress.value) return@collect
                trustedNodeAddress.value = next
                probeEpoch++
                // The new choice starts on its own nodes; a public stand-in comes back only if
                // needed (iOS `setTrustedNodeAddress`).
                publicFallbackAllowed = allowsPublicFallback(next)
                dropPublicFallback("node selection changed")
                publicFallbackAnchor = System.currentTimeMillis()
                // Entering or leaving trusted-node mode invalidates every existing
                // connection/registry entry - same reset shape as clearPool().
                connections.values.forEach { it.close() }
                connections.clear()
                if (next != null) {
                    registry.resetTo(listOf(next), "Trusted")
                } else {
                    manualEndpoints.clear()
                    discoveredEndpoints.clear()
                    dnsResolvedEndpoints.clear()
                    wssSeedEndpoints.clear()
                    lastDnsResolveAt = 0L
                    registry.resetTo(seeds, "Seed")
                    // Pinned -> automatic must connect near-instantly: race the persisted
                    // last-known-good nodes from previous automatic sessions in the immediate
                    // refreshNow() below instead of starting from the bundled seeds alone.
                    seedFromPersistedKnownGood()
                }
                consecutiveDeadCycles = 0
                _nodeConnectionsBlocked.value = false
                publish()
                refreshNow()
            }
        }

        registerNetworkCallback()

        // Signed out: probing pauses (see [probingPaused]) and the idle connections close, as in
        // the background (audit 2026-11-25 N12, B6; 2026-11-27 R1).
        scope.launch {
            walletManager.signedInAddressFlow.collect { address ->
                val now = address != null
                if (now == signedIn.value) return@collect
                signedIn.value = now
                if (!now) {
                    closeIdleConnections(idleForMs = 0L, reason = "signed out")
                    ensureIdleClose()
                } else {
                    consecutiveDeadCycles = 0
                    unhealthyStallCycles = 0
                }
            }
        }

        // The app's front and back (iOS KaChatApp's scene phase calling enterBackground /
        // enterForeground): read here rather than called from the Application.
        scope.launch {
            var first = true
            notificationHelper.appForegroundFlow.collect { foreground ->
                if (first) {
                    first = false
                    if (foreground) enterForeground()
                    return@collect
                }
                if (foreground) enterForeground() else enterBackground()
            }
        }
    }

    private fun allowsPublicFallback(pinned: String?): Boolean {
        val trimmed = pinned?.trim().orEmpty()
        if (trimmed.isEmpty()) return true
        return isSameNode(trimmed, AppSettingsRepository.DEFAULT_TRUSTED_NODE_ADDRESS)
    }

    /**
     * Loads the persisted last-known-good node addresses (written by [persistKnownGoodNodes]
     * at the end of every healthy automatic-mode cycle) into [discoveredEndpoints], so the next
     * probe cycle dials them immediately. They intentionally enter as ordinary "Discovered"
     * entries: if one has died since the last run it racks up failures like any other candidate
     * and gets pruned, so a stale persisted list can never wedge the pool.
     */
    private suspend fun seedFromPersistedKnownGood() {
        val persisted = try {
            settings.knownGoodNodeAddresses.first()
        } catch (e: Exception) {
            Log.w("NodePoolManager", "Could not load persisted known-good nodes: ${e.message}")
            emptyList()
        }
        lastPersistedKnownGood = persisted.toSet()
        for (address in persisted) {
            if (discoveredEndpoints.size >= maxDiscoveredEndpoints) break
            if (address !in seeds && com.kachat.app.services.grpc.isGossipedNodeAddress(address)) discoveredEndpoints.add(address)
        }
        if (persisted.isNotEmpty()) {
            Log.i("NodePoolManager", "Seeded pool with ${persisted.size} persisted known-good node(s)")
        }
        // Automatic Scan's saved wss seeds are raced with them, so a launch where gRPC is blocked
        // connects through a known wss node in about a second (iOS 961fc05a).
        val (pruned, live) = wssSeedStore.prune(WssSeedStore.STALE_AFTER_MS)
        if (pruned.isNotEmpty()) Log.i("NodePoolManager", "Dropped ${pruned.size} wss seed(s) with no answer in 24 hours")
        live.take(WssSeedStore.MAX_SEEDS).forEach { wssSeedEndpoints.add(it) }
    }

    /**
     * Persists the pool's current best Active nodes (best-latency first, capped at
     * [maxPersistedKnownGood]) whenever the set actually changed — the other half of
     * [seedFromPersistedKnownGood]'s instant-reconnect path. Automatic mode only; the pinned
     * trusted node is its own persisted setting.
     */
    private fun persistKnownGoodNodes(force: Boolean = false) {
        if (trustedNodeAddress.value != null) return
        // gRPC nodes only: the wss seeds keep their own list (WssSeedStore), and public
        // stand-ins are for this outage only.
        val best = registry.snapshot()
            .filter { registry.statusOf(it) == "Active" && it.type != NodeRegistry.TYPE_PUBLIC && !it.isWebSocket }
            .sortedBy { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
            .take(maxPersistedKnownGood)
            .map { it.address }
        if (best.isEmpty() || best.toSet() == lastPersistedKnownGood) return
        // At most every 5 minutes (and on background): latency jitter reorders the best few on
        // most cycles, and each change used to be a write (iOS 961fc05a, audit N7).
        val now = System.currentTimeMillis()
        if (!force && lastPersistedKnownGood.isNotEmpty() && now - lastPersistedKnownGoodAt < KNOWN_GOOD_SAVE_INTERVAL_MS) return
        lastPersistedKnownGoodAt = now
        lastPersistedKnownGood = best.toSet()
        scope.launch {
            try {
                settings.setKnownGoodNodeAddresses(best)
            } catch (e: Exception) {
                Log.w("NodePoolManager", "Could not persist known-good nodes: ${e.message}")
            }
        }
    }

    /**
     * Watches the device's default network (WiFi <-> cellular handoffs, VPN up/down) and resets
     * node health the moment the path changes. Without this, verdicts formed on the OLD path
     * poison the new one: nodes quarantined behind a captive WiFi portal stayed quarantined
     * after switching to working cellular, connections silently bound to a dead VPN tunnel ate
     * full RPC timeouts, and a fully-blocked verdict ([nodeConnectionsBlocked]) outlived the
     * network that earned it. Mirrors the "network epoch" idea from iOS's POOLS_v2.
     */
    private fun registerNetworkCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        try {
            online.value = cm.activeNetwork != null
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val handle = network.networkHandle
                    val prior = lastNetworkHandle
                    lastNetworkHandle = handle
                    val wasOnline = online.value
                    online.value = true
                    // First sighting after process start is not a change; same handle re-announced
                    // (capability churn on one network) is not a change either.
                    if (prior == null || prior == handle) {
                        // Back online on the same network: the connections that waited redial now
                        // and one cycle runs at once (iOS `waitUntilOnline`, audit B7).
                        if (!wasOnline && prior != null) onBackOnline()
                        return
                    }
                    onNetworkPathChanged()
                }

                override fun onLost(network: Network) {
                    // The default network is gone; another may already be the default.
                    online.value = try { cm.activeNetwork != null && cm.activeNetwork != network } catch (e: Exception) { false }
                    if (!online.value) Log.i("NodePoolManager", "Offline - node probing waits for the network")
                }
            })
        } catch (e: Exception) {
            // Missing permission or an OEM quirk — the pool still works, just without
            // instant path-change recovery (the probe loop eventually re-probes everything).
            Log.w("NodePoolManager", "Could not register network callback: ${e.message}")
        }
    }

    /**
     * The default network actually changed — scrap per-path state and re-probe immediately.
     * Debounced so a flapping VPN (rapid up/down/up) collapses into one reset every 2s instead
     * of a teardown storm that would itself wedge discovery.
     */
    private fun onNetworkPathChanged() {
        val now = System.currentTimeMillis()
        if (now - lastNetworkResetAt < 2_000L) return
        lastNetworkResetAt = now
        publicFallbackAnchor = now
        scope.launch {
            // Many path changes leave the primary's socket working (a VPN coming up, a network
            // appearing beside the one in use). Ask it first: if it answers, its connections are
            // kept, with no teardown (iOS 961fc05a `handleEpochChange`, audit 2026-11-25 B6). A
            // socket the change killed fails the ping.
            val primary = runCatching { broadcastAddress() }.getOrNull()
            val connection = primary?.let { connections[it] }
            val stillAnswers = connection != null && connection.isConnected && try {
                connection.getInfo(timeoutMs = 3_000L)
                true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (stillAnswers) {
                Log.i("NodePoolManager", "Default network changed - primary $primary still answers, connections kept")
                registry.forgiveFailures()
                lastDnsResolveAt = 0L
                consecutiveDeadCycles = 0
                unhealthyStallCycles = 0
                prunedAt.clear()
                _nodeConnectionsBlocked.value = false
                publish()
                return@launch
            }
            resetForNewNetworkPath()
        }
    }

    /** Back online on the network K was on: what waited for the network goes again. While
     *  probing is paused (background, or signed out) nothing is dialed: the return to
     *  the front reconnects (audit 2026-11-25 N4). */
    private fun onBackOnline() {
        publicFallbackAnchor = System.currentTimeMillis()
        consecutiveDeadCycles = 0
        unhealthyStallCycles = 0
        // A channel that kept failing while offline would wait out its own backoff first.
        connections.values.forEach { it.resetConnectBackoff() }
        if (probingPaused()) {
            Log.i("NodePoolManager", "Back online - nothing dialed while paused")
            return
        }
        Log.i("NodePoolManager", "Back online - reconnecting")
        reconnectStaleConnections()
        refreshNow()
    }

    private fun resetForNewNetworkPath() {
        Log.i("NodePoolManager", "Default network changed - resetting node health and reconnecting")
        // In-flight probe results belong to the old path; discard them.
        probeEpoch++
        // Failure verdicts are per-network: a node dead on WiFi gets a fresh 3-strike
        // allowance on the VPN/cellular path instead of staying Quarantined.
        registry.forgiveFailures()
        // Sockets are bound to the old path; dial fresh on the new one.
        connections.values.forEach { it.close() }
        connections.clear()
        // Re-resolve DNS on the new path (a poisoned/blocked resolver may now work, and
        // split-horizon answers can differ per network), and restart backoff from hot.
        lastDnsResolveAt = 0L
        consecutiveDeadCycles = 0
        unhealthyStallCycles = 0
        prunedAt.clear()
        _nodeConnectionsBlocked.value = false
        publish()
        // In the background, or signed out, nothing is dialed on a network change: a new
        // connection here was pinned and kept alive until the next foreground (audit 2026-11-25
        // N4). The return to the front, or signing in, probes at once.
        if (probingPaused()) return
        refreshNow()
    }

    /** Probing pauses while signed out (audit 2026-11-25 N12), and in the background whether or
     *  not push delivers (audit 2026-11-27 R1, R2; B3 and N7 paused it only with push or with
     *  notifications off). Nothing in the no-push background needs a node: the light 1:1 pass and
     *  the room poll read the indexer, and a notification's Reply or a send dials its node on
     *  demand through [connectionFor]. Without this the primary was kept with a 60 s HTTP/2
     *  keepalive all night, and a pool short of 8 nodes ran a full discovery wave every 2 min. */
    private fun probingPaused(): Boolean =
        !signedIn.value || !notificationHelper.isAppInForeground

    private fun activeCount(): Int = registry.snapshot().count { registry.statusOf(it) == "Active" }

    private fun startProbing() {
        probeJob?.cancel()
        probeJob = scope.launch {
            while (true) {
                // Background gate: while the app is BACKGROUNDED, push or not, stop dialing nodes
                // entirely (suspend on the combined flow: no probes, no discovery, no keepalive
                // traffic) and resume instantly on foreground (audit 2026-11-27 R1, R2). On resume
                // a probe cycle runs immediately, and the on-foreground
                // reconnectStaleConnections() call in KaChatApplication independently revives
                // any connections the OS quietly killed during the pause. A background cold start
                // (SyncWorker, an FCM message) waits here too, so it never runs discovery.
                // Signed out it waits too (audit 2026-11-25 N12): nothing on the sign-in screen needs
                // the pool, and a request that does dials its node itself.
                if (probingPaused()) {
                    combine(notificationHelper.appForegroundFlow, signedIn) { foreground, signed ->
                        signed && foreground
                    }.first { it }
                }
                // Offline every probe fails (and used to count against the node): wait for the
                // network instead (iOS 961fc05a `waitUntilOnline`, audit B7).
                if (!online.value) online.first { it }
                // One bad cycle (a node's malformed reply, a channel that wouldn't build) is
                // logged and the loop goes on, instead of probing stopping for good.
                val activeBefore = activeCount()
                val anyReachable = try {
                    probeCycle()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("NodePoolManager", "Probe cycle failed: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
                if (anyReachable) consecutiveDeadCycles = 0 else consecutiveDeadCycles++
                val activeAfter = activeCount()
                unhealthyStallCycles = NodeProbeCadence.nextStallCycles(unhealthyStallCycles, activeBefore, activeAfter, targetActiveNodes)
                val wait = if (trustedNodeAddress.value != null) {
                    // Trusted mode's registry only ever holds the one pinned node, so it is never
                    // compared with targetActiveNodes. This probe is the pinned node's keepalive, on
                    // the foreground beat (30 s, 60 s on a metered path) so it wakes the radio with
                    // the other polls (audit 2026-11-25 N9, B3). A pinned node that doesn't answer
                    // backs off like a dead pool, to two minutes (audit N13).
                    NodeProbeCadence.trusted(consecutiveDeadCycles, trustedKeepaliveMillis())
                } else {
                    // Short of a healthy pool (a fresh launch, a network that blocks most nodes):
                    // 10 s, stretching to 60 s while the Active count doesn't rise (audit N8). Every
                    // node dead: 20 s doubling to two minutes, until something answers; a network
                    // change resets both. Healthy: the foreground beat, and probeCycle itself shrinks
                    // to the primary on a metered path.
                    NodeProbeCadence.automatic(
                        consecutiveDeadCycles, activeAfter, targetActiveNodes, unhealthyStallCycles, meteredNetwork.isMetered,
                        beatMs = ForegroundBeat.intervalMs,
                    )
                }
                val delayMillis = wait.millis
                if (!notificationHelper.isAppInForeground) {
                    // Went to the background during this cycle: the gate above pauses the next one.
                    // The front of the app ends this wait at once.
                    kotlinx.coroutines.withTimeoutOrNull(maxOf(delayMillis, backgroundDelayMillis)) {
                        notificationHelper.appForegroundFlow.first { it }
                    }
                } else if (wait.onBeat && wait.millis > ForegroundBeat.intervalMs) {
                    // A stretched unhealthy probe: the first beat at least that far away (R6).
                    ForegroundBeat.sleepUntilBeatAtLeast(wait.millis, offsetMs = PROBE_BEAT_OFFSET_MS)
                } else if (wait.onBeat) {
                    ForegroundBeat.sleepUntilNextBeat(offsetMs = PROBE_BEAT_OFFSET_MS)
                } else {
                    delay(delayMillis)
                }
            }
        }
    }

    /** The pool's one connection to [address], opened if there is none. Two callers racing here
     *  get the same one: the loser's connection is closed before it ever connects (a plain
     *  getOrPut could run its builder for both and leave one open for good). */
    private fun connectionFor(address: String): KaspadConnection {
        connections[address]?.let { return it }
        // On a metered path the HTTP/2 keepalive is 45 s, not 20 (iOS 961fc05a, audit B4).
        val keepAlive = if (meteredNetwork.isMeteredFlow.value) KaspadConnection.METERED_KEEPALIVE_SECONDS else KaspadConnection.KEEPALIVE_SECONDS
        val created = KaspadConnection(address, scope, keepAlive)
        val existing = connections.putIfAbsent(address, created)
        if (existing != null) {
            created.close()
            return existing
        }
        created.connect()
        // Opened while the pool is idle (a send from a notification, a background wake): closed
        // again once nothing uses it (audit 2026-11-25 N6).
        ensureIdleClose()
        return created
    }

    /** Closes the pool's connection to [address] unless it is pinned or a request is waiting on
     *  it (iOS 83806b3d `closeProbeConnection`). */
    private fun closeUnusedConnection(address: String) {
        val connection = connections[address] ?: return
        if (connection.isPinned || connection.hasPendingRequests || address == trustedNodeAddress.value) return
        if (connections.remove(address, connection)) connection.close()
    }

    /**
     * Resolves [dnsSeedHostnames] to IP addresses (mirrors iOS's NodeProfiler.refreshDNSSeeds) —
     * the hardcoded [seeds] IPs above have no way to recover if they go stale/unreachable, so this
     * gives the pool an independent way to find fresh nodes without an app update. Cooldown-gated
     * so a run of unhealthy 30s probe cycles doesn't turn into a DNS-lookup hot loop.
     *
     * Looks up all hostnames in parallel, each bounded by [dnsLookupTimeoutMillis] — this used to
     * be a sequential `for` loop calling the blocking `InetAddress.getAllByName` one hostname at a
     * time with no timeout, which meant a single slow/hanging resolver lookup delayed every other
     * hostname behind it, and — since this runs unconditionally on the very first probe cycle,
     * gating node discovery on a cold launch — could stall the whole pool's first probe pass for
     * many seconds before a single node was even attempted.
     */
    private suspend fun resolveDnsSeedsIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastDnsResolveAt < dnsResolveCooldownMillis) return
        lastDnsResolveAt = now
        val resolved = coroutineScope {
            dnsSeedHostnames.map { hostname ->
                async {
                    try {
                        withTimeoutOrNull(dnsLookupTimeoutMillis) {
                            // Accept both IPv4 and IPv6 answers — on-device testing on an
                            // IPv6-heavy/NAT64 network found these hostnames resolving almost
                            // entirely to AAAA records, so an IPv4-only filter (as iOS's
                            // resolveDNSSeed does, matching hints.ai_family = AF_INET) left this
                            // fallback with zero usable endpoints whenever the hardcoded IPv4
                            // [seeds] were also down. IPv6 literals get bracketed for the gRPC
                            // target string, matching standard host:port authority syntax (RFC 3986).
                            java.net.InetAddress.getAllByName(hostname).map { addr ->
                                val host = if (addr is java.net.Inet6Address) "[${addr.hostAddress}]" else addr.hostAddress
                                "$host:$dnsSeedPort"
                            }
                        } ?: emptyList()
                    } catch (e: Exception) {
                        // This seed's DNS lookup failed (resolver down, host unreachable, etc.) —
                        // the cooldown above means all of them get retried again shortly.
                        Log.w("NodePoolManager", "DNS lookup failed for $hostname: ${e.javaClass.simpleName}: ${e.message}")
                        emptyList()
                    }
                }
            }.awaitAll()
        }.flatten()
        for (endpoint in resolved) {
            if (dnsResolvedEndpoints.size >= maxDnsResolvedEndpoints) break
            if (recentlyPruned(endpoint)) continue
            dnsResolvedEndpoints.add(endpoint)
        }
    }

    /**
     * Automatic Scan keeps up to three public wRPC nodes (`wss://` on 443, from the Kaspa
     * resolvers) among its seed candidates (iOS 961fc05a `refreshWssSeeds`, owner 2026-11-25), so a
     * network that blocks gRPC still finds nodes: they're raced with the gRPC seeds, ranked by the
     * same rules (below gRPC), and saved, so the next launch dials a known one at once. Run beside
     * the DNS seed refresh; nothing while a node is pinned. A wss seed with no answer in 24 hours
     * is dropped and only live ones count toward the three; a walk of the resolvers that finds
     * nothing new waits longer before the next (2 minutes, doubling to 15; audit C8).
     */
    private suspend fun refreshWssSeedsIfNeeded() {
        if (trustedNodeAddress.value != null) return
        val (removed, live) = wssSeedStore.prune(WssSeedStore.STALE_AFTER_MS)
        for (address in removed) {
            wssSeedEndpoints.remove(address)
            connections.remove(address)?.close()
            registry.remove(address)
        }
        if (removed.isNotEmpty()) Log.i("NodePoolManager", "Dropped ${removed.size} wss seed(s) with no answer in 24 hours")
        live.take(WssSeedStore.MAX_SEEDS).forEach { wssSeedEndpoints.add(it) }
        val wanted = WssSeedStore.MAX_SEEDS - live.size
        if (wanted <= 0) return
        val now = System.currentTimeMillis()
        if (lastWssSeedWalkAt != 0L && now - lastWssSeedWalkAt < wssSeedWalkCooldownMillis) return
        lastWssSeedWalkAt = now
        val found = PublicNodeResolver.resolve(com.kachat.app.util.KaspaNetwork.isTestnet, wanted, live.toSet())
        if (trustedNodeAddress.value != null) return
        val added = found.filter { wssSeedStore.add(it) }
        if (added.isEmpty()) {
            wssSeedWalkCooldownMillis = minOf(15 * 60_000L, maxOf(2 * 60_000L, wssSeedWalkCooldownMillis * 2))
            return
        }
        wssSeedWalkCooldownMillis = 0L
        // Dialed in this same cycle (its second wave) rather than left for the next.
        added.forEach { wssSeedEndpoints.add(it) }
        Log.i("NodePoolManager", "Added ${added.size} public wss node(s) as seed candidates")
    }

    /** Classification for registry rows — depends on which tracked set the address lives in. */
    private fun typeOf(address: String): String = when {
        registry.snapshot().any { it.address == address && it.type == NodeRegistry.TYPE_PUBLIC } -> NodeRegistry.TYPE_PUBLIC
        seeds.contains(address) || wssSeedEndpoints.contains(address) -> "Seed"
        manualEndpoints.contains(address) -> "Manual"
        dnsResolvedEndpoints.contains(address) -> "DNS"
        else -> "Discovered"
    }

    /**
     * Races probes of [addresses] in parallel (each bounded by [discoveryProbeTimeoutMillis])
     * and publishes every SUCCESS the moment it lands — the first responder starts serving
     * status/broadcast duty immediately while the slower candidates are still being profiled,
     * instead of the old await-everything-then-publish shape where one dead candidate's full
     * timeout held the whole pool at "Disconnected". Failures are only written by the caller
     * after the wave completes (and only if the epoch still matches), keeping the stale-cycle
     * discard semantics for everything except early good news.
     */
    private suspend fun probeWave(addresses: List<String>, epoch: Int): List<NodeProbeResult> =
        addresses.map { address ->
            scope.async {
                // Whether this probe opens the connection or finds one already up (iOS 83806b3d
                // `closeProbeConnectionIfUnused`'s `wasConnected`).
                val wasConnected = connections[address]?.isConnected == true
                // An address no channel can be built for counts as unreachable, not as the whole
                // wave failing.
                val connection = try {
                    connectionFor(address)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w("NodePoolManager", "No channel for $address: ${e.javaClass.simpleName}")
                    return@async NodeProbeResult(address, false, null, null, null, null, null, null, error = e.javaClass.simpleName)
                }
                val result = probeExisting(address, connection, discoveryProbeTimeoutMillis)
                if (!result.reachable) {
                    // The underlying stream likely died — drop it so the next cycle
                    // opens a fresh connection instead of retrying a broken one forever.
                    connections.remove(address)?.close()
                } else {
                    if (epoch == probeEpoch) {
                        registry.update(address, typeOf(address), result)
                        _nodeConnectionsBlocked.value = false
                        publish()
                    }
                    // A node that answers but isn't synced can't be used: the connection this probe
                    // opened for it closes again rather than staying up (keepalive pings, and a
                    // reconnect on every drop) - iOS 83806b3d. Peer gossip reopens one if it needs it.
                    if (!wasConnected && result.isSynced == false) closeUnusedConnection(address)
                }
                result
            }
        }.awaitAll()

    /** How often the pinned node is probed: its keepalive, the foreground beat (30 s, 60 s on a
     *  metered path; 30 s / 45 s on its own timer before, audit 2026-11-25 N9). */
    private fun trustedKeepaliveMillis(): Long = ForegroundBeat.intervalMs

    /** Whether [address] was pruned as dead within the last hour (audit 2026-11-25 N8). */
    /** Notes [address] as pruned. Gossip can name a new address every cycle for the life of the
     *  process, so the memory is kept to [MAX_PRUNED_MEMORY] entries: the expired ones go first,
     *  then the oldest (audit 2026-11-27 R9). */
    private fun rememberPruned(address: String, nowMs: Long = System.currentTimeMillis()) {
        prunedAt[address] = nowMs
        if (prunedAt.size <= MAX_PRUNED_MEMORY) return
        prunedAt.entries.removeIf { nowMs - it.value >= PRUNED_MEMORY_MS }
        val excess = prunedAt.size - MAX_PRUNED_MEMORY
        if (excess > 0) {
            prunedAt.entries.sortedBy { it.value }.take(excess).forEach { prunedAt.remove(it.key, it.value) }
        }
    }

    private fun recentlyPruned(address: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val at = prunedAt[address] ?: return false
        if (nowMs - at < PRUNED_MEMORY_MS) return true
        prunedAt.remove(address, at)
        return false
    }

    /** Returns true when at least one probed node was reachable this cycle (epoch-discarded
     *  cycles report true so they stay neutral for the dead-cycle backoff in startProbing). */

    private suspend fun probeCycle(force: Boolean = false): Boolean {
        // Captured up front so a mode switch (trusted <-> normal discovery) that happens
        // *while this cycle's own probes are in flight* can be detected below and the whole
        // cycle's results discarded instead of writing stale seed/discovered entries into the
        // registry after a resetTo() already ran for the new mode.
        val epoch = probeEpoch

        // Trusted-node mode: no discovery at all - just keep this one connection alive and
        // report its health. No seeds, no DNS, no peer-gossip, no latency-based selection.
        val trusted = trustedNodeAddress.value
        if (trusted != null) {
            // Public stand-ins stay up beside the pinned node while they serve.
            val standIns = registry.publicFallbacks().map { it.address }.toSet()
            connections.keys.filter { it != trusted && it !in standIns }.forEach { addr -> connections.remove(addr)?.close() }
            // The pinned node pushed a notification, or answered a request other than the last
            // probe, within the keepalive interval: it is alive and its connection isn't idle, so
            // the probe is spared (iOS 961fc05a `checkPrimaryHealth`; audit 2026-11-25 N9). A
            // Refresh always probes.
            val existing = connections[trusted]
            val answeredOther = existing?.lastResponseAtMs?.takeIf { it > lastTrustedProbeDoneAt }
            val spokeAt = listOfNotNull(existing?.lastNotificationAtMs, answeredOther).maxOrNull()
            if (!force && existing != null && existing.isConnected && standIns.isEmpty() &&
                spokeAt != null && System.currentTimeMillis() - spokeAt < trustedKeepaliveMillis()
            ) {
                registry.recordAlive(trusted)
                _nodeConnectionsBlocked.value = false
                publish()
                syncPinnedConnections()
                return true
            }
            val trustedConnection = connectionFor(trusted)
            val result = probeExisting(trusted, trustedConnection)
            lastTrustedProbeDoneAt = System.currentTimeMillis()
            if (epoch != probeEpoch) return true
            // A stream that died reconnects on its own channel, whose own backoff spaces the
            // dials, so it is kept (a new channel and TLS handshake every cycle before, audit
            // 2026-11-25 N13). One that still looks up but didn't answer may be a socket that died
            // silently: that one is dialed fresh.
            if (!result.reachable && trustedConnection.isConnected) {
                if (connections.remove(trusted, trustedConnection)) trustedConnection.close()
            }
            registry.update(trusted, "Trusted", result)
            // The stand-ins are probed too: dead ones are replaced, live ones ranked.
            if (standIns.isNotEmpty()) {
                val standInResults = probeWave(standIns.toList(), epoch)
                if (epoch != probeEpoch) return true
                val stillIn = registry.publicFallbacks().map { it.address }.toSet()
                standInResults.filter { !it.reachable && it.address in stillIn }
                    .forEach { registry.update(it.address, NodeRegistry.TYPE_PUBLIC, it) }
            }
            val standInServing = registry.publicFallbacks().any { registry.statusOf(it) == "Active" }
            // With discovery off, the pinned node is the only reachability signal there is:
            // 2+ consecutive failed probes is the honest "node connections look blocked or
            // down here" threshold for the connection screen (one failure could be a blip).
            val failures = registry.snapshot().firstOrNull { it.address == trusted }?.consecutiveFailures ?: 0
            _nodeConnectionsBlocked.value = !result.reachable && failures >= 2 && !standInServing
            publish()
            syncPinnedConnections()
            return result.reachable || standInServing
        }

        // Gated on truly *Active* count, not just "not yet Quarantined" — a fresh/unprobed seed
        // starts out "Suspect" rather than Quarantined, so gating on non-Quarantined meant this
        // never fired until the hardcoded seeds above had each racked up 3 full failed cycles
        // (~90s) to formally flip to Quarantined. If those seeds are all actually dead (as they
        // periodically seem to go), that's 90 seconds of zero connectivity on every fresh launch
        // before the DNS fallback ever got a chance to run. Checking Active directly means this
        // fires on the very first cycle whenever there isn't already a healthy pool. Compared
        // against targetActiveNodes (not a small fixed number) so this keeps refreshing DNS
        // seeds until the pool is genuinely healthy, not just "a few seeds happened to respond."
        //
        // Launched CONCURRENTLY with the first probe wave below (it used to be awaited first),
        // so a slow resolver never delays dialing the seeds/persisted known-good nodes we
        // already have in hand — fresh DNS answers get their own probe wave later this cycle.
        val activeCount = registry.snapshot().count { registry.statusOf(it) == "Active" }
        // A cycle that runs in the background (a Refresh or a network change that raced the move
        // to the back; the loop itself pauses there) never discovers: no DNS, no wss walk, no
        // peer gossip, and only the primary is probed, whatever the pool's health (audit
        // 2026-11-27 R2). Discovery waits for the front.
        val background = !notificationHelper.isAppInForeground
        val discover = activeCount < targetActiveNodes && !background
        // What the DNS and wss refreshes add this cycle is probed in its second wave below; what
        // was there already is part of the first wave's plan, not probed again on top of it.
        val seedCandidatesBefore = (dnsResolvedEndpoints.toList() + wssSeedEndpoints.toList()).toSet()
        val dnsJob = if (discover) scope.async { resolveDnsSeedsIfNeeded() } else null
        // The public wss seeds are looked up beside the DNS seeds (iOS `refreshWssSeeds`): where
        // gRPC is blocked they are the nodes that answer.
        val wssJob = if (discover) scope.async { refreshWssSeedsIfNeeded() } else null

        // Metered steady state: with a healthy pool on cellular, probing all ~26 endpoints
        // every cycle — and keeping a persistent gRPC channel (20s HTTP/2 keepalive pings each)
        // open to every one of them — is pure background cost. Probe ONLY the current primary
        // (the same best-active node getBroadcastConnection() serves); the untracked-connection
        // sweep below then closes every other idle channel, which is the "close idle discovered
        // channels between probe cycles" option rather than per-connection keepalive tuning:
        // each KaspadConnection holds its MessageStream open for its whole life, so
        // keepAliveWithoutCalls(false) would change nothing — the stream IS an active call.
        // There is no reopen churn while the state holds (closed channels stay closed; nothing
        // redials them until the pool turns unhealthy or the primary fails over). Registry
        // verdicts for the unprobed nodes just go stale, which a status display tolerates; if
        // the primary dies, the next cycle promotes the next stale-Active node, redials it, and
        // failures decay the truly dead ones until the pool drops below target and full
        // discovery resumes automatically.
        //
        // Unmetered (Wi-Fi) steady state, the same idea less strictly: the best
        // [wifiSteadyStateNodes] Active nodes (the primary, its standby and the cross-check node
        // among them) are probed each cycle and every other channel is closed by the sweep below,
        // instead of ~26 channels each pinging every 20 s. The full wave still runs every
        // [wifiFullWaveEveryCycles]th cycle, so the rest of the pool's verdicts (and the node
        // list) stay fresh, and at once whenever the pool drops below target.
        val standIns = registry.publicFallbacks().map { it.address }
        val fullWave = (seeds + manualEndpoints + discoveredEndpoints + dnsResolvedEndpoints + wssSeedEndpoints + standIns).distinct()
        val healthy = activeCount >= targetActiveNodes
        val plan = NodeWavePlan.kind(
            background = background,
            metered = meteredNetwork.isMetered,
            healthy = healthy,
            activeCount = activeCount,
            cyclesSinceFullWave = cyclesSinceFullWave,
            fullWaveEveryCycles = wifiFullWaveEveryCycles,
        )
        val primaryWave = registry.activeBestFirst().firstOrNull()?.let { listOf(it.address) }
        val firstWave = when (plan) {
            // In the background with no Active node there is nothing to keep up: nothing is
            // dialed, and the front runs discovery.
            NodeWavePlan.Kind.BACKGROUND_PRIMARY -> primaryWave ?: return false
            NodeWavePlan.Kind.PRIMARY -> primaryWave ?: fullWave
            NodeWavePlan.Kind.STEADY -> {
                cyclesSinceFullWave++
                registry.activeBestFirst().take(wifiSteadyStateNodes).map { it.address }
                    .ifEmpty { fullWave }
            }
            // A pool short of nodes on a metered path: the nodes that answered and the saved
            // known-good ones, with the full ~45-endpoint wave only every
            // [wifiFullWaveEveryCycles]th cycle (audit 2026-11-27 R6).
            NodeWavePlan.Kind.KNOWN_GOOD -> {
                cyclesSinceFullWave++
                val answered = registry.snapshot().filter { it.lastProbe?.reachable == true }.map { it.address }
                (answered + lastPersistedKnownGood.filter { it in fullWave }).distinct().ifEmpty { fullWave }
            }
            NodeWavePlan.Kind.FULL -> {
                cyclesSinceFullWave = 0
                fullWave
            }
        }.let { wave -> (wave + standIns).distinct() }
        val firstResults = probeWave(firstWave, epoch)

        // Probe whatever the concurrent DNS resolution just added in this same cycle rather
        // than sitting on it until the next tick — on a cold start where the bundled seeds
        // have all gone stale, these fresh answers ARE the pool.
        dnsJob?.await()
        wssJob?.await()
        // Only what this cycle's refreshes added: the whole DNS and wss list used to ride along
        // every cycle, which undid the primary-only and steady-state waves.
        val secondWave = (dnsResolvedEndpoints.toList() + wssSeedEndpoints.toList())
            .filter { it !in seedCandidatesBefore && it !in firstWave }
            .distinct()
        val secondResults = if (secondWave.isNotEmpty()) probeWave(secondWave, epoch) else emptyList()

        // The user may have switched into trusted-node mode while these probes were still in
        // flight - resetTo("Trusted") already ran for that switch, so writing this normal-mode
        // cycle's results now would just re-populate the registry with stale seed/discovered
        // entries right after they were supposed to be cleared.
        if (epoch != probeEpoch) return true

        val results = firstResults + secondResults
        val probedAddresses = firstWave + secondWave

        // Drop connections for addresses no longer tracked (e.g. after clearPool()). In the
        // background a connection a Reply or a send is waiting on is left to finish; the idle
        // sweep closes it after.
        connections.entries.filter { (addr, conn) -> addr !in probedAddresses && !(background && conn.hasPendingRequests) }
            .forEach { (addr, conn) -> if (connections.remove(addr, conn)) conn.close() }
        if (background) ensureIdleClose()

        results.forEach { result ->
            registry.update(result.address, typeOf(result.address), result)
            if (result.reachable && result.address in wssSeedEndpoints) wssSeedStore.recordSuccess(result.address)
        }

        // Prune discovered/DNS-resolved nodes that have gone bad, freeing room for potentially
        // better ones and bounding long-term resource usage — never prune Seed/Manual,
        // those are always intentionally tracked regardless of health.
        registry.snapshot()
            .filter { (it.type == "Discovered" || it.type == "DNS") && registry.statusOf(it) == "Quarantined" }
            .forEach { record ->
                rememberPruned(record.address)
                discoveredEndpoints.remove(record.address)
                dnsResolvedEndpoints.remove(record.address)
                connections.remove(record.address)?.close()
                registry.remove(record.address)
            }

        // Peer-gossip discovery: only kick in while the pool is unhealthy, reusing an existing
        // connection rather than opening a new one just for this — combined with DNS-seed
        // resolution above, this is the full v1 discovery mechanism (no aggressive/conservative
        // pacing beyond the cooldown/cap already in place).
        //
        // Bootstraps from the best *reachable* node (Active or Suspect), not just a fully
        // "Active" one — GetPeerAddresses only needs a live gRPC response, not a fully synced
        // node, and requiring Active here meant discovery could never get off the ground at
        // all if every seed was reachable-but-unsynced (Suspect) rather than cleanly Active.
        //
        // Gated on the pool's overall Active count against targetActiveNodes, not just the 6
        // hardcoded seeds specifically — the previous "fewer than 3 of the *seeds*" check meant
        // gossip discovery stopped expanding the moment 3 of those 6 fixed IPs happened to
        // respond, even if the pool's real active total was still small. iOS's equivalent keeps
        // discovering until it reaches a real active-node target (8-12), not until a handful of
        // specific bootstrap addresses look fine.
        val activeCountAfterProbe = registry.snapshot().count { registry.statusOf(it) == "Active" }
        if (!background && activeCountAfterProbe < targetActiveNodes && discoveredEndpoints.size < maxDiscoveredEndpoints) {
            val discoverRecord = registry.snapshot()
                .filter { registry.statusOf(it) != "Quarantined" }
                .minByOrNull { it.lastProbe?.latencyMs ?: Long.MAX_VALUE }
            val discoverFrom = discoverRecord?.address
            // Opened for this when the probe closed it (an unsynced node), and closed again after
            // unless the node is Active (iOS 83806b3d `discoverFromNode`).
            val discoveryWasConnected = discoverFrom?.let { connections[it]?.isConnected == true } ?: false
            val conn = discoverFrom?.let { address ->
                // only a node that answered its probe is dialed again for this
                connections[address] ?: if (discoverRecord?.lastProbe?.reachable == true) {
                    try { connectionFor(address) } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        null
                    }
                } else null
            }
            if (conn != null && discoverFrom != null) {
                try {
                    conn.getPeerAddresses().addressesList.map { it.addr }.forEach { addr ->
                        // Only a well-formed host:port: any node can answer this, and a target
                        // gRPC can't build throws in the next probe wave. Peers gossip their P2P
                        // port, which is tried on the gRPC port; other ports, and nodes pruned
                        // within the hour, are skipped (audit 2026-11-25 N8).
                        val candidate = gossipedGrpcCandidate(addr, dnsSeedPort) ?: return@forEach
                        if (!seeds.contains(candidate) && !manualEndpoints.contains(candidate) && !recentlyPruned(candidate) &&
                            discoveredEndpoints.size < maxDiscoveredEndpoints
                        ) {
                            discoveredEndpoints.add(candidate)
                        }
                    }
                } catch (e: Exception) {
                    // Ignore — next cycle will retry with whatever's active then.
                    Log.w("NodePoolManager", "Peer-gossip discovery via $discoverFrom failed: ${e.javaClass.simpleName}: ${e.message}")
                }
                val activeNow = registry.snapshot().any { it.address == discoverFrom && registry.statusOf(it) == "Active" }
                if (!discoveryWasConnected && !activeNow) closeUnusedConnection(discoverFrom)
            }
        }

        syncPinnedConnections()

        publish()

        // Remember today's best Active nodes for tomorrow's cold start / pinned->automatic
        // switch — the other half of the instant-connect path (see seedFromPersistedKnownGood).
        persistKnownGoodNodes()

        val anyReachable = results.any { it.reachable }
        if (anyReachable) {
            _nodeConnectionsBlocked.value = false
        } else if (results.size >= 3 && consecutiveDeadCycles >= 1) {
            // A meaningful candidate set (seeds + persisted + DNS answers) came back 100% dead
            // for the second-plus consecutive cycle: the gRPC port is almost certainly blocked
            // on this network (or the uplink is gone). Surfaced on the connection screen; any
            // single success or a network-path change clears it instantly.
            _nodeConnectionsBlocked.value = true
        }
        return anyReachable
    }

    private fun publish() {
        val allInfo = registry.snapshot()
            .map(::toNodeInfo)
            .sortedWith(
                compareByDescending<NodeInfo> { it.status == "Active" }
                    .thenBy { it.latency.removeSuffix("ms").toIntOrNull() ?: Int.MAX_VALUE }
            )
        _allNodes.value = allInfo
        _activeNodes.value = allInfo.filter { it.status == "Active" }
        _primaryAddress.value = runCatching { broadcastAddress() }.getOrNull()
            ?.takeIf { address -> registry.snapshot().any { it.address == address && registry.statusOf(it) == "Active" } }
    }

    private fun toNodeInfo(record: NodeRecord): NodeInfo {
        val status = registry.statusOf(record)
        val color = when (status) {
            "Active" -> 0xFF4CD964
            "Suspect" -> 0xFFF39C12
            else -> 0xFFFF3B30 // Quarantined / unreachable
        }
        return NodeInfo(
            ip = record.address,
            type = record.type,
            latency = record.lastProbe?.latencyMs?.let { "${it}ms" } ?: "—",
            daaScore = record.lastProbe?.virtualDaaScore?.toString() ?: "N/A",
            status = status,
            color = color
        )
    }

    /** Real "Last Sync" source — most recent successful probe across every known node. */
    fun lastSuccessAt(): Long? = registry.lastSuccessAt()

    /**
     * Returns a connection to a currently healthy node, for broadcasting a transaction
     * via gRPC SubmitTransaction — bypasses the REST gateway, which mishandles
     * payload-carrying transactions (see KaspadConnection.submitTransaction). Prefers
     * the best-known Active node; falls back to a DNS-resolved address if one's already
     * been found (more likely to actually be reachable than the static seed list if
     * those have gone stale), and only as a last resort to the first hardcoded seed.
     */
    fun getBroadcastConnection(): KaspadConnection = connectionFor(broadcastAddress())

    /** The best Active public stand-in with a connection, while requests go to stand-ins. */
    private fun servingStandIn(): String? {
        if (!preferStandIns) return null
        return registry.activeBestFirst()
            .firstOrNull { it.type == NodeRegistry.TYPE_PUBLIC && connections.containsKey(it.address) }
            ?.address
    }

    /** The node that answers for a pinned node: the pinned node itself, or a public stand-in while
     *  it can't be reached (the default node only); null in automatic mode. */
    private fun pinnedServing(): String? {
        val trusted = trustedNodeAddress.value ?: return null
        return servingStandIn() ?: trusted
    }

    /** The node [getBroadcastConnection] hands out: the trusted node (or its public stand-in),
     *  else a stand-in while gRPC fails, else the best Active node that has a connection, else a
     *  DNS answer or the first seed. */
    private fun broadcastAddress(): String {
        pinnedServing()?.let { return it }
        servingStandIn()?.let { return it }
        registry.activeBestFirst().firstOrNull()?.address?.takeIf { connections.containsKey(it) }?.let { return it }
        // While probing is paused (the background, signed out) the idle connections were closed
        // by the pause, not because their node failed: the best Active node is still the best bet
        // for a Reply, then a saved known-good node, before a bundled seed (audit 2026-11-27 R1).
        if (probingPaused()) {
            registry.activeBestFirst().firstOrNull()?.address?.let { return it }
            dnsResolvedEndpoints.firstOrNull()?.let { return it }
            lastPersistedKnownGood.firstOrNull()?.let { return it }
        }
        return dnsResolvedEndpoints.firstOrNull() ?: seeds.first()
    }

    /**
     * Marks the connections that reconnect without end: the trusted node, or the primary (the
     * one the next broadcast uses, where the block scanners subscribe) and its standby, the next
     * best Active node (iOS 83806b3d `UtxoSubscriptionManager.syncPinnedConnections`). Any other
     * connection reconnects a few times while it was used lately, then waits for a request.
     */
    private fun syncPinnedConnections() {
        // Public stand-ins stay pinned while they're in the pool: they serve while gRPC fails.
        val standIns = registry.publicFallbacks().map { it.address }.toSet()
        val pinned = trustedNodeAddress.value?.let { setOf(it) + standIns } ?: run {
            val primary = runCatching { broadcastAddress() }.getOrNull()
            val standby = registry.activeBestFirst().map { it.address }.firstOrNull { it != primary }
            setOfNotNull(primary, standby) + standIns
        }
        connections.forEach { (address, connection) -> connection.isPinned = address in pinned }
    }

    /**
     * The UTXOs at [address] from the node the next broadcast would use, in the same shape the
     * REST gateway returns them, or null when the node cannot answer (no UTXO index, dead
     * stream, timeout) so the caller can fall back to REST. The spend path asks here first: a
     * burst of sends used to hit api.kaspa.org once per send for the UTXO set and got HTTP 429
     * back, failing the message. A node has no such limit, and it is what iOS asks.
     */
    suspend fun getUtxosByAddress(address: String): List<UtxoEntry>? {
        return try {
            getBroadcastConnection().getUtxosByAddresses(listOf(address)).map { entry ->
                UtxoEntry(
                    address = entry.address.ifEmpty { address },
                    outpoint = Outpoint(transactionId = entry.outpoint.transactionId, index = entry.outpoint.index),
                    utxoEntry = UtxoData(
                        amount = entry.utxoEntry.amount,
                        scriptPublicKey = ScriptPublicKey(entry.utxoEntry.scriptPublicKey.scriptPublicKey),
                        blockDaaScore = entry.utxoEntry.blockDaaScore,
                        isCoinbase = entry.utxoEntry.isCoinbase,
                        covenantId = entry.utxoEntry.covenantId.ifEmpty { null }
                    )
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // The node's own request timeout: a failed node, so REST is asked instead.
            Log.w("NodePoolManager", "Node UTXO fetch timed out, falling back to REST: ${e.message}")
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A cancelled caller is not a failed node: no REST fallback, the cancel goes on.
            throw e
        } catch (e: Exception) {
            Log.w("NodePoolManager", "Node UTXO fetch failed, falling back to REST: ${e.message}")
            null
        }
    }

    /**
     * The UTXOs at [addresses] from a node, never REST (iOS `getUtxosByAddresses(_:allowRestFallback:
     * false)`): only a node's answer carries each coin's covenant id, which is what tells a kUSDT
     * coin from a look-alike. Throws when the node can't answer.
     */
    suspend fun getUtxosByAddressesFromNode(addresses: List<String>): List<UtxoEntry> {
        if (addresses.isEmpty()) return emptyList()
        return getBroadcastConnection().getUtxosByAddresses(addresses).map { entry ->
            UtxoEntry(
                address = entry.address,
                outpoint = Outpoint(transactionId = entry.outpoint.transactionId, index = entry.outpoint.index),
                utxoEntry = UtxoData(
                    amount = entry.utxoEntry.amount,
                    scriptPublicKey = ScriptPublicKey(entry.utxoEntry.scriptPublicKey.scriptPublicKey),
                    blockDaaScore = entry.utxoEntry.blockDaaScore,
                    isCoinbase = entry.utxoEntry.isCoinbase,
                    covenantId = entry.utxoEntry.covenantId.ifEmpty { null }
                )
            )
        }
    }

    /**
     * [getUtxosByAddressesFromNode], asked of two different nodes - the primary and the next best
     * Active one - keeping only the coins both list identically (security audit M6: the pool's
     * nodes are gossip-discovered and spoken to over plaintext gRPC, so one node's word isn't
     * proof). The trusted node, when one is set, is asked alone: the person chose to trust it.
     * Throws when fewer than two nodes answer.
     */
    suspend fun getUtxosByAddressesAgreed(addresses: List<String>): List<UtxoEntry> {
        if (addresses.isEmpty()) return emptyList()
        if (pinnedServing() != null) return getUtxosByAddressesFromNode(addresses)
        val primary = broadcastAddress()
        val second = registry.activeBestFirst().map { it.address }.firstOrNull { it != primary }
            ?: throw IllegalStateException("no second node to cross-check with")
        fun key(entry: protowire.Rpc.RpcUtxosByAddressesEntry) = listOf(
            entry.outpoint.transactionId.lowercase(), entry.outpoint.index.toString(), entry.utxoEntry.amount.toString(),
            entry.utxoEntry.scriptPublicKey.scriptPublicKey.lowercase(), entry.utxoEntry.covenantId.lowercase(),
        ).joinToString("|")
        val (first, other) = coroutineScope {
            val a = async { connectionFor(primary).getUtxosByAddresses(addresses) }
            val b = async { connectionFor(second).getUtxosByAddresses(addresses) }
            a.await() to b.await()
        }
        val agreed = other.mapTo(HashSet()) { key(it) }
        return first.filter { key(it) in agreed }.map { entry ->
            UtxoEntry(
                address = entry.address,
                outpoint = Outpoint(transactionId = entry.outpoint.transactionId, index = entry.outpoint.index),
                utxoEntry = UtxoData(
                    amount = entry.utxoEntry.amount,
                    scriptPublicKey = ScriptPublicKey(entry.utxoEntry.scriptPublicKey.scriptPublicKey),
                    blockDaaScore = entry.utxoEntry.blockDaaScore,
                    isCoinbase = entry.utxoEntry.isCoinbase,
                    covenantId = entry.utxoEntry.covenantId.ifEmpty { null }
                )
            )
        }
    }

    /**
     * Submits through [submit] and, if it throws, checks whether the network has [txId] anyway
     * before passing the error on - see [SubmitConfirmation] (iOS ad29da9, audit IOS-014). The id
     * is [transaction]'s, computed locally ([com.kachat.app.util.KaspaTransactionId]); should that
     * ever fail, the submit runs exactly as before, unchecked. [rest] is the REST API for the
     * acceptance lookup, null to ask the nodes only.
     */
    suspend fun submitConfirmingKnown(transaction: RawTransaction, rest: KaspaRestApi?, submit: suspend () -> String): String {
        val txId = runCatching { com.kachat.app.util.KaspaTransactionId.compute(transaction) }.getOrDefault("")
        return submitConfirmingKnown(txId, rest, spent = transaction.inputs.map { it.previousOutpoint }, rememberUnknownOutcome = false, submit = submit)
    }

    /**
     * [submitConfirmingKnown] for a transaction whose id the caller already computed
     * ([expectedTxId]): the version-1 `.kachat` builders, whose transactions aren't
     * [RawTransaction]s (iOS 9139e88 `submitRpcTransaction(_:expectedTxId:)`, audit IOS-014).
     */
    suspend fun submitConfirmingKnown(
        expectedTxId: String,
        rest: KaspaRestApi?,
        /** The transaction's inputs: held out of every coin pick from before the submit, so a send
         *  running beside it can't pick them while it is on its way, and kept out once it went
         *  (iOS 83806b3d `holdOutpointsForSubmit`, `reserveSpentOutpoints`). A failed submit, or
         *  one whose outcome is unknown, gives them back: a resend then picks the same coins, so at
         *  most one of the two can land. */
        spent: List<Outpoint> = emptyList(),
        /** A version-1 submit (iOS `submitRpcTransaction`): when its outcome is unknown, its coins
         *  are remembered ([com.kachat.app.services.tokens.UnknownOutcomeOutpoints]) so the next
         *  kUSDT send from them spends one again. The v0 sends above don't (as on iOS). */
        rememberUnknownOutcome: Boolean = true,
        submit: suspend () -> String,
    ): String {
        val txId = expectedTxId.lowercase()
        val hold = OutpointReservations.shared.hold(spent)
        // Which nodes answered this submit with a rejection: their own mempool answer isn't taken
        // as proof that it went out (iOS 83806b3d `SubmitAnswers`).
        val answers = SubmitAnswers()
        val sent = try {
            SubmitConfirmation.submitOrConfirmKnown(
                txId = txId,
                isKnown = { id, afterRejection -> isTransactionKnown(id, rest, afterRejection, answers) },
                onRecovered = { e ->
                    Log.w("NodePoolManager", "Submit of ${txId.take(12)} reported \"${e.message}\" but the network has it: treated as sent")
                },
                // After a lost answer any verified node counts (iOS `confirmAfterLostAnswer`).
                isKnownAfterLostAnswer = { id -> isTransactionKnown(id, rest, afterRejection = false) },
                submit = { kotlinx.coroutines.withContext(answers) { submit() } },
            )
        } catch (e: Throwable) {
            OutpointReservations.shared.release(hold)
            // not held, as above; remembered, so the next kUSDT send from these coins spends one
            // of them again and at most one of the two can land (iOS 5b7c772c `markUnknownOutcome`)
            if (rememberUnknownOutcome && e is SendOutcomeUnknownException) {
                com.kachat.app.services.tokens.UnknownOutcomeOutpoints.shared.mark(spent)
            }
            throw e
        }
        OutpointReservations.shared.reserve(spent)
        return sent
    }

    /**
     * Submits [transaction] through the broadcast connection. An "orphan where orphan is
     * disallowed" rejection waits for the parent and tries again, then lets the node hold it as an
     * orphan - see [SubmitConfirmation.submitWaitingForParent] (iOS 4eb492f, which does this in
     * NodePoolService.submitTransaction for messages, payments and reactions alike).
     * [escalateOrphan] false stops at the wait: for sends that move money and must not sit in a
     * node's orphan pool (iOS 4f8e14bb).
     */
    suspend fun submitWaitingForParent(transaction: RawTransaction, allowOrphan: Boolean = false, escalateOrphan: Boolean = true): String =
        SubmitConfirmation.submitWaitingForParent(
            allowOrphan = allowOrphan,
            escalateOrphan = escalateOrphan,
            onHeldAsOrphan = { id ->
                Log.i("NodePoolManager", "${id.take(12)} submitted as an orphan: its parent hadn't reached that node yet")
            },
        ) { orphan -> getBroadcastConnection().submitTransaction(transaction, allowOrphan = orphan) }

    /** One fee bucket from a node: sompi per gram, and the seconds a transaction paying it is
     *  expected to wait. */
    data class NodeFeeBucket(val feerate: Double, val seconds: Double)

    /**
     * The node's own fee estimate: the priority bucket, and the first normal bucket (the priority
     * one when a node sends no normal bucket). Read from a node, so a fee picture never depends on
     * a third-party API; throws when none answers (the caller falls back to REST).
     *
     * A hedged request, as iOS e426432 `NodePoolService.feeEstimate` (`executeHedged`): up to five
     * candidates (the trusted node alone when one is set; otherwise the healthy nodes, fastest
     * first), already-connected ones moved to the front, the first three asked. A connected node
     * starts after [FEE_HEDGE_DELAY_MS] times its place (the first one at once), a node still to be
     * dialed one place later; each waits up to 10 s. The first answer wins and the others are
     * cancelled; when all fail, the last error is thrown.
     */
    suspend fun feeEstimate(): Pair<NodeFeeBucket, NodeFeeBucket> {
        val addresses = pinnedServing()?.let { listOf(it) }
            ?: registry.activeBestFirst()
                .take(5)
                .map { it.address }
                .ifEmpty { listOf(dnsResolvedEndpoints.firstOrNull() ?: seeds.first()) }
        val connected = addresses.filter { connections[it]?.isConnected == true }.toSet()
        val selected = addresses.sortedByDescending { it in connected }.take(3)
        return coroutineScope {
            val outcomes = Channel<Result<Pair<NodeFeeBucket, NodeFeeBucket>>>(selected.size)
            val jobs = selected.mapIndexed { index, address ->
                val wait = if (address in connected) FEE_HEDGE_DELAY_MS * index else FEE_HEDGE_DELAY_MS * (index + 1)
                launch {
                    if (wait > 0) delay(wait)
                    val outcome = try {
                        val r = connectionFor(address).getFeeEstimate(timeoutMs = 10_000)
                        if (r.hasError() && r.error.message.isNotEmpty()) throw IllegalStateException(r.error.message)
                        val priority = r.estimate.priorityBucket
                        val normal = r.estimate.normalBucketsList.firstOrNull() ?: priority
                        Result.success(NodeFeeBucket(priority.feerate, priority.estimatedSeconds) to NodeFeeBucket(normal.feerate, normal.estimatedSeconds))
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        Result.failure(e)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("NodePoolManager", "Hedged fee estimate failed on $address: ${e.message}")
                        Result.failure(e)
                    }
                    outcomes.send(outcome)
                }
            }
            var last: Throwable = IllegalStateException("All hedged requests failed")
            repeat(selected.size) {
                val outcome = outcomes.receive()
                val value = outcome.getOrNull()
                if (value != null) {
                    jobs.forEach { it.cancel() }
                    return@coroutineScope value
                }
                outcome.exceptionOrNull()?.let { last = it }
            }
            throw last
        }
    }

    /** Whether the network already has [txId]: in a mempool (the primary node and up to three
     *  others), or accepted (REST API) - asked twice, 1.5 s apart (iOS
     *  NodePoolService.isTransactionKnown). [afterRejection]: a node refused it, so a REST
     *  "accepted" counts only from the network's built-in REST server ([builtInRestApi]), never
     *  [rest] when that is a custom one set in Settings (iOS d23e6f7a `builtInOnly`). */
    suspend fun isTransactionKnown(
        txId: String,
        rest: KaspaRestApi?,
        afterRejection: Boolean = false,
        /** The submit's own rejections: those nodes are not asked, and after a verdict two others
         *  must hold it (iOS 83806b3d). Null: any node, one is enough. */
        answers: SubmitAnswers? = null,
    ): Boolean {
        val acceptanceApi = if (afterRejection) builtInRestApi() else rest
        val skip = answers?.answeredKeys ?: emptySet()
        val needed = answers?.holdersNeeded ?: 1
        return SubmitConfirmation.isKnown(
            txId = txId,
            inMempool = { verifiedMempoolHolders(it, skip) >= needed },
            acceptedViaRest = { id -> acceptanceApi != null && isAcceptedViaRest(acceptanceApi, id) },
        )
    }

    /**
     * How many nodes - the primary, then the three fastest other Active ones (the trusted node
     * alone, when one is set), never those in [excluding] - hold [txId] in their mempool: not as
     * an orphan, and as a transaction whose recomputed id is [txId] (iOS 83806b3d
     * `verifiedMempoolHolders`). Asked in parallel, 2 s each.
     */
    private suspend fun verifiedMempoolHolders(txId: String, excluding: Set<String>): Int {
        val addresses = pinnedServing()?.let { listOf(it) } ?: run {
            val primary = runCatching { broadcastAddress() }.getOrNull()
            val others = registry.activeBestFirst()
                .map { it.address }
                .filter { it != primary && it !in excluding }
                .take(3)
            listOfNotNull(primary) + others
        }
        val asked = addresses.filter { it !in excluding }
        if (asked.isEmpty()) return 0
        return coroutineScope {
            asked.map { address ->
                async {
                    try {
                        connectionFor(address).hasVerifiedMempoolEntry(txId)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }
            }.awaitAll().count { it }
        }
    }

    /** The running network's built-in REST server and its client, built once per URL. */
    @Volatile private var builtInRest: Pair<String, KaspaRestApi>? = null

    /** A client for the running network's built-in REST server
     *  ([AppSettingsRepository.defaultKaspaRestUrl]), whatever Settings names; null when it
     *  can't be built. */
    private fun builtInRestApi(): KaspaRestApi? {
        val url = AppSettingsRepository.defaultKaspaRestUrl(com.kachat.app.util.KaspaNetwork.launch).trim()
        builtInRest?.let { (cachedUrl, api) -> if (cachedUrl == url) return api }
        return try {
            val api = retrofit2.Retrofit.Builder()
                .baseUrl(if (url.endsWith("/")) url else "$url/")
                .client(okHttpClient)
                .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
                .build()
                .create(KaspaRestApi::class.java)
            builtInRest = url to api
            api
        } catch (e: Exception) {
            Log.w("NodePoolManager", "Couldn't build the built-in REST client: ${e.message}")
            null
        }
    }

    /** Whether a node still holds [txId] in its mempool ([isInAnyMempool]): a `.kachat` commit
     *  that isn't on chain yet is waiting there, or was dropped (iOS b219bb0 `getMempoolEntry`). */
    suspend fun isInMempool(txId: String): Boolean = isInAnyMempool(txId)

    /** Whether the primary node - the one the next broadcast uses, most likely to hold a
     *  transaction just submitted - or one of the three fastest others (the trusted node alone,
     *  when one is set) holds [txId] in its mempool, orphan pool included (iOS d23e6f7a
     *  `getMempoolEntry`). Asked in parallel, 2 s each. */
    private suspend fun isInAnyMempool(txId: String): Boolean {
        val asked = pinnedServing()?.let { listOf(connectionFor(it)) } ?: run {
            val primary = getBroadcastConnection()
            val others = registry.activeBestFirst()
                .filter { connections[it.address] !== primary }
                .take(3)
                .map { connectionFor(it.address) }
            listOf(primary) + others
        }
        return coroutineScope {
            asked.map { connection -> async { connection.hasMempoolEntry(txId) } }.awaitAll().any { it }
        }
    }

    private suspend fun isAcceptedViaRest(rest: KaspaRestApi, txId: String): Boolean = try {
        withTimeoutOrNull(8_000L) { rest.getTransactionAcceptance(txId).isAccepted == true } ?: false
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Drops the cached connection the next [getBroadcastConnection] would return, so it dials
     * fresh. A silently-died gRPC stream is otherwise only reaped by the 30s probe cycle — any
     * submit in that window queued onto the dead stream and ate the full 15s timeout, failing
     * the message while the app still looked "connected" (in trusted-node mode the health
     * readout is pinned Active). The send path uses this for its one-shot reconnect retry.
     */
    fun refreshBroadcastConnection() {
        val address = pinnedServing()
            ?: servingStandIn()
            ?: registry.activeBestFirst().firstOrNull()?.address
            ?: dnsResolvedEndpoints.firstOrNull() ?: seeds.firstOrNull() ?: return
        connections.remove(address)?.let { runCatching { it.close() } }
    }

    /**
     * Triggers an immediate out-of-cycle probe pass — "Refresh Pool".
     *
     * The returned job ends when the probe pass does - Connection Status spins until then.
     */
    fun refreshNow(): Job = scope.launch { probeCycle(force = true) }

    /** Drops discovered/DNS-resolved/manual nodes and all connections, resets to just the seed list — "Clear Connection Pool". */
    fun clearPool(): Job {
        dropPublicFallback("connection pool cleared")
        manualEndpoints.clear()
        discoveredEndpoints.clear()
        dnsResolvedEndpoints.clear()
        wssSeedEndpoints.clear()
        prunedAt.clear()
        unhealthyStallCycles = 0
        lastDnsResolveAt = 0L
        connections.values.forEach { it.close() }
        connections.clear()
        registry.resetTo(seeds, "Seed")
        publish()
        return refreshNow()
    }

    /** Drops all persistent connections so the next probe cycle opens fresh ones — "Reconnect". */
    fun reconnect(): Job {
        connections.values.forEach { it.close() }
        connections.clear()
        return refreshNow()
    }

    /**
     * Reconnects any currently-tracked connection that's dead right now — cheap, non-disruptive
     * alternative to [reconnect]/[clearPool] (which tear down every connection unconditionally),
     * meant to be called whenever the app returns to the foreground (see
     * `KaChatApplication`'s `ProcessLifecycleOwner` observer). A connection can die silently while
     * backgrounded/asleep, and each [KaspadConnection] already self-heals from that on its own
     * (see its `scheduleAutoReconnect`), but backgrounding can suspend the coroutine that would
     * notice the drop, so this gives already-tracked-but-dead connections an immediate nudge
     * instead of waiting for the next 5-30s probe cycle to notice and replace them.
     */
    fun reconnectStaleConnections() {
        // The pinned nodes, and ones requests used in the last 10 minutes: not every node a probe
        // ever touched (iOS 83806b3d `reconnectDisconnected`).
        syncPinnedConnections()
        connections.values
            .filter { !it.isConnected && (it.isPinned || it.usedWithin(KaspadConnection.RECENT_USE_MS)) }
            .forEach { it.connect() }
    }

    /** Adds and immediately probes a user-supplied "host:port" endpoint — "Add Custom Endpoint". */
    fun addManualEndpoint(address: String) {
        val typed = address.trim()
        val parsedWss = if (isWebSocketNodeAddress(typed)) com.kachat.app.services.grpc.parseNodeAddress(typed) else null
        if (parsedWss == null && !typed.matches(Regex("^[^:\\s]+:\\d+$"))) return
        // A wss node is kept by its node key, as the seeds and stand-ins are, so one node typed
        // two ways is one entry and one socket (audit 2026-11-25 T7).
        val key = parsedWss?.key ?: typed
        manualEndpoints.add(key)
        scope.launch {
            registry.update(key, "Manual", probeExisting(key, connectionFor(key)))
            publish()
        }
    }

    // MARK: - Live blocks

    /**
     * New blocks from the pool's primary node, for as long as this is collected (the Explorer's
     * live list, iOS 961fc05a `ExplorerLive` on `NodePoolService.subscribeBlockAdded` +
     * `addBlockHandler`). Subscribes under [owner] on the broadcast connection, moves to the
     * pool's best connection when that one's stream dies, and pauses while the app is in the
     * background. When the last client of a connection leaves, the node is told to stop sending
     * blocks (NOTIFY_STOP, audit 2026-11-25 B1): cancelling the collector is enough.
     *
     * [fullBlocks]: every transaction with its outputs and the block's verbose data (hash, blue
     * score, parents, children), as the Explorer lists them. A gRPC node sends that anyway; a wss
     * node's JSON is then decoded in full rather than only its payload transactions. A slow
     * collector misses blocks rather than holding the stream up (the newest 64 are kept).
     */
    fun liveBlocks(owner: String, fullBlocks: Boolean = true): Flow<protowire.Rpc.RpcBlock> = channelFlow {
        if (fullBlocks) KaspadConnection.fullBlockClients.incrementAndGet()
        try {
            notificationHelper.appForegroundFlow.collectLatest { foreground ->
                if (!foreground) return@collectLatest
                // 2 s doubling to 30 s while it keeps failing, and offline it waits for the network
                // (audit 2026-11-25 N1); the Explorer's list carries on as soon as a node answers.
                val retry = blockStreamRetry(firstDelayMs = 2_000L, maxDelayMs = 30_000L)
                while (true) {
                    var subscribedOn: KaspadConnection? = null
                    try {
                        val connection = getBroadcastConnection()
                        // Captured before subscribing: a death racing the NOTIFY_START reads as moved.
                        val subscribedGeneration = connection.connectionGeneration.value
                        val blocks = connection.subscribeToBlockAdded(owner)
                        subscribedOn = connection
                        coroutineScope {
                            val collector = launch { blocks.collect { send(it) } }
                            connection.connectionGeneration.first { it != subscribedGeneration }
                            collector.cancel()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("NodePoolManager", "Live blocks for $owner interrupted, retrying: ${e.javaClass.simpleName}: ${e.message}")
                    } finally {
                        val on = subscribedOn
                        if (on != null) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                                try {
                                    withTimeoutOrNull(5_000L) { on.unsubscribeFromBlockAdded(owner) }
                                } catch (e: Exception) {
                                    Log.d("NodePoolManager", "Live blocks stop for $owner failed: ${e.message}")
                                }
                            }
                        }
                    }
                    retry.pause()
                }
            }
        } finally {
            if (fullBlocks) KaspadConnection.fullBlockClients.decrementAndGet()
        }
    }.buffer(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)

    /** Suspends until the phone has a network (at once when it has). */
    suspend fun awaitOnline() {
        if (!online.value) online.first { it }
    }

    /** The wait between a block stream's tries, for one loop: doubling from [firstDelayMs] to
     *  [maxDelayMs] while it keeps failing, then for the network (audit 2026-11-25 N1). */
    fun blockStreamRetry(
        firstDelayMs: Long = BlockStreamRetry.FIRST_DELAY_MS,
        maxDelayMs: Long = BlockStreamRetry.MAX_DELAY_MS,
    ): BlockStreamRetry = BlockStreamRetry(firstDelayMs, maxDelayMs, awaitOnline = { awaitOnline() })

    // MARK: - Background (iOS 961fc05a `enterBackground` / `enterForeground`)

    /**
     * The app went to the background. The public fallback's watch stops; push or not, every
     * connection that carries nothing (no request waiting, no block client) is closed, the former
     * primary's included: an open one was kept alive with HTTP/2 pings and redialed after every
     * drop for nothing (audit 2026-11-25 B3). The probe loop itself already sleeps in the
     * background, and the block scanners stop themselves there. The known-good list is
     * saved, since the app may not come back. Each step stops if the app came back meanwhile (N2).
     */
    private fun enterBackground() {
        lifecycleGeneration++
        val generation = lifecycleGeneration
        stopPublicFallbackWatch()
        val previous = lifecycleJob
        lifecycleJob = scope.launch {
            previous?.join()
            if (lifecycleGeneration != generation) return@launch
            persistKnownGoodNodes(force = true)
            // The background needs no connection, push or not (audit 2026-11-27 R1): a Reply or a
            // send dials its node on demand.
            closeIdleConnections(idleForMs = 0L, reason = "the background") { lifecycleGeneration != generation }
            // What is still open (a block client stopping, a request in flight), and anything
            // opened later in the background, is closed once idle (audit 2026-11-25 N6).
            if (lifecycleGeneration == generation) ensureIdleClose()
        }
    }

    /**
     * Closes every connection that carries nothing: no request waiting, no block client, and no
     * request in the last [idleForMs]. Stops early once [stop] says so (back in the foreground).
     */
    private fun closeIdleConnections(idleForMs: Long, reason: String, stop: () -> Boolean = { false }) {
        var closed = 0
        val now = System.currentTimeMillis()
        for ((address, connection) in connections.entries.toList()) {
            if (stop()) return
            if (connection.hasPendingRequests || connection.hasBlockSubscribers) continue
            if (idleForMs > 0 && connection.usedWithin(idleForMs, now)) continue
            if (connections.remove(address, connection)) {
                connection.close()
                closed++
            }
        }
        if (closed > 0) Log.i("NodePoolManager", "Closed $closed idle connection(s) for $reason, ${connections.size} left")
    }

    /**
     * While probing is paused (the background, or signed out), one small sweep closes
     * any connection that has been idle for [IDLE_CLOSE_MS], for as long as connections are open:
     * the close on going to the background ran once, so a connection a block client still held
     * then, or one opened later (a network change, a send from a notification, a background
     * wake), stayed open with its keepalive until the next foreground (audit 2026-11-25 N6).
     */
    @Synchronized
    private fun ensureIdleClose() {
        if (!probingPaused() || connections.isEmpty()) return
        if (idleCloseJob?.isActive == true) return
        idleCloseJob = scope.launch {
            while (connections.isNotEmpty()) {
                delay(IDLE_CLOSE_MS)
                if (!probingPaused()) return@launch
                closeIdleConnections(idleForMs = IDLE_CLOSE_MS, reason = "an idle pool")
            }
        }
    }

    /** Back in the foreground: the public fallback's watch runs again, with a fresh grace window
     *  (the last gRPC answer is old because the app was asleep, not because gRPC is blocked).
     *  Dead connections come back through [reconnectStaleConnections] and the probe loop. */
    private fun enterForeground() {
        lifecycleGeneration++
        val generation = lifecycleGeneration
        publicFallbackAnchor = System.currentTimeMillis()
        if (signedIn.value) {
            idleCloseJob?.cancel()
            idleCloseJob = null
        }
        val previous = lifecycleJob
        lifecycleJob = scope.launch {
            previous?.join()
            if (lifecycleGeneration != generation) return@launch
            startPublicFallbackWatch()
        }
    }

    // MARK: - Public wss fallback (iOS 961fc05a `NodePoolService.evaluatePublicFallback`)

    private fun startPublicFallbackWatch() {
        if (publicFallbackWatchJob?.isActive == true) return
        publicFallbackWatchJob = scope.launch {
            while (true) {
                delay(PUBLIC_FALLBACK_WATCH_MS)
                try {
                    evaluatePublicFallback()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("NodePoolManager", "Public fallback check failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
    }

    private fun stopPublicFallbackWatch() {
        publicFallbackWatchJob?.cancel()
        publicFallbackWatchJob = null
    }

    /**
     * When no node of the pool's own has answered for 30 s while one was being tried and the phone
     * is online - gRPC blocked (DPI, a firewall that refuses HTTP/2), or the pinned default node
     * down - up to two public nodes speaking wRPC over `wss://` join the pool
     * ([PublicNodeResolver]), ranked below every gRPC node, and requests go to them. A stand-in
     * that stops answering is replaced. They leave once gRPC has served for two minutes
     * ([considerHandBack]). Never when the user pinned a node of their own. Your addresses are
     * then known to the public node, as they are to the Kaspa REST API the app already falls back
     * to.
     */
    @Synchronized
    private fun evaluatePublicFallback(nowMs: Long = System.currentTimeMillis()) {
        var standIns = registry.publicFallbacks()
        if (!publicFallbackAllowed) {
            if (standIns.isNotEmpty() || _usingPublicFallback.value) dropPublicFallback("a node of your own is pinned")
            return
        }
        if (standIns.isNotEmpty()) {
            // Dead stand-ins go, and new ones are asked for below (on the same cooldown).
            val dead = registry.removeDeadPublicFallbacks(STAND_IN_GRACE_MS, nowMs)
            if (dead.isNotEmpty()) {
                deadStandIns += dead
                dead.forEach { connections.remove(it)?.close() }
                Log.i("NodePoolManager", "Public wss node(s) stopped answering, replacing: ${dead.joinToString()}")
                standIns = registry.publicFallbacks()
                publish()
            }
        }
        if (standIns.isNotEmpty()) {
            considerHandBack(nowMs)
            return
        }
        if (_usingPublicFallback.value) {
            // Nothing stands in now: every stand-in died.
            resetPublicFallbackState()
            publish()
        }

        if (publicFallbackJob?.isActive == true || !online.value || nowMs - publicFallbackAnchor < PUBLIC_FALLBACK_GRACE_MS) return
        val (lastSuccess, recentFailures) = registry.ownNodeConnectivity(60_000L, nowMs)
        if (lastSuccess != null && nowMs - lastSuccess < 30_000L) return
        if (recentFailures == 0) return
        lastPublicFallbackAttempt?.let { if (nowMs - it < publicFallbackCooldownMillis) return }
        lastPublicFallbackAttempt = nowMs

        val epoch = probeEpoch
        val avoiding = deadStandIns.toSet()
        Log.i("NodePoolManager", "No gRPC node has answered for 30s - asking for public wss nodes")
        publicFallbackJob = scope.launch {
            val found = PublicNodeResolver.resolve(com.kachat.app.util.KaspaNetwork.isTestnet, count = 2, avoiding = avoiding)
            adoptPublicFallback(found, epoch)
        }
    }

    private suspend fun adoptPublicFallback(found: List<String>, epoch: Int) {
        // The node selection changed while the resolvers were asked, or the fallback was dropped.
        if (epoch != probeEpoch || !publicFallbackAllowed) return
        val (lastSuccess, _) = registry.ownNodeConnectivity(60_000L)
        if (lastSuccess != null && System.currentTimeMillis() - lastSuccess < 30_000L) return
        val added = found.filter { registry.addPublicFallback(it) }
        if (added.isEmpty()) {
            // Nothing new: the next walk waits longer.
            publicFallbackCooldownMillis = minOf(15 * 60_000L, publicFallbackCooldownMillis * 2)
            Log.i("NodePoolManager", "No new public wss node found - next try in ${publicFallbackCooldownMillis / 1000}s")
            return
        }
        publicFallbackCooldownMillis = PUBLIC_FALLBACK_COOLDOWN_MS
        if (!_usingPublicFallback.value) publicFallbackSince = System.currentTimeMillis()
        _usingPublicFallback.value = true
        preferStandIns = true
        handBackSince = null
        Log.i("NodePoolManager", "Using public wss node(s) while gRPC is unreachable: ${added.joinToString()}")
        // Probed at once, so the first one that answers serves.
        for (address in added) {
            if (epoch != probeEpoch) return
            val result = probeExisting(address, connectionFor(address), discoveryProbeTimeoutMillis)
            if (registry.publicFallbacks().any { it.address == address }) registry.update(address, NodeRegistry.TYPE_PUBLIC, result)
            if (!result.reachable) connections.remove(address)?.close()
        }
        syncPinnedConnections()
        publish()
    }

    /**
     * Stand-ins are in the pool. Once gRPC has answered steadily for [HAND_BACK_AFTER_MS] requests
     * go back to it, and once it has served for that long too the stand-ins go. Steadily: an
     * answer within the last 45 s at every look for two minutes; one answer used to be enough, so
     * under partial DPI the pool could flap between a stand-in and gRPC every minute (audit
     * 2026-11-25 N5). A hand-back that doesn't hold doubles the wait before the next.
     */
    private fun considerHandBack(nowMs: Long) {
        val (lastSuccess, recentFailures) = registry.ownNodeConnectivity(60_000L, nowMs)
        val grpcAnswers = lastSuccess != null && nowMs - lastSuccess < 45_000L && lastSuccess > (publicFallbackSince ?: Long.MIN_VALUE)
        grpcSteadySince = if (grpcAnswers) grpcSteadySince ?: nowMs else null
        val grpcSteady = grpcSteadySince?.let { nowMs - it >= HAND_BACK_AFTER_MS } ?: false

        // gRPC stopped answering after a hand-back: requests go to the stand-ins again, and if that
        // hand-back was the last one, the next waits twice as long.
        if (!preferStandIns && (lastSuccess == null || nowMs - lastSuccess >= 30_000L) && recentFailures > 0) {
            preferStandIns = true
            if (handBackUnproven) {
                handBackUnproven = false
                handBackBackoffMillis = minOf(30 * 60_000L, handBackBackoffMillis * 2)
                Log.i("NodePoolManager", "gRPC didn't hold - next hand-back in ${handBackBackoffMillis / 1000}s at the earliest")
            }
            syncPinnedConnections()
            publish()
        }
        if (grpcSteady && preferStandIns && nowMs - (lastHandBackAttempt ?: Long.MIN_VALUE / 2) >= handBackBackoffMillis) {
            lastHandBackAttempt = nowMs
            handBackUnproven = true
            preferStandIns = false
            Log.i("NodePoolManager", "gRPC has answered for two minutes - requests go back to gRPC")
            syncPinnedConnections()
            publish()
        }

        val healthy = !preferStandIns && grpcAnswers
        handBackSince = if (healthy) handBackSince ?: nowMs else null
        if (handBackSince?.let { nowMs - it >= HAND_BACK_AFTER_MS } == true) {
            dropPublicFallback("gRPC has served for two minutes")
        }
    }

    private fun resetPublicFallbackState() {
        publicFallbackSince = null
        _usingPublicFallback.value = false
        preferStandIns = false
        handBackSince = null
        grpcSteadySince = null
        handBackBackoffMillis = 60_000L
        handBackUnproven = false
        publicFallbackCooldownMillis = PUBLIC_FALLBACK_COOLDOWN_MS
        deadStandIns.clear()
    }

    /** Every public stand-in leaves: their connections close (a block client on one moves to the
     *  pool's best node, its stream generation having moved). */
    @Synchronized
    private fun dropPublicFallback(reason: String) {
        publicFallbackJob?.cancel()
        publicFallbackJob = null
        val wasUsing = _usingPublicFallback.value
        val gone = registry.removePublicFallbacks()
        resetPublicFallbackState()
        gone.forEach { connections.remove(it)?.close() }
        if (gone.isEmpty() && !wasUsing) return
        Log.i("NodePoolManager", "Public wss nodes dropped ($reason)")
        syncPinnedConnections()
        publish()
    }

    private companion object {
        /** How often the public fallback is looked at while the app is open. */
        const val PUBLIC_FALLBACK_WATCH_MS = 20_000L
        /** No fallback this soon after boot, a pin switch, a path change or the foreground. */
        const val PUBLIC_FALLBACK_GRACE_MS = 20_000L
        const val PUBLIC_FALLBACK_COOLDOWN_MS = 60_000L
        /** A stand-in that hasn't answered in this long is dead, and replaced. */
        const val STAND_IN_GRACE_MS = 60_000L
        /** How long gRPC must serve before the stand-ins go. */
        const val HAND_BACK_AFTER_MS = 120_000L
        /** The known-good list is written at most this often. */
        const val KNOWN_GOOD_SAVE_INTERVAL_MS = 5 * 60_000L
        /** Where the pinned node's probe sits in the foreground beat (the room sweep is at 0). */
        const val PROBE_BEAT_OFFSET_MS = 1_200L
        /** The most pruned nodes remembered at once (see rememberPruned). */
        private const val MAX_PRUNED_MEMORY = 200
        /** How long a pruned node stays out of gossip and DNS answers. */
        const val PRUNED_MEMORY_MS = 60 * 60_000L
        /** While the pool is idle, a connection unused this long is closed. */
        const val IDLE_CLOSE_MS = 30_000L

        /**
         * iOS `NetworkEpochMonitor.effectiveHedgeDelayMs`: 3000 ms on unmetered WiFi or wired
         * (`.excellent`), and never under that on an expensive (metered / constrained) path - which
         * covers metered WiFi and cellular, the only paths where the 400 / 800 ms tiers would apply.
         */
        const val FEE_HEDGE_DELAY_MS = 3_000L
    }
}

/**
 * Which nodes one probe cycle of Automatic Scan dials; pure, for the tests.
 */
internal object NodeWavePlan {
    enum class Kind {
        /** In the background: the primary alone, or nothing when there is none; never discovery
         *  (audit 2026-11-27 R2). */
        BACKGROUND_PRIMARY,
        /** A healthy pool on a metered path: the primary alone. */
        PRIMARY,
        /** A healthy pool on Wi-Fi between full waves: the best few Active nodes. */
        STEADY,
        /** A pool short of nodes on a metered path that has some: the nodes that answered and the
         *  saved known-good ones, between full waves (audit 2026-11-27 R6). */
        KNOWN_GOOD,
        /** Every endpoint known. */
        FULL,
    }

    fun kind(
        background: Boolean,
        metered: Boolean,
        healthy: Boolean,
        activeCount: Int,
        cyclesSinceFullWave: Int,
        fullWaveEveryCycles: Int,
    ): Kind {
        val betweenFullWaves = cyclesSinceFullWave < fullWaveEveryCycles - 1
        return when {
            background -> Kind.BACKGROUND_PRIMARY
            healthy && metered -> Kind.PRIMARY
            healthy && betweenFullWaves -> Kind.STEADY
            !healthy && metered && activeCount > 0 && betweenFullWaves -> Kind.KNOWN_GOOD
            else -> Kind.FULL
        }
    }
}

/**
 * How long the node pool's probe loop waits before its next cycle; pure, for the tests.
 */
internal object NodeProbeCadence {
    /** [millis] to wait, or [onBeat]: sleep to the next foreground beat ([ForegroundBeat]), whose
     *  interval [millis] is, so the radio wakes once for every poll. */
    data class Wait(val millis: Long, val onBeat: Boolean)

    const val UNHEALTHY_MS = 10_000L
    const val UNHEALTHY_MAX_MS = 60_000L
    const val DEAD_MAX_MS = 120_000L
    const val HEALTHY_MS = 30_000L
    const val HEALTHY_METERED_MS = 60_000L

    /** Every node dead for [deadCycles] cycles in a row: 20 s, 40 s, 80 s, then two minutes. */
    fun deadBackoffMillis(deadCycles: Int): Long =
        minOf(UNHEALTHY_MS shl deadCycles.coerceIn(0, 5), DEAD_MAX_MS)

    /** The pinned node: its keepalive on the beat; when it doesn't answer, the dead-pool backoff
     *  once that is longer than the beat (audit 2026-11-25 N13). */
    fun trusted(deadCycles: Int, keepaliveMs: Long): Wait {
        if (deadCycles <= 0) return Wait(keepaliveMs, onBeat = true)
        val backoff = deadBackoffMillis(deadCycles)
        return if (backoff <= keepaliveMs) Wait(keepaliveMs, onBeat = true) else Wait(backoff, onBeat = false)
    }

    /** Automatic Scan: dead pool backs off; short of [target] Active nodes, 10 s stretching with
     *  [stallCycles] to 60 s (audit 2026-11-25 N8), and once that stretch reaches the beat
     *  ([beatMs]) it lands on the beat at least that far away, so it wakes the radio with the
     *  other polls (audit 2026-11-27 R6); healthy, the beat. An on-beat [Wait] longer than the
     *  beat means "the first beat at least [Wait.millis] away". */
    fun automatic(
        deadCycles: Int,
        activeCount: Int,
        target: Int,
        stallCycles: Int,
        metered: Boolean,
        beatMs: Long = if (metered) HEALTHY_METERED_MS else HEALTHY_MS,
    ): Wait = when {
        deadCycles > 0 -> Wait(deadBackoffMillis(deadCycles), onBeat = false)
        activeCount < target -> {
            val stretch = minOf(UNHEALTHY_MS shl stallCycles.coerceIn(0, 5), UNHEALTHY_MAX_MS)
            Wait(stretch, onBeat = stretch >= beatMs)
        }
        else -> Wait(beatMs, onBeat = true)
    }

    /** The stall count after a cycle: back to 0 when the pool is healthy or the Active count rose. */
    fun nextStallCycles(previous: Int, activeBefore: Int, activeAfter: Int, target: Int): Int =
        if (activeAfter >= target || activeAfter > activeBefore) 0 else previous + 1
}

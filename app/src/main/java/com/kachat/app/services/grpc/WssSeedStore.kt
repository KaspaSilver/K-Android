package com.kachat.app.services.grpc

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * Automatic Scan's public wss seeds (iOS 961fc05a `NodeProfiler.refreshWssSeeds`): up to three
 * public wRPC nodes from the Kaspa resolvers, kept among the seed candidates so a network that
 * blocks gRPC still finds nodes. Saved per network in files/NodePool/wss-seeds-<network>.json, so
 * the next launch dials a known one at once. Each remembers when it was first found and when it
 * last answered: a seed with no answer in 24 hours is dropped ([prune]), and only live ones count
 * toward the three (audit 2026-11-25 C8).
 */
class WssSeedStore(private val directory: File?, private val testnet: Boolean) {
    data class Seed(val address: String, val firstSeenMs: Long, val lastSuccessMs: Long? = null)

    private val gson = Gson()
    private val lock = Any()
    private var seeds: MutableMap<String, Seed>? = null

    private val file: File? get() = directory?.let { File(it, if (testnet) "wss-seeds-testnet.json" else "wss-seeds-mainnet.json") }

    private fun loaded(): MutableMap<String, Seed> {
        seeds?.let { return it }
        val read = try {
            file?.takeIf { it.exists() }?.readText()?.let { text ->
                gson.fromJson<List<Seed>>(text, object : TypeToken<List<Seed>>() {}.type)
            }.orEmpty()
        } catch (e: Exception) {
            Log.w("WssSeedStore", "Couldn't read the wss seeds: ${e.javaClass.simpleName}")
            emptyList()
        }
        val map = read.filter { parseNodeAddress(it.address)?.webSocket == true }.associateBy { it.address }.toMutableMap()
        seeds = map
        return map
    }

    fun all(): List<Seed> = synchronized(lock) { loaded().values.toList() }

    /** Adds [address] as a seed; false when it was already one. */
    fun add(address: String, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val map = loaded()
        if (map.containsKey(address)) return false
        map[address] = Seed(address, nowMs)
        save(map)
        true
    }

    /** [address] answered: saved at most once an hour per seed (its standing is what matters,
     *  not every keepalive). */
    fun recordSuccess(address: String, nowMs: Long = System.currentTimeMillis()) = synchronized(lock) {
        val map = loaded()
        val seed = map[address] ?: return
        if (seed.lastSuccessMs != null && nowMs - seed.lastSuccessMs < 60 * 60 * 1000L) return
        map[address] = seed.copy(lastSuccessMs = nowMs)
        save(map)
    }

    /** Drops the seeds known for over [staleAfterMs] with no answer in that time; returns the
     *  removed ones (so their connections can be closed) and the live ones left. */
    fun prune(staleAfterMs: Long, nowMs: Long = System.currentTimeMillis()): Pair<List<String>, List<String>> = synchronized(lock) {
        val map = loaded()
        val (removed, live) = pruneSeeds(map.values.toList(), staleAfterMs, nowMs)
        if (removed.isNotEmpty()) {
            removed.forEach { map.remove(it) }
            save(map)
        }
        removed to live
    }

    private fun save(map: Map<String, Seed>) {
        val target = file ?: return
        try {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeText(gson.toJson(map.values.toList()))
            if (!tmp.renameTo(target)) {
                target.writeText(tmp.readText())
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w("WssSeedStore", "Couldn't save the wss seeds: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        /** How many wss seeds Automatic Scan keeps. */
        const val MAX_SEEDS = 3

        /** A seed with no answer in this long is dropped. */
        const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

        /** Pure: which of [seeds] are stale (known over [staleAfterMs] with no answer in that time)
         *  and which are live. */
        fun pruneSeeds(seeds: List<Seed>, staleAfterMs: Long, nowMs: Long): Pair<List<String>, List<String>> {
            val cutoff = nowMs - staleAfterMs
            val removed = mutableListOf<String>()
            val live = mutableListOf<String>()
            for (seed in seeds) {
                if (seed.firstSeenMs < cutoff && (seed.lastSuccessMs ?: Long.MIN_VALUE) < cutoff) removed += seed.address
                else live += seed.address
            }
            return removed to live
        }
    }
}

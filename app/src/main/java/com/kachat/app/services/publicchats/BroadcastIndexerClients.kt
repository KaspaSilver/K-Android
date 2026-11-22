package com.kachat.app.services.publicchats

import android.util.Log
import com.kachat.app.services.BroadcastIndexerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.kachat.app.util.capResponseBodies
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The public chat indexer clients (PUBLIC_CHATS_INDEXER.md): the app-wide one, which follows the
 * message indexer (iOS 148a5331 `publicChatIndexerURL` is `indexerURL`), and one per room that
 * has its own indexer (Room Info). Built here rather than in NetworkService so Public Chats adds
 * no code to the shared network layer; the same OkHttp client, without the message indexer's
 * health interceptor: a room read never counts towards the message indexer's outage, and the
 * arena keeps its own health (iOS `PublicChatIndexerClient.arenaHealth`).
 */
@Singleton
class BroadcastIndexerClients @Inject constructor(
    private val settings: PublicChatSettings,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _broadcastIndexerApi = MutableStateFlow<BroadcastIndexerApi?>(null)

    /** The app-wide room indexer, or null when none is set (testnet has none yet). */
    val broadcastIndexerApi: StateFlow<BroadcastIndexerApi?> = _broadcastIndexerApi

    private val byUrl = ConcurrentHashMap<String, BroadcastIndexerApi>()

    init {
        scope.launch {
            settings.broadcastIndexerUrl.collectLatest { url ->
                // Blank = no server: never keep the other network's client.
                _broadcastIndexerApi.value = if (url.isBlank()) null else create(url) ?: _broadcastIndexerApi.value
            }
        }
    }

    /**
     * A client for one base URL, cached per URL. A room message is on chain, so any indexer
     * watching the same network serves the same room, which is why a room can be pointed at its
     * own indexer without changing the app-wide one. Blank falls back to the app-wide client.
     */
    fun broadcastIndexerApiFor(baseUrl: String): BroadcastIndexerApi? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty()) return _broadcastIndexerApi.value
        byUrl[trimmed]?.let { return it }
        val created = create(trimmed) ?: return _broadcastIndexerApi.value
        byUrl[trimmed] = created
        return created
    }

    /**
     * The shared client, with every answer size-capped (iOS audit 2026-11-21 #19
     * `PublicChatIndexerClient.maxResponseBytes`): a history page bigger than any real page, or a
     * tracked-rooms list bigger than 256 KB, is not read at all - it fails as an IOException.
     */
    private val cappedClient: OkHttpClient = okHttpClient.newBuilder()
        .capResponseBodies("Public chats indexer") { request ->
            if (request.url.encodedPath.endsWith("broadcast-channels")) MAX_TRACKED_BYTES else MAX_RESPONSE_BYTES
        }
        .build()

    /** A malformed URL never crashes: it is logged and no client is made. */
    private fun create(baseUrl: String): BroadcastIndexerApi? = try {
        val sanitized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        Retrofit.Builder()
            .baseUrl(sanitized)
            .client(cappedClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BroadcastIndexerApi::class.java)
    } catch (e: Exception) {
        Log.w("BroadcastIndexerClients", "Invalid public chat indexer URL", e)
        null
    }

    companion object {
        /** The most a history page may weigh: a page is at most 200 rows and a room message's
         *  payload is under 100 KB, so a real page stays well under this (iOS `maxResponseBytes`). */
        const val MAX_RESPONSE_BYTES = 24L * 1024 * 1024
        /** The tracked-rooms list (iOS: 256 KB). */
        const val MAX_TRACKED_BYTES = 256L * 1024
    }
}

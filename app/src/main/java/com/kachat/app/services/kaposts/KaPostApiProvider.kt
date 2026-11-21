package com.kachat.app.services

import android.util.Log
import com.kachat.app.repository.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The KaPost indexer's client (KaPost's `NetworkService.kapostApi`, kept here so KaChat's
 * NetworkService stays as it is). KaPost runs on KaChat's own indexer (iOS 148a5331
 * `AppSettings.kaPostIndexerURL` is `indexerURL`): blank on testnet, where there is no client.
 */
@Singleton
class KaPostApiProvider @Inject constructor(
    settings: AppSettingsRepository,
    private val okHttpClient: OkHttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _kapostApi = MutableStateFlow<KaPostApi?>(null)
    val kapostApi: StateFlow<KaPostApi?> = _kapostApi

    /** Kaspa REST, for an address's KaPost transactions while the indexer is down. */
    private val _restPayloadApi = MutableStateFlow<KaPostRestPayloadApi?>(null)
    val restPayloadApi: StateFlow<KaPostRestPayloadApi?> = _restPayloadApi

    private val gson = com.google.gson.GsonBuilder()
        .registerTypeAdapterFactory(com.kachat.app.util.UtxoMath.RestUtxoGuard)
        .create()

    init {
        scope.launch {
            settings.kapostIndexerUrl.collectLatest { url ->
                // Blank = no server - never keep the other network's client.
                if (url.isBlank()) _kapostApi.value = null else create<KaPostApi>(url)?.let { _kapostApi.value = it }
            }
        }
        scope.launch {
            settings.kaspaRestUrl.collectLatest { url ->
                create<KaPostRestPayloadApi>(url)?.let { _restPayloadApi.value = it }
            }
        }
    }

    /** A malformed URL keeps whatever client was there, as NetworkService does. */
    private inline fun <reified T> create(baseUrl: String): T? = try {
        val sanitized = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        Retrofit.Builder()
            .baseUrl(sanitized)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(T::class.java)
    } catch (e: Exception) {
        Log.w("KaPostApiProvider", "Invalid base URL, keeping previous client: $baseUrl", e)
        null
    }
}

/** One address's transactions on Kaspa REST, only what a KaPost is read from (KaPost's
 *  `KaspaRestApi.getAddressPayloads`; iOS ee5ec98, a person's posts with the indexer down). */
interface KaPostRestPayloadApi {
    @retrofit2.http.GET("addresses/{address}/full-transactions")
    suspend fun getAddressPayloads(
        @retrofit2.http.Path("address") address: String,
        @retrofit2.http.Query("limit") limit: Int,
        @retrofit2.http.Query("offset") offset: Int,
        @retrofit2.http.Query("resolve_previous_outpoints") resolvePreviousOutpoints: String = "no",
        @retrofit2.http.Query("fields") fields: String = "transaction_id,is_accepted,payload,block_time",
    ): List<AddressPayloadRow>
}

/** One row of [KaPostRestPayloadApi.getAddressPayloads]. */
data class AddressPayloadRow(
    @com.google.gson.annotations.SerializedName("transaction_id") val transactionId: String,
    @com.google.gson.annotations.SerializedName("is_accepted") val isAccepted: Boolean? = null,
    val payload: String? = null,
    @com.google.gson.annotations.SerializedName("block_time") val blockTime: Long? = null,
)

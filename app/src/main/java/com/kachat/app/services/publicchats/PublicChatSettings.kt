package com.kachat.app.services.publicchats

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.kachat.app.repository.AppSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public Chats' own settings, kept in the app's settings store (the same DataStore
 * [AppSettingsRepository] reads, under the keys KaPost's Android app used):
 * - the room indexer: the message indexer (iOS 148a5331 `AppSettings.publicChatIndexerURL` is
 *   `indexerURL`), so there is no Public Chats entry in Connection Settings;
 * - each room's own indexer, per network (Room Info, iOS `PublicChatService.setIndexerOverride`);
 * - the default rooms switched off in Edit Default Public Chats live with the read markers
 *   ([com.kachat.app.services.BroadcastReadStateStore]), per account, as on iOS.
 */
@Singleton
class PublicChatSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val appSettings: AppSettingsRepository,
) {
    /** The app-wide room indexer: the message indexer (blank on testnet, which has none yet). */
    val broadcastIndexerUrl: Flow<String> get() = appSettings.indexerUrl

    /** The explorer a room's "View on explorer" opens (Settings > Kaspa Explorer). */
    val kaspaExplorer: Flow<com.kachat.app.models.KaspaExplorer> get() = appSettings.kaspaExplorer

    /** Per-room overrides are kept per network (iOS 421a832): a mainnet room's indexer is not
     *  asked on testnet. */
    private val overridesKey
        get() = if (com.kachat.app.util.KaspaNetwork.isTestnet) KEY_OVERRIDES_TESTNET else KEY_OVERRIDES

    /** Per-room indexer overrides. A room absent from the map follows [broadcastIndexerUrl]. */
    val broadcastIndexerOverrides: Flow<Map<String, String>> =
        dataStore.data.map { prefs -> parseOverrides(prefs[overridesKey].orEmpty()) }.distinctUntilChanged()

    /** Points one room at its own indexer. A blank [url] clears the override. */
    suspend fun setBroadcastIndexerOverride(channel: String, url: String) {
        val key = channel.trim().lowercase()
        val trimmed = url.trim()
        dataStore.edit { prefs ->
            val current = parseOverrides(prefs[overridesKey].orEmpty()).toMutableMap()
            if (trimmed.isEmpty()) current.remove(key) else current[key] = trimmed
            prefs[overridesKey] = current.entries.joinToString("\n") { "${it.key}\u0000${it.value}" }
        }
    }

    /** Whether room senders' .kas avatars load (an avatar URL is anyone's, so it can be turned off). */
    val broadcastShowKnsAvatars: Flow<Boolean> = dataStore.data.map { it[KEY_SHOW_KNS_AVATARS] ?: true }

    /**
     * Accounts blocked in KaPost, per account: their room messages and pushes are left out too
     * (iOS 9e22075 `PublicChatStore.blockedSenders`). The same keys KaPost's block list uses, so
     * a Block from a room and one from a post are one list.
     */
    fun kapostsBlocked(walletAddress: String): Flow<Set<String>> =
        dataStore.data.map { it[blockedKey(walletAddress)] ?: emptySet() }

    fun kapostsMuted(walletAddress: String): Flow<Set<String>> =
        dataStore.data.map { it[mutedKey(walletAddress)] ?: emptySet() }

    suspend fun setKapostsBlocked(walletAddress: String, value: Set<String>) =
        dataStore.edit { it[blockedKey(walletAddress)] = value }

    suspend fun setKapostsMuted(walletAddress: String, value: Set<String>) =
        dataStore.edit { it[mutedKey(walletAddress)] = value }

    private companion object {
        val KEY_OVERRIDES = stringPreferencesKey("broadcast_indexer_overrides")
        val KEY_OVERRIDES_TESTNET = stringPreferencesKey("broadcast_indexer_overrides.testnet")
        val KEY_SHOW_KNS_AVATARS = booleanPreferencesKey("broadcast_show_kns_avatars")

        fun blockedKey(walletAddress: String) = stringSetPreferencesKey("kaposts_blocked_$walletAddress")
        fun mutedKey(walletAddress: String) = stringSetPreferencesKey("kaposts_muted_$walletAddress")

        fun parseOverrides(raw: String): Map<String, String> =
            raw.split('\n').mapNotNull { line ->
                val parts = line.split('\u0000')
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0] to parts[1] else null
            }.toMap()
    }
}

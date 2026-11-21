package com.kachat.app.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kachat.app.repository.AppSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Connection Settings > Trestly (iOS 5b7c772c `ConnectionSettingsView.trestlySections`): where
 * kUSDT balances are looked up, each answer checked on the node. KaChat only reads balances there;
 * buying and swapping kUSDT happen in KaPay.
 */
@HiltViewModel
class TrestlySettingsViewModel @Inject constructor(
    private val settings: AppSettingsRepository,
) : ViewModel() {
    /** The Testnet switch's network's Trestly API URL (its default when unset). */
    val selectedTrestlyApiUrl: StateFlow<String> =
        settings.selectedTrestlyApiUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Saves [value] for the selected network; blank goes back to the network's default. */
    fun setTrestlyApiUrl(value: String) {
        viewModelScope.launch { settings.setTrestlyApiUrl(value) }
    }

    companion object {
        /** iOS `isCleartextHTTP`: the field asks for unencrypted http. A bare hostname is fine. */
        fun isCleartextHttp(raw: String): Boolean = raw.trim().lowercase().startsWith("http://")

        /** iOS `normalizedOrDefault` / `normalizedHTTPSURL`: trimmed, anything not already https://
         *  given it in front, blank the [fallback]. */
        fun normalizedOrDefault(raw: String, fallback: String): String {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return fallback
            return if (trimmed.lowercase().startsWith("https://")) trimmed else "https://$trimmed"
        }
    }
}

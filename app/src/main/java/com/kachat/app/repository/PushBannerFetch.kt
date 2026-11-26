package com.kachat.app.repository

/**
 * The pure parts of [BroadcastRepository.fetchBeforePushBanner] (iOS 961fc05a), apart so they can
 * be unit tested.
 */
object PushBannerFetch {
    /** The wait between two asks while the indexer hasn't served the pushed row yet. */
    const val RETRY_GAP_MS = 800L

    /** A transaction id: 64 hex characters (iOS `isTxIdShaped`). */
    fun isTxIdShaped(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}

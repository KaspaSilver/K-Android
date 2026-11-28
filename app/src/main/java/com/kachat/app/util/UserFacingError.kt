package com.kachat.app.util

import com.kachat.app.R

/**
 * One place that turns a caught exception into a sentence a person can act on. Port of iOS's
 * `UserFacingError` (d887c98): about forty call sites read `e.message ?: "<fallback>"`, which shows
 * the fallback only when the exception happens to have no message — so the sentence people
 * actually saw was usually the technical one underneath, "Unable to resolve host …" or a Gson
 * parse trace.
 *
 * It differs from iOS on purpose in one respect. iOS ends with `error.localizedDescription`, which
 * Foundation writes as prose; Java's `getMessage()` is written for a log. So an unrecognised
 * exception here returns the CALLER's [fallback] rather than its own message — the call sites
 * already pass a good one ("Consolidation failed", "Transfer failed") and it was only ever being
 * used when the message was null.
 *
 * Exceptions the app throws itself ([IllegalArgumentException], [IllegalStateException] and
 * [PortfolioAddressImportError]-style app types) keep their message: those strings were written
 * for the person reading them, and dropping them would lose the specific reason.
 */
object UserFacingError {

    fun message(error: Throwable?, fallback: String): String {
        if (error == null) return fallback
        return when (error) {
            is kotlinx.coroutines.CancellationException -> text(R.string.rel_wallet_cancelled, "Cancelled.")

            // The app's own `require`/`check`/`throw` - authored for the reader.
            is IllegalArgumentException, is IllegalStateException ->
                error.message?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() }?.let(::translated) ?: fallback

            is java.net.UnknownHostException,
            is java.net.ConnectException,
            is java.net.NoRouteToHostException,
            is java.net.PortUnreachableException ->
                text(R.string.rel_wallet_err_cant_reach, "Couldn't reach the server. Check the address in Settings and your connection.")

            is java.net.SocketTimeoutException, is java.io.InterruptedIOException ->
                text(R.string.rel_wallet_err_timed_out, "The connection timed out. Check your connection and try again.")

            is javax.net.ssl.SSLException ->
                text(R.string.rel_wallet_err_secure, "Couldn't make a secure connection to the server.")

            is com.google.gson.JsonParseException, is com.google.gson.JsonIOException ->
                text(R.string.rel_wallet_err_unreadable, "The server sent something the app couldn't read. Try again; if it keeps happening, update the app.")

            is retrofit2.HttpException ->
                "The server returned an error (${error.code()}). Try again in a moment."

            is io.grpc.StatusRuntimeException, is io.grpc.StatusException ->
                text(R.string.rel_wallet_err_node, "The Kaspa node returned an error. The app will try another node; try again in a moment.")

            // Keystore and crypto failures: the underlying text names providers and algorithms and
            // tells the reader nothing they can act on.
            is java.security.GeneralSecurityException -> fallback

            // Anything else IO-shaped is a network problem in this app - OkHttp wraps most of them.
            is java.io.IOException ->
                text(R.string.rel_wallet_err_network, "A network error occurred. Check your connection and try again.")

            else -> {
                val appAuthored = error.javaClass.name.startsWith("com.kachat.app")
                if (appAuthored) {
                    error.message?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() }?.let(::translated) ?: fallback
                } else {
                    fallback
                }
            }
        }
    }

    /** The same treatment for a reason that is already a string. */
    fun message(text: String?, fallback: String): String =
        text?.trim()?.stripKnownPrefixes()?.takeIf { it.isNotEmpty() }?.let(::translated) ?: fallback

    /** The app's own common error sentences, in English as the send paths throw them (they match
     *  on that text), and their translations (iOS 01f71184 `UserFacingError`: "the app's own
     *  common error sentences have translations"). */
    internal val KNOWN_SENTENCES: Map<String, Int> = mapOf(
        "Cancelled." to R.string.rel_wallet_cancelled,
        "No internet connection. Check your connection and try again." to R.string.rel_wallet_err_no_internet,
        "The connection timed out. Check your connection and try again." to R.string.rel_wallet_err_timed_out,
        "Couldn't reach the server. Check the address in Settings and your connection." to R.string.rel_wallet_err_cant_reach,
        "Couldn't make a secure connection to the server." to R.string.rel_wallet_err_secure,
        "A network error occurred. Check your connection and try again." to R.string.rel_wallet_err_network,
        "The server sent something the app couldn't read. Try again; if it keeps happening, update the app." to R.string.rel_wallet_err_unreadable,
        "The Kaspa node returned an error. The app will try another node; try again in a moment." to R.string.rel_wallet_err_node,
        "An unknown error occurred." to R.string.rel_wallet_err_unknown,
        "Something went wrong. Please try again." to R.string.kp_9a3ea0598e7f,
        "No spendable UTXOs available" to R.string.rel_wallet_err_no_spendable_available,
        "No spendable UTXOs" to R.string.no_spendable_utxos,
        "Could not get private key" to R.string.rel_wallet_err_no_private_key,
        "Insufficient funds for payment" to R.string.rel_wallet_err_insufficient_payment,
        "Insufficient funds after fee" to R.string.rel_wallet_err_insufficient_after_fee,
        "Insufficient funds for handshake" to R.string.rel_wallet_err_insufficient_handshake,
        "Insufficient funds for self-stash fee" to R.string.rel_wallet_err_insufficient_self_stash,
        "Balance too low to cover fee" to R.string.balance_too_low_to_cover_fee,
        "Missing group secrets - try rejoining this group." to R.string.rel_wallet_err_group_secrets,
        "Missing admin group secrets." to R.string.rel_wallet_err_admin_secrets,
        "No spendable UTXOs at this address" to R.string.rel_wallet_no_spendable_at_address,
        "Insufficient funds to cover that amount plus the network fee" to R.string.rel_wallet_insufficient_plus_fee,
    )

    /** [sentence] in the app's language when it's one of [KNOWN_SENTENCES]; any other text as is. */
    fun translated(sentence: String): String {
        val id = KNOWN_SENTENCES[sentence] ?: return sentence
        return text(id, sentence)
    }

    /** A string resource, or [english] where resources can't be read (a unit test). */
    private fun text(id: Int, english: String): String =
        try { tr(id) } catch (e: Exception) { english }

    private fun String.stripKnownPrefixes(): String {
        var cleaned = this
        for (prefix in listOf("Network error: ", "API error: ", "Keychain error: ", "Encryption error: ")) {
            if (cleaned.startsWith(prefix)) cleaned = cleaned.removePrefix(prefix)
        }
        return cleaned.trim()
    }
}

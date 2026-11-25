package com.kachat.app.services

import android.util.Log
import com.kachat.app.services.kachatnames.KachatNames
import com.kachat.app.services.kachatnames.KachatNamesRegistry
import com.kachat.app.services.kachatnames.KachatNamesService
import com.kachat.app.services.kachatnames.resolvedOwner
import com.kachat.app.util.KaspaAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Name services K reads: only `.kachat`, KaChat's own names (Kaspa covenants, KACHAT_NAMES.md),
 * read through [KachatNamesRegistry] (the names indexer, or the chain itself). Every other Kaspa
 * name service (KNS's .kas, dotk's .k, Kaspa Names' .kaspa) was removed on 2026-11-23 (owner's
 * call, iOS 518fdc8c): K resolves, shows and manages .kachat names only.
 */
enum class NameServiceTLD(val raw: String) {
    KACHAT("kachat");

    /** ".kachat" - the display suffix. */
    val suffix: String get() = ".$raw"

    /** Who runs it, for empty states. */
    val serviceName: String get() = "KaChat Names"

    /** Whether the app can read this service on the current network: only where its registry is
     *  launched. Typed-name resolution never asks a registry that isn't launched. */
    val isLive: Boolean get() = KachatNamesService.isLaunched

    companion object {
        /** Splits typed input into its label and the ".kachat" ending, if typed. */
        fun splitTypedName(input: String): Pair<String, NameServiceTLD?> {
            val trimmed = input.trim()
            if (trimmed.lowercase().endsWith(KACHAT.suffix)) return trimmed.dropLast(KACHAT.suffix.length) to KACHAT
            return trimmed to null
        }
    }
}

/** What a typed name points to. */
data class NameResolution(
    val tld: NameServiceTLD,
    /** The name as shown (with or without its ending, per Show .kachat Endings). */
    val display: String,
    /** Where it points; null when the name is not registered (or has no address to pay). */
    val address: String?,
    /** The registry could not be asked (network or server failure), so "not registered" is unknown. */
    val failed: Boolean,
    /** The registry isn't live on this network: nothing can resolve. */
    val notLive: Boolean = false,
) {
    /** The name with its ending always, for text other people read later (a KaPost @mention): a
     *  bare name there wouldn't say what it is, whatever this phone shows (iOS
     *  `NameResolution.fullName`). */
    val fullName: String
        get() = if (display.endsWith(tld.suffix)) display else display + tld.suffix

    companion object {
        /**
         * The registry's answer is outside data (iOS `NameResolution.init`): an address that
         * isn't a valid one on the network the app runs on (a wrong length, the other network,
         * garbage) is dropped here, so no name can put it into a send or the Address Book.
         * Trimmed and lowercased; null for no address or a bad one.
         */
        fun validatedAddress(raw: String?): String? =
            raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && KaspaAddress.isValidOnActiveNetwork(it) }
    }
}

/** One .kachat name an address holds. */
data class OwnedServiceName(
    /** The bare canonical name, without the suffix. */
    val name: String,
    /** The display form. */
    val display: String,
    val tld: NameServiceTLD,
    val isProvisional: Boolean,
)

@Singleton
class NameServicesClient @Inject constructor(
    // Lazy: the registry (and the node/REST plumbing behind it) is only built where .kachat is
    // live.
    private val kachatRegistry: dagger.Lazy<KachatNamesRegistry>,
) {
    /** The .kachat names each of [addresses] holds (active or in grace), where the registry is
     *  live; empty elsewhere. */
    suspend fun kachatNames(addresses: List<String>): Map<String, List<OwnedServiceName>> {
        if (!NameServiceTLD.KACHAT.isLive || addresses.isEmpty()) return emptyMap()
        val registry = kachatRegistry.get()
        registry.refreshIfStale()
        val result = mutableMapOf<String, List<OwnedServiceName>>()
        for (address in addresses) {
            val key = KachatNamesRegistry.keyOf(address) ?: continue
            val held = runCatching { registry.heldNames(key) }.getOrNull().orEmpty()
            if (held.isEmpty()) continue
            result[address] = held.map { OwnedServiceName(name = it.name, display = com.kachat.app.models.KachatNameLabel.display(it.name), tld = NameServiceTLD.KACHAT, isProvisional = false) }
        }
        return result
    }

    /** Whether [address] holds a .kachat name (grace and lapsed included). Account discovery asks
     *  this so an address that holds only a name, and no KAS, is still found. */
    suspend fun ownsAnyName(address: String): Boolean {
        if (!NameServiceTLD.KACHAT.isLive) return false
        val key = KachatNamesRegistry.keyOf(address) ?: return false
        val registry = kachatRegistry.get()
        registry.refreshIfStale()
        return runCatching { registry.names(key, includeInactive = true) }.getOrNull().orEmpty().isNotEmpty()
    }

    // Forward resolution (typed name -> address)

    /** What [input] points to as a .kachat name: one answer, or none when the label isn't a valid
     *  name. */
    suspend fun resolveEverywhere(input: String): List<NameResolution> {
        val label = NameServiceTLD.splitTypedName(input).first
        if (label.isEmpty()) return emptyList()
        return listOfNotNull(resolveKachat(label))
    }

    /** `.kachat`: the registry's owner of a name that is active or in its grace period - an owner
     *  stays reachable until the name is back on the market; only a lapsed name doesn't resolve
     *  (KACHAT_NAMES.md section 4, [KachatNames.resolvedOwner]). Same rules as the gap: a-z, 0-9,
     *  hyphen. */
    private suspend fun resolveKachat(label: String): NameResolution? {
        val canonical = KachatNames.Codec.normalize(label)
        if (!KachatNames.Codec.isValid(canonical)) return null
        val display = com.kachat.app.models.KachatNameLabel.display(canonical)
        if (!NameServiceTLD.KACHAT.isLive) {
            return NameResolution(NameServiceTLD.KACHAT, display, null, failed = false, notLive = true)
        }
        val registry = kachatRegistry.get()
        registry.refreshIfStale()
        return try {
            val found = registry.lookup(canonical)
            val owner = KachatNames.resolvedOwner(found, registry.graceMs)
            if (owner != null) {
                val record = (found as? com.kachat.app.services.kachatnames.Lookup.Registered)?.info
                    ?: throw KachatNames.Failure("the registry has no record for that name")
                // a node must hold this exact record before anything is sent to it (iOS 4f8e14bb)
                if (record.name != canonical) throw KachatNames.Failure("the indexer answered for another name")
                registry.confirmOnChain(record)
            }
            NameResolution(NameServiceTLD.KACHAT, display, answer(display, owner?.let { KachatNamesRegistry.address(it) }), failed = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, ".kachat lookup failed: ${e.message}")
            NameResolution(NameServiceTLD.KACHAT, display, null, failed = true)
        }
    }

    /** [NameResolution.validatedAddress], with a log line when the registry's address is dropped. */
    private fun answer(display: String, raw: String?): String? {
        val address = NameResolution.validatedAddress(raw)
        if (address == null && !raw.isNullOrBlank()) Log.w(TAG, "$display answered an address that isn't valid here; ignored")
        return address
    }

    companion object {
        private const val TAG = "NameServices"

        /** Whether typed input could be a .kachat name (and is not an address): a bare label, or a
         *  label ending in ".kachat". */
        fun looksLikeName(input: String): Boolean {
            val trimmed = input.trim().lowercase()
            if (trimmed.startsWith("kaspa:") || trimmed.startsWith("kaspatest:")) return false
            val label = NameServiceTLD.splitTypedName(trimmed).first
            return label.isNotEmpty() && label.all { it.isLetterOrDigit() || it == '-' || it == '_' } &&
                !mixesScripts(label)
        }

        /** Letters from more than one writing system in one label ("аlice": a Cyrillic а among
         *  Latin letters) - a lookalike of another name, never resolved (security audit L5). */
        internal fun mixesScripts(label: String): Boolean {
            val scripts = HashSet<Character.UnicodeScript>()
            var i = 0
            while (i < label.length) {
                val cp = label.codePointAt(i)
                if (Character.isLetter(cp)) {
                    val script = when (val s = Character.UnicodeScript.of(cp)) {
                        // Japanese, Korean and Chinese writing mix these as a matter of course
                        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
                        Character.UnicodeScript.HANGUL, Character.UnicodeScript.BOPOMOFO -> Character.UnicodeScript.HAN
                        else -> s
                    }
                    if (script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) scripts += script
                }
                i += Character.charCount(cp)
            }
            return scripts.size > 1
        }

        /** The answer a typed name gets: its .kachat owner, if it has one. */
        @Suppress("UNUSED_PARAMETER")
        fun primary(results: List<NameResolution>, typed: String): NameResolution? =
            results.firstOrNull { it.tld == NameServiceTLD.KACHAT && it.address != null }

        /** Why a typed name has no answer: no such .kachat name, the registry couldn't be asked,
         *  or there's no .kachat registry on this network. The one not-found message every
         *  address field shows. */
        @Suppress("UNUSED_PARAMETER")
        fun notFoundMessage(context: android.content.Context, typed: String, results: List<NameResolution>): String {
            if (results.any { it.notLive } || !NameServiceTLD.KACHAT.isLive) {
                return context.getString(com.kachat.app.R.string.kachat_names_not_live_here)
            }
            // Couldn't be checked is not "no such name" (iOS 4f8e14bb).
            results.firstOrNull { it.failed }?.let {
                return context.getString(com.kachat.app.R.string.couldnt_look_up_name_try_again, it.display)
            }
            return context.getString(com.kachat.app.R.string.names54_no_username_found)  // audit 2026-11-24 #36
        }
    }
}

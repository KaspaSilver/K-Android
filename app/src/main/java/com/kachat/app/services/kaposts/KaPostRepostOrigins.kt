package com.kachat.app.services

import com.kachat.app.models.KaPostDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What a plain repost reposted (iOS 01f71184 `KaPostRepostOrigins`). A repost goes on chain as a
 * quote with no words, and the indexer's quote reference carries only the quoted post's own
 * words, so a repost of a repost named the middle reposter over an empty card. This follows the
 * chain (up to [MAX_DEPTH] reposts deep) to the first post with words: the indexer's `get-post`,
 * else the chain. Answers are kept for the run; a failed lookup is tried again after
 * [RETRY_AFTER_MS].
 */
@Singleton
class KaPostRepostOrigins @Inject constructor(
    private val service: KaPostsService,
    private val chainReader: KaPostChainReader,
) {
    private val resolver = Resolver(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler("KaPostRepostOrigins")),
        quoteOf = ::quote,
    )

    /** The original already found for [quoted], or null (read while drawing: no lookup). */
    fun cached(quoted: KaPostDraft.QuotedRef): KaPostDraft.QuotedRef? = resolver.cached(quoted)

    /** The first post with words behind the plain repost [quoted], or null. */
    suspend fun original(quoted: KaPostDraft.QuotedRef): KaPostDraft.QuotedRef? = resolver.original(quoted)

    /** The post [id] quotes, or null when it quotes nothing or can't be read. */
    private suspend fun quote(id: String): KaPostDraft.QuotedRef? {
        val post = try { service.fetchPost(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
        if (post != null) {
            val quote = post.quote ?: return null
            val pubkey = quote.referencedSenderPubkey ?: return null
            val address = KaPostsService.kaspaAddressFromPubkey(pubkey) ?: return null
            return KaPostDraft.QuotedRef(
                remoteId = quote.referencedContentId,
                text = com.kachat.app.util.KaPostsProtocol.stripMarker(quote.decodedMessage ?: ""),
                posterAddress = address,
                timestamp = null,
                posterPubkey = pubkey,
            )
        }
        // The indexer is down or hasn't caught up: the chain has every post.
        val record = chainReader.fetch(id) ?: return null
        if (record.action != "quote") return null
        val quotedId = record.referencedId ?: return null
        val quoted = chainReader.fetch(quotedId) ?: return null
        val address = KaPostsService.kaspaAddressFromPubkey(quoted.authorPubkey) ?: return null
        return KaPostDraft.QuotedRef(
            remoteId = quotedId,
            text = quoted.message,
            posterAddress = address,
            timestamp = quoted.blockTimeMillis,
            posterPubkey = quoted.authorPubkey,
        )
    }

    /** The cache and the walk, apart from the network so it can be tested. */
    class Resolver(
        private val scope: CoroutineScope,
        private val now: () -> Long = System::currentTimeMillis,
        private val quoteOf: suspend (String) -> KaPostDraft.QuotedRef?,
    ) {
        private val found = HashMap<String, KaPostDraft.QuotedRef>()
        private val failedAt = HashMap<String, Long>()
        private val running = HashMap<String, Deferred<KaPostDraft.QuotedRef?>>()

        @Synchronized
        fun cached(quoted: KaPostDraft.QuotedRef): KaPostDraft.QuotedRef? {
            val id = quoted.remoteId ?: return null
            return found[id]
        }

        suspend fun original(quoted: KaPostDraft.QuotedRef): KaPostDraft.QuotedRef? {
            if (!isPlainRepost(quoted)) return null
            val id = quoted.remoteId ?: return null
            val task = synchronized(this) {
                found[id]?.let { return it }
                failedAt[id]?.let { if (now() - it < RETRY_AFTER_MS) return null }
                running.getOrPut(id) {
                    // Recorded by the lookup itself: a cell scrolled away mid-lookup doesn't
                    // lose the answer for the next one. Started once it's in [running].
                    scope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        val result = try { follow(id) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
                        synchronized(this@Resolver) {
                            running.remove(id)
                            if (result != null) {
                                found[id] = result
                                failedAt.remove(id)
                            } else {
                                failedAt[id] = now()
                            }
                        }
                        result
                    }
                }
            }
            task.start()
            return task.await()
        }

        private suspend fun follow(from: String): KaPostDraft.QuotedRef? {
            var current = from
            val seen = hashSetOf(from)
            repeat(MAX_DEPTH) {
                val next = quoteOf(current) ?: return null
                if (!isPlainRepost(next)) return next
                val nextId = next.remoteId ?: return null
                if (!seen.add(nextId)) return null
                current = nextId
            }
            return null
        }
    }

    companion object {
        /** How many reposts deep the walk goes. */
        const val MAX_DEPTH = 5
        /** A failed lookup is asked again after this. */
        const val RETRY_AFTER_MS = 120_000L

        /** A quote with no words of its own: a plain repost (needs an id to follow). */
        fun isPlainRepost(quoted: KaPostDraft.QuotedRef): Boolean {
            if (quoted.remoteId.isNullOrEmpty()) return false
            return quoted.text.isBlank()
        }
    }
}

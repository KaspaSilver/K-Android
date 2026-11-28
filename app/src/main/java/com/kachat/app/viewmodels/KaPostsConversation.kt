package com.kachat.app.viewmodels

import com.kachat.app.models.KaPostDraft

/**
 * KaPost conversations as X draws them (iOS 961fc05a, owner 2026-11-25: "match X"): the poster's
 * own thread, the order of everyone else's replies, and who a reply answers.
 */
object KaPostsConversation {
    /**
     * The next segment of a poster's thread among [replies]: their own earliest reply. A thread is
     * the poster's own posts, each under their previous one; anyone else's reply is a comment,
     * never part of the thread, even when it's the only one (iOS `loadSelfThreadChain`).
     */
    /**
     * The thread root (local id) your sent reply to your own post [parentId] continues, or null
     * (iOS 01f71184 T4 `extendSelfThreadChain`): the root whose chain ends at the parent, or the
     * parent itself when it has no chain yet. [chains] is root local id -> its chain's local ids.
     */
    fun selfThreadChainRoot(chains: Map<String, List<String>>, parentId: String): String? =
        chains.entries.firstOrNull { it.value.lastOrNull() == parentId }?.key
            ?: if (chains[parentId].isNullOrEmpty()) parentId else null

    fun selfThreadNext(replies: List<KaPostDraft>, poster: String): KaPostDraft? =
        replies.filter { it.posterAddress == poster && it.remoteId != null }.minByOrNull { it.timestamp }

    /** Where a reply ranks: the poster's own 0, yours 1, people you follow 2, everyone else 3. */
    fun replyTier(reply: KaPostDraft, poster: String, me: String, followed: Set<String>): Int = when {
        reply.posterAddress == poster -> 0
        me.isNotEmpty() && reply.posterAddress == me -> 1
        reply.posterAddress in followed -> 2
        else -> 3
    }

    /** X's order: by tier, then the most liked, then the oldest (iOS `commentsExcludingThread`).
     *  The tier is worked out once per reply, not in every comparison. */
    fun rankReplies(replies: List<KaPostDraft>, poster: String, me: String, followed: Set<String>): List<KaPostDraft> =
        replies
            .map { it to replyTier(it, poster, me, followed) }
            .sortedWith(
                compareBy<Pair<KaPostDraft, Int>> { it.second }
                    .thenByDescending { it.first.likes }
                    .thenBy { it.first.timestamp }
            )
            .map { it.first }

    /** How long your own reply counts as just sent, and goes up with the poster's (10 minutes). */
    const val FRESH_OWN_REPLY_MS = 600_000L

    /** Your reply sent while the post is open (not on chain yet, or within ten minutes): it goes
     *  up where you'd look for it; a later page's older ones stay below. */
    fun goesUp(reply: KaPostDraft, me: String, nowMs: Long): Boolean =
        me.isNotEmpty() && reply.posterAddress == me &&
            (reply.remoteId == null || nowMs - reply.timestamp < FRESH_OWN_REPLY_MS)

    /** The poster's replies and yours: the top of the list. */
    fun isTopTier(reply: KaPostDraft, poster: String, me: String): Boolean =
        reply.posterAddress == poster || (me.isNotEmpty() && reply.posterAddress == me)

    /**
     * Who a reply answers, as an address, for "Replying to" on a reply shown outside its
     * conversation (a profile's Replies, Bookmarks; iOS `replyingToLabel`): the parent's author,
     * known for this session's replies ([KaPostDraft.parentPosterPubkey]), else the parent's own
     * post when it's loaded ([parentAuthor]), else the first signed mention other than the poster,
     * which K writes as the parent's author. That names the poster for their own thread reply
     * (audit 2026-11-25 L5): K leaves the signer out of a reply's signed mentions, so for a thread
     * reply the first mention was someone else, or none. Null for a post.
     */
    fun replyingToAddress(
        post: KaPostDraft,
        addressOf: (String) -> String?,
        parentAuthor: (String) -> String?,
    ): String? {
        val parentId = post.parentRemoteId?.takeIf { it.isNotEmpty() } ?: return null
        post.parentPosterPubkey?.let(addressOf)?.let { return it }
        parentAuthor(parentId)?.let { return it }
        for (pubkey in post.mentionedPubkeys) {
            val address = addressOf(pubkey) ?: continue
            if (address != post.posterAddress) return address
        }
        return null
    }
}

/**
 * The order an open post's replies are shown in, kept while it's open (iOS `CommentOrderMemo`,
 * audit 2026-11-25 L4). Ranked once, when the replies first show: re-sorting on every pass (with a
 * follow lookup in each comparison) cost time while scrolling, and moved rows under the reader each
 * time a page or a like landed. Rows already shown keep their place; replies that arrive later
 * (the next page) are ranked among themselves and go below, except your own fresh reply, which
 * goes up with the poster's and yours as X shows it. A plain box: writing it while a list is drawn
 * starts no new pass.
 */
class CommentOrderMemo {
    private val orders = HashMap<String, List<String>>()

    /** Closed: the next opening ranks afresh, with the likes as they are then. */
    @Synchronized
    fun forget(postId: String) {
        orders.remove(postId)
    }

    @Synchronized
    fun ordered(
        replies: List<KaPostDraft>,
        postId: String,
        rank: (List<KaPostDraft>) -> List<KaPostDraft>,
        goesUp: (KaPostDraft) -> Boolean,
        isTopTier: (KaPostDraft) -> Boolean,
    ): List<KaPostDraft> {
        val byKey = LinkedHashMap<String, KaPostDraft>(replies.size)
        for (reply in replies) byKey.putIfAbsent(key(reply), reply)
        val previous = orders[postId].orEmpty()
        val shown = previous.mapNotNull { byKey[it] }.toMutableList()
        val known = previous.toHashSet()
        val seenNew = HashSet<String>()
        val arrived = replies.filter { reply ->
            val key = key(reply)
            key !in known && seenNew.add(key)
        }
        if (arrived.isEmpty() && shown.size == previous.size) return shown
        val ranked = rank(arrived)
        val result = if (previous.isEmpty()) {
            ranked
        } else {
            val up = ranked.filter(goesUp)
            // after the replies already shown at the top (the poster's own and yours)
            val insertAt = shown.indexOfFirst { !isTopTier(it) }.let { if (it < 0) shown.size else it }
            shown.addAll(insertAt, up)
            shown.addAll(ranked.filterNot(goesUp))
            shown
        }
        orders[postId] = result.map(::key)
        return result
    }

    companion object {
        /** A reply's key: its txid, or its local id before it has one. */
        fun key(reply: KaPostDraft): String = reply.remoteId ?: reply.id
    }
}

/**
 * Parent post id -> its author's address, for "Replying to" (iOS `ReplyParentAuthors`). A miss is
 * asked again after 15 seconds, not on every redraw: the search walks every loaded list and its
 * replies, and the parent may load later (a profile's Posts page, an opened thread). A plain box,
 * so writing it while a row is drawn starts no new pass.
 */
class ReplyParentAuthors(private val now: () -> Long = System::currentTimeMillis) {
    private val known = HashMap<String, String>()
    private val missedAt = HashMap<String, Long>()

    @Synchronized
    fun author(parentId: String, find: (String) -> KaPostDraft?): String? {
        known[parentId]?.let { return it }
        val time = now()
        missedAt[parentId]?.let { if (time - it < RETRY_MS) return null }
        val parent = find(parentId)
        if (parent == null || parent.posterAddress.isEmpty()) {
            missedAt[parentId] = time
            return null
        }
        known[parentId] = parent.posterAddress
        missedAt.remove(parentId)
        return parent.posterAddress
    }

    @Synchronized
    fun clear() {
        known.clear()
        missedAt.clear()
    }

    companion object {
        const val RETRY_MS = 15_000L
    }
}

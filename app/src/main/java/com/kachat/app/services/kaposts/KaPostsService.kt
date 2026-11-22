package com.kachat.app.services

import android.util.Log
import com.kachat.app.util.KaPostsProtocol
import com.kachat.app.util.KaspaAddress
import com.kachat.app.util.KaspaMessageSigner
import com.kachat.app.util.Secp256k1
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import retrofit2.HttpException
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

// MARK: - Wire models (verbatim K field names; identity fields deliberately ignored - KNS owns
// ALL identity via the pubkey -> Kaspa address bridge, so userNickname/avatar fields never map)

/** Embedded reference the indexer attaches to quote posts. */
data class KQuoteRef(
    val referencedContentId: String?,
    val referencedMessage: String?,
    val referencedSenderPubkey: String?,
) {
    val decodedMessage: String? get() = referencedMessage?.let { KaPostsProtocol.decodeB64(it) }
}

data class KPost(
    val id: String,
    val userPublicKey: String,
    val postContent: String,
    val timestamp: Long,
    val repliesCount: Int?,
    val quotesCount: Int?,
    val upVotesCount: Int?,
    val downVotesCount: Int?,
    /** How many times it was tipped (KAPOSTS_INDEXER.md 5.11); absent on an older indexer. */
    val tipsCount: Int? = null,
    val parentPostId: String?,
    val mentionedPubkeys: List<String>?,
    val isUpvoted: Boolean?,
    val isDownvoted: Boolean?,
    val blockedUser: Boolean?,
    val contentType: String?,
    val isQuote: Boolean?,
    val quote: KQuoteRef?,
    /** Set by the indexer when the text shown is an accepted edit (ms). [postContent] is then
     *  the edited text; [timestamp] stays the original's. */
    val editedAt: Long? = null,
    /** Present when this post is a poll (KAPOSTS_INDEXER.md section 5.9): the options, the
     *  counts so far, this reader's own vote and when voting closes. */
    val poll: KPoll? = null,
) {
    /** Base64 -> plain text (K encodes all content fields). */
    val decodedContent: String? get() = KaPostsProtocol.decodeB64(postContent)
}

/**
 * The indexer's poll object (KAPOSTS_INDEXER.md section 5.9). [options] are base64, like every
 * content field the API returns. [myVote] is the requesting pubkey's own option index, or null.
 */
data class KPoll(
    val options: List<String>?,
    val counts: List<Int>?,
    val total: Int?,
    val closesAt: Long?,
    val myVote: Int?,
) {
    val decodedOptions: List<String>
        get() = options.orEmpty().map { KaPostsProtocol.decodeB64(it) ?: "" }
}

data class KPagination(val hasMore: Boolean?, val nextCursor: String?, val prevCursor: String?)

data class KPostsResponse(val posts: List<KPost>?, val pagination: KPagination?)

data class KRepliesResponse(val replies: List<KPost>?, val pagination: KPagination?)

/** `get-post?id=` - one post. */
data class KPostResponse(val post: KPost?)

/** `get-thread?id=` - the post plus its ancestors, ROOT FIRST and excluding the post itself. */
data class KThreadResponse(val ancestors: List<KPost>?, val post: KPost?)

/**
 * One fetched page, keeping the RAW page's paging facts alongside the filtered rows.
 *
 * The KaChat-marker filter (and the viewer's mute/block lists further up) can shrink a 25-item
 * server page down to two visible rows, so "did we reach the end?" can only ever be answered from
 * the untouched response: [hasMore]/[cursor] come from the server's pagination block, and [rawIds]
 * are every id the page carried, filtered or not. Callers use [rawIds] to detect a deployment that
 * ignores `before` (a second page repeating the same ids) and stop instead of looping.
 */
data class KPage<T>(
    val items: List<T>,
    val rawIds: List<String>,
    val cursor: String?,
    val hasMore: Boolean,
) {
    companion object {
        fun <T> empty(): KPage<T> = KPage(emptyList(), emptyList(), null, false)
    }
}

data class KNotification(
    val id: String,
    val userPublicKey: String,
    val postContent: String?,
    val timestamp: Long,
    val contentType: String?,
    val voteType: String?,
    val contentId: String?,
    /** contentType "tip" only: what the tip paid you, in sompi (contentId = the tipped post). */
    val tipAmountSompi: Long? = null,
) {
    val decodedContent: String? get() = postContent?.let { KaPostsProtocol.decodeB64(it) }
}

/** One tip of a post: who, how much, when (`get-post-tippers`, KAPOSTS_INDEXER.md 5.11). */
data class KTip(
    /** The tip's txid. */
    val id: String,
    val userPublicKey: String,
    val amountSompi: Long,
    val timestamp: Long,
)

data class KTippersResponse(val tippers: List<KTip>?, val pagination: KPagination?)

data class KNotificationsResponse(val notifications: List<KNotification>?, val pagination: KPagination?)

/** One actor row from get-post-engagement (KaChat indexer fork). */
data class KEngagementEntry(
    val actorPubkey: String,
    val actionTxId: String,
    val timestamp: Long,
    val kind: String, // upvote | downvote | repost | quote
)

data class KEngagementResponse(val engagement: List<KEngagementEntry>?, val pagination: KPagination?)

data class KFollowUser(val userPublicKey: String, val timestamp: Long?)

/**
 * The users-list endpoints wrap their items under "posts" (verified live against the fork) -
 * keep the other plausible keys as fallbacks in case deployments differ.
 */
data class KFollowListResponse(
    val posts: List<KFollowUser>?,
    val users: List<KFollowUser>?,
    val following: List<KFollowUser>?,
    val followers: List<KFollowUser>?,
    val pagination: KPagination? = null,
) {
    val items: List<KFollowUser> get() = posts ?: users ?: following ?: followers ?: emptyList()
}

data class KUserDetails(
    val userPublicKey: String?,
    val followersCount: Int?,
    val followingCount: Int?,
    val followedUser: Boolean?,
)

/** REST read API of the K social indexer (Settings > Connection Settings > KaPost Indexer). */
interface KaPostApi {
    @GET("get-posts-watching")
    suspend fun getPostsWatching(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-contents-following")
    suspend fun getContentsFollowing(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-posts")
    suspend fun getUserPosts(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("includeReplies") includeReplies: String? = null,
        @Query("before") before: String? = null,
    ): KPostsResponse

    @GET("get-replies")
    suspend fun getReplies(
        @Query("post") postId: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    /** One post by id, any age, any author - what makes a reply's context reachable at all. */
    @GET("get-post")
    suspend fun getPost(
        @Query("id") id: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KPostResponse

    /** A post plus its ancestor chain, root first, done server-side in one request. */
    @GET("get-thread")
    suspend fun getThread(
        @Query("id") id: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KThreadResponse

    /** get-replies dual mode: `user` instead of `post` returns all replies MADE BY that user. */
    @GET("get-replies")
    suspend fun getUserReplies(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KRepliesResponse

    @GET("get-notifications")
    suspend fun getNotifications(
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KNotificationsResponse

    /** Who tipped a post, newest first (KAPOSTS_INDEXER.md 5.11). */
    @GET("get-post-tippers")
    suspend fun getPostTippers(
        @Query("postId") postId: String,
        @Query("limit") limit: Int = 50,
        @Query("before") before: String? = null,
    ): KTippersResponse

    @GET("get-post-engagement")
    suspend fun getPostEngagement(
        @Query("postId") postId: String,
        @Query("type") type: String = "all",
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KEngagementResponse

    /**
     * The follow-list endpoints are not documented as cursored; `before` is sent anyway so a
     * deployment that supports it paginates, and one that ignores it simply replays page one -
     * which the caller detects (no new ids) and treats as "end of list" instead of looping.
     */
    @GET("get-users-following")
    suspend fun getUsersFollowing(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-users-followers")
    suspend fun getUsersFollowers(
        @Query("userPubkey") userPubkey: String,
        @Query("requesterPubkey") requesterPubkey: String,
        @Query("limit") limit: Int = 100,
        @Query("before") before: String? = null,
    ): KFollowListResponse

    @GET("get-user-details")
    suspend fun getUserDetails(
        @Query("user") user: String,
        @Query("requesterPubkey") requesterPubkey: String,
    ): KUserDetails

    /** Hands a signed transaction to the indexer to submit at its time (section 5.10). */
    @POST("schedule-post")
    suspend fun schedulePost(@Body body: KSchedulePostRequest): KScheduleResponse

    /** This wallet's scheduled posts, as the indexer sees them. */
    @GET("scheduled-posts")
    suspend fun getScheduledPosts(@Query("pubkey") pubkey: String): KScheduledPostsResponse

    /** Takes back a post the indexer has not submitted yet. */
    @POST("cancel-scheduled-post")
    suspend fun cancelScheduledPost(@Body body: KCancelScheduledRequest): KScheduleResponse
}

/** `POST /schedule-post` (section 5.10). [transaction] is the Kaspa REST `POST /transactions`
 *  body verbatim, which is what the indexer submits on the phone's behalf. */
data class KSchedulePostRequest(
    val pubkey: String,
    val txId: String,
    val notBefore: Long,
    val signature: String,
    val transaction: KRestTransactionEnvelope,
)

data class KRestTransactionEnvelope(val transaction: RawTransaction)

data class KCancelScheduledRequest(val pubkey: String, val txId: String, val signature: String)

data class KScheduleResponse(val txId: String?, val notBefore: Long?, val status: String?)

/** One row of `GET /scheduled-posts`. */
data class KScheduledPost(
    val txId: String,
    val notBefore: Long?,
    val status: String?,
    val submittedAt: Long?,
    val error: String?,
    val postContent: String?,
)

data class KScheduledPostsResponse(val posts: List<KScheduledPost>?)

/** The indexer answered a request with an error of its own (iOS `KaPostsAPIError.api`): it was
 *  reached, and said no. */
class KaPostsApiException(message: String, cause: Throwable, val code: String? = null) : IllegalStateException(message, cause) {
    /** The indexer's own "nothing like that here" answer (`NOT_FOUND`): for a cancel, it holds no
     *  still-scheduled post with that id. Every other answer (rate limit, bad signature, storage
     *  error) says nothing about whether it will still send it (iOS b78fdb0 `isNotFound`). */
    val isNotFound: Boolean get() = code == "NOT_FOUND"
}

/** The payload can't fit one transaction ([KaPostsService.payloadFitsOneTransaction], iOS
 *  `KaPostsAPIError.tooLong`). The same text never fits on a later try. */
class KaPostTooLongException(message: String) : IllegalStateException(message)

/** The action was made as another account than the one open now: never signed or sent (iOS
 *  f954738 `KaPostsAPIError.accountChanged`, "The account changed, so this wasn't sent."). */
class KaPostAccountChangedException(message: String) : IllegalStateException(message)

/**
 * KaPosts client - mirrors iOS KaPostsAPIClient. Reads come from the K indexer REST API
 * (KaChat-marker-filtered); writes never touch REST - every K action is an on-chain Kaspa
 * self-send transaction whose payload the indexer ingests from the chain.
 *
 * Read methods THROW on network/API failure (the ViewModel catches into a feed error state,
 * matching iOS); this deliberately differs from the app's null-swallowing read services
 * because the feed UI surfaces errors.
 */
@Singleton
class KaPostsService @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val kaPostApiProvider: KaPostApiProvider,
    private val walletManager: WalletManager,
    private val walletService: WalletService,
    /** A scheduled post's transaction, signed now and sent later. */
    private val scheduledTxBuilder: KaPostScheduledTxBuilder,
    private val settings: com.kachat.app.repository.AppSettingsRepository,
    /** With the indexer down (iOS ee5ec98): whether it is, a person's posts, new posts. */
    private val health: KaPostsIndexerHealth,
    private val chainPosts: KaPostsChainPosts,
    private val liveFeed: KaPostsLiveChainFeed,
) {
    companion object {
        private const val TAG = "KaPostsService"

        /** A read while the indexer is down with nothing saved, asked briefly (iOS ee5ec98). */
        private const val DOWN_READ_TIMEOUT_MS = 6_000L
        private const val PROBE_TIMEOUT_MS = 20_000L

        /** When this account last put a post action on chain (epoch ms, 0 = not this run): your
         *  profile's pages loaded before it are out of date - a new post, a delete, an edit
         *  (iOS 33095ac `ownActivityAt`). */
        @Volatile
        var ownActivityAt: Long = 0L
            private set

        /** How long after posting a post, reply or quote can still be edited. The indexer
         *  enforces the same window on chain time; after it the text is permanent. */
        const val EDIT_WINDOW_MS = 2 * 60 * 60 * 1000L

        /** A poll carries two to four options (KAPOSTS_INDEXER.md section 5.9). */
        const val POLL_MIN_OPTIONS = 2
        const val POLL_MAX_OPTIONS = 4

        /**
         * Compressed (02/03 + x) or raw x-only pubkey hex -> Kaspa address. THE bridge that
         * keeps identity in KNS: every K pubkey maps to an address and resolves through the
         * app's normal contacts/KNS chain.
         */
        fun kaspaAddressFromPubkey(pubkeyHex: String): String? {
            val raw = decodeHex(pubkeyHex) ?: return null
            val xOnly = when (raw.size) {
                33 -> raw.sliceArray(1..32)
                32 -> raw
                else -> return null
            }
            return KaspaAddress.encode(com.kachat.app.util.KaspaNetwork.hrp, 0x00, xOnly)
        }

        /**
         * The inverse bridge for @mentions: a Kaspa address' 32-byte x-only payload as the
         * compressed KaPost pubkey (BIP-340 even-Y convention, matching desktop/iOS). Used to
         * fill mentioned_pubkeys from a KNS-domain contact's address alone.
         */
        fun kapostPubkeyFromAddress(address: String): String? = try {
            val (version, payload) = KaspaAddress.decode(address)
            if (version == 0x00.toByte() && payload.size == 32) {
                "02" + payload.joinToString("") { "%02x".format(it) }
            } else null
        } catch (_: Exception) {
            null
        }

        /**
         * Whether [payload] fits one transaction with one input: its fee mass within the node's
         * 100,000-gram limit. The composer's byte cap ([com.kachat.app.models.KaPostDraft.postLength])
         * keeps posts well inside it; this stops anything that got past it before a fee is shown
         * or a coin is spent, with a reason instead of "too many coins" (iOS 369794e).
         */
        fun payloadFitsOneTransaction(payload: String): Boolean =
            com.kachat.app.util.KaspaMass.calculateMass(
                numInputs = 1,
                outputScriptLens = listOf(34),
                payloadSize = payload.toByteArray(Charsets.UTF_8).size,
            ) <= com.kachat.app.util.KaspaMass.MAX_STANDARD_MASS

        // MARK: Sanity checks on indexer answers (iOS audit 2026-11-21 #19)
        //
        // The indexer's rows are believed for display, but only once they look like what the
        // chain could hold: ids are txids, authors are pubkeys, times are not far in the future,
        // amounts are within Kaspa's supply and counts are clamped. Its answers carry no
        // signatures, so the indexer is still trusted for authorship; only the chain fallback
        // verifies signatures.

        /** Largest base64 post content accepted (a post fits one transaction, about 100 KB). */
        const val MAX_CONTENT_CHARACTERS = 200_000
        /** Largest count (likes, replies, tips, followers...) shown from the indexer. */
        const val MAX_COUNT = 1_000_000_000
        /** All the KAS there will ever be, in sompi: a bigger amount is not a real tip. */
        const val MAX_SUPPLY_SOMPI = 28_700_000_000L * 100_000_000L
        /** Rows past this in one answer are dropped (the indexer caps `limit` at 100). */
        const val MAX_ROWS_PER_PAGE = 200

        /**
         * The `requesterPubkey` sent where the answer has no per-viewer fields (engagement and
         * follow lists, the health probe): only feeds, threads, replies, polls, notifications and
         * user details need it to be the reader. Elsewhere it would only tell the indexer who is
         * looking (iOS audit 2026-11-21 #24 `anonymousRequester`).
         */
        val ANONYMOUS_REQUESTER = "02" + "0".repeat(64)

        fun clampedCount(count: Int?): Int? = count?.coerceIn(0, MAX_COUNT)

        private fun isHex(text: String?, vararg lengths: Int): Boolean =
            text != null && text.length in lengths &&
                text.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun isTxId(text: String?): Boolean = isHex(text, 64)

        /** A compressed (66 hex) or x-only (64 hex) pubkey. */
        fun isPubkey(text: String?): Boolean = isHex(text, 64, 66)

        /** A time in ms that could be real: after Kaspa's launch year, at most a day ahead. */
        fun isPlausibleTimestamp(ms: Long): Boolean =
            ms > 1_600_000_000_000L && ms <= System.currentTimeMillis() + 86_400_000L

        /** Gson leaves an absent field null whatever its Kotlin type, so nothing is assumed. */
        @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
        fun isWellFormed(post: KPost?): Boolean {
            if (post == null || !isTxId(post.id) || !isPubkey(post.userPublicKey)) return false
            if (post.postContent == null || post.postContent.length > MAX_CONTENT_CHARACTERS) return false
            if (!isPlausibleTimestamp(post.timestamp)) return false
            val parent = post.parentPostId
            if (!parent.isNullOrEmpty() && !isTxId(parent)) return false
            post.poll?.let { poll ->
                if ((poll.options?.size ?: 0) !in 1..32 || (poll.counts?.size ?: 0) > 64) return false
            }
            if ((post.quote?.referencedMessage?.length ?: 0) > MAX_CONTENT_CHARACTERS) return false
            return true
        }

        /** The post with every count clamped to `0..MAX_COUNT`, so nothing downstream overflows. */
        fun clamped(post: KPost): KPost = post.copy(
            repliesCount = clampedCount(post.repliesCount),
            quotesCount = clampedCount(post.quotesCount),
            upVotesCount = clampedCount(post.upVotesCount),
            downVotesCount = clampedCount(post.downVotesCount),
            tipsCount = clampedCount(post.tipsCount),
            mentionedPubkeys = post.mentionedPubkeys?.filter { isPubkey(it) }?.take(50),
            poll = post.poll?.let { poll ->
                poll.copy(
                    counts = poll.counts?.map { it.coerceIn(0, MAX_COUNT) },
                    total = clampedCount(poll.total),
                    myVote = poll.myVote?.takeIf { it in 0 until (poll.options?.size ?: 0) },
                )
            },
        )

        /** Well-formed posts only, counts clamped, at most [MAX_ROWS_PER_PAGE]. */
        fun sanitized(posts: List<KPost>): List<KPost> =
            posts.take(MAX_ROWS_PER_PAGE).filter { isWellFormed(it) }.map { clamped(it) }

        @Suppress("SENSELESS_COMPARISON")
        fun isWellFormed(notification: KNotification?): Boolean {
            if (notification == null || !isTxId(notification.id) || !isPubkey(notification.userPublicKey)) return false
            if (!isPlausibleTimestamp(notification.timestamp)) return false
            if ((notification.postContent?.length ?: 0) > MAX_CONTENT_CHARACTERS) return false
            val amount = notification.tipAmountSompi
            if (amount != null && amount !in 0..MAX_SUPPLY_SOMPI) return false
            return true
        }

        private val CONTENT_ID_SHAPE = Regex("^[0-9a-f]{64}$")
        private val PUBKEY_SHAPE = Regex("^0[23][0-9a-f]{64}$")

        /** A post, reply, quote or poll id as the chain makes it: a 64-character lowercase txid. */
        fun isWellFormedContentId(id: String?): Boolean = id != null && CONTENT_ID_SHAPE.matches(id)

        /** A compressed KaPost pubkey: 02/03 + 64 lowercase hex. */
        fun isWellFormedPubkey(pubkey: String?): Boolean = pubkey != null && PUBKEY_SHAPE.matches(pubkey)

        /**
         * Ids and keys from the indexer go into what this account signs. One shaped like
         * `edit:<txid>` or `delete:<txid>` would make a reply or an unquote sign exactly an edit or
         * a delete of one of our own posts (the prefix in [KaPostsProtocol.editSigningString]
         * only helps if the id can't carry it), so nothing is signed over anything but the exact
         * shapes. A malformed one can only come from a bad indexer answer.
         */
        internal fun requireWellFormed(ids: List<String?> = emptyList(), pubkeys: List<String?> = emptyList()) {
            if (ids.all(::isWellFormedContentId) && pubkeys.all(::isWellFormedPubkey)) return
            throw com.google.gson.JsonParseException("Malformed KaPost id or key")
        }

        /**
         * The mentions array a post, reply, edit or poll signs: valid shapes only, in order, one
         * per person, never self. Both checks are on the x-only key: a key worked out from an
         * address is "02" + x, so with exact matching an author holding an 03 key slipped past the
         * self check, and one person could be listed under both spellings (iOS 369794e).
         */
        fun mentionsJson(mentionedPubkeys: List<String>, me: String): String {
            val mine = KaPostsChainPosts.xOnly(me)
            val seen = HashSet<String>()
            val shape = Regex("^0[23][0-9a-f]{64}$")
            val clean = mentionedPubkeys
                .map { it.lowercase() }
                .filter {
                    it.matches(shape) && KaPostsChainPosts.xOnly(it) != mine &&
                        seen.add(KaPostsChainPosts.xOnly(it))
                }
            return "[" + clean.joinToString(",") { "\"$it\"" } + "]"
        }

        private fun decodeHex(hex: String): ByteArray? {
            if (hex.length % 2 != 0 || hex.isEmpty()) return null
            return try {
                ByteArray(hex.length / 2) { i ->
                    hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: NumberFormatException) {
                null
            }
        }
    }

    /** The KaPost indexer client. A network with no KaPost indexer (testnet) never builds one, so
     *  this gives up after a moment with an error instead of suspending forever - every caller
     *  already handles a failed request (iOS 1ae83d8, KasiaAPIClient .noIndexer). */
    private suspend fun api(): KaPostApi = kaPostApiProvider.kapostApi.value
        ?: kotlinx.coroutines.withTimeoutOrNull(10_000) { kaPostApiProvider.kapostApi.filterNotNull().first() }
        ?: throw java.io.IOException("KaPost isn't available on this network")

    // MARK: - Requester identity (own compressed pubkey, derived once per wallet)

    private var cachedRequesterPubkey: String? = null
    private var cachedRequesterAddress: String? = null

    /**
     * K identifies users by 66-hex COMPRESSED secp256k1 pubkey. The wallet stores x-only, so
     * derive compressed from the private key and cache per wallet address.
     */
    fun requesterPubkey(): String {
        val address = walletManager.getAddress()
        cachedRequesterPubkey?.let { if (cachedRequesterAddress == address) return it }
        val priv = BigInteger(1, walletManager.getPrivateKeyBytes())
        val compressed = Secp256k1.G.multiply(priv).normalize().getEncoded(true)
        val hex = compressed.joinToString("") { "%02x".format(it) }
        cachedRequesterPubkey = hex
        cachedRequesterAddress = address
        return hex
    }

    // MARK: - Reads (KaChat-filtered, page-shaped)

    /**
     * Wraps one raw response into a [KPage]. `hasMore` prefers the server's own flag and falls
     * back to "the page came back full"; the cursor prefers the server's opaque `nextCursor` and
     * falls back to the last raw id, which the caller's no-new-ids guard makes safe on
     * deployments where that is not what `before` means.
     */
    private fun <T> pageOf(
        raw: List<T>,
        filtered: List<T>,
        pagination: KPagination?,
        limit: Int,
        idOf: (T) -> String,
    ): KPage<T> {
        val rawIds = raw.map(idOf)
        return KPage(
            items = filtered,
            rawIds = rawIds,
            cursor = pagination?.nextCursor ?: rawIds.lastOrNull(),
            hasMore = pagination?.hasMore ?: (raw.size >= limit),
        )
    }

    /** Global feed (K "watching" = all posts). Filtered to KaChat-marked posts. */
    suspend fun fetchGlobalFeedPage(limit: Int = 25, before: String? = null): KPage<KPost> =
        withChain(before, read = {
            val requester = requesterPubkey()
            val response = get(
                KPostsResponse::class.java, "get-posts-watching",
                mapOf("requesterPubkey" to requester, "limit" to "$limit", "before" to before),
            ) { it.getPostsWatching(requester, limit, before) }
            val raw = response.posts.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }, chain = {
            // nothing lists every post but the indexer: what's new since it went away
            liveFeed.posts.value.filter { it.parentPostId == null }
        })

    /** Content from accounts the requester follows. Top-level content only - replies live under threads. */
    suspend fun fetchFollowingFeedPage(limit: Int = 25, before: String? = null): KPage<KPost> =
        withChain(before, read = {
            val requester = requesterPubkey()
            val response = get(
                KPostsResponse::class.java, "get-contents-following",
                mapOf("requesterPubkey" to requester, "limit" to "$limit", "before" to before),
            ) { it.getContentsFollowing(requester, limit, before) }
            val raw = response.posts.orEmpty()
            val filtered = filterKaChat(raw).filter { (it.contentType ?: "post") != "reply" }
            pageOf(raw, filtered, response.pagination, limit) { it.id }
        }, chain = {
            followedPostsFromChain().filter { it.parentPostId == null }
        })

    /** One user's posts (by K pubkey). NOTE: the deployed indexer ignores includeReplies
     *  (get-posts serves content_type post/quote only) - use fetchUserReplies for replies. */
    suspend fun fetchUserPostsPage(
        pubkey: String,
        limit: Int = 25,
        before: String? = null,
        includeReplies: Boolean = false,
    ): KPage<KPost> = withChain(before, read = {
        val requester = requesterPubkey()
        val replies = if (includeReplies) "true" else null
        val response = get(
            KPostsResponse::class.java, "get-posts",
            mapOf("user" to pubkey, "requesterPubkey" to requester, "limit" to "$limit", "includeReplies" to replies, "before" to before),
        ) { it.getUserPosts(pubkey, requester, limit, replies, before) }
        val raw = response.posts.orEmpty()
        pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
    }, chain = {
        val posts = chainPosts.posts(pubkey).orEmpty()
        if (includeReplies) posts else posts.filter { it.parentPostId == null }
    })

    /** One user's replies across ALL threads: get-replies with `user` instead of `post`
     *  (verified live + against the fork's handle_get_replies). Items carry parentPostId.
     *  The profile Replies tab must read this - get-posts never returns replies. */
    suspend fun fetchUserRepliesPage(pubkey: String, limit: Int = 25, before: String? = null): KPage<KPost> =
        withChain(before, read = {
            val requester = requesterPubkey()
            val response = get(
                KRepliesResponse::class.java, "get-replies",
                mapOf("user" to pubkey, "requesterPubkey" to requester, "limit" to "$limit", "before" to before),
            ) { it.getUserReplies(pubkey, requester, limit, before) }
            val raw = response.replies.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }, chain = {
            chainPosts.posts(pubkey).orEmpty().filter { it.parentPostId != null }
        })

    suspend fun fetchRepliesPage(
        postId: String,
        limit: Int = 25,
        before: String? = null,
        /** False for the feed's small "is this a thread" probe: one disk write per commented
         *  post while scrolling, for a page nothing reads back (audit M13). */
        keepPage: Boolean = true,
    ): KPage<KPost> =
        withChain(before, read = {
            val requester = requesterPubkey()
            val response = get(
                KRepliesResponse::class.java, "get-replies",
                mapOf("post" to postId, "requesterPubkey" to requester, "limit" to "$limit", "before" to before),
                keepPage = keepPage,
            ) { it.getReplies(postId, requester, limit, before) }
            val raw = response.replies.orEmpty()
            pageOf(raw, filterKaChat(raw), response.pagination, limit) { it.id }
        }, chain = {
            liveFeed.posts.value.filter { it.parentPostId == postId }
        })

    /**
     * One post by id, any age, any author.
     *
     * Deliberately NOT marker-filtered: the feeds drop posts without the KaChat marker because
     * they are another client's content, but a parent IS the context the reader asked for, and a
     * hole in a thread is worse than a Kasia-origin post inside it.
     */
    suspend fun fetchPost(id: String): KPost? {
        val requester = requesterPubkey()
        val post = get(KPostResponse::class.java, "get-post", mapOf("id" to id, "requesterPubkey" to requester)) {
            it.getPost(id, requester)
        }.post ?: return null
        // a post that can't be what the chain holds is a bad answer (iOS audit 2026-11-21 #19)
        if (!isWellFormed(post)) throw com.google.gson.JsonParseException("Malformed KaPost from the indexer")
        return clamped(post)
    }

    /** A post's whole ancestor chain, root first - one request instead of one per level. */
    suspend fun fetchThread(id: String): List<KPost> {
        val requester = requesterPubkey()
        val response = get(KThreadResponse::class.java, "get-thread", mapOf("id" to id, "requesterPubkey" to requester)) {
            it.getThread(id, requester)
        }
        // checked as iOS does (audit 2026-11-21 #19): a malformed post is a bad answer, and only
        // well-formed ancestors, counts clamped, are kept
        response.post?.let { if (!isWellFormed(it)) throw com.google.gson.JsonParseException("Malformed KaPost from the indexer") }
        return sanitized(response.ancestors.orEmpty())
    }

    /** The requester's notification stream - actions on OUR content. */
    suspend fun fetchNotificationsPage(limit: Int = 50, before: String? = null): KPage<KNotification> {
        val requester = requesterPubkey()
        val response = get(
            KNotificationsResponse::class.java, "get-notifications",
            mapOf("requesterPubkey" to requester, "limit" to "$limit", "before" to before),
        ) { it.getNotifications(requester, limit, before) }
        val raw = response.notifications.orEmpty()
        val wellFormed = raw.take(MAX_ROWS_PER_PAGE).filter { isWellFormed(it) }
        return pageOf(raw, wellFormed, response.pagination, limit) { it.id }
    }

    /** Per-post actor lists (KaChat indexer fork) - works for ANY post. */
    suspend fun fetchPostEngagementPage(
        postId: String,
        type: String = "all",
        limit: Int = 50,
        before: String? = null,
    ): KPage<KEngagementEntry> {
        // nothing per-viewer in this answer: the reader isn't named (iOS audit 2026-11-21 #24)
        val requester = ANONYMOUS_REQUESTER
        val response = get(
            KEngagementResponse::class.java, "get-post-engagement",
            mapOf("postId" to postId, "type" to type, "requesterPubkey" to requester, "limit" to "$limit", "before" to before),
        ) { it.getPostEngagement(postId, type, requester, limit, before) }
        val raw = response.engagement.orEmpty()
        val entries = raw.take(MAX_ROWS_PER_PAGE).filter { isPubkey(it.actorPubkey) && isTxId(it.actionTxId) }
        return pageOf(raw, entries, response.pagination, limit) { it.actionTxId }
    }

    /** Who tipped a post, newest first (`get-post-tippers`, KAPOSTS_INDEXER.md 5.11). */
    suspend fun fetchTippersPage(postId: String, limit: Int = 50, before: String? = null): KPage<KTip> {
        val response = get(
            KTippersResponse::class.java, "get-post-tippers",
            mapOf("postId" to postId, "limit" to "$limit", "before" to before),
        ) { it.getPostTippers(postId, limit, before) }
        val raw = response.tippers.orEmpty()
        val cursor = response.pagination?.nextCursor
        val tips = raw.take(MAX_ROWS_PER_PAGE).filter {
            isTxId(it.id) && isPubkey(it.userPublicKey) && it.amountSompi in 0..MAX_SUPPLY_SOMPI
        }
        return KPage(
            items = tips,
            rawIds = raw.map { it.id },
            cursor = cursor,
            // iOS: more only when the server says so AND hands a cursor.
            hasMore = response.pagination?.hasMore == true && cursor != null,
        )
    }

    /** Who [pubkey] follows (followers=false) or who follows them (followers=true). */
    suspend fun fetchFollowListPage(
        pubkey: String,
        followers: Boolean,
        limit: Int = 50,
        before: String? = null,
    ): KPage<KFollowUser> {
        // nothing per-viewer in this answer: the reader isn't named (iOS audit 2026-11-21 #24)
        val requester = ANONYMOUS_REQUESTER
        val query = mapOf("userPubkey" to pubkey, "requesterPubkey" to requester, "limit" to "$limit", "before" to before)
        val response = if (followers) {
            get(KFollowListResponse::class.java, "get-users-followers", query) { it.getUsersFollowers(pubkey, requester, limit, before) }
        } else {
            get(KFollowListResponse::class.java, "get-users-following", query) { it.getUsersFollowing(pubkey, requester, limit, before) }
        }
        val raw = response.items
        val users = raw.take(MAX_ROWS_PER_PAGE).filter { isPubkey(it.userPublicKey) }
        return pageOf(raw, users, response.pagination, limit) { it.userPublicKey }
    }

    /** Single-page convenience for callers that never paginate (the notification poller). */
    suspend fun fetchNotifications(limit: Int = 100, before: String? = null): List<KNotification> =
        fetchNotificationsPage(limit, before).items

    suspend fun fetchUserDetails(pubkey: String): KUserDetails {
        val requester = requesterPubkey()
        val details = get(KUserDetails::class.java, "get-user-details", mapOf("user" to pubkey, "requesterPubkey" to requester)) {
            it.getUserDetails(pubkey, requester)
        }
        // counts clamped (iOS audit 2026-11-21 #19)
        return details.copy(
            followersCount = clampedCount(details.followersCount),
            followingCount = clampedCount(details.followingCount),
        )
    }

    /**
     * KaChat-only filter: keep posts whose decoded content carries the invisible marker, and
     * drop content the indexer masked for blocked users.
     */
    private fun filterKaChat(posts: List<KPost>): List<KPost> = sanitized(posts).filter { post ->
        if (post.blockedUser == true) return@filter false
        val content = post.decodedContent ?: return@filter false
        KaPostsProtocol.isKaChatContent(content)
    }

    // MARK: - With the indexer down (iOS ee5ec98)

    private val pageGson = com.google.gson.Gson()

    /**
     * Every indexer read. Reports to [health], keeps each read's first page ([KaPostsPageCache]),
     * and answers from it while the indexer is confirmed down or the phone has no connection.
     * Any other failure throws as it always did, so the caller asks the indexer again.
     */
    private suspend fun <T : Any> get(
        type: Class<T>,
        path: String,
        query: Map<String, String?>,
        /** False: answered as usual, but its page isn't saved ([fetchRepliesPage]'s probe). */
        keepPage: Boolean = true,
        call: suspend (KaPostApi) -> T,
    ): T {
        val client = api()
        val sent = query.entries.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()
        val pageKey = KaPostsPageCache.key(settings.kapostIndexerUrl.first(), path, sent)
        // Confirmed down: the saved page at once, not after a timeout. The probe looks for its
        // return. With nothing saved it's still asked, briefly: any answer ends the outage.
        val down = health.isDown.value
        if (down) savedPage(type, pageKey)?.let { return it }
        val decoded: T = try {
            if (down) {
                kotlinx.coroutines.withTimeoutOrNull(DOWN_READ_TIMEOUT_MS) { call(client) }
                    ?: throw java.net.SocketTimeoutException("The KaPost indexer didn't answer")
            } else {
                call(client)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (KaPostsIndexerHealth.meansDown(e.code())) {
                health.noteFailure()
                if (health.isDown.value) savedPage(type, pageKey)?.let { return it }
            } else {
                health.noteAnswered()
            }
            throw apiError(e)
        } catch (e: Exception) {
            // A huge answer isn't read: an answer this build won't take, not an outage (iOS
            // audit 2026-11-21 #19).
            if (KaPostsIndexerHealth.isUnreadableBody(e) || e is com.kachat.app.util.ResponseTooLargeException ||
                e.cause is com.kachat.app.util.ResponseTooLargeException) {
                // The indexer answered, with something this build can't read: no outage.
                health.noteUnreadableAnswer()
                throw e
            }
            health.note(e)
            // A saved page only for a confirmed outage or a phone with no connection.
            if (health.isDown.value || health.meansOffline(e)) savedPage(type, pageKey)?.let { return it }
            throw e
        }
        health.noteAnswered()
        if (pageKey != null && keepPage) {
            KaPostsPageCache.save(appContext, pageKey) { pageGson.toJson(decoded).toByteArray(Charsets.UTF_8) }
        }
        return decoded
    }

    private suspend fun <T : Any> savedPage(type: Class<T>, key: String?): T? {
        val data = KaPostsPageCache.load(appContext, key ?: return null) ?: return null
        // Off Main: the caller is a screen, and a saved page can be a long one.
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
                pageGson.fromJson(String(data, Charsets.UTF_8), type)
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Whether the indexer answers. A placeholder key, since the answer isn't read for anyone. */
    suspend fun probeIndexer(): Boolean {
        val client = kaPostApiProvider.kapostApi.value ?: return false
        return try {
            kotlinx.coroutines.withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                client.getPostsWatching(ANONYMOUS_REQUESTER, 1, null)
            } != null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpException) {
            // Any answer of its own ends the outage: a 4xx is an answer, about this request.
            !KaPostsIndexerHealth.meansDown(e.code())
        } catch (e: Exception) {
            KaPostsIndexerHealth.isUnreadableBody(e)
        }
    }

    /**
     * A first page while the indexer is down: what the read saved (or nothing), with what [chain]
     * adds. No cursor, since one is only good with the indexer. Any other time, just the read.
     */
    private suspend fun withChain(
        before: String?,
        read: suspend () -> KPage<KPost>,
        chain: suspend () -> List<KPost>,
    ): KPage<KPost> {
        val page = try {
            read()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (before != null || !health.isDown.value) throw e
            val rows = chain()
            if (rows.isEmpty()) throw e
            return chainPage(emptyList(), rows)
        }
        if (before != null || !health.isDown.value) return page
        return chainPage(page.items, chain())
    }

    /** The indexer's copy wins a tie: it has the counts and the viewer's own votes. Deletes and
     *  edits the block stream saw during the outage apply to the saved page and the chain rows
     *  alike ([KaPostsLiveChainFeed.amended]); otherwise a post deleted while it's down came back
     *  on every refresh (iOS 369794e). */
    private fun chainPage(saved: List<KPost>, chain: List<KPost>): KPage<KPost> {
        val known = saved.mapTo(HashSet()) { it.id }
        val merged = liveFeed.amended((saved + chain.filter { it.id !in known }).sortedByDescending { it.timestamp })
        return KPage(items = merged, rawIds = merged.map { it.id }, cursor = null, hasMore = false)
    }

    /** The people this account follows, as their posts on chain: the first 20, a few at a time. */
    private suspend fun followedPostsFromChain(): List<KPost> {
        val address = try { walletManager.getAddress() } catch (_: Exception) { return emptyList() }
        val pubkeys = settings.kapostsFollowing(address).first().sorted().take(20)
            .mapNotNull { kapostPubkeyFromAddress(it) }
        val found = mutableListOf<KPost>()
        for (group in pubkeys.chunked(4)) {
            found += kotlinx.coroutines.coroutineScope {
                group.map { pubkey -> async { chainPosts.posts(pubkey).orEmpty() } }.awaitAll().flatten()
            }
        }
        // On x-only: `pubkeys` is built from addresses as "02" + x, and a real post carries true parity.
        val live = pubkeys.mapTo(HashSet()) { KaPostsChainPosts.xOnly(it) }
        val seen = HashSet<String>()
        return (found + liveFeed.posts.value.filter { KaPostsChainPosts.xOnly(it.userPublicKey) in live })
            .filter { seen.add(it.id) }
    }

    /** Surfaces the indexer's JSON error body message instead of Retrofit's opaque HTTP line. */
    private fun apiError(e: HttpException): IllegalStateException {
        val body = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
        val answered = body
            ?.let { Regex("\"error\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        // An error body of its own is the indexer's answer (iOS `.api`); without one it's not.
        val code = body
            ?.let { Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
        if (answered != null) return KaPostsApiException(answered, e, code)
        return IllegalStateException("KaPost indexer error (HTTP ${e.code()})", e)
    }

    /** An indexer write: reports to [health], like every read. */
    private suspend fun <T> posting(block: suspend () -> T): T {
        val answer = try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (KaPostsIndexerHealth.meansDown(e.code())) health.noteFailure() else health.noteAnswered()
            throw apiError(e)
        } catch (e: Exception) {
            if (KaPostsIndexerHealth.isUnreadableBody(e)) health.noteUnreadableAnswer() else health.note(e)
            throw e
        }
        health.noteAnswered()
        return answer
    }

    // MARK: - Writes (on-chain self-send transactions; txid = content id)

    /**
     * Publishes a KaChat post on-chain. The exclusivity marker is prepended INSIDE the message
     * (the only channel the read API surfaces). Returns the transaction id = post id.
     *
     * `mentionedPubkeys` (compressed hex) are client-resolved @mentions: they go into the signed
     * mentioned_pubkeys array and the indexer turns each into a `contentType: "mention"`
     * notification for that user. Deduped; malformed entries and the author's own key dropped.
     */
    suspend fun submitPost(text: String, mentionedPubkeys: List<String> = emptyList(), expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val pubkey = requesterPubkey()
        val mentions = mentionsJson(mentionedPubkeys, pubkey)
        val signature = sign(KaPostsProtocol.postSigningString(b64, mentions))
        return submitPayloadTx(KaPostsProtocol.postPayload(pubkey, signature, b64, mentions), expectedOwner)
    }

    /**
     * Replies to a post (its K txid). mentioned_pubkeys carries the parent author (the spec's
     * reply-notification hook) PLUS any @mentions the comment text resolved to — same dedupe/
     * validate/self-drop rules as [submitPost], so @someone works in comments exactly like in
     * top-level posts.
     */
    suspend fun submitReply(
        text: String,
        postId: String,
        parentAuthorPubkey: String?,
        mentionedPubkeys: List<String> = emptyList(),
        expectedOwner: String? = null,
    ): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(postId))
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val pubkey = requesterPubkey()
        val mentions = mentionsJson(listOfNotNull(parentAuthorPubkey) + mentionedPubkeys, pubkey)
        val signature = sign(KaPostsProtocol.replySigningString(postId, b64, mentions))
        return submitPayloadTx(KaPostsProtocol.replyPayload(pubkey, signature, postId, b64, mentions), expectedOwner)
    }

    /**
     * Replaces the text of one of our own posts, replies or quotes
     * (KAPOSTS_INDEXER.md section 5.7). Same mention rules as a post, so an @mention added in
     * the edit notifies the person it names. The indexer accepts it only within
     * [EDIT_WINDOW_MS] of the original, measured on chain time.
     */
    suspend fun submitEdit(text: String, postId: String, mentionedPubkeys: List<String> = emptyList(), expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(postId))
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val pubkey = requesterPubkey()
        val mentions = mentionsJson(mentionedPubkeys, pubkey)
        val signature = sign(KaPostsProtocol.editSigningString(postId, b64, mentions))
        return submitPayloadTx(KaPostsProtocol.editPayload(pubkey, signature, postId, b64, mentions), expectedOwner)
    }

    /**
     * Publishes a poll: the question IS the post's text (marker inside, exactly like any post,
     * so a client that knows no polls still reads the question), while the options and the
     * closing time ride in the payload (KAPOSTS_INDEXER.md section 5.9). Returns the poll's id.
     */
    suspend fun submitPoll(
        question: String,
        options: List<String>,
        closesAtMs: Long,
        mentionedPubkeys: List<String> = emptyList(),
        expectedOwner: String? = null,
    ): String {
        checkOwner(expectedOwner)
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + question)
        val csv = KaPostsProtocol.pollOptionsCsv(options)
        val pubkey = requesterPubkey()
        val mentions = mentionsJson(mentionedPubkeys, pubkey)
        val signature = sign(KaPostsProtocol.pollSigningString(b64, csv, closesAtMs, mentions))
        return submitPayloadTx(KaPostsProtocol.pollPayload(pubkey, signature, b64, csv, closesAtMs, mentions), expectedOwner)
    }

    /**
     * Votes in a poll. One vote per pubkey - a later one replaces the earlier - and the indexer
     * ignores anything cast after the poll closed (section 5.9).
     */
    suspend fun submitPollVote(pollId: String, optionIndex: Int, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(pollId))
        val pubkey = requesterPubkey()
        val signature = sign(KaPostsProtocol.pollVoteSigningString(pollId, optionIndex))
        return submitPayloadTx(KaPostsProtocol.pollVotePayload(pubkey, signature, pollId, optionIndex), expectedOwner)
    }

    // MARK: - Scheduled posts (KAPOSTS_INDEXER.md section 5.10)

    /** A post signed now for later: the id it will have on chain, its bytes, and what it spends. */
    data class ScheduledTransaction(
        val txId: String,
        val transaction: RawTransaction,
        val spentOutpoints: List<String>,
    )

    /**
     * Builds and signs the transaction for [text] without submitting it - the post goes out at
     * the chosen time, sent by the indexer or, failing that, by this phone.
     */
    suspend fun buildScheduledPost(text: String, mentionedPubkeys: List<String> = emptyList()): ScheduledTransaction {
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + text)
        val pubkey = requesterPubkey()
        val mentions = mentionsJson(mentionedPubkeys, pubkey)
        val signature = sign(KaPostsProtocol.postSigningString(b64, mentions))
        val payload = KaPostsProtocol.postPayload(pubkey, signature, b64, mentions)
        if (!payloadFitsOneTransaction(payload)) throw tooLong()
        val built = scheduledTxBuilder.buildSignedPayloadSelfSend(payload.toByteArray(Charsets.UTF_8)).getOrThrow()
        return ScheduledTransaction(
            txId = built.txId,
            transaction = built.transaction,
            spentOutpoints = built.spentOutpoints,
        )
    }

    /** Hands a signed post to the indexer to submit at [notBeforeMs]. */
    suspend fun scheduleOnServer(txId: String, transaction: RawTransaction, notBeforeMs: Long) {
        val pubkey = requesterPubkey()
        val signature = sign(KaPostsProtocol.scheduleSigningString(txId, notBeforeMs))
        val client = api()
        posting {
            client.schedulePost(
                KSchedulePostRequest(
                    pubkey = pubkey,
                    txId = txId,
                    notBefore = notBeforeMs,
                    signature = signature,
                    transaction = KRestTransactionEnvelope(transaction),
                )
            )
        }
    }

    /** Takes back a post the indexer has not submitted yet. */
    suspend fun cancelScheduledOnServer(txId: String) {
        val pubkey = requesterPubkey()
        val signature = sign(KaPostsProtocol.cancelScheduleSigningString(txId))
        val client = api()
        posting {
            client.cancelScheduledPost(KCancelScheduledRequest(pubkey = pubkey, txId = txId, signature = signature))
        }
    }

    /** What the indexer is holding for this wallet, and how each one went. */
    suspend fun fetchScheduledPosts(): List<KScheduledPost> {
        val requester = requesterPubkey()
        return get(KScheduledPostsResponse::class.java, "scheduled-posts", mapOf("pubkey" to requester)) {
            it.getScheduledPosts(requester)
        }.posts.orEmpty()
    }

    /** Submits a scheduled post from this phone, when the indexer never took it. */
    suspend fun submitScheduledLocally(transaction: RawTransaction): String {
        val txId = scheduledTxBuilder.submitSignedTransaction(transaction)
        settleScope.launch {
            kotlinx.coroutines.delay(2_000)
            try { walletService.refreshBalance() } catch (_: Exception) {}
        }
        return txId
    }

    /**
     * Deletes one of our own posts, replies or quotes (KAPOSTS_INDEXER.md section 5.8), at any
     * age. The chain keeps the bytes it always had; the indexer stops serving the post.
     */
    suspend fun submitDelete(postId: String, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(postId))
        val signature = sign(KaPostsProtocol.deleteSigningString(postId))
        return submitPayloadTx(KaPostsProtocol.deletePayload(requesterPubkey(), signature, postId), expectedOwner)
    }

    /** Casts an upvote/downvote on a post. */
    suspend fun submitVote(postId: String, upvote: Boolean, authorPubkey: String, expectedOwner: String? = null): String =
        submitVoteAction(postId, if (upvote) "upvote" else "downvote", authorPubkey, expectedOwner)

    /** Removal counter-action (fork): withdraws our existing up/down vote on a post. */
    suspend fun submitUnvote(postId: String, authorPubkey: String, expectedOwner: String? = null): String =
        submitVoteAction(postId, "unvote", authorPubkey, expectedOwner)

    private suspend fun submitVoteAction(postId: String, vote: String, authorPubkey: String, expectedOwner: String?): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(postId), pubkeys = listOf(authorPubkey))
        val signature = sign(KaPostsProtocol.voteSigningString(postId, vote, authorPubkey))
        return submitPayloadTx(KaPostsProtocol.votePayload(requesterPubkey(), signature, postId, vote, authorPubkey), expectedOwner)
    }

    /** Follows/unfollows a K identity (66-hex compressed pubkey). */
    suspend fun submitFollow(follow: Boolean, followedPubkey: String, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        val action = if (follow) "follow" else "unfollow"
        requireWellFormed(pubkeys = listOf(followedPubkey))
        val signature = sign(KaPostsProtocol.followSigningString(action, followedPubkey))
        return submitPayloadTx(KaPostsProtocol.followPayload(requesterPubkey(), signature, action, followedPubkey), expectedOwner)
    }

    /**
     * Quotes a post - K's repost mechanism (no separate repost action; quotesCount is the live
     * counter). A PLAIN repost is a quote whose message is just the KaChat marker; a
     * quote-with-commentary carries marker + text.
     */
    suspend fun submitQuote(text: String?, contentId: String, quotedAuthorPubkey: String, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(contentId), pubkeys = listOf(quotedAuthorPubkey))
        val b64 = KaPostsProtocol.b64(KaPostsProtocol.KACHAT_MARKER + (text ?: ""))
        val signature = sign(KaPostsProtocol.quoteSigningString(contentId, b64, quotedAuthorPubkey))
        return submitPayloadTx(KaPostsProtocol.quotePayload(requesterPubkey(), signature, contentId, b64, quotedAuthorPubkey), expectedOwner)
    }

    /** Removal counter-action: withdraws our quote/repost of [contentId]. */
    suspend fun submitUnquote(contentId: String, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        requireWellFormed(ids = listOf(contentId))
        val signature = sign(KaPostsProtocol.unquoteSigningString(contentId))
        return submitPayloadTx(KaPostsProtocol.unquotePayload(requesterPubkey(), signature, contentId), expectedOwner)
    }

    /**
     * A tip's payload (KAPOSTS_INDEXER.md 5.11): `kchat:1:tip:<pubkey>:<signature>:<post_id>:
     * <author_pubkey>:<sealed_hex>`, signed by this account like every KaPosts action. The
     * transaction itself is a chat payment to the author (ChatViewModel.sendPayment).
     */
    fun tipPayload(postId: String, authorPubkey: String, sealedHex: String): ByteArray {
        requireWellFormed(ids = listOf(postId), pubkeys = listOf(authorPubkey))
        val signature = sign(KaPostsProtocol.tipSigningString(postId, authorPubkey, sealedHex))
        return KaPostsProtocol.tipPayload(requesterPubkey(), signature, postId, authorPubkey, sealedHex)
            .toByteArray(Charsets.UTF_8)
    }

    private fun accountChanged() =
        KaPostAccountChangedException(appContext.getString(com.kachat.app.R.string.kp5_account_changed_not_sent))

    /**
     * Throws [KaPostAccountChangedException] when the action was made as [expectedOwner] and
     * another account is open now; null skips the check (iOS f954738 `checkOwner`). Every KaPost
     * action carries the account it was made by, so a switch while one waits never signs it as
     * the next account.
     */
    fun checkOwner(expectedOwner: String?) {
        if (expectedOwner == null) return
        val current = runCatching { walletManager.getAddress() }.getOrNull()
        if (current != expectedOwner) throw accountChanged()
    }

    private fun tooLong() = KaPostTooLongException(appContext.getString(com.kachat.app.R.string.kaposts_post_too_long))

    private fun sign(message: String): String =
        KaspaMessageSigner.sign(message, walletManager.getPrivateKeyBytes(), KaspaMessageSigner.SigningMode.KASPA_PERSONAL_MESSAGE)

    /**
     * Shared write core: zero-amount self-send from the identity address with the K payload
     * attached. WalletService routes payload txs over gRPC (the REST gateway rejects them) and
     * refreshes the balance after submit.
     */
    private suspend fun submitPayloadTx(payload: String, expectedOwner: String? = null): String {
        checkOwner(expectedOwner)
        val account = walletManager.getAddress()
        if (!payloadFitsOneTransaction(payload)) throw tooLong()
        val txId = try {
            walletService.sendKaspa(
                toAddress = account,
                amountSompi = 0,
                payloadBytes = payload.toByteArray(Charsets.UTF_8),
            )
        } catch (e: Exception) {
            // The wait outlasted the account: said as the account change it is (iOS f954738
            // throws `accountChanged` there, not `missingWallet`).
            if (e !is SendOutcomeUnknownException && runCatching { walletManager.getAddress() }.getOrNull() != account) {
                throw accountChanged()
            }
            throw e
        }
        // Only what shows on your profile: a post, reply, quote, edit, delete or poll (iOS 33095ac).
        val action = payload.split(":", limit = 5).getOrNull(2)
        if (action != null && action in setOf("post", "reply", "quote", "unquote", "edit", "delete", "poll")) {
            ownActivityAt = System.currentTimeMillis()
        }
        Log.d(TAG, "Submitted ${payload.take(12)} action tx ${txId.take(12)}")
        // sendKaspa refreshed the balance once, immediately; a second pass after the UTXO change
        // settles (Kaspa blocks are ~1s) is what reliably catches the fee just spent (iOS).
        settleScope.launch {
            kotlinx.coroutines.delay(2_000)
            try { walletService.refreshBalance() } catch (_: Exception) {}
        }
        return txId
    }

    private val settleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
}

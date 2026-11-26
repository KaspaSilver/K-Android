package com.kachat.app.services

import android.content.Context
import android.util.Log
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Profile pictures and banners on this phone (iOS `KNSProfileImageCache`, KNSAvatarView.swift).
 *
 * - They have their own disk store ([directory], 64 MB), so chat photos and link previews can't
 *   push them out.
 * - The copy on disk always shows at once, whatever the host's cache headers say. Coil used to
 *   follow them: an X picture is fresh for a week from when X's CDN fetched it, a GitHub one for
 *   five minutes, so after a relaunch most avatars waited for the network, and kept the person
 *   glyph when that request was slow or failed.
 * - A copy older than five days is checked for a newer picture behind it ([revalidateIfDue]):
 *   one conditional HEAD when the host gave validators, a download otherwise; a changed picture
 *   redraws through [com.kachat.app.ui.screens.ProfileImageRefetch].
 * - A download that failed is not tried again for a minute ([failedRecently]): a dead link isn't
 *   requested again on every scroll.
 *
 * Every download through the profile loader records its validators here ([ValidatorRecorder]).
 */
object ProfileImageStore {
    private const val TAG = "ProfileImageStore"

    /** The folder (cacheDir/KachatProfileImages): Coil's store in [IMAGES], the checks beside it.
     *  Settings > Storage > Cache > Profiles measures and clears it. */
    private const val FOLDER = "KachatProfileImages"
    private const val IMAGES = "images"
    private const val CHECKS_FILE = "checks.json"

    /** iOS `diskHighWaterBytes`. */
    const val MAX_DISK_BYTES = 64L * 1024 * 1024

    /** A copy is checked for a newer picture once it is this old (iOS `revalidationInterval`). */
    const val REVALIDATE_AFTER_MS = 5L * 24 * 60 * 60 * 1000

    /** A failed download waits this long before it's tried again (iOS `downloadFailureCooldown`). */
    const val FAILURE_COOLDOWN_MS = 60_000L

    /** A check that couldn't reach the host isn't tried again sooner than this. */
    private const val CHECK_RETRY_MS = 10L * 60 * 1000

    private const val MAX_CHECKS = 2_000
    private const val MAX_FAILURES = 512

    fun directory(context: Context): File = File(context.applicationContext.cacheDir, FOLDER)

    /** Coil's disk store for profile pictures. */
    fun imagesDirectory(context: Context): File = File(directory(context), IMAGES)

    /** What the host said about a picture last time it was downloaded or checked. */
    data class Check(
        val validatedAt: Long,
        val eTag: String? = null,
        val lastModified: String? = null,
        val contentLength: Long? = null,
    ) {
        val hasValidators: Boolean get() = eTag != null || lastModified != null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.kachat.app.util.StartupSafety.logOnlyHandler(TAG))
    private val lock = Any()
    @Volatile private var appContext: Context? = null
    private var checks: MutableMap<String, Check>? = null
    private var persistJob: Job? = null
    private val checking = ConcurrentHashMap.newKeySet<String>()
    private val checkFailedAt = ConcurrentHashMap<String, Long>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    // Decisions (pure, unit tested)

    /** Whether a copy last checked by [check] is due a look for a newer picture: never checked,
     *  or checked [REVALIDATE_AFTER_MS] or more ago. */
    internal fun isDue(check: Check?, nowMs: Long, maxAgeMs: Long = REVALIDATE_AFTER_MS): Boolean =
        check == null || nowMs - check.validatedAt >= maxAgeMs

    /** iOS `validatorMatches`: a header the host didn't send says nothing; one it sent must be the
     *  one stored. */
    internal fun <T> validatorMatches(stored: T?, received: T?): Boolean =
        received == null || (stored != null && stored == received)

    /** Whether a 2xx answer to the HEAD says the picture is the one on disk. */
    internal fun sameAsStored(stored: Check, eTag: String?, lastModified: String?, contentLength: Long?): Boolean =
        validatorMatches(stored.eTag, eTag) &&
            validatorMatches(stored.lastModified, lastModified) &&
            validatorMatches(stored.contentLength, contentLength)

    /** How long until [url] may be downloaded again after a failure; 0 when it may now. */
    fun cooldownLeftMs(url: String?, nowMs: Long = System.currentTimeMillis()): Long {
        val at = url?.let { failedAt[it] } ?: return 0L
        return (FAILURE_COOLDOWN_MS - (nowMs - at)).coerceAtLeast(0L)
    }

    fun failedRecently(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean = cooldownLeftMs(url, nowMs) > 0L

    /** A picture failed to load: not asked for again for [FAILURE_COOLDOWN_MS]. */
    fun noteFailure(url: String?, nowMs: Long = System.currentTimeMillis()) {
        if (url.isNullOrBlank()) return
        failedAt[url] = nowMs
        if (failedAt.size > MAX_FAILURES) {
            failedAt.entries.removeIf { nowMs - it.value >= FAILURE_COOLDOWN_MS }
        }
    }

    fun noteLoaded(url: String?) {
        if (url != null) failedAt.remove(url)
    }

    // The checks

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        // read off the main thread, before the first avatar asks
        scope.launch { loadedChecks() }
    }

    private fun loadedChecks(): MutableMap<String, Check> = synchronized(lock) {
        checks ?: readChecks().also { checks = it }
    }

    fun check(url: String): Check? = loadedChecks()[url]

    /** A download or check answered: the picture at [url] is as the host says now. */
    internal fun record(url: String, check: Check) {
        synchronized(lock) {
            val all = loadedChecks()
            all[url] = check
            if (all.size > MAX_CHECKS) {
                all.entries.sortedBy { it.value.validatedAt }.take(all.size - MAX_CHECKS).forEach { all.remove(it.key) }
            }
        }
        persistSoon()
    }

    private fun stamp(url: String, nowMs: Long = System.currentTimeMillis()) {
        val old = check(url) ?: Check(nowMs)
        record(url, old.copy(validatedAt = nowMs))
    }

    private fun readChecks(): MutableMap<String, Check> {
        val context = appContext ?: return HashMap()
        val file = File(directory(context), CHECKS_FILE)
        val raw = runCatching { file.takeIf { it.isFile }?.readText() }.getOrNull() ?: return HashMap()
        return runCatching {
            val out = HashMap<String, Check>()
            for ((url, value) in JsonParser.parseString(raw).asJsonObject.entrySet()) {
                if (!value.isJsonObject) continue
                val o = value.asJsonObject
                fun s(name: String): String? = o.get(name)?.takeIf { it.isJsonPrimitive }?.asString
                val at = o.get("validatedAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: continue
                out[url] = Check(at, s("eTag"), s("lastModified"), o.get("contentLength")?.takeIf { it.isJsonPrimitive }?.asLong)
            }
            out
        }.getOrElse { HashMap() }
    }

    /** One write for a burst of answers (a screen of avatars landing together). */
    private fun persistSoon() {
        synchronized(lock) {
            if (persistJob?.isActive == true) return
            persistJob = scope.launch {
                delay(2_000)
                persist()
            }
        }
    }

    private fun persist() {
        val context = appContext ?: return
        val json = synchronized(lock) {
            val root = JsonObject()
            for ((url, c) in checks.orEmpty()) {
                root.add(url, JsonObject().apply {
                    addProperty("validatedAt", c.validatedAt)
                    c.eTag?.let { addProperty("eTag", it) }
                    c.lastModified?.let { addProperty("lastModified", it) }
                    c.contentLength?.let { addProperty("contentLength", it) }
                })
            }
            root.toString()
        }
        runCatching {
            val dir = directory(context).apply { mkdirs() }
            val tmp = File(dir, "$CHECKS_FILE.tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(File(dir, CHECKS_FILE))) {
                File(dir, CHECKS_FILE).writeText(json)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "could not write the checks: ${it.message}") }
    }

    /** Settings > Storage > Cache > Profiles: the pictures and what was known about them. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun clearAll(context: Context) {
        synchronized(lock) {
            persistJob?.cancel()
            persistJob = null
            checks = HashMap()
        }
        checking.clear()
        checkFailedAt.clear()
        failedAt.clear()
        runCatching { LinkPreviewService.profileImageLoader(context).diskCache?.clear() }
        runCatching { File(directory(context), CHECKS_FILE).delete() }
    }

    // Looking for a newer picture

    /**
     * The picture at [url] just showed from this phone's copy: when that copy is due
     * ([REVALIDATE_AFTER_MS]), it is checked behind it (iOS `image(for:)`'s background
     * `revalidate`). Cheap when it isn't due: one map read.
     */
    fun revalidateIfDue(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        init(context)
        val now = System.currentTimeMillis()
        if (!isDue(check(url), now)) return
        if (checkFailedAt[url]?.let { now - it < CHECK_RETRY_MS } == true) return
        if (!checking.add(url)) return
        scope.launch {
            try {
                revalidate(context.applicationContext, url)
            } finally {
                checking.remove(url)
            }
        }
    }

    /**
     * Checks the copy of [url] on this phone against the host now. True when a different picture
     * came down (the avatars showing it then redraw). A copy that isn't on this phone is left
     * alone; it downloads by itself the next time it shows.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun revalidate(context: Context, url: String): Boolean = withContext(Dispatchers.IO) {
        init(context)
        val loader = LinkPreviewService.profileImageLoader(context)
        val disk = loader.diskCache ?: return@withContext false
        val before = fingerprint(disk, url) ?: return@withContext false
        val stored = check(url)
        if (stored != null && stored.hasValidators) {
            when (head(url, stored)) {
                HeadAnswer.SAME -> {
                    stamp(url)
                    checkFailedAt.remove(url)
                    return@withContext false
                }
                HeadAnswer.UNREACHABLE -> {
                    checkFailedAt[url] = System.currentTimeMillis()
                    return@withContext false
                }
                HeadAnswer.CHANGED -> Unit
            }
        }
        // No validators to compare, or they changed: the picture again, past both caches.
        val request = ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(CachePolicy.WRITE_ONLY)
            .diskCachePolicy(CachePolicy.WRITE_ONLY)
            .size(com.kachat.app.ui.screens.ProfileImageRefetch.REFETCH_MAX_PX)
            .build()
        val ok = try {
            loader.execute(request) is SuccessResult
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            checkFailedAt[url] = System.currentTimeMillis()
            return@withContext false
        }
        checkFailedAt.remove(url)
        stamp(url)
        val changed = fingerprint(disk, url) != before
        if (changed) com.kachat.app.ui.screens.ProfileImageRefetch.noteChanged(url)
        changed
    }

    private enum class HeadAnswer { SAME, CHANGED, UNREACHABLE }

    /** iOS `revalidate`'s HEAD: 304, or validators that match, is the same picture. */
    private fun head(url: String, stored: Check): HeadAnswer {
        val request = runCatching {
            Request.Builder().url(url).head().apply {
                stored.eTag?.let { header("If-None-Match", it) }
                stored.lastModified?.let { header("If-Modified-Since", it) }
            }.build()
        }.getOrNull() ?: return HeadAnswer.UNREACHABLE
        return try {
            LinkPreviewService.profileHttpClient().newCall(request).execute().use { response ->
                when {
                    response.code == 304 -> HeadAnswer.SAME
                    response.code !in 200..299 -> HeadAnswer.UNREACHABLE
                    sameAsStored(
                        stored,
                        response.header("ETag"),
                        response.header("Last-Modified"),
                        response.header("Content-Length")?.toLongOrNull(),
                    ) -> HeadAnswer.SAME
                    else -> HeadAnswer.CHANGED
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "check failed: ${e.javaClass.simpleName}")
            HeadAnswer.UNREACHABLE
        }
    }

    /** The copy's bytes, checksummed; null when this phone doesn't hold [url]. */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    internal fun fingerprint(disk: coil.disk.DiskCache, url: String): Long? =
        runCatching {
            disk.openSnapshot(url)?.use { snapshot ->
                val crc = java.util.zip.CRC32()
                crc.update(snapshot.data.toFile().readBytes())
                crc.value
            }
        }.getOrNull()

    /**
     * On the profile loader's client, outside the redirect handling: a picture downloaded (a GET
     * answered 200) records what the host said about it, under the URL Coil keys its copy by.
     */
    class ValidatorRecorder : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (request.method == "GET" && response.code == 200) {
                record(
                    request.url.toString(),
                    Check(
                        validatedAt = System.currentTimeMillis(),
                        eTag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        contentLength = response.header("Content-Length")?.toLongOrNull(),
                    ),
                )
            }
            return response
        }
    }

    /** The client for [head] and the profile loader: [base] with [ValidatorRecorder] first. */
    internal fun withRecorder(base: OkHttpClient.Builder): OkHttpClient.Builder =
        base.addInterceptor(ValidatorRecorder())
}

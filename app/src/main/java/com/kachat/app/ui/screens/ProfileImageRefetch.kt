package com.kachat.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * User Info > Rescan Profile's picture side (iOS bc95d17 `KNSProfileImageCacheControl.refetch`,
 * `knsProfileImageRefetched`): downloads profile pictures again whatever Coil's caches hold, then
 * tells every avatar ([ContactAvatar]) and banner ([WholeBanner]) showing one to redraw it.
 *
 * The download goes to the network only - it reads neither the memory nor the disk cache, so no
 * validators are sent (a host may answer "not modified" to a changed picture) - and on success it
 * replaces the URL's disk entry and its plain memory entry with the new picture. A download that
 * fails writes nothing: the old picture stays.
 */
object ProfileImageRefetch {
    private val _generations = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** How many times each picture URL was downloaded again this run: avatars and banners key
     *  their image request on it, so a new number loads the new picture. */
    val generations: StateFlow<Map<String, Int>> = _generations.asStateFlow()

    /** Downloads these pictures again whatever the caches hold, then has every avatar and
     *  banner showing one redraw it. Returns how many came back. */
    suspend fun refetch(context: Context, urls: List<String>): Int {
        val app = context.applicationContext
        val loader = com.kachat.app.services.LinkPreviewService.profileImageLoader(app)
        var fetched = 0
        for (url in urls.filter { it.isNotBlank() }.toSet()) {
            val request = ImageRequest.Builder(app)
                .data(url)
                // network only, no validators; the answer replaces the cached copies
                .memoryCachePolicy(CachePolicy.WRITE_ONLY)
                .diskCachePolicy(CachePolicy.WRITE_ONLY)
                .size(REFETCH_MAX_PX)
                .build()
            val ok = runCatching { loader.execute(request) is SuccessResult }.getOrElse { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                false
            }
            if (!ok) continue
            com.kachat.app.services.ProfileImageStore.noteLoaded(url)
            fetched++
            _generations.update { it + (url to ((it[url] ?: 0) + 1)) }
        }
        return fetched
    }

    /** A picture's copy on this phone was replaced by a different one
     *  ([com.kachat.app.services.ProfileImageStore.revalidate]): every avatar and banner showing
     *  it redraws. */
    fun noteChanged(url: String) {
        _generations.update { it + (url to ((it[url] ?: 0) + 1)) }
    }

    /**
     * The hourly profile rescan's look at these pictures (iOS 961fc05a
     * `KNSProfileImageCacheControl.refreshIfChanged`, audit 2026-11-25 B17: it used to download
     * every contact's avatar and banner again each hour). A picture this phone doesn't hold is
     * left alone: a new link downloads by itself the next time it shows. One it holds is checked
     * against the host ([com.kachat.app.services.ProfileImageStore.revalidate]: a conditional HEAD,
     * or a download when the host gave no validators), and only a changed picture redraws. Not
     * sooner than [RESCAN_REVALIDATE_MIN_AGE_MS] after its last check. Returns how many changed.
     */
    suspend fun refreshIfChanged(context: Context, urls: List<String>): Int {
        val app = context.applicationContext
        val store = com.kachat.app.services.ProfileImageStore
        store.init(app)
        var changed = 0
        for (url in urls.filter { it.isNotBlank() }.toSet()) {
            if (!store.isDue(store.check(url), System.currentTimeMillis(), RESCAN_REVALIDATE_MIN_AGE_MS)) continue
            if (store.revalidate(app, url)) changed++
        }
        return changed
    }

    /** The hourly rescan doesn't check a picture again sooner than this. */
    private const val RESCAN_REVALIDATE_MIN_AGE_MS = 50L * 60 * 1000

    /** Biggest side the fresh copy is decoded at for the memory cache; a view that needs more
     *  reads the new file from the disk cache. */
    internal const val REFETCH_MAX_PX = 1024

    /** Coil parameter carrying the generation: it changes the request (so the image reloads) but
     *  not its memory cache key (so it finds the fresh copy). */
    internal const val PARAMETER = "kachat#refetch"
}

/**
 * The model an avatar or banner loads for [url]: the URL itself until Rescan Profile downloaded
 * it again, then a request carrying that download's number - a new request, so the composable
 * loads again and shows the new picture, with the cached one as its placeholder meanwhile.
 */
@Composable
fun rememberProfileImageModel(url: String?): Any? {
    val generations by ProfileImageRefetch.generations.collectAsState()
    val generation = url?.let { generations[it] } ?: 0
    val context = LocalContext.current
    return remember(url, generation) {
        if (url == null || generation == 0) {
            url
        } else {
            ImageRequest.Builder(context)
                .data(url)
                .setParameter(ProfileImageRefetch.PARAMETER, generation, memoryCacheKey = null)
                .placeholderMemoryCacheKey(url)
                .build()
        }
    }
}

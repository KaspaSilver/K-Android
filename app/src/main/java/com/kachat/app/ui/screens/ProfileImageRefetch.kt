package com.kachat.app.ui.screens

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil.request.ImageRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Profile pictures that changed (iOS bc95d17 `knsProfileImageRefetched`): tells every avatar
 * ([ContactAvatar]) and banner ([WholeBanner]) showing one to redraw it. The forced download
 * (`refetch`) went with iOS 01f71184: nothing called it; the hourly rescan's [refreshIfChanged]
 * checks pictures against their host instead.
 */
object ProfileImageRefetch {
    private val _generations = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** How many times each picture URL was downloaded again this run: avatars and banners key
     *  their image request on it, so a new number loads the new picture. */
    val generations: StateFlow<Map<String, Int>> = _generations.asStateFlow()

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

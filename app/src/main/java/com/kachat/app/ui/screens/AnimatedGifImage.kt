package com.kachat.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.kachat.app.util.GifDecoder
import com.kachat.app.util.GifPrep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Plays an animated GIF from its bytes, looping (iOS d7c56280 `AnimatedImageView`): one frame
 * decoded at a time, at the file's own timing, into two bitmaps drawn in turn, so a long GIF
 * costs one frame of memory, not all of them. Stops when it leaves the composition (a bubble
 * scrolled away, the full-screen view closed).
 *
 * Only what [GifPrep.isSafeToAnimate] allows plays (iOS 5b7c772c, checked again here because one
 * frame of an unchecked file can be gigabytes); anything else, and the time before the first
 * frame, shows [still].
 */
@Composable
fun AnimatedGifImage(
    data: ByteArray,
    still: ImageBitmap,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    /** Holds the frame it shows, and picks up from there once false again: the bubble behind
     *  the full-screen viewer, which plays the same GIF (audit 2026-11-25 R7). */
    paused: Boolean = false,
    /** [data] already passed [GifPrep.isSafeToAnimate] ([SafeGifBytes]): not scanned again. */
    alreadyChecked: Boolean = false,
) {
    var frame by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    val pausedState = androidx.compose.runtime.rememberUpdatedState(paused)
    // A chat left open behind the home screen keeps its composition: the loop waits for the app to
    // be seen again rather than decode frames nobody sees.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(data, lifecycle) {
        withContext(Dispatchers.Default) {
            if (!alreadyChecked && !GifPrep.isSafeToAnimate(data)) return@withContext
            val first = runCatching { GifDecoder(data) }.getOrNull() ?: return@withContext
            val buffers = arrayOf(
                Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888),
                Bitmap.createBitmap(first.width, first.height, Bitmap.Config.ARGB_8888),
            )
            val images = buffers.map { it.asImageBitmap() }
            var next = 0
            var decoder: GifDecoder? = first
            while (isActive) {
                val current = decoder ?: GifDecoder(data)
                decoder = null
                var shown = 0
                while (isActive) {
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        lifecycle.currentStateFlow.first { it.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
                    }
                    if (pausedState.value) {
                        androidx.compose.runtime.snapshotFlow { pausedState.value }.first { !it }
                    }
                    val decoded = runCatching { current.nextFrame() }.getOrNull() ?: break
                    buffers[next].setPixels(decoded.pixels, 0, current.width, 0, 0, current.width, current.height)
                    frame = images[next]
                    next = 1 - next
                    shown++
                    delay(decoded.delayMs.toLong())
                }
                // One frame or none: nothing to loop.
                if (shown <= 1) break
            }
        }
    }
    Image(
        bitmap = frame ?: still,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

/**
 * A chat GIF's playable bytes per message content (its raw base64 string), so a bubble scrolled
 * back into view doesn't decode the whole payload and scan every frame again (audit 2026-11-25
 * R7), the way the stills have their bitmap cache. An entry with no bytes means "not a GIF that
 * plays" (anything else, or over the play limits). 8 MB.
 */
internal object SafeGifBytes {
    private val NOT_PLAYABLE = ByteArray(0)
    private val cache = object : android.util.LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size.coerceAtLeast(1)
    }

    /** The bytes to play for [key], working them out with [load] (null: not playable) the first time. */
    fun get(key: String, load: () -> ByteArray?): ByteArray? {
        cache.get(key)?.let { return it.takeIf { bytes -> bytes !== NOT_PLAYABLE } }
        val loaded = load()
        cache.put(key, loaded ?: NOT_PLAYABLE)
        return loaded
    }
}

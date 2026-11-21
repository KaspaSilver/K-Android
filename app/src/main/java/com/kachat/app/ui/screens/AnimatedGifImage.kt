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
) {
    var frame by remember(data) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(data) {
        withContext(Dispatchers.Default) {
            if (!GifPrep.isSafeToAnimate(data)) return@withContext
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
